import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.di.AppContainer
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.util.AppDispatchers
import dylan.util.Connectivity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The persistent trail, as a component of the lifecycle rather than of the sink: what
 * [AppContainer] actually puts on disk, and what it used to lose.
 *
 * `AppContainer.closeLogTrail` is called from `stop()`. `shutdown()` — the terminal form every
 * teardown path is supposed to call — also cancels every `componentScope`, including the log
 * sink's writer, *and then logs its own last line*. That line went into a channel whose drain
 * loop had already been cancelled, so the trail ended at "stop" forever: the process's final
 * state was on neither disk nor console, and a teardown that crashed was indistinguishable from
 * a clean one several days later.
 */
class LogTrailLifecycleTest {
    private val tmp: String = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-trail-${System.nanoTime()}"
    private val log = LogBuffer(minLevel = LogLevel.DEBUG)
    private val built = mutableListOf<AppContainer>()
    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "trailMain") }
    private val disp =
        AppDispatchers(
            main = mainExecutor.asCoroutineDispatcher(),
            io = Dispatchers.IO,
            dbLane = Dispatchers.IO.limitedParallelism(1, "dbLane"),
            state = Dispatchers.Default.limitedParallelism(1, "state"),
        )

    @BeforeTest
    fun setup() = FileSystem.SYSTEM.createDirectories(tmp.toPath())

    @AfterTest
    fun teardown() {
        runBlocking { built.forEach { runCatching { it.shutdown() } } }
        built.clear()
        mainExecutor.shutdownNow()
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    @Test
    fun shutdownWritesItsOwnLastLineToTheFileTrail() =
        runBlocking {
            val c = startedContainer("shutdown")
            awaitUntil("boot sweep", atLeast = 1)

            runBlocking { c.shutdown() }

            val trail = trailText()
            assertTrue(
                "shutdown session=" in trail,
                "shutdown's own lifecycle line never reached the file trail. Trail:\n$trail",
            )
        }

    @Test
    fun stopWritesItsLastLineAndBackgroundFlushes() =
        runBlocking {
            val c = startedContainer("stop")
            awaitUntil("boot sweep", atLeast = 1)

            runBlocking { c.stop() }
            awaitLineInFile("stop session=")

            // `onBackground` is a *non-suspend* lifecycle hook (MainActivity.onStop /
            // RootView).background) that fires the drain with a timeout. Whatever it has queued
            // must be on disk without the caller having to await anything.
            c.log.i("lifecycle", "marked from the main thread while backgrounded session=mark")
            c.onBackground()
            awaitLineInFile("marked from the main thread while backgrounded session=mark")
        }

    /** A line written after the last flush must not be lost by `close()` alone. */
    @Test
    fun aLineLoggedAfterStopStillLandsInTheFile() =
        runBlocking {
            val c = startedContainer("late")
            awaitUntil("boot sweep", atLeast = 1)
            runBlocking { c.stop() }

            c.log.i("lifecycle", "emitted after stop session=late")
            awaitLineInFile("emitted after stop session=late")
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun startedContainer(name: String): AppContainer =
        newContainer(name).also {
            runBlocking { it.open() }
            it.start()
        }

    private fun newContainer(name: String): AppContainer {
        val dir = "$tmp-$name".also { FileSystem.SYSTEM.createDirectories(it.toPath()) }
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    disp.state +
                    CoroutineExceptionHandler { _, t -> log.e("test", "scope: ${t.message}") },
            )
        return AppContainer(
            cfg = dylan.config.AppConfig(),
            disp = disp,
            scope = scope,
            baseDir = dir,
            driverFactory = DriverFactory("$dir/dylan.db", log),
            fs = FileSystem.SYSTEM,
            netMonitor = TestNet,
            httpEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
            engineFactory = { error("no engine in a log-trail lifecycle test") },
            log = log,
        ).also { built += it }
    }

    /** Whatever the trail holds. The `0` file is the live one; archives are named `.1`, `.2`. */
    private fun trailText(): String {
        val dir = (built.last().baseDir.toPath() / "logs")
        return (0..2)
            .map { dir / "dylan.log.$it" }
            .filter { FileSystem.SYSTEM.exists(it) }
            .joinToString("\n") { p ->
                FileSystem.SYSTEM
                    .source(p)
                    .buffer()
                    .readUtf8()
            }
    }

    private suspend fun awaitLineInFile(fragment: String) {
        val ok =
            withTimeoutOrNull(10_000) {
                while (fragment !in trailText()) delay(25)
                true
            }
        assertTrue(ok == true, "timed out waiting for '$fragment' in the file trail.\n${trailText()}")
    }

    private fun logLines(fragment: String): List<String> = log.dump().map { it.msg }.filter { it.contains(fragment) }

    private suspend fun awaitUntil(
        fragment: String,
        atLeast: Int,
    ) {
        val ok =
            withTimeoutOrNull(10_000) {
                while (logLines(fragment).size < atLeast) delay(5)
                true
            }
        assertTrue(ok == true, "timed out waiting for $atLeast × '$fragment' in the ring")
    }

    private object TestNet : NetMonitor {
        override fun connectivity(): Flow<Connectivity> = MutableStateFlow(Connectivity(true, NetClass.UNMETERED))

        override fun current(): NetClass = NetClass.UNMETERED

        override fun isOnline(): Boolean = true
    }
}
