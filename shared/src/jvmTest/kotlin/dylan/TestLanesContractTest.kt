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
import kotlinx.coroutines.test.runTest
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
 * actively *violated* by the harness. A missing `withContext(disp.on(Lane.STATE))` hop was therefore
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
        // The single-lane ceiling, not the multi-lane one. This is a *negative*: two tasks on one
        // lane would overlap the moment the first released the permit, which
        // `stateLaneIsSinglePermit` already shows happens inside `RENDEZVOUS_TIMEOUT_MS`. Paying
        // the 2 s multi-lane ceiling here bought no extra confidence and cost 1.75 s of wall clock.
        assertFalse(
            lanesOverlapBlocking(
                lanes.disp,
                listOf(Lane.STATE to 1, Lane.STATE to 1),
                timeoutMs = dylan.support.RENDEZVOUS_TIMEOUT_MS,
            ),
            "a single limitedParallelism(1) lane cannot hold two tasks",
        )
    }

    @Test
    fun ioOverlapsTheSerializedLanesOnRealThreads() {
        assertTrue(
            lanesOverlapBlocking(lanes.disp, listOf(Lane.STATE to 1, Lane.IO to 1)),
            "withContext(disp.on(Lane.IO)) genuinely overlaps the state lane, as on device",
        )
    }

    /**
     * The production profile runs on the wall clock, and this is *why* it has to.
     *
     * `Dispatchers.Default` is not a `Delay`, so `limitedParallelism(1)` over it falls back to the
     * global `DefaultDelay` and a 25 ms lane delay really costs 25 ms. Pinned because it is the
     * reason a test on this profile must never assert on elapsed time, and because the virtual
     * profile below is only a different answer to the same question, not a different question.
     */
    @Test
    fun productionLaneDelaysAreRealTime() =
        runBlocking {
            val before = System.nanoTime()
            withContext(lanes.disp.state) { delay(REAL_LANE_DELAY_MS) }
            val elapsedMs = (System.nanoTime() - before) / 1_000_000
            assertTrue(
                elapsedMs >= REAL_LANE_DELAY_MS,
                "a production-profile state-lane delay is real time; got ${elapsedMs}ms",
            )
        }

    /**
     * The fix, pinned in the direction that matters. `AppDispatchers` hands back the *raw*
     * dispatcher, so a `TestDispatcher` can be installed and `LimitedDispatcher` — which *is* a
     * `Delay`, constructed as `dispatcher as? Delay ?: DefaultDelay` — routes the delay into the
     * shared scheduler. The same `limitedParallelism(1)` that buys the serialization contract also
     * keeps virtual time reachable, which it did not when `AppDispatchers` wrapped its lanes.
     *
     * Without this the whole suite has to sleep through its own timeouts; with it a 10-minute
     * backoff is ten microseconds of scheduler. The single-permit claim is checked here too, so
     * "virtual" can never quietly mean "unserialised".
     */
    @Test
    fun aVirtualLaneIsStillSinglePermitAndItsDelaysAreFree() =
        runTest {
            val virtual = TestLanes.virtual(testScheduler)
            // The barrier can only trip if two tasks are in the lane at once, which one driver
            // thread makes impossible. A short ceiling is therefore the whole proof.
            assertFalse(
                laneAdmitsOverlap(virtual.disp, Lane.STATE, timeoutMs = VIRTUAL_RENDEZVOUS_MS),
                "virtual time must not cost the single-permit contract",
            )
            withContext(virtual.disp.state) { delay(REAL_LANE_DELAY_MS) }
            assertEquals(REAL_LANE_DELAY_MS, testScheduler.currentTime, "the lane delay is virtual, not wall clock")
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

/** A virtual lane has one driver thread, so "no overlap" needs no time at all to establish. */
private const val VIRTUAL_RENDEZVOUS_MS = 25L

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
