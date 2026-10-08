package dylan

import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.di.AppContainer
import dylan.di.LazyDatabase
import dylan.di.lazyGraph
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.playback.PlayerEngine
import dylan.util.AppDispatchers
import dylan.util.NetClass
import dylan.util.NetMonitor
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Graph construction under concurrency — the properties that make the *deferred* open sound.
 *
 *  * a graph member is built exactly once, however many threads reach it at the same instant;
 *  * the SQLite driver is created exactly once, however many threads read the data graph cold;
 *  * `open()` settles on every path, including the one where `shutdown()` won the race.
 *
 * Each test turns the race into a schedule rather than hoping for one: every thread is released
 * from a start latch together, and the thing under contention blocks inside itself until all its
 * contenders have arrived — bounded, because with the fix they never do, and that timeout *is* the
 * assertion.
 */
class GraphConcurrencyTest {
    private val tmp: String = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-conc-${System.nanoTime()}"
    private val log = LogBuffer(minLevel = LogLevel.DEBUG)
    private val built = mutableListOf<AppContainer>()
    private val disp =
        AppDispatchers(
            main = Dispatchers.Default.limitedParallelism(1, "main"),
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
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    // ---- C2: one build per graph member -----------------------------------------------------

    /**
     * The red/green proof for the `lazyGraph` thread-safety mode.
     *
     * `PUBLICATION` and `SYNCHRONIZED` differ in exactly one observable way — whether the
     * initialiser may run twice at once — so the test builds a lazy whose builder parks inside
     * itself until every contender has entered it, and counts the entries.
     *
     * Under `PUBLICATION` the barrier trips (every thread is inside the builder simultaneously) and
     * the count is [CONTENDERS]. Under `SYNCHRONIZED` the first thread holds the lazy's lock, the
     * rest queue on it, the barrier times out, and the count is 1. The timeout cannot manufacture a
     * false green for the broken build: the first thread publishes nothing until the barrier trips
     * *or* expires, so a count of 1 under `PUBLICATION` would need the other seven threads to stay
     * unscheduled for the whole window while already past the start latch and runnable.
     */
    @Test
    fun aGraphMemberIsBuiltOnceWhenEveryThreadReachesItAtTheSameInstant() {
        val entries = AtomicInteger(0)
        val allArrived = CyclicBarrier(CONTENDERS)
        val graph =
            lazyGraph {
                entries.incrementAndGet()
                runCatching { allArrived.await(BARRIER_WINDOW_MS, TimeUnit.MILLISECONDS) }
                Any()
            }

        val values = race(CONTENDERS) { graph.value }

        assertEquals(1, entries.get(), "the graph builder ran ${entries.get()}× under a $CONTENDERS-way race")
        assertEquals(1, values.toSet().size, "concurrent readers saw more than one published instance")
    }

    /**
     * The same property through the real container. On a cold Android start the main thread (the
     * first composition reads `container.orchestrator`) and the io lane ([`openOnIo`]) both reach
     * `net` and `playback`, and every one of those builders leaks something unrepeatable if it runs
     * twice: three Ktor clients on the shared engine, two download workers, an `Orchestrator`
     * inbox and states collector, a component `SupervisorJob`.
     *
     * Asserted as behaviour rather than as a counter: after a storm from every graph at once the
     * container must still open, and every graph must still be one instance.
     */
    @Test
    fun aColdStartStormOverEveryGraphLeavesOneWorkingContainer() {
        val c = newContainer(freshDir("storm"))
        race(CONTENDERS) {
            when (Thread.currentThread().id % 4) {
                0L -> c.net
                1L -> c.playback
                2L -> c.data
                else -> c.files
            }
            null
        }

        val opened =
            runBlocking {
                withTimeoutOrNull(OPEN_CEILING_MS) {
                    c.open()
                    true
                }
            }
        assertEquals(true, opened, "open() did not complete after a concurrent storm over every graph")

        val orchestrators = race(4) { c.playback.orchestrator }
        assertEquals(1, orchestrators.toSet().size, "two threads saw two different playback graphs")
        assertSame(orchestrators.first(), c.orchestrator, "the playback graph is not a singleton")
    }

    // ---- H1: one driver for the process -----------------------------------------------------

    /**
     * A cold read of the data graph is the main thread's first act on Android, and it runs on
     * whichever thread got there first. What has to hold either way is that only **one** of them
     * opens SQLite: the previous shape compared-and-set the *result*, so every thread that arrived
     * before the winner published ran the open too — two `Schema.create`s against one path, on the
     * same file, concurrently — and the loser was closed only afterwards.
     *
     * What this does **not** claim is that the cold read is cheap: it still opens the database on
     * the caller's thread when the caller's thread gets there first. That is a platform contract
     * (gate the first read on `open()`), and `aColdGraphReadSaysSoExactlyOnce` is what keeps it
     * visible.
     */
    @Test
    fun aColdGraphReadOpensTheDriverOnceHoweverManyThreadsRaceForIt() {
        val dir = freshDir("coldread")
        val driverFactory = DriverFactory(dir + "/dylan.db", log)
        val opens = AtomicInteger(0)
        val allArrived = CyclicBarrier(CONTENDERS)
        val db =
            LazyDatabase(
                createDriver = {
                    opens.incrementAndGet()
                    runCatching { allArrived.await(BARRIER_WINDOW_MS, TimeUnit.MILLISECONDS) }
                    driverFactory.createDriver()
                },
                log = log,
            )

        // A contender may *throw* here rather than return, and on the old code it usually does: two
        // `Schema.create` runs against one path collide in SQLite. That is a finding of its own, so
        // the outcome is collected rather than allowed to abort the run, and the assertions below
        // report both halves — how many times the open ran, and how many distinct handles came out.
        val outcomes = raceAllowingFailure(CONTENDERS) { db.value }

        assertEquals(1, opens.get(), "the SQLite open ran ${opens.get()}× for one process")
        val handles = outcomes.filterIsInstance<dylan.db.Dylan>()
        val failed = outcomes.filterIsInstance<Throwable>().map { it::class.simpleName }
        assertEquals(CONTENDERS, handles.size, "a racing cold read failed instead of waiting its turn: $failed")
        assertEquals(1, handles.toSet().size, "two racing threads were handed two different handles")
        assertTrue(db.isOpen, "the handle was never published")
        // The warm path must be an atomic load, not a second driver.
        assertSame(handles.first(), db.value, "the warm read handed back a different handle")
        assertEquals(1, opens.get(), "the warm read re-ran the open")
    }

    /**
     * The documented WARN is a real signal, not decoration: exactly one per process, naming what
     * the caller paid for. If it ever stops firing, the cold-read path is gone — or the platform is
     * being told nothing about an open it paid for on the UI thread.
     */
    @Test
    fun aColdGraphReadSaysSoExactlyOnce() {
        val dir = freshDir("warned")
        val db =
            LazyDatabase(
                createDriver = { DriverFactory(dir + "/dylan.db", log).createDriver() },
                log = log,
            )
        race(4) { db.value }
        val warns = log.dump().filter { it.msg.contains("graph read before open()") }
        assertEquals(1, warns.size, "expected exactly one WARN about a cold read, got ${warns.size}")
        assertTrue(db.isOpen, "the handle was never published")
    }

    // ---- H2: `open()` always settles -------------------------------------------------------

    /**
     * `launchOpen` cancels the open job when `shutDown` is already set, and a job cancelled before
     * its body first dispatches never runs it — so nothing ever completed `opened`, and `open()`
     * plus all four background jobs awaited it with no timeout, forever.
     *
     * `shutdown()` first is exactly the losing side of that race: `stop()` returns without having
     * reached RUNNING, so `shutDown` is already set when `launchOpen` runs. The assertion is that
     * `open()` *settles* — it must not park.
     */
    @Test
    fun openSettlesEvenWhenShutdownWonTheRace() {
        val c = newContainer(freshDir("shut"))
        runBlocking { c.shutdown() }

        val settled =
            runBlocking {
                withTimeoutOrNull(SETTLE_CEILING_MS) {
                    runCatching { c.open() }
                    true
                }
            }
        assertEquals(
            true,
            settled,
            "open() parked forever: `opened` was never completed on the shutdown-won path",
        )
        assertEquals(0, c.backgroundJobCount, "a shut-down container took background work")
    }

    /**
     * The same invariant under the race that produces it, rather than only on one side of it: a
     * caller parked in `open()` while another thread shuts the container down must be released
     * either way.
     *
     * *Released* means "returned or threw" — after the fix the shutdown-won interleaving is a
     * completed exceptionally, which is the honest answer for a caller that lost the race, and
     * `parked.isDone` is the property that distinguishes it from the old indefinite park.
     *
     * This one cannot be scheduled — `launchOpen` and `shutdown` are only microseconds apart — so
     * it is a soak, not a proof of the interleaving. What it does prove is that no interleaving of
     * the two leaves a caller parked forever, which is the property the teardown contract rests on.
     */
    @Test
    fun aCallerParkedInOpenIsReleasedWhenTheContainerShutsDownUnderIt() {
        repeat(SOAK_ROUNDS) {
            val c = newContainer(freshDir("soak$it"))
            val caller = Executors.newSingleThreadExecutor()
            try {
                val parked =
                    caller.submit<Boolean> {
                        runBlocking { c.open() }
                        true
                    }
                runBlocking { c.shutdown() }
                runCatching { parked.get(SETTLE_CEILING_MS, TimeUnit.MILLISECONDS) }
                assertTrue(parked.isDone, "round $it: open() was still parked ${SETTLE_CEILING_MS}ms after shutdown()")
            } finally {
                caller.shutdownNow()
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    /**
     * Runs [block] on [n] real threads released together, and returns every result.
     *
     * Real threads, not coroutines: the properties under test are about threads that reach a lazy
     * or a lock at the same instant, and a single-permit lane would serialise exactly the thing
     * being measured.
     */
    private fun <T> race(
        n: Int,
        block: () -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        try {
            val futures =
                List(n) {
                    pool.submit<T> {
                        start.await()
                        block()
                    }
                }
            start.countDown()
            return futures.map { it.get(RACE_CEILING_MS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** [race], but a thread that threw contributes its throwable instead of aborting the run. */
    private fun <T : Any> raceAllowingFailure(
        n: Int,
        block: () -> T,
    ): List<Any> =
        race(n) {
            try {
                block()
            } catch (t: Throwable) {
                t
            }
        }

    private fun newContainer(dir: String): AppContainer =
        AppContainer(
            cfg = AppConfig(),
            disp = disp,
            scope = CoroutineScope(SupervisorJob() + disp.state),
            baseDir = dir,
            driverFactory = DriverFactory(dir + "/dylan.db", log),
            fs = FileSystem.SYSTEM,
            netMonitor = TestNet,
            httpEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
            engineFactory = { unusedEngine() },
            log = log,
        ).also { built += it }

    private fun unusedEngine(): PlayerEngine = error("no engine in a graph concurrency test")

    private fun freshDir(name: String): String = "$tmp-$name".also { FileSystem.SYSTEM.createDirectories(it.toPath()) }

    private object TestNet : NetMonitor {
        override fun current(): NetClass = NetClass.UNMETERED

        override fun isOnline(): Boolean = true

        override fun changes(): Flow<NetClass> = MutableStateFlow(NetClass.UNMETERED)
    }

    private companion object {
        /** Enough threads that the lazy's own lock is the only thing keeping them apart. */
        const val CONTENDERS = 8

        /**
         * How long a builder waits for its rivals. With the fix nobody arrives and the wait is the
         * whole cost of the test; without it the barrier trips in microseconds.
         */
        const val BARRIER_WINDOW_MS = 1_500L
        const val SOAK_ROUNDS = 12
        const val RACE_CEILING_MS = 60_000L
        const val SETTLE_CEILING_MS = 10_000L
        const val OPEN_CEILING_MS = 30_000L
    }
}
