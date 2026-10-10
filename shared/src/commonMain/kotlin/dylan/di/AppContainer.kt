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
import dylan.repo.upgradeCandidates
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

    private val database = LazyDatabase({ driverFactory.createDriver() }, log)
    private val opened = CompletableDeferred<Unit>()
    private val openLaunched = AtomicBoolean(false)

    /**
     * The [open] coroutine itself, so teardown can wait for it.
     *
     * It used to be launched with the `Job` discarded, which made it the one coroutine in the
     * container that neither [stop] nor [shutdown] could reach: it descends from the *platform*
     * scope, not from [containerJob], and it is not in [bgJobs]. So a `stop()` that landed while
     * [openOnIo] was between `database.open()` and `net` closed the Ktor clients and the shared
     * engine out from under a `buildNet()` still constructing them — and then `buildNet()`
     * finished and published a graph full of closed clients into [netRef], where nothing would
     * ever close them again. Joining first makes "when it returns, nothing this container
     * started is still running" true of the open as well as the background work.
     */
    private val openJob = AtomicReference<kotlinx.coroutines.Job?>(null)
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
     *
     * An [AtomicReference] and not a `var`: [start]'s `bootComponents` publishes this from the
     * **io** lane and every state-lane handler reads it, so a plain field is an unsynchronised
     * cross-lane publication of a non-null value — the state lane can read `null` forever after a
     * toast has already been wired, and the platform's only symptom is a missing error message.
     */
    var onToast: ((String) -> Unit)?
        get() = toastRef.load()
        set(value) {
            toastRef.store(value)
        }

    private val toastRef = AtomicReference<((String) -> Unit)?>(null)

    /** Live background coroutines the container owns: 0 before [start] and after [stop]. */
    val backgroundJobCount: Int get() = bgJobs.load().size

    fun createEngine(): PlayerEngine = engineFactory()

    // ---- graph construction ----------------------------------------------------------------

    private fun buildFiles(): FileGraph {
        val sink =
            FileLogSink(
                fs = fs,
                dir = baseDir.toPath() / "logs",
                // `componentScope`, not `CoroutineScope(scope.coroutineContext + disp.io)`. The
                // latter parents the sink's writer loop to the *platform* scope, which is not the
                // container's to cancel: `shutdown()` cancelled `containerJob` and left the
                // `drainLoop` — parked forever on `queue.receive()` — alive, holding a closed
                // `FileSystem` handle and re-opening `dylan.log.0` on the next line written after
                // `closeLogTrail()`. `componentScope` puts it under `containerJob`, so the writer
                // is the first thing to go when the container is torn down.
                scope = componentScope("logsink"),
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
                    connectTimeoutMillis = WS_CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = WS_REQUEST_TIMEOUT_MS
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
                    // `componentScope("orchestrator", disp.state)`, NOT the platform `scope`. The
                    // Orchestrator launches its inbox loop and its states collector into whatever
                    // scope it is handed (Orchestrator.init), so passing the platform scope made
                    // both children of the *platform* SupervisorJob — which `shutdown()` does not
                    // cancel, since it owns `containerJob` instead. Measured before this change:
                    // `shutdown()` cancelled the 4 background jobs and left the state-lane inbox
                    // running and still turning intents into state, i.e. the terminal teardown's
                    // "nothing this container started is still running" was false for the one
                    // component that owns all the lane-confined mutable state.
                    //
                    // `disp.state` reproduces the platform scope's dispatcher, so this is a
                    // lifetime change only — no lane moves.
                    scope = componentScope("orchestrator", disp.state),
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
     *
     * **It never returns without settling.** `opened` is what this function and all four
     * background jobs await, and there is deliberately no timeout around those awaits: a timeout
     * would turn a *failed* open into a silent one, carrying on against a graph that was never
     * built. So [launchOpen] settles `opened` on every path — success, failure, and the
     * cancel-before-first-dispatch case it used to leave pending forever — and this suspends until
     * one of them happens. It throws if the open failed, and if the container was shut down before
     * it could start.
     *
     * It is also the only correct gate for a UI that must not force the cold open itself: a
     * platform that reads `orchestrator` before this returns performs the SQLite open on whatever
     * thread composed.
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
            closeNetworkLayer()
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
            // `stop()` returns without closing the network layer unless it actually transitioned
            // RUNNING→STOPPING. A container that never reached RUNNING (an `open()` that threw, so
            // `start()` rolled the phase back to IDLE) would therefore have had its three Ktor
            // clients and the shared OkHttp/Darwin engine leaked with nothing left to close them:
            // `stop()` cannot be retried (the CAS is the whole mutual exclusion) and the graph is
            // terminal. Both closes are idempotent, so the RUNNING path pays nothing for this.
            closeNetworkLayer()
            log.i("lifecycle", "shutdown session=$sessionId")
            // The line above is emitted *after* `cancelAndJoin` has already cancelled the log
            // sink's writer coroutine, so the channel it lands in has no reader left. Flushing
            // here is what puts the terminal line on disk: without it the trail ends at "stop",
            // the graph's final state is missing, and a teardown crash is indistinguishable from
            // a clean `stop()`.
            closeLogTrail()
        } finally {
            phase.store(Lifecycle.IDLE)
        }
    }

    /**
     * Joins the [open] coroutine, then closes the three Ktor clients and the shared engine.
     *
     * The join is first and load-bearing: `openOnIo` builds `net` (three `HttpClient`s on the
     * shared engine) and publishes it into [netRef], so closing before the open finishes either
     * closes clients that are about to be replaced by a fresh set nobody will ever close, or —
     * if `netRef.exchange` already ran — lets `buildNet()` publish a graph of *closed* clients
     * into the ref after teardown has finished.
     *
     * Split out of [stop] so [shutdown] gets it too: `stop()` returns without closing anything
     * unless it actually transitioned RUNNING→STOPPING, so a container whose `open()` threw (phase
     * rolled back to IDLE) and which is then shut down would otherwise leak its clients and its
     * engine with nothing left to release them — `stop()` cannot be retried, because the CAS *is*
     * the whole mutual exclusion.
     */
    private suspend fun closeNetworkLayer() {
        openJob.load()?.let { cancelAndJoin(listOf(it)) }
        netRef.exchange(null)?.close()
        runCatching { httpEngine.close() }
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
        // Lost the race to `shutdown()` before there was even a job: this container will never
        // open, and `opened` is awaited — unbounded — by `open()` and by all four `bgJobs`, so
        // leaving it pending parks every caller for the rest of the process. Settle it here
        // instead; `open()` on a container that has been shut down is a usage error and says so.
        if (shutDown.load()) {
            opened.completeExceptionally(IllegalStateException("container was shut down before open()"))
            return
        }
        val job =
            scope.launch(disp.on(Lane.IO)) {
                runCatching { openOnIo() }
                    .onSuccess { opened.complete(Unit) }
                    .onFailure { t ->
                        opened.completeExceptionally(t)
                        log.e("boot", "graph open failed: ${t.message}")
                    }
            }
        // `opened` is settled by the body's `runCatching` — including the `CancellationException`
        // that a mid-flight cancel throws — but a job cancelled *before its body first dispatched*
        // never runs it. That was a real hang: `stop()` can return without having reached RUNNING,
        // so `shutdown()` would set `shutDown`, `launchOpen` would cancel the freshly stored job,
        // and nothing would ever complete `opened`. Whichever thread settles it wins; the other's
        // `completeExceptionally` on an already-completed deferred is a no-op.
        job.invokeOnCompletion { cause ->
            if (!opened.isCompleted) {
                opened.completeExceptionally(cause ?: IllegalStateException("open() was cancelled"))
            }
        }
        openJob.store(job)
        // Lost the race to shutdown(): that container will never open, so cancel immediately
        // rather than leaving an open running against a closed engine.
        if (shutDown.load()) job.cancel()
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
                // Two reasons to do nothing this tick, and they are the *shape* of the tick rather
                // than two early exits out of it: a metered link must not spend cellular on a scan,
                // and a scan that runs while a transfer is in flight competes with it for the db
                // lane it reads on. Written as one positive condition so "skip" is stated once.
                if (netMonitor.current() != NetClass.METERED &&
                    cacheManager.inFlightJobKeys.value.isEmpty()
                ) {
                    runCatching { scanQualityUpgrades() }
                        .onSuccess { n -> if (n > 0) log.i("qualityScan", "enqueued $n upgrades") }
                        .onFailure { log.e("qualityScan", it.message ?: "failed") }
                }
            }
        }

    /**
     * One round trip. The predicate, the `has_320` filter, the `ORDER BY` and the `LIMIT` are all in
     * SQL, so the cap bounds the query rather than a post-map `.take(n)`.
     *
     * This used to `selectAllCached()` and then `selectSong()` **per row**, on this same db lane,
     * every 30 minutes over the whole cached catalogue: the limit bounded nothing, and a 300-row
     * cache cost 301 round trips through the single-threaded db lane, blocking playback DB work for
     * the duration. [dylan.repo.upgradeCandidates] is the set-based form.
     */
    private suspend fun scanQualityUpgrades(): Int {
        val candidates = data.db.upgradeCandidates(disp, UPGRADE_FROM_BITRATE, MAX_UPGRADE_CANDIDATES)
        var enqueued = 0
        for (key in candidates) {
            if (key in cacheManager.inFlightJobKeys.value) continue
            // Dedupe: if already at 320 or upgrade intent pending, DownloadEngine will no-op.
            playback.downloads.enqueue(DownloadJob(key, Priority.QUALITY_UPGRADE, HIGH_BITRATE, cfg.clock.nowMs()))
            enqueued++
        }
        return enqueued
    }

    /**
     * Snapshot write + WS teardown + log drain, on the caller's thread — which is the **UI
     * thread** on Android (`MainActivity.onStop`) and the main queue on iOS (`.inactive`).
     *
     * It reads the *refs*, never the lazy properties. `playback`/`net` here would force
     * `buildPlayback()` → `buildNet()` and `data.db` → `LazyDatabase.value` on the thread that
     * must not block: a cold SQLite open (schema probe + create + four PRAGMAs), three Ktor
     * clients and the whole playback graph, inside `onStop`, for a graph that `open()` was
     * explicitly deferred off the main thread to avoid. Reading the refs means a container that
     * has not finished opening simply has nothing to snapshot — which is exactly right: there is
     * no orchestrator state yet to persist and no socket to drop.
     *
     * `netRef` is the mirror [net] writes in its initialiser, so once the graph is built this is
     * behaviourally identical to the previous `net.searchChannel.onBackground()`.
     */
    fun onBackground() {
        playbackRef.load()?.orchestrator?.onBackground()
        netRef.load()?.searchChannel?.onBackground()
        // Fire-and-forget drain with timeout — at most LOG_FLUSH_TIMEOUT_MS of background budget.
        fileLogRef.load()?.let { sink ->
            scope.launch(disp.on(Lane.IO)) { runCatching { sink.flush(LOG_FLUSH_TIMEOUT_MS) } }
        }
    }

    private suspend fun closeLogTrail() {
        val sink = fileLogRef.load() ?: return
        runCatching { sink.flush(LOG_FLUSH_TIMEOUT_MS) }
        runCatching { sink.close() }
    }

    /**
     * Cancel then join each job, tolerating a caller that IS one of them.
     *
     * The self-check is what makes this safe to point at any coroutine in the container: joining a
     * job from inside that same job waits for a completion that cannot happen without the caller,
     * so the teardown hangs forever. `openJob` is reachable this way because `openOnIo` runs in a
     * `runCatching` whose failure path can re-enter the container.
     */
    private suspend fun cancelAndJoin(jobs: List<Job>) {
        val me = kotlin.coroutines.coroutineContext[Job]
        for (j in jobs) j.cancel()
        withContext(NonCancellable) {
            for (j in jobs) {
                if (j === me) continue
                j.join()
            }
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

        /** Handshake + round-trip budget for the search socket; `socketTimeoutMillis` is Ktor's. */
        private const val WS_CONNECT_TIMEOUT_MS = 8_000L
        private const val WS_REQUEST_TIMEOUT_MS = 8_000L

        /** Bits per emitted hex digit: 4 bits is exactly one of the 16 characters in the alphabet. */
        private const val HEX_DIGIT_BITS = 4

        /** UUID-shaped grouping of [HEX_DIGIT_BITS]-bit digits, dash-separated as it is stored. */
        private val SESSION_ID_HEX_GROUPS = listOf(8, 4, 4, 4, 12)

        private fun newSessionId(): String {
            val hex = "0123456789abcdef"

            fun h(n: Int) = buildString { repeat(n) { append(hex[Random.nextBits(HEX_DIGIT_BITS)]) } }
            return SESSION_ID_HEX_GROUPS.joinToString("-") { h(it) }
        }
    }
}

/**
 * One build per graph member, for the life of the process.
 *
 * This was `LazyThreadSafetyMode.PUBLICATION`, with a comment claiming "no lock … publication is
 * enough". That was backwards: `PUBLICATION` guarantees only that the *value* is safely
 * published, and it **explicitly permits the initialiser to run more than once concurrently** —
 * `SYNCHRONIZED` is the mode that runs it once.
 *
 * It was not academic. Every builder here has heavy, unrepeatable side effects — three Ktor
 * clients on the shared engine ([buildNet]), two download worker coroutines plus an
 * `Orchestrator` inbox and states collector on a fresh `SupervisorJob` ([buildPlayback]) — and a
 * cold Android start races the main thread (the first composition reads `container.orchestrator`)
 * against the io lane (`[openOnIo]`) for both. A losing build leaked every one of those, and
 * could publish a graph of clients [closeNetworkLayer] had already closed.
 *
 * `SYNCHRONIZED` holds the *caller* too, so a graph read blocks until whoever is building it
 * finishes. That is the intended trade: waiting for a build already under way is strictly better
 * than duplicating it, and the alternative — building on the reader's thread — is what
 * [LazyDatabase] then has to pay for.
 */
internal inline fun <T> lazyGraph(noinline build: () -> T): Lazy<T> = lazy(LazyThreadSafetyMode.SYNCHRONIZED, build)

private fun <T> AtomicReference<List<T>>.append(item: T) {
    while (true) {
        val cur = load()
        if (compareAndSet(cur, cur + item)) return
    }
}
