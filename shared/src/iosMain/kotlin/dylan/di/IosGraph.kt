package dylan.di

import dylan.bridge.BridgeLanes
import dylan.bridge.FlowAdapter
import dylan.bridge.KotlinSubscription
import dylan.bridge.PlayerStateAdapter
import dylan.bridge.PositionAdapter
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.diag.logBufferExceptionHandler
import dylan.download.DownloadJob
import dylan.download.Priority
import dylan.model.DylanFailure
import dylan.model.MiniEntity
import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Quality
import dylan.model.Repeat
import dylan.model.Song
import dylan.model.SongKey
import dylan.model.message
import dylan.playback.IosPlayerEngine
import dylan.playback.NativeAudioOutput
import dylan.repo.toSong
import dylan.search.SaavnSearchChannel
import dylan.util.AppDispatchers
import dylan.util.IosNetMonitor
import dylan.util.Lane
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Settings > Storage figures (mirrors the cachedCountAndBytes row Android reads). */
data class CacheStats(
    val songCount: Long,
    val totalBytes: Long,
)

/** Library > Downloads row: admitted song joined with its cached_files row. */
data class CachedSongInfo(
    val song: Song,
    val bitrate: Int,
    val bytes: Long,
    val pinned: Boolean,
)

/**
 * Swift-facing surface over [SaavnSearchChannel] (§6.4): fire-and-forget demand + render-on-arrival.
 * The channel's `suggestions` StateFlow carries an [Answer], whose [Answer.epoch] is meaningless to
 * Swift, so this bridge delivers a plain (query, items) closure instead — same conflate, no
 * `flowOn`, main-lane delivery via [FlowAdapter].
 */
class IosSearchBridge internal constructor(
    private val channel: SaavnSearchChannel,
    private val scope: CoroutineScope,
    private val lanes: BridgeLanes,
) {
    fun warmUp() {
        channel.warmUp()
    }

    fun request(query: String) {
        channel.request(query)
    }

    fun onBackground() {
        channel.onBackground()
    }

    /** Latest answered query with its suggestions; empty pair until the first answer lands. */
    fun subscribeSuggestions(onEach: (String, List<MiniEntity>) -> Unit): KotlinSubscription =
        FlowAdapter(channel.suggestions.map { a -> (a?.query ?: "") to (a?.items ?: emptyList()) }, scope, lanes)
            .subscribe(onEach = { p -> onEach(p.first, p.second) })
}

/**
 * iOS graph bootstrap + Swift facade (§9.10/§9.11/§12.2).
 *
 * Construction reuses [AppContainer] verbatim — its init performs downloads.start(), the startup
 * reconciler run, restoreFromSnapshot() and the weekly GC + home-cache eviction loop exactly as on
 * Android/JVM. Every shared-layer fix (FIFO WS correlation, perma-token album lookup, part-cap,
 * dedupe) therefore ships by reuse; none of it is mirrored here.
 *
 * The framework block in shared/build.gradle.kts has no export() lines, so only this module's own
 * API crosses into Swift: members of [container] whose signatures use kotlinx.coroutines/okio types
 * are absent from the generated header by design. Swift talks to the graph exclusively through the
 * concrete-closure subscriptions and helpers below — the §9.11 "concrete adapters" decision applied
 * at graph level. [queueAsList]/[shuffleOrderAsList] exist because PersistentList does not bridge to
 * Swift collections (R4-F14).
 *
 * A flat Swift-facing facade by design: the threshold counts the Swift API surface, not logic.
 */
@Suppress("TooManyFunctions")
class IosGraph private constructor(
    val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val scope: CoroutineScope,
    val container: AppContainer,
    private val net: IosNetMonitor,
) {
    private var engine: IosPlayerEngine? = null

    /** Bridge lanes come from the graph, never from a hardcoded dispatcher. */
    private val lanes: BridgeLanes = BridgeLanes(disp, container.log)

    /** Search-tab entry point: WS warm-up + render-on-arrival suggestions (§6.4). */
    val search: IosSearchBridge by lazy(LazyThreadSafetyMode.PUBLICATION) {
        IosSearchBridge(
            container.searchChannel,
            scope,
            BridgeLanes(disp, container.log, "bridge/suggestions"),
        )
    }

    /** Toast surface for Swift; every toast is also mirrored into the LogBuffer. */
    var onToast: ((String) -> Unit)? = null

    init {
        // Assigned here, read on the state lane: the container owns the orchestrator's toast sink
        // so create() never has to touch the playback graph (and therefore never has to force the
        // SQLite open) on the main thread.
        container.onToast = { msg -> onToast?.invoke(msg) }
    }

    // ---- engine lifecycle (§4.3 attachable engine, §9.10) -------------------------------

    /** Builds the Kotlin reactive surface over the Swift output impl and attaches it. */
    fun attachAudio(output: NativeAudioOutput) {
        detachEngine()
        val e =
            IosPlayerEngine(output, scope, disp.main, container.log).also { engine = it }
        container.orchestrator.attachEngine(e)
    }

    fun detachEngine() {
        container.orchestrator.detachEngine()
        engine = null
    }

    // ---- environment pushes ---------------------------------------------------------------

    /** NWPathMonitor pushes here from Swift (D14: isExpensive || isConstrained). */
    fun pushMetered(isMetered: Boolean) {
        net.pushMetered(isMetered)
    }

    /** NWPathMonitor pushes here from Swift (path.status == .satisfied). */
    fun pushOnline(isOnline: Boolean) {
        net.pushOnline(isOnline)
    }

    fun onBackground() {
        // Delegates to container: snapshot write + WS close + fire-and-forget
        // FileLogSink.flush(2s) so background lines reach disk.
        container.onBackground()
    }

    /**
     * Terminal teardown. iOS had none: `AppEnvironment` only ever cancelled the path monitor, so
     * every Ktor client, the SQLite driver, the file-log handle and every collector the stores
     * bound in `init` outlived the graph for the process lifetime. Call it when the last store
     * releases the graph.
     */
    suspend fun dispose() {
        container.shutdown()
    }

    // ---- Flow→Swift subscriptions (§9.11) -------------------------------------------------

    fun subscribePlayerState(onEach: (PlayerState) -> Unit): KotlinSubscription = PlayerStateAdapter(scope, container.orchestrator.state, lanes).subscribe(onEach)

    fun subscribePosition(onEach: (Long) -> Unit): KotlinSubscription = PositionAdapter(scope, container.orchestrator.positionMs, lanes).subscribe(onEach)

    /** Per-key download ring (R7-P1); -1 means "no active job" so the closure stays unboxed Int. */
    fun subscribeProgress(
        key: SongKey,
        onEach: (Int) -> Unit,
    ): KotlinSubscription =
        FlowAdapter(
            container.downloads.progress
                .map { it[key] ?: -1 }
                .distinctUntilChanged(),
            scope,
            lanes,
        ).subscribe(onEach)

    // ---- bridging helpers -------------------------------------------------------------------

    fun queueAsList(state: PlayerState): List<Song> = state.queue.toList()

    fun shuffleOrderAsList(state: PlayerState): List<Int>? = state.shuffleOrder?.toList()

    /**
     * Phase/repeat as stable lowercase strings — Swift never spells Kotlin sealed-subclass or
     * enum-case names, so cinterop label mangling can never bite the UI layer.
     */
    fun phaseKind(state: PlayerState?): String =
        when (val p = state?.phase) {
            is Phase.Resolving -> "resolving"
            is Phase.Downloading -> "downloading"
            is Phase.Ready -> "ready"
            is Phase.Playing -> "playing"
            is Phase.Paused -> "paused"
            is Phase.Error -> "error"
            else -> "idle"
        }

    fun failureOf(state: PlayerState?): DylanFailure? = (state?.phase as? Phase.Error)?.failure

    fun repeatKind(state: PlayerState?): String =
        when (state?.repeat) {
            Repeat.ALL -> "all"
            Repeat.ONE -> "one"
            else -> "off"
        }

    /**
     * Transport truth: pause glyph (and lock-screen 1.0 rate) ONLY while actually
     * Playing. Ready is transient prepared-not-audible — showing pause there lied
     * on every track change until the first engine event. Converges within one
     * TrackChanged; mirrors Android AppRoot's `phase is Playing`.
     */
    fun playPauseShowsPause(state: PlayerState?): Boolean = state?.phase is Phase.Playing

    fun failureMessage(failure: DylanFailure): String = failure.message()

    // ---- settings / storage / library helpers (all suspend → Swift completion handlers) ------

    suspend fun isHighQualityPref(): Boolean = container.settings.qualityPref() == Quality.BITRATE_320

    /** Routes through Intent.SetQuality so persistence AND the upgrade trigger stay in shared. */
    suspend fun setHighQualityPref(high: Boolean) {
        container.settings.setQualityPref(if (high) Quality.BITRATE_320 else Quality.BITRATE_128)
        container.orchestrator.submit(dylan.playback.Intent.SetQuality(if (high) Quality.BITRATE_320 else Quality.BITRATE_128))
    }

    suspend fun cachedStats(): CacheStats =
        withContext(disp.on(Lane.DB)) {
            val r =
                container.db.dylanQueries
                    .cachedCountAndBytes()
                    .executeAsOne()
            CacheStats(r.song_count, r.total_bytes)
        }

    suspend fun libraryDownloads(): List<CachedSongInfo> =
        withContext(disp.on(Lane.DB)) {
            container.db.dylanQueries.selectAllCached().executeAsList().mapNotNull { row ->
                val s =
                    container.db.dylanQueries
                        .selectSong(row.provider, row.song_id)
                        .executeAsOneOrNull()
                        ?: return@mapNotNull null
                CachedSongInfo(
                    song = s.toSong(),
                    bitrate = row.bitrate.toInt(),
                    bytes = row.bytes,
                    pinned = row.pinned == 1L,
                )
            }
        }

    /** Album heart ⇒ favorites-all-tracks bulk download within the pinned budget (D12/§8.5). */
    suspend fun enqueueBulkDownloads(songs: List<Song>) {
        if (songs.isEmpty()) return
        val bits = container.settings.qualityPref().bits
        val now = cfg.clock.nowMs()
        songs.forEach { container.downloads.enqueue(DownloadJob(it.key, Priority.USER_BULK, bits, now)) }
        container.cacheManager.enforceBudget(netNewBytes = 0)
    }

    /** SongRow "Download now" context action: explicit USER_NOW at effective quality (§7.2). */
    suspend fun enqueueDownloadNow(song: Song) {
        val metered = container.netMonitor.current() == dylan.util.NetClass.METERED
        val bits = if (metered) cfg.meteredQuality.bits else container.settings.qualityPref().bits
        container.downloads.enqueue(DownloadJob(song.key, Priority.USER_NOW, bits, cfg.clock.nowMs()))
    }

    suspend fun clearCacheExcludingProtected(): Long = container.cacheManager.clearCacheExcludingProtected()

    /** Now-Playing quality chip: real cached bitrate of the current key; 0 when uncached. */
    suspend fun cachedBitrateOf(key: SongKey): Int =
        withContext(disp.on(Lane.DB)) {
            container.db.dylanQueries
                .selectCached(key.provider, key.songId)
                .executeAsOneOrNull()
                ?.bitrate
                ?.toInt() ?: 0
        }

    /** Library > Downloads swipe-remove: drop the cached row + its file; favorites/pins untouched. */
    suspend fun removeDownload(key: SongKey) {
        val row =
            withContext(disp.on(Lane.DB)) {
                container.db.dylanQueries
                    .selectCached(key.provider, key.songId)
                    .executeAsOneOrNull()
            }
        withContext(disp.on(Lane.DB)) { container.db.dylanQueries.deleteCached(key.provider, key.songId) }
        if (row != null) {
            val f = container.paths.final(key, row.bitrate.toInt(), row.ext)
            runCatching { container.fs.delete(f) }
        }
    }

    companion object {
        /**
         * baseDir = Application Support dir (Swift side). Audio lives at <baseDir>/audio per §8.2;
         * DB directory intentionally NOT backup-excluded (§5.6). Scope mirrors Android's
         * DylanApp.onCreate exactly (SupervisorJob + state lane + CEH, D23/B5a parity) — single
         * SupervisorJob shared between graph and container so a reconciler throw cannot kill one
         * half of the graph while leaving the other alive.
         */
        fun create(
            baseDir: String,
            logMinLevel: LogLevel = LogLevel.INFO,
        ): IosGraph {
            val cfg = AppConfig()
            // `Dispatchers.IO` is declared in kotlinx-coroutines' concurrent+native source set,
            // but on Native its Dispatchers member is `internal` (DefaultIoScheduler) and the
            // member shadows the public extension property, so user code cannot reach it — see
            // kotlin/native/src/Dispatchers.kt in kotlinx.coroutines. The shared worker pool is
            // therefore what backs `io` here, as on the JVM test target. The lane views are named
            // so a thread dump says which lane a thread belongs to.
            val disp =
                AppDispatchers(
                    main = Dispatchers.Main,
                    io = Dispatchers.Default,
                    dbLane = Dispatchers.Default.limitedParallelism(1, "dbLane"),
                    state = Dispatchers.Default.limitedParallelism(1, "state"),
                )
            // Built before the graph so the DB open, the CEH and the console mirror all share
            // one buffer — the file trail is the record, NSLog is only a mirror.
            val log = LogBuffer(minLevel = logMinLevel)
            log.bindSink { e -> platform.Foundation.NSLog("Dylan:%@ %@", e.tag, e.msg) }
            val stateJob = kotlinx.coroutines.SupervisorJob()
            val ceh = logBufferExceptionHandler(log, "scope") { m, _ -> platform.Foundation.NSLog("dylan:scope %@", m) }
            val sharedScope = CoroutineScope(stateJob + disp.state + ceh)
            val net = IosNetMonitor()
            val graph =
                IosGraph(
                    cfg = cfg,
                    disp = disp,
                    scope = sharedScope,
                    net = net,
                    container =
                        AppContainer(
                            cfg = cfg,
                            disp = disp,
                            scope = sharedScope,
                            baseDir = baseDir,
                            driverFactory = DriverFactory(log),
                            netMonitor = net,
                            httpEngine = Darwin.create(),
                            engineFactory = {
                                throw IllegalStateException("iOS engines are built by IosGraph.attachAudio")
                            },
                            log = log,
                        ),
                )
            // Directories, the SQLite open and the Ktor clients are deferred to the io lane by
            // start() so none of it runs inside @MainActor AppEnvironment.init, i.e. before the
            // first frame. A platform that can await it should instead do
            // `scope.launch { graph.container.open() }` (or call open() from its own loader) and
            // let start() join the same one-shot open.
            graph.container.start()
            return graph
        }
    }
}
