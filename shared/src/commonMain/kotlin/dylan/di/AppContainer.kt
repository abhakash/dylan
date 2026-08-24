package dylan.di

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.model.SongKey
import dylan.net.apiClient
import dylan.net.bulkClient
import dylan.playback.Orchestrator
import dylan.provider.saavn.SaavnProvider
import dylan.repo.Favorites
import dylan.repo.History
import dylan.repo.HomeCacheRepo
import dylan.repo.SearchHistoryRepo
import dylan.repo.SettingsStore
import dylan.repo.weeklyGc
import dylan.search.SaavnSearchChannel
import dylan.util.AppDispatchers
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

class AppContainer(
    val cfg: AppConfig,
    val disp: AppDispatchers,
    val scope: kotlinx.coroutines.CoroutineScope,
    baseDir: String,
    driverFactory: DriverFactory,
    val netMonitor: NetMonitor,
    httpEngine: HttpClientEngine,
    private val engineFactory: () -> dylan.playback.PlayerEngine,
    logMinLevel: dylan.diag.LogLevel = dylan.diag.LogLevel.INFO,
) {
    val log = LogBuffer(minLevel = logMinLevel)
    val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    val fs: FileSystem = FileSystem.SYSTEM
    val paths = Paths((baseDir.toPath() / "audio"), fs)

    // Persistent diagnostic trail (<baseDir>/logs/dylan.log.*) — survives restarts so a bug
    // reported days later is still diagnosable. Bound before any component logs.
    val fileLog =
        dylan.diag.FileLogSink(
            fs = fs,
            dir = baseDir.toPath() / "logs",
            scope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + disp.io),
        )

    init {
        log.bindSink(fileLog::accept)
    }

    val db = Dylan(driverFactory.createDriver())
    val settings = SettingsStore(db, disp, cfg)
    val breakers = Breakers()

    private val api = apiClient(httpEngine, cfg)
    private val bulk = bulkClient(httpEngine, cfg)
    private val ws =
        HttpClient(httpEngine) {
            install(WebSockets) { pingIntervalMillis = cfg.wsPingIntervalMs.toLong() }
        }

    val provider = SaavnProvider(api, cfg)
    val searchChannel = SaavnSearchChannel(api, ws, cfg, scope, log)

    val cacheManager = CacheManager(db, fs, paths, protectedKeys, cfg, disp, log)
    val downloads =
        DownloadEngine(
            db = db,
            fs = fs,
            paths = paths,
            cfg = cfg,
            disp = disp,
            provider = provider,
            bulk = bulk,
            breakers = breakers,
            cacheManager = cacheManager,
            netClass = { netMonitor.current() },
            qualityPref = { settings.qualityPref() },
            log = log,
        )
    val orchestrator =
        Orchestrator(
            scope = scope,
            disp = disp,
            db = db,
            fs = fs,
            paths = paths,
            cfg = cfg,
            downloads = downloads,
            cacheManager = cacheManager,
            settings = settings,
            net = netMonitor,
            protectedKeys = protectedKeys,
            log = log,
        )
    val favorites = Favorites(db, disp, cacheManager)
    val history = History(db, disp)
    val searchHistory = SearchHistoryRepo(db, disp, cfg)
    val homeCache = HomeCacheRepo(db, disp, cfg)
    val reconciler = Reconciler(db, fs, paths, cfg, disp, downloads, cacheManager, log)

    fun createEngine(): dylan.playback.PlayerEngine = engineFactory()

    init {
        log.i("boot", "container up dir=$baseDir")
        downloads.start()
        scope.launch(disp.io) {
            val t0 = dylan.util.nowMs()
            runCatching { reconciler.run() }
                .onSuccess { log.i("reconciler", "boot sweep done in ${dylan.util.nowMs() - t0}ms") }
                .onFailure { log.e("reconciler", it.message ?: "failed") }
            orchestrator.restoreFromSnapshot()
        }
        scope.launch(disp.io) {
            while (true) {
                val now = dylan.util.nowMs()
                val last = runCatching { settings.get("gc_last_ms")?.toLongOrNull() ?: 0L }.getOrDefault(0L)
                val weekMs = 7L * 24 * 60 * 60 * 1000
                if (now - last >= weekMs) {
                    log.i("weeklyGc", "running (last=$last)")
                    runCatching { db.weeklyGc(disp, cfg) }
                        .onSuccess { log.i("weeklyGc", "done") }
                        .onFailure { log.e("weeklyGc", it.message ?: "failed") }
                    runCatching { homeCache.evictWeekly() }
                        .onSuccess { log.i("homeCacheEvict", "done") }
                        .onFailure { log.e("homeCacheEvict", it.message ?: "failed") }
                    runCatching { settings.put("gc_last_ms", now.toString()) }
                }
                val nextLast = runCatching { settings.get("gc_last_ms")?.toLongOrNull() ?: now }.getOrDefault(now)
                val delayMs = (nextLast + weekMs - dylan.util.nowMs()).coerceAtLeast(60_000L)
                kotlinx.coroutines.delay(delayMs)
            }
        }
        // QUALITY_UPGRADE idle scanner — closes same-key window-skip gap (§7.3 sufficiency)
        scope.launch(disp.io) {
            while (true) {
                kotlinx.coroutines.delay(30L * 60 * 1000)
                if (netMonitor.current() == dylan.util.NetClass.METERED) continue
                if (cacheManager.inFlightJobKeys.value.isNotEmpty()) continue
                runCatching { scanQualityUpgrades() }
                    .onSuccess { n -> if (n > 0) log.i("qualityScan", "enqueued $n upgrades") }
                    .onFailure { log.e("qualityScan", it.message ?: "failed") }
            }
        }
    }

    private suspend fun scanQualityUpgrades(): Int {
        val candidates =
            withContext(disp.dbLane) {
                val rows = db.dylanQueries.selectAllCached().executeAsList()
                rows
                    .filter { it.bitrate == 128L && (it.pinned == 1L || it.play_count >= 2) }
                    .mapNotNull { row ->
                        val s = db.dylanQueries.selectSong(row.provider, row.song_id).executeAsOneOrNull() ?: return@mapNotNull null
                        if (s.has_320 != 1L) null else SongKey(s.provider, s.song_id) to row
                    }.take(3)
            }
        for ((key, row) in candidates) {
            if (key in cacheManager.inFlightJobKeys.value) continue
            // Dedupe: if already at 320 or upgrade intent pending, DownloadEngine will no-op.
            downloads.enqueue(dylan.download.DownloadJob(key, dylan.download.Priority.QUALITY_UPGRADE, 320, dylan.util.nowMs()))
        }
        return candidates.size
    }

    fun onBackground() {
        orchestrator.onBackground()
        searchChannel.onBackground()
    }
}
