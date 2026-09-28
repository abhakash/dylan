package dylan.support

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.Cached_files
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.Priority
import dylan.model.Paged
import dylan.model.PlayerState
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.Intent
import dylan.playback.Orchestrator
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.repo.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The playback graph wired the way `AppContainer` wires it, minus the platform layers.
 *
 * Lanes come from [TestLanes], i.e. the production `limitedParallelism(1)` configuration, so the
 * serialization invariant is under test instead of bypassed. The engine is a [FakePlayerEngine] on a
 * **virtual** looper, so its 10 Hz position poll, its looper hop and the 30 s listen heuristic cost
 * no wall time. Lane delays stay real, because `AppDispatchers.LaneDispatcher` implements no
 * `Delay` — see [TestLanes].
 *
 * Every wait is event-driven ([awaitState], [awaitWindow], [settleIntents]) with a generous ceiling
 * that a healthy test never approaches, so no assertion in the graph suite pays for waiting.
 *
 * No virtual clock anywhere: the lanes are real, the engine's looper is real, and the only virtual
 * dispatcher in the suite belongs to the engine *contract* tests, where there is no graph whose
 * timeouts could be overtaken. A `runTest` here would make every `withTimeout` virtual while the
 * work it is waiting for happens on real threads, which fires the timeout in microseconds.
 */
class GraphHarness(
    val cfg: AppConfig,
    parent: CoroutineScope,
    val lanes: TestLanes = TestLanes(),
    val clock: MutableClock = MutableClock(),
    val net: FakeNetMonitor = FakeNetMonitor(online = true, netClass = dylan.util.NetClass.UNMETERED),
) {
    val log = LogBuffer(minLevel = LogLevel.DEBUG)
    val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    val fs: FileSystem = FileSystem.SYSTEM
    val root: Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-graph-${System.nanoTime()}").toPath()
    val db: Dylan
    val paths: Paths
    val cacheManager: CacheManager
    val downloads: DownloadEngine
    val settings: SettingsStore
    val orchestrator: Orchestrator
    val engine: FakePlayerEngine
    val provider = StubProvider()

    /** Child of [parent] so [close] can quiesce the graph before the SQLite file goes away. */
    private val job = SupervisorJob(parent.coroutineContext[Job])

    /**
     * Graph coroutines get a *local* exception handler. Without one, an exception raised while the
     * graph is being torn down lands in the process-wide handler, and `runTest` in a later, entirely
     * unrelated class then fails with `UncaughtExceptionsBeforeTest` — a phantom failure with no
     * visible connection to the code that caused it. [uncaughtExceptions] keeps it attributable.
     */
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    val uncaughtExceptions: List<Throwable> get() = uncaught.toList()

    val scope: CoroutineScope =
        CoroutineScope(
            parent.coroutineContext.minusKey(Job) + job + CoroutineExceptionHandler { _, t -> uncaught += t },
        )

    /** Body and status the next `bulk` request answers with. */
    var responseBody: ByteArray = ftypBody(1_000)
    var responseStatus: HttpStatusCode = HttpStatusCode.OK
    val requestedUrls: MutableList<String> = mutableListOf()

    init {
        fs.createDirectories(root)
        db = Dylan(DriverFactory("$root/dylan.db", log).createDriver())
        paths = Paths(root / "audio", fs)
        fs.createDirectories(paths.audioDir)
        cacheManager = CacheManager(db, fs, paths, protectedKeys, cfg, lanes.disp, log)
        downloads =
            DownloadEngine(
                db = db,
                fs = fs,
                paths = paths,
                cfg = cfg,
                disp = lanes.disp,
                provider = provider,
                bulk =
                    HttpClient(
                        MockEngine { request ->
                            requestedUrls += request.url.toString()
                            respond(
                                content = responseBody,
                                status = responseStatus,
                                headers =
                                    headersOf(
                                        "Content-Length" to listOf(responseBody.size.toString()),
                                        "Content-Type" to listOf("audio/mp4"),
                                    ),
                            )
                        },
                    ),
                breakers = Breakers(),
                cacheManager = cacheManager,
                netClass = { net.current() },
                qualityPref = { cfg.defaultQuality },
                log = log,
            )
        settings = SettingsStore(db, lanes.disp, cfg)
        orchestrator =
            Orchestrator(
                scope = scope,
                disp = lanes.disp,
                db = db,
                fs = fs,
                paths = paths,
                cfg = cfg,
                downloads = downloads,
                cacheManager = cacheManager,
                settings = settings,
                net = net,
                log = log,
            )
        engine = FakePlayerEngine.onRealLooper(scope)
        orchestrator.attachEngine(engine)
        downloads.start()
    }

    val state: PlayerState get() = orchestrator.state.value

    fun submit(intent: Intent) = orchestrator.submit(intent)

    /**
     * Returns the moment [predicate] holds. [ceiling] is a failure bound, not a sleep.
     *
     * The wait runs on a real dispatcher, not the `runTest` scheduler: the graph's lanes are real
     * `limitedParallelism(1)` pools, so a *virtual* `withTimeout` would jump the clock 20 s in
     * microseconds and fire before a single download completed. Real time here costs nothing,
     * because the wait ends the instant the state matches.
     */
    suspend fun awaitState(
        ceiling: Long = CEILING_MS,
        predicate: (PlayerState) -> Boolean,
    ): PlayerState = wallClock { withTimeout(ceiling) { orchestrator.state.first(predicate) } }

    private fun playing(state: PlayerState): Boolean = state.phase is dylan.model.Phase.Playing

    // Block body on purpose: ktlint has no max-line-length here, so an expression body is joined
    // back onto one line and then trips detekt's 120-char MaxLineLength.
    suspend fun awaitPlaying(ceiling: Long = CEILING_MS): PlayerState = awaitState(ceiling) { playing(it) }

    /**
     * Inbox barrier. `orchestrator.state` is a `StateFlow`, so a predicate over engine-side effects
     * (`seeks`, `preparedWindows`, toasts) is only re-evaluated when the *state* changes — and
     * `Intent.Seek` changes nothing. A full `CycleRepeat` round-trip is a mutation that always
     * produces three emissions; because the inbox is a single-permit lane and FIFO, observing the
     * last one proves everything submitted before it has been processed.
     */
    suspend fun settleIntents(ceiling: Long = CEILING_MS) {
        // Three, not two: the cycle is OFF → ALL → ONE → OFF, so only a multiple of three returns
        // to the starting value from every starting value.
        repeat(REPEAT_CYCLE_LENGTH) {
            val before = state.repeat
            submit(Intent.CycleRepeat)
            awaitState(ceiling) { it.repeat != before }
        }
    }

    /** Cycle repeat until it equals [target], awaiting every step. Deterministic at any start value. */
    suspend fun setRepeat(
        target: dylan.model.Repeat,
        ceiling: Long = CEILING_MS,
    ) {
        var guard = REPEAT_CYCLE_LENGTH
        while (state.repeat != target && guard-- > 0) {
            val before = state.repeat
            submit(Intent.CycleRepeat)
            awaitState(ceiling) { it.repeat != before }
        }
        check(state.repeat == target) { "could not reach $target from ${state.repeat}" }
    }

    /**
     * Wait until the engine has been handed a window containing [songRef], as `provider:songId`.
     *
     * itemIds are generation-stamped — `g<gen>:<provider>:<songId>:<bits>` (`SongKey.itemId`) — so
     * there is no `saavn:a` prefix left to match: the selector is matched inside the id, and the
     * generation is deliberately not part of the selector.
     */
    suspend fun awaitWindow(
        songRef: String,
        ceiling: Long = CEILING_MS,
    ) {
        val needle = ":$songRef:"
        wallClock { withTimeout(ceiling) { engine.awaitWindow { it.contains(needle) } } }
    }

    suspend fun awaitCachedRow(
        id: String,
        ceiling: Long = CEILING_MS,
    ): Cached_files {
        wallClock { withTimeout(ceiling) { downloads.states.first { SongKey("saavn", id) in it } } }
        return db.dylanQueries.selectCached("saavn", id).executeAsOne()
    }

    private suspend fun <T> wallClock(block: suspend () -> T): T = withContext(wall) { block() }

    fun seedCached(
        id: String,
        bytes: Int = 1_000,
        durationS: Long = 100L,
    ) {
        val key = SongKey("saavn", id)
        fs.write(paths.final(key, 128, "m4a")) { write(ftypBody(bytes)) }
        db.dylanQueries.insertSong("saavn", id, id, "", null, null, "", "", durationS, 1L, "enc-$id", null, 0L)
        db.dylanQueries.insertCached("saavn", id, 128L, "m4a", bytes.toLong(), 0L, null, 0L, 0L, null)
    }

    fun enqueue(
        key: SongKey,
        bits: Int = 128,
        reason: Priority = Priority.PREFETCH_NEXT,
    ) = downloads.enqueue(DownloadJob(key, reason, bits, clock.nowMs()))

    fun songs(vararg ids: String): List<Song> = ids.map { testSong(it) }

    /**
     * Suspend teardown. Cancelling the graph *before* deleting its SQLite file is not cosmetic:
     * the pre-wave teardown deleted the directory while the engine loop still held the connection,
     * and the resulting `SQLException` surfaced as `UncaughtExceptionsBeforeTest` in whichever test
     * happened to run next — a phantom failure with no connection to the test that caused it.
     */
    suspend fun close() {
        runCatching { orchestrator.detachEngine() }
        runCatching { downloads.stop() }
        runCatching { job.cancelAndJoin() }
        runCatching { fs.deleteRecursively(root) }
    }

    class StubProvider : MusicProvider {
        /** When set, [resolveStream] parks until completed — the "resolve never returns" case. */
        var gate: CompletableDeferred<Unit>? = null
        var resolveCalls = 0
        var resolveToNull = false

        override suspend fun search(
            query: String,
            page: Int,
        ): Paged<Song> = Paged(emptyList(), 0, page)

        override suspend fun album(id: String): dylan.model.Album? = null

        override suspend fun artist(id: String): dylan.model.Artist? = null

        override suspend fun home(): dylan.model.HomeFeed = dylan.model.HomeFeed(emptyList())

        override suspend fun topSearches(): List<dylan.model.MiniEntity> = emptyList()

        override suspend fun resolveStream(
            resolveRef: String,
            q: dylan.model.Quality,
        ): SignedStream? {
            resolveCalls++
            gate?.await()
            return if (resolveToNull) null else SignedStream("http://mock/audio", "mp4")
        }
    }

    companion object {
        /** Real-time context for the harness's own waits; see [awaitState]. */
        val wall =
            kotlinx.coroutines.Dispatchers.Default
                .limitedParallelism(1, "graph-await")

        /** Failure bound only — a passing test returns from every `await*` in microseconds. */
        const val CEILING_MS = 20_000L

        /** `CycleRepeat` is a 3-cycle: OFF → ALL → ONE → OFF. */
        const val REPEAT_CYCLE_LENGTH = 3

        /**
         * A *real* mp4 head: `ftyp` at 4 and a printable major brand at 8. The brand is not
         * decoration — `sniffContainer` requires it, so a fixture carrying `ftyp` alone is a body
         * the engine rejects as `CORRUPT_CONTAINER`, and every graph test that expected a track to
         * play reported the sniff and then timed out waiting for `Playing`.
         */
        fun ftypBody(size: Int): ByteArray {
            val b = ByteArray(size)
            "ftyp".encodeToByteArray().copyInto(b, 4, 0, minOf(4, size - 4).coerceAtLeast(0))
            "M4A ".encodeToByteArray().copyInto(b, 8, 0, minOf(4, size - 8).coerceAtLeast(0))
            return b
        }
    }
}
