package dylan.di

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.db.RecentAlbums
import dylan.diag.LogBuffer
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.model.Song
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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random

class AppContainer(
    val cfg: AppConfig,
    val disp: AppDispatchers,
    val scope: kotlinx.coroutines.CoroutineScope,
    private val baseDir: String,
    driverFactory: DriverFactory,
    val netMonitor: NetMonitor,
    httpEngine: HttpClientEngine,
    private val engineFactory: () -> dylan.playback.PlayerEngine,
    logMinLevel: dylan.diag.LogLevel = dylan.diag.LogLevel.INFO,
    /** App version for the boot log line — keep in sync with root VERSION. */
    val version: String = APP_VERSION,
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
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 8_000
            }
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

    data class HomeSnapshot(
        val loaded: Boolean = false,
        val jumpBack: List<Song> = emptyList(),
        val favorites: List<Song> = emptyList(),
        val albums: List<RecentAlbums> = emptyList(),
    )

    val homeSnapshot = MutableStateFlow(HomeSnapshot())

    /** Per-process session id for correlating log lines across a single run (no Orchestrator edits). */
    val sessionId: String = newSessionId()

    fun createEngine(): dylan.playback.PlayerEngine = engineFactory()

    private var started = false
    private val bgJobs = mutableListOf<Job>()

    /** Pure ctor — no I/O. Call [start] after `setContent` to avoid blocking first frame. */
    fun start() {
        if (started) return
        started = true
        log.i("boot", "container up v=$version session=$sessionId dir=$baseDir")
        downloads.start()
        // Single source of truth for protected keys — replaces scattered triple-union reads.
        bgJobs +=
            scope.launch(disp.state) {
                combine(
                    orchestrator.state,
                    cacheManager.inFlightJobKeys,
                    cacheManager.upgradeSourceKeys,
                ) { st, inflight, upgrade ->
                    buildSet {
                        st.current?.let { add(it.key) }
                        st.nextUp?.let { add(it.key) }
                        addAll(inflight)
                        addAll(upgrade)
                    }
                }.collect { protectedKeys.value = it }
            }
        bgJobs +=
            scope.launch(disp.io) {
                val t0 = dylan.util.nowMs()
                runCatching { reconciler.run() }
                    .onSuccess { log.i("reconciler", "boot sweep done in ${dylan.util.nowMs() - t0}ms") }
                    .onFailure { log.e("reconciler", it.message ?: "failed") }
                orchestrator.restoreFromSnapshot()
            }
        bgJobs +=
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
        bgJobs +=
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

    fun stop() {
        bgJobs.forEach { it.cancel() }
        bgJobs.clear()
        downloads.stop()
        // Best-effort drain so stop-time lines survive; fire-and-forget with timeout.
        scope.launch(disp.io) { runCatching { fileLog.flush(LOG_FLUSH_TIMEOUT_MS) } }
        started = false
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
        // Fire-and-forget drain with timeout — at most LOG_FLUSH_TIMEOUT_MS of
        // background budget spent flushing the diagnostic trail.
        scope.launch(disp.io) { runCatching { fileLog.flush(LOG_FLUSH_TIMEOUT_MS) } }
    }

    companion object {
        /** Keep in sync with root VERSION (bump-version.sh is the writer). */
        const val APP_VERSION = "0.1.0"
        const val LOG_FLUSH_TIMEOUT_MS = 2_000L

        private fun newSessionId(): String {
            val hex = "0123456789abcdef"

            fun h(n: Int) = buildString { repeat(n) { append(hex[Random.nextBits(4)]) } }
            return "${h(8)}-${h(4)}-${h(4)}-${h(4)}-${h(12)}"
        }
    }
}
