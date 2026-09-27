package dylan

import dylan.bridge.BridgeLanes
import dylan.bridge.Conflation
import dylan.bridge.FlowAdapter
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.di.AppContainer
import dylan.diag.FileLogSink
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.model.SongKey
import dylan.playback.PlayerEngine
import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.NetClass
import dylan.util.NetMonitor
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The graph's lifecycle contract, plus the cold-start numbers that justify moving the SQLite
 * open out of the constructor. Everything runs against a real [DriverFactory] on a real temp
 * directory: a fake driver would make the measurement meaningless, because the cost *is* the
 * file.
 */
class GraphLifecycleTest {
    private val tmp: String = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-graph-${System.nanoTime()}"
    private val log = LogBuffer(minLevel = LogLevel.DEBUG)
    private val built = mutableListOf<AppContainer>()
    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, MAIN_THREAD) }
    private val disp =
        AppDispatchers(
            main = mainExecutor.asCoroutineDispatcher(),
            io = Dispatchers.IO,
            dbLane = Dispatchers.IO.limitedParallelism(1, "dbLane"),
            state = Dispatchers.Default.limitedParallelism(1, "state"),
        )

    @BeforeTest
    fun setup() {
        FileSystem.SYSTEM.createDirectories(tmp.toPath())
    }

    @AfterTest
    fun teardown() {
        runBlocking { built.forEach { runCatching { it.shutdown() } } }
        built.clear()
        mainExecutor.shutdownNow()
    }

    // ---- cold start -------------------------------------------------------------------------

    @Test
    fun constructorPerformsNoIo() {
        val dir = freshDir("ctor")
        val fs = FileSystem.SYSTEM
        val c = newContainer(dir)

        assertFalse(fs.exists((dir.toPath() / "audio")), "ctor created the audio dir")
        assertFalse(fs.exists((dir.toPath() / "logs")), "ctor created the logs dir")
        assertFalse(fs.exists(dir.toPath() / "dylan.db"), "ctor opened SQLite")

        runBlocking { c.open() }
        assertTrue(fs.exists((dir.toPath() / "audio")), "open() did not create the audio dir")
        assertTrue(fs.exists((dir.toPath() / "logs")), "open() did not create the logs dir")
        assertTrue(fs.exists(dir.toPath() / "dylan.db"), "open() did not open SQLite")
    }

    @Test
    fun openIsIdempotentAndNeverWipes() {
        val c = newContainer(freshDir("idem"))
        runBlocking {
            c.open()
            c.open()
            c.open()
        }
        // A second open on an existing database would re-enter the driver's destructive fallback.
        assertFalse(log.dump().any { it.msg.contains("wiping dev DB") }, "repeat open() wiped the library")
    }

    @Test
    fun coldStartCostsMoveFromTheConstructorToTheIoLane() {
        val fresh = freshDir("cold")
        val warm = warmDir()
        val mkdirAudio =
            median(COLD_SAMPLES) {
                Paths((freshDir("m").toPath() / "audio"), FileSystem.SYSTEM)
            }
        val mkdirLogs = median(COLD_SAMPLES) { FileSystem.SYSTEM.createDirectories(freshDir("l").toPath() / "logs") }
        val openNew = median(COLD_SAMPLES) { DriverFactory(freshDir("d").toString() + "/dylan.db", log).createDriver() }
        val openExisting = median(COLD_SAMPLES) { DriverFactory(warm + "/dylan.db", log).createDriver() }
        val eagerCtor = median(COLD_SAMPLES) { eagerCtorCost(fresh) }
        val ctorOnly = median(COLD_SAMPLES) { newContainer(freshDir("ctoronly")) }
        val ctorAndOpen = median(COLD_SAMPLES) { newContainer(freshDir("both")).also { runBlocking { it.open() } } }

        println(
            COLD_START_FMT.format(
                COLD_SAMPLES,
                mkdirAudio / MS,
                mkdirLogs / MS,
                openNew / MS,
                openExisting / MS,
                eagerCtor / MS,
                ctorOnly / MS,
                ctorAndOpen / MS,
            ),
        )
        assertTrue(ctorOnly * CTOR_IO_SHARE < eagerCtor, "constructor still does measurable I/O: ${ctorOnly / MS}ms")
        assertTrue(ctorAndOpen > ctorOnly, "open() did no work at all — the measurement is not measuring")
    }

    /** Exactly what the old constructor did on the caller's thread: two mkdirs, one SQLite open. */
    private fun eagerCtorCost(dir: String): Long {
        val sinkScope = CoroutineScope(SupervisorJob() + disp.io)
        return measureNanoTime {
            val fs = FileSystem.SYSTEM
            Paths((dir.toPath() / "audio"), fs)
            FileLogSink(fs = fs, dir = dir.toPath() / "logs", scope = sinkScope)
            DriverFactory(dir + "/dylan.db", log).createDriver()
        }.also { sinkScope.cancel() }
    }

    private fun warmDir(): String = freshDir("warm").also { dir -> runBlocking { newContainer(dir).open() } }

    // ---- lifecycle --------------------------------------------------------------------------

    @Test
    fun startIsIdempotentAndReEntrySafe() {
        val c = startedContainer("start")
        c.start()
        c.start()
        c.start()
        assertEquals(1, logLines("start session=").size, "start() ran more than once")
        assertEquals(BACKGROUND_JOBS, c.backgroundJobCount)
    }

    @Test
    fun stopThenStartRestartsTheBackgroundWork() {
        val c = startedContainer("cycle")
        runBlocking { awaitUntil("boot sweep", atLeast = 1) }
        runBlocking { c.stop() }
        assertEquals(1, logLines("stop session=").size, "stop() did not run exactly once")

        c.start()
        runBlocking { awaitUntil("boot sweep", atLeast = 2) }
        assertEquals(2, logLines("start session=").size, "restart did not run start() again")
    }

    /**
     * DI-1. The loop used to be launched into a constructor-owned `SupervisorJob` that `stop()`
     * cancelled for good, so one stop/start cycle bricked every download with no log line at all.
     * `cancel()` is the cheapest write the engine makes from inside its own scope: no provider,
     * no network, no file — so it is a pure liveness probe of the scope.
     */
    @Test
    fun downloadEngineSurvivesAStopStartCycle() {
        val c = startedContainer("di1")
        val engine = c.downloads
        val dead = SongKey("saavn", "dead")
        val alive = SongKey("saavn", "alive")

        engine.stop()
        engine.cancel(dead, keepPart = true)
        runBlocking { delay(SETTLE_MS) }
        assertFalse(dead in engine.states.value, "stop() left the engine scope live")

        engine.start()
        engine.cancel(alive, keepPart = true)
        val restarted =
            runBlocking {
                withTimeoutOrNull(RESTART_TIMEOUT_MS) { awaitCounter { alive in engine.states.value } }
            }
        assertTrue(restarted == true, "start() relaunched into the cancelled scope (DI-1)")
    }

    @Test
    fun noCoroutineOutlivesStop() {
        val c = startedContainer("stop")
        runBlocking { awaitUntil("boot sweep", atLeast = 1) }
        runBlocking { c.stop() }

        assertEquals(0, c.backgroundJobCount, "a background coroutine survived stop()")
        assertTrue(c.protectedKeys.value.isEmpty(), "protectedKeys was not reset by stop()")
    }

    @Test
    fun shutdownReachesComponentScopesAndIsTerminal() {
        val c = startedContainer("shutdown")
        val component = c.componentScope("probe")
        val ticks = AtomicInteger(0)
        val job = component.launch { ticks.incrementAndGet() }
        runBlocking { job.join() }
        assertEquals(1, ticks.get())

        runBlocking { c.shutdown() }
        assertFalse(job.isActive, "a component-scope coroutine survived shutdown()")
        c.start()
        assertEquals(0, c.backgroundJobCount, "a shut-down container accepted start()")
    }

    // ---- bridge -----------------------------------------------------------------------------

    @Test
    fun bridgeDeliversOnTheMainLane() {
        val lanes = BridgeLanes(disp, log, "test/bridge")
        val scope = CoroutineScope(SupervisorJob() + disp.io)
        val source = MutableStateFlow(0L)
        val threads = mutableListOf<String>()
        val laneTags = mutableListOf<Lane?>()
        val delivered = AtomicInteger(0)
        val sub =
            FlowAdapter(source, scope, lanes, Conflation.KEEP_EVERY).subscribe(
                onEach = {
                    threads += Thread.currentThread().name
                    laneTags += disp.current()
                    delivered.incrementAndGet()
                },
            )
        waitFor(delivered, 1)
        sub.cancel()
        scope.cancel()
        assertTrue(threads.isNotEmpty(), "nothing was delivered")
        assertTrue(threads.all { it.startsWith(MAIN_THREAD) }, "callback left the main lane: $threads")
        assertTrue(laneTags.all { it == Lane.MAIN }, "callback ran off the main lane: $laneTags")
    }

    @Test
    fun keepEveryDoesNotRenderFramesSwiftWouldDiscard() {
        val lanes = BridgeLanes(disp, log, "test/bridge")
        val scope = CoroutineScope(SupervisorJob() + disp.io)
        val source = MutableStateFlow(PlayerState())
        val delivered = AtomicInteger(0)
        val sub =
            FlowAdapter(source, scope, lanes, Conflation.KEEP_EVERY).subscribe(
                onEach = { delivered.incrementAndGet() },
            )
        waitFor(delivered, 1)
        val playing = PlayerState(repeat = Repeat.ONE)
        source.value = playing
        waitFor(delivered, 2)
        // A burst of structurally identical states: StateFlow conflates by equals, so a data
        // class payload means SwiftUI is handed each phase change once and never a stale frame.
        repeat(BURST) { source.value = playing }
        runBlocking { delay(SETTLE_MS) }
        sub.cancel()
        scope.cancel()
        assertEquals(2, delivered.get(), "KEEP_EVERY over-delivered on an identical StateFlow burst")
    }

    @Test
    fun latestOnlyCollapsesAHighRateBurst() {
        val lanes = BridgeLanes(disp, log, "test/bridge")
        val scope = CoroutineScope(SupervisorJob() + disp.io)
        val source = MutableStateFlow(0L)
        val delivered = AtomicInteger(0)
        val last = AtomicInteger(0)
        val sub =
            FlowAdapter(source, scope, lanes).subscribe(
                onEach = {
                    delivered.incrementAndGet()
                    last.set(it.toInt())
                    Thread.sleep(BURST_SLEEP_MS)
                },
            )
        waitFor(delivered, 1)
        repeat(BURST) { source.value = it.toLong() }
        runBlocking { delay(SETTLE_MS) }
        sub.cancel()
        scope.cancel()
        assertTrue(delivered.get() < BURST, "LATEST_ONLY delivered all $BURST values")
        assertEquals(BURST - 1, last.get(), "LATEST_ONLY dropped the newest value")
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun startedContainer(name: String): AppContainer =
        newContainer(freshDir(name)).also {
            runBlocking { it.open() }
            it.start()
        }

    private fun newContainer(dir: String): AppContainer {
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    disp.state +
                    CoroutineExceptionHandler { _, t -> log.e("test", "scope: ${t.message}") },
            )
        return AppContainer(
            cfg = AppConfig(),
            disp = disp,
            scope = scope,
            baseDir = dir,
            driverFactory = DriverFactory(dir + "/dylan.db", log),
            netMonitor = TestNet,
            httpEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
            engineFactory = { unusedEngine() },
            log = log,
        ).also { built += it }
    }

    private fun unusedEngine(): PlayerEngine = error("no engine in a graph lifecycle test")

    private fun freshDir(name: String): String = "$tmp-$name".also { FileSystem.SYSTEM.createDirectories(it.toPath()) }

    private fun logLines(fragment: String): List<String> = log.dump().map { it.msg }.filter { it.contains(fragment) }

    private suspend fun awaitUntil(
        fragment: String,
        atLeast: Int,
    ) {
        val ok =
            withTimeoutOrNull(RESTART_TIMEOUT_MS) {
                while (logLines(fragment).size < atLeast) delay(POLL_MS)
                true
            }
        assertEquals(true, ok, "timed out waiting for $atLeast × '$fragment'")
    }

    private suspend fun awaitCounter(predicate: () -> Boolean): Boolean {
        while (!predicate()) delay(POLL_MS)
        return true
    }

    private fun waitFor(
        counter: AtomicInteger,
        target: Int,
    ) {
        val deadline = System.nanoTime() + RESTART_TIMEOUT_MS * MS
        while (counter.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
        }
    }

    private fun median(
        n: Int,
        block: () -> Unit,
    ): Long = (1..n).map { measureNanoTime(block) }.sorted()[n / 2]

    private object TestNet : NetMonitor {
        override fun current(): NetClass = NetClass.UNMETERED

        override fun isOnline(): Boolean = true

        override fun changes(): Flow<NetClass> = MutableStateFlow(NetClass.UNMETERED)
    }

    private companion object {
        const val COLD_SAMPLES = 7
        const val BURST = 200
        const val BURST_SLEEP_MS = 2L
        const val POLL_MS = 5L
        const val SETTLE_MS = 300L
        const val RESTART_TIMEOUT_MS = 10_000L
        const val BACKGROUND_JOBS = 4
        const val CTOR_IO_SHARE = 4
        const val MS = 1_000_000.0
        const val MAIN_THREAD = "bridgeMain"
        const val COLD_START_FMT =
            "cold-start ms (median of %d) — mkdir audio=%.3f mkdir logs=%.3f " +
                "driver new=%.3f driver existing=%.3f | eager ctor total=%.3f " +
                "new ctor=%.3f new ctor+open=%.3f"
    }
}
