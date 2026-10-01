@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.di

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.db.RecentAlbums
import dylan.diag.FileLogSink
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.Priority
import dylan.model.Song
import dylan.model.SongKey
import dylan.net.apiClient
import dylan.net.bulkClient
import dylan.playback.Orchestrator
import dylan.playback.PlayerEngine
import dylan.provider.saavn.SaavnProvider
import dylan.repo.Favorites
import dylan.repo.History
import dylan.repo.HomeCacheRepo
import dylan.repo.SearchHistoryRepo
import dylan.repo.SettingsStore
import dylan.repo.weeklyGc
import dylan.search.SaavnSearchChannel
import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.NetClass
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.random.Random

/** Coarse lifecycle phase. A single atomic ref is the whole start/stop mutual exclusion. */
private enum class Lifecycle {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
}

/**
 * The composition root. It owns the [SupervisorJob] every component scope descends from, builds
 * the four graphs, and is the only place in the codebase that knows all of them at once.
 *
 * Construction is genuinely I/O-free: no directory is created, no driver is opened, no Ktor
 * client is built and no component is instantiated. [open] does that on the io lane; [start]
 * calls it if nobody has. What a platform graph gets back from the constructor is a set of
 * four lazy facades ([data], [files], [net], [playback]), so a component that only needs a
 * logger cannot reach the database by asking the container for one.
 */
class AppContainer(
    val cfg: AppConfig,
    val disp: AppDispatchers,
    val scope: CoroutineScope,
    val baseDir: String,
    driverFactory: DriverFactory,
    val netMonitor: NetMonitor,
    private val httpEngine: HttpClientEngine,
    private val engineFactory: () -> PlayerEngine,
    /** Android Home-screen projection. Platform-owned state, injected — see [HomeSnapshotHolder]. */
    home: HomeSnapshotHolder = HomeSnapshotHolder(),
    /**
     * The filesystem every file-touching component uses. Injected rather than defaulted to
     * `FileSystem.SYSTEM`, which is a JVM-only declaration and therefore does not resolve in
     * commonMain — the iOS klib compile fails on it. `commonMain` must not name a JVM-only symbol,
     * so each platform passes its own (both are the same `FileSystem.SYSTEM` at runtime).
     */
    val fs: FileSystem,
    /**
     * The shared ring. A platform graph can pass its own [log] instead so it can bind a console
     * mirror and hand the same buffer to DriverFactory before the SQLite open — a schema wipe
     * must never land in a ring nobody reads.
     */
    logMinLevel: LogLevel = LogLevel.INFO,
    val log: LogBuffer = LogBuffer(minLevel = logMinLevel),
    /** App version for the boot log line — keep in sync with root VERSION. */
    val version: String = APP_VERSION,
) {
    /**
     * Single writer of the protected-key set (current + next-up + in-flight + upgrade sources).
     * Published by the job in [start] from [playback] state and cache state only.
     */
    val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())

    /** Per-process session id for correlating log lines across a single run. */
    val sessionId: String = newSessionId()

    private val database = LazyDatabase(driverFactory, log)
    private val opened = CompletableDeferred<Unit>()
    private val openLaunched = AtomicBoolean(false)
    private val phase = AtomicReference(Lifecycle.IDLE)
    private val bgJobs = AtomicReference<List<Job>>(emptyList())
    private val componentJobs = AtomicReference<List<Job>>(emptyList())
    private val shutDown = AtomicBoolean(false)
    private val containerJob = SupervisorJob(scope.coroutineContext[Job])
    private val netRef = AtomicReference<NetGraph?>(null)
    private val fileLogRef = AtomicReference<FileLogSink?>(null)
    private val playbackRef = AtomicReference<PlaybackGraph?>(null)

    data class HomeSnapshot(
        val loaded: Boolean = false,
        val jumpBack: List<Song> = emptyList(),
        val favorites: List<Song> = emptyList(),
        val albums: List<RecentAlbums> = emptyList(),
    )

    // ---- the four graphs -------------------------------------------------------------------

    val files: FileGraph by lazyGraph { buildFiles() }

    val data: DataGraph by lazyGraph { buildData() }

    val net: NetGraph by lazyGraph { buildNet().also { netRef.store(it) } }

    val playback: PlaybackGraph by lazyGraph { buildPlayback().also { playbackRef.store(it) } }

    private val cache: CacheManager by lazyGraph { buildCache() }

    // ---- flat accessors --------------------------------------------------------------------
    // Forwarding getters with no state of their own, kept only until the platform layers move to
    // container.data / container.files / container.net / container.playback. The facades are the
    // supported shape; these exist so this wave does not have to restructure androidApp.

    val db: Dylan get() = data.db
    val settings: SettingsStore get() = data.settings
    val favorites: Favorites get() = data.favorites
    val history: History get() = data.history
    val searchHistory: SearchHistoryRepo get() = data.searchHistory
    val homeCache: HomeCacheRepo get() = data.homeCache
    val paths: Paths get() = files.paths
    val fileLog: FileLogSink get() = files.fileLog
    val provider: SaavnProvider get() = net.provider
    val searchChannel: SaavnSearchChannel get() = net.searchChannel
    val orchestrator: Orchestrator get() = playback.orchestrator
    val downloads: DownloadEngine get() = playback.downloads
    val cacheManager: CacheManager get() = cache

    /** Android-only UI state; the holder is constructed by the platform, not by the graph. */
    val home: HomeSnapshotHolder = home

    /** Compatibility accessor for the platform UI; retired with the holder's adoption. */
    val homeSnapshot: MutableStateFlow<HomeSnapshot> get() = home.state

    /**
     * Platform toast sink, read from the state lane. The container owns the orchestrator's toast
     * hook so wiring it does not force the playback graph open on the caller's thread.
     */
    var onToast: ((String) -> Unit)? = null

    /** Live background coroutines the container owns: 0 before [start] and after [stop]. */
    val backgroundJobCount: Int get() = bgJobs.load().size

    fun createEngine(): PlayerEngine = engineFactory()

    // ---- graph construction ----------------------------------------------------------------

    private fun buildFiles(): FileGraph {
        val sink =
            FileLogSink(
                fs = fs,
                dir = baseDir.toPath() / "logs",
                scope = CoroutineScope(scope.coroutineContext + disp.io),
            )
        fileLogRef.store(sink)
        log.bindSink(FILE_SINK_KEY) { e -> sink.accept(e) }
        return FileGraph(fs, Paths(baseDir.toPath() / "audio", fs), sink)
    }

    private fun buildCache(): CacheManager =
        CacheManager(
            db = database.value,
            fs = files.fs,
            paths = files.paths,
            protectedKeys = protectedKeys,
            cfg = cfg,
            disp = disp,
            log = log,
        )

    private fun buildData(): DataGraph {
        val db = database.value
        val settings = SettingsStore(db, disp, cfg)
        return DataGraph(
            db = db,
            settings = settings,
            favorites = Favorites(db, disp, cache, cfg.clock),
            history = History(db, disp),
            searchHistory = SearchHistoryRepo(db, disp, cfg),
            homeCache = HomeCacheRepo(db, disp, cfg),
        )
    }

    private fun buildNet(): NetGraph {
        val api = apiClient(httpEngine, cfg)
        val bulk = bulkClient(httpEngine, cfg)
        val ws =
            HttpClient(httpEngine) {
                install(WebSockets) { pingIntervalMillis = cfg.wsPingIntervalMs.toLong() }
                install(HttpTimeout) {
                    connectTimeoutMillis = 8_000
                    requestTimeoutMillis = 8_000
                }
            }
        // The search engine maps and decodes JSON, which is CPU-bound and does not yield, so it
        // belongs on io — NOT on `state`, which also carries the orchestrator inbox and the 10 Hz
        // position ticker. (The channel forces io internally too; this keeps the scope's default
        // consistent with where its work actually runs.)
        val channel = SaavnSearchChannel(api, ws, cfg, componentScope("search", disp.io), disp, log, netMonitor)
        return NetGraph(
            api,
            bulk,
            ws,
            SaavnProvider(api, cfg, componentScope("provider", disp.io), disp, netMonitor, log),
            channel,
            netMonitor,
        )
    }

    private fun buildPlayback(): PlaybackGraph {
        val breakers = Breakers()
        val downloads =
            DownloadEngine(
                db = data.db,
                fs = files.fs,
                paths = files.paths,
                cfg = cfg,
                disp = disp,
                provider = net.provider,
                bulk = net.bulk,
                breakers = breakers,
                cacheManager = cache,
                netClass = { netMonitor.current() },
                qualityPref = { settings.qualityPref() },
                log = log,
            )
        return PlaybackGraph(
            orchestrator =
                Orchestrator(
                    scope = scope,
                    disp = disp,
                    db = data.db,
                    fs = files.fs,
                    paths = files.paths,
                    cfg = cfg,
                    downloads = downloads,
                    cacheManager = cache,
                    settings = settings,
                    net = netMonitor,
                    log = log,
                ),
            downloads = downloads,
            reconciler = Reconciler(data.db, files.fs, files.paths, cfg, disp, downloads, cache, log),
            breakers = breakers,
            cacheManager = cache,
        )
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /**
     * Creates `<baseDir>/audio` and `<baseDir>/logs`, opens SQLite (schema probe, first-run
     * `Schema.create`, the four PRAGMAs) and builds the three Ktor clients — all on the io lane.
     *
     * This is the work that used to happen inside the constructor, on the main thread, before the
     * first frame, on both platforms. Call it from a background task once the UI is up; [start]
     * calls it for you if nobody has, so omitting it degrades to today's behaviour rather than to
     * a broken graph. Idempotent and safe to call concurrently.
     */
    suspend fun open() {
        launchOpen()
        opened.await()
    }

    /**
     * Idempotent and re-entry safe: a second [start] while the first is still publishing its jobs
     * is a no-op, because the IDLE→STARTING transition is a single compareAndSet. Returns once
     * the background jobs exist, not once they have done anything.
     */
    fun start() {
        if (shutDown.load()) return
        if (!phase.compareAndSet(Lifecycle.IDLE, Lifecycle.STARTING)) return
        val begun =
            runCatching {
                launchOpen()
                log.i("lifecycle", "start session=$sessionId")
                bgJobs.store(listOfNotNull(publishProtectedKeys(), bootComponents(), weeklyGcLoop(), qualityScanLoop()))
            }
        phase.store(if (begun.isSuccess) Lifecycle.RUNNING else Lifecycle.IDLE)
        begun.getOrThrow()
    }

    /**
     * Cancels and joins every coroutine this container launched, resets [protectedKeys], flushes
     * and closes the log trail, and closes the three Ktor clients plus the shared
     * [httpEngine] — the container takes ownership of the engine passed to it. When it returns,
     * nothing this container started is still running.
     *
     * [start] after [stop] relaunches the background work (reconciler, restore, weekly GC, quality
     * scan, protected-key publisher). The HTTP layer is deliberately not rebuilt — a container
     * that stopped and must serve traffic again has to be replaced. [shutdown] is the terminal
     * form and is what a teardown path should call.
     */
    suspend fun stop() {
        if (!phase.compareAndSet(Lifecycle.RUNNING, Lifecycle.STOPPING)) return
        try {
            cancelAndJoin(bgJobs.exchange(emptyList()))
            playbackRef.load()?.let { it.downloads.stop() }
            netRef.exchange(null)?.close()
            runCatching { httpEngine.close() }
            closeLogTrail()
            protectedKeys.value = emptySet()
            log.i("lifecycle", "stop session=$sessionId")
        } finally {
            phase.store(Lifecycle.IDLE)
        }
    }

    /**
     * Terminal teardown: [stop] plus every scope handed out by [componentScope], so nothing the
     * graph ever started is still running when it returns. After this the container cannot be
     * restarted — build a new one. This is the call an iOS `AppEnvironment` teardown path needs;
     * today it has none.
     */
    suspend fun shutdown() {
        if (!shutDown.compareAndSet(false, true)) return
        stop()
        phase.store(Lifecycle.STOPPING)
        try {
            cancelAndJoin(componentJobs.exchange(emptyList()))
            containerJob.cancel()
            log.i("lifecycle", "shutdown session=$sessionId")
        } finally {
            phase.store(Lifecycle.IDLE)
        }
    }

    /**
     * A scope owned by this container, not by the component that receives it: the
     * `SupervisorJob` is created here and cancelled by [shutdown], so no component is
     * responsible for its own cancellation and none of them is unreachable by a teardown.
     */
    fun componentScope(
        name: String,
        dispatcher: CoroutineDispatcher = disp.io,
    ): CoroutineScope {
        val job = SupervisorJob(containerJob)
        if (!shutDown.load()) componentJobs.append(job)
        return CoroutineScope(
            job + dispatcher +
                CoroutineExceptionHandler { _, t ->
                    log.c(name, "job crashed: ${t.message ?: t::class.simpleName}")
                },
        )
    }

    private fun launchOpen() {
        if (!openLaunched.compareAndSet(false, true)) return
        scope.launch(disp.on(Lane.IO)) {
            runCatching { openOnIo() }
                .onSuccess { opened.complete(Unit) }
                .onFailure { t ->
                    opened.completeExceptionally(t)
                    log.e("boot", "graph open failed: ${t.message}")
                }
        }
    }

    private suspend fun openOnIo() {
        disp.assertInContext(Lane.IO)
        val t0 = cfg.clock.nowMs()
        files
        database.open()
        net
        val ms = cfg.clock.nowMs() - t0
        log.i("boot", "container up v=$version session=$sessionId dir=$baseDir openMs=$ms")
    }

    private fun publishProtectedKeys(): Job =
        scope.launch(disp.on(Lane.STATE)) {
            opened.await()
            val graph = playback
            val policy = cacheManager
            combine(
                graph.orchestrator.state,
                policy.inFlightJobKeys,
                policy.upgradeSourceKeys,
            ) { st, inflight, upgrade ->
                buildSet {
                    st.current?.let { add(it.key) }
                    st.nextUp?.let { add(it.key) }
                    addAll(inflight)
                    addAll(upgrade)
                }
            }.collect { protectedKeys.value = it }
        }

    private fun bootComponents(): Job =
        scope.launch(disp.on(Lane.IO)) {
            opened.await()
            val graph = playback
            graph.orchestrator.toast = { msg ->
                log.i("toast", msg)
                onToast?.invoke(msg)
            }
            graph.downloads.start()
            val t0 = cfg.clock.nowMs()
            runCatching { graph.reconciler.run() }
                .onSuccess { log.i("reconciler", "boot sweep done in ${cfg.clock.nowMs() - t0}ms") }
                .onFailure { log.e("reconciler", it.message ?: "failed") }
            graph.orchestrator.restoreFromSnapshot()
        }

    private fun weeklyGcLoop(): Job =
        scope.launch(disp.on(Lane.IO)) {
            opened.await()
            while (true) {
                runCatching { weeklyGcOnce() }.onFailure { log.e("weeklyGc", it.message ?: "failed") }
                delay(nextGcDelayMs())
            }
        }

    private suspend fun weeklyGcOnce() {
        val now = cfg.clock.nowMs()
        val last = runCatching { settings.get(GC_LAST_KEY)?.toLongOrNull() ?: 0L }.getOrDefault(0L)
        if (now - last < WEEK_MS) return
        log.i("weeklyGc", "running (last=$last)")
        runCatching { data.db.weeklyGc(disp, cfg) }
            .onSuccess { log.i("weeklyGc", "done") }
            .onFailure { log.e("weeklyGc", it.message ?: "failed") }
        runCatching { data.homeCache.evictWeekly() }
            .onSuccess { log.i("homeCacheEvict", "done") }
            .onFailure { log.e("homeCacheEvict", it.message ?: "failed") }
        runCatching { settings.put(GC_LAST_KEY, now.toString()) }
    }

    private suspend fun nextGcDelayMs(): Long {
        val now = cfg.clock.nowMs()
        val nextLast = runCatching { settings.get(GC_LAST_KEY)?.toLongOrNull() ?: now }.getOrDefault(now)
        return (nextLast + WEEK_MS - now).coerceAtLeast(MIN_GC_DELAY_MS)
    }

    private fun qualityScanLoop(): Job =
        scope.launch(disp.on(Lane.IO)) {
            opened.await()
            while (true) {
                delay(QUALITY_SCAN_INTERVAL_MS)
                if (netMonitor.current() == NetClass.METERED) continue
                if (cacheManager.inFlightJobKeys.value.isNotEmpty()) continue
                runCatching { scanQualityUpgrades() }
                    .onSuccess { n -> if (n > 0) log.i("qualityScan", "enqueued $n upgrades") }
                    .onFailure { log.e("qualityScan", it.message ?: "failed") }
            }
        }

    private suspend fun scanQualityUpgrades(): Int {
        val candidates =
            withContext(disp.on(Lane.DB)) {
                data.db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .filter { it.bitrate == UPGRADE_FROM_BITRATE && (it.pinned == 1L || it.play_count >= 2) }
                    .mapNotNull { row ->
                        val song =
                            data.db.dylanQueries
                                .selectSong(row.provider, row.song_id)
                                .executeAsOneOrNull()
                        if (song == null || song.has_320 != 1L) {
                            null
                        } else {
                            SongKey(song.provider, song.song_id)
                        }
                    }.take(MAX_UPGRADE_CANDIDATES)
            }
        var enqueued = 0
        for (key in candidates) {
            if (key in cacheManager.inFlightJobKeys.value) continue
            // Dedupe: if already at 320 or upgrade intent pending, DownloadEngine will no-op.
            playback.downloads.enqueue(DownloadJob(key, Priority.QUALITY_UPGRADE, HIGH_BITRATE, cfg.clock.nowMs()))
            enqueued++
        }
        return enqueued
    }

    fun onBackground() {
        playback.orchestrator.onBackground()
        net.searchChannel.onBackground()
        flushLogAsync()
    }

    private fun flushLogAsync() {
        val sink = fileLogRef.load() ?: return
        // Fire-and-forget drain with timeout — at most LOG_FLUSH_TIMEOUT_MS of background budget.
        scope.launch(disp.on(Lane.IO)) { runCatching { sink.flush(LOG_FLUSH_TIMEOUT_MS) } }
    }

    private suspend fun closeLogTrail() {
        val sink = fileLogRef.load() ?: return
        runCatching { sink.flush(LOG_FLUSH_TIMEOUT_MS) }
        runCatching { sink.close() }
    }

    private suspend fun cancelAndJoin(jobs: List<Job>) {
        for (j in jobs) j.cancel()
        withContext(NonCancellable) {
            for (j in jobs) j.join()
        }
    }

    companion object {
        /** Shipped version. Generated into `AppVersion.kt` from the root `VERSION` file at build time. */
        const val APP_VERSION: String = dylan.di.APP_VERSION
        const val LOG_FLUSH_TIMEOUT_MS = 2_000L
        private const val FILE_SINK_KEY = "file"
        private const val GC_LAST_KEY = "gc_last_ms"
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private const val MIN_GC_DELAY_MS = 60_000L
        private const val QUALITY_SCAN_INTERVAL_MS = 30L * 60 * 1000
        private const val UPGRADE_FROM_BITRATE = 128L
        private const val HIGH_BITRATE = 320
        private const val MAX_UPGRADE_CANDIDATES = 3

        private fun newSessionId(): String {
            val hex = "0123456789abcdef"

            fun h(n: Int) = buildString { repeat(n) { append(hex[Random.nextBits(4)]) } }
            return "${h(8)}-${h(4)}-${h(4)}-${h(4)}-${h(12)}"
        }
    }
}

/** No lock: every graph member is immutable once built, and publication is enough. */
private inline fun <T> lazyGraph(noinline build: () -> T): Lazy<T> = lazy(LazyThreadSafetyMode.PUBLICATION, build)

private fun <T> AtomicReference<List<T>>.append(item: T) {
    while (true) {
        val cur = load()
        if (compareAndSet(cur, cur + item)) return
    }
}
