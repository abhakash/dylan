package dylan

import dylan.support.FakePlayerEngine
import dylan.support.PARALLEL_RENDEZVOUS_TIMEOUT_MS
import dylan.support.RENDEZVOUS
import dylan.support.TestLanes
import dylan.support.laneAdmitsOverlap
import dylan.support.lanesOverlap
import dylan.support.peakInLaneConcurrency
import dylan.support.testTrack
import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lane contract itself — the tests that make the *harness* auditable.
 *
 * What they buy: before this, `state`/`dbLane` were `Dispatchers.Default` in every test, so the
 * single-task-at-a-time invariant the architecture rests on was not merely untested, it was
 * actively *violated* by the harness. A missing `withContext(disp.state)` hop was therefore
 * invisible in CI and only surfaced on device. [aMultiPermitLaneIsDetected] is the control that
 * proves the detector can see red.
 */
class TestLanesContractTest {
    private val lanes = TestLanes()

    @Test
    fun stateLaneIsSinglePermit() {
        assertFalse(
            laneAdmitsOverlapBlocking(lanes.disp, Lane.STATE),
            "state must be limitedParallelism(1): two tasks must never be in the lane at once",
        )
        assertEquals(1, peakInLaneBlocking(lanes.disp, Lane.STATE), "state lane peak occupancy")
    }

    @Test
    fun dbLaneIsSinglePermit() {
        assertFalse(
            laneAdmitsOverlapBlocking(lanes.disp, Lane.DB),
            "dbLane is the single-threaded SQLDelight lane; it cannot admit two writers",
        )
        assertEquals(1, peakInLaneBlocking(lanes.disp, Lane.DB), "dbLane peak occupancy")
    }

    @Test
    fun mainLaneIsSinglePermit() {
        assertFalse(laneAdmitsOverlapBlocking(lanes.disp, Lane.MAIN))
    }

    @Test
    fun aMultiPermitLaneIsDetected() {
        // The control. A multi-permit lane must be reported as one, otherwise every
        // "single-permit" assertion in this file is vacuous.
        val permissive = AppDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default)
        assertTrue(
            laneAdmitsOverlapBlocking(permissive, Lane.STATE, timeoutMs = PARALLEL_RENDEZVOUS_TIMEOUT_MS),
            "guard is vacuous: a multi-permit lane must be detected as one",
        )
    }

    @Test
    fun ioIsGenuinelyMultiThreaded() {
        assertTrue(
            laneAdmitsOverlapBlocking(lanes.disp, Lane.IO, timeoutMs = PARALLEL_RENDEZVOUS_TIMEOUT_MS),
            "io is Dispatchers.IO in production; the stress helper must actually overlap",
        )
    }

    @Test
    fun theStressHelperHoldsEightIoTasksAtOnce() {
        assertEquals(
            8,
            peakInLaneBlocking(lanes.disp, Lane.IO, n = 8, holdMs = PARALLEL_RENDEZVOUS_TIMEOUT_MS),
        )
    }

    /**
     * Two `limitedParallelism(1)` lanes are independent, so production genuinely can hold `state`
     * and `dbLane` occupied at the same time. Collapsing them onto one dispatcher would silently
     * remove every cross-lane interleaving the app has.
     */
    @Test
    fun twoSerializedLanesAreIndependentOfEachOther() {
        assertTrue(
            lanesOverlapBlocking(lanes.disp, listOf(Lane.STATE to 1, Lane.DB to 1)),
            "state and dbLane are two limitedParallelism(1) lanes: they must be occupiable together",
        )
    }

    @Test
    fun oneSerializedLaneIsNotIndependentOfItself() {
        assertFalse(
            lanesOverlapBlocking(lanes.disp, listOf(Lane.STATE to 1, Lane.STATE to 1)),
            "a single limitedParallelism(1) lane cannot hold two tasks",
        )
    }

    @Test
    fun ioOverlapsTheSerializedLanesOnRealThreads() {
        assertTrue(
            lanesOverlapBlocking(lanes.disp, listOf(Lane.STATE to 1, Lane.IO to 1)),
            "withContext(disp.io) genuinely overlaps the state lane, as on device",
        )
    }

    /**
     * Pinned, and load-bearing: `AppDispatchers.LaneDispatcher` deliberately does not implement
     * `Delay`, and `on(lane)` hands back that same wrapper, so **no** `delay()` inside a lane is
     * virtual — production or test. Every lane delay is a real `DefaultDelay` wake-up plus an
     * extra dispatch hop. Virtual time is therefore available only to *test-owned* dispatchers;
     * [FakePlayerEngine]'s event/position loop is one, which is how the 10 Hz position poll and
     * the 30 s listen heuristic became reachable at all.
     */
    @Test
    fun laneDelaysAreRealTimeNotVirtual() =
        runBlocking {
            val before = System.nanoTime()
            withContext(lanes.disp.state) { delay(REAL_LANE_DELAY_MS) }
            val elapsedMs = (System.nanoTime() - before) / 1_000_000
            assertTrue(
                elapsedMs >= REAL_LANE_DELAY_MS,
                "a state-lane delay is real time today (LaneDispatcher has no Delay); got ${elapsedMs}ms",
            )
        }

    /**
     * Relative perf bound, and the measurable payoff of putting the engine on a *test-owned*
     * virtual dispatcher: three minutes of 10 Hz position polling costs virtual milliseconds
     * instead of three minutes of wall time. Pre-wave there was no position ticking at all, so
     * this workload was untestable rather than slow.
     */
    @Test
    fun aVirtualPositionPollCostsNoWallClock() =
        FakePlayerEngine.soloTest { engine ->
            engine.prepare(listOf(testTrack("a", LONG_TRACK_MS)))
            engine.advanceUntilIdle()
            engine.play()
            engine.advanceUntilIdle()
            val before = System.nanoTime()
            engine.advanceVirtualTime(LONG_TRACK_MS)
            val elapsedNs = System.nanoTime() - before
            assertTrue(
                elapsedNs < WALL_CLOCK_CEILING_NS,
                "10 Hz polling across ${LONG_TRACK_MS / 1000}s of track time must be free, " +
                    "took ${elapsedNs / 1_000_000}ms",
            )
            assertTrue(
                engine.ticks >= POLL_ITERATIONS_FLOOR,
                "the poll must actually have run at least $POLL_ITERATIONS_FLOOR times, got ${engine.ticks}",
            )
        }
}

private const val REAL_LANE_DELAY_MS = 25L
private const val LONG_TRACK_MS = 180_000L
private const val POLL_ITERATIONS_FLOOR = 1_000
private const val WALL_CLOCK_CEILING_NS = 5_000_000_000L

private fun laneAdmitsOverlapBlocking(
    disp: AppDispatchers,
    lane: Lane,
    n: Int = RENDEZVOUS,
    timeoutMs: Long = dylan.support.RENDEZVOUS_TIMEOUT_MS,
) = runBlocking { laneAdmitsOverlap(disp, lane, n, timeoutMs) }

private fun lanesOverlapBlocking(
    disp: AppDispatchers,
    counts: List<Pair<Lane, Int>>,
    timeoutMs: Long = PARALLEL_RENDEZVOUS_TIMEOUT_MS,
) = runBlocking { lanesOverlap(disp, counts, timeoutMs) }

private fun peakInLaneBlocking(
    disp: AppDispatchers,
    lane: Lane,
    n: Int = RENDEZVOUS,
    holdMs: Long = dylan.support.RENDEZVOUS_TIMEOUT_MS,
) = runBlocking { peakInLaneConcurrency(disp, lane, n, holdMs) }
