package dylan

import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.LaneTag
import dylan.util.LaneViolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lane contract: entering a lane publishes it, `on` tags the context, and the raw
 * `disp.state` properties stay usable so the existing `scope.launch(disp.on(Lane.STATE))` call sites
 * keep compiling *and* become checkable.
 */
class AppDispatchersTest {
    private val disp =
        AppDispatchers(
            Dispatchers.Main,
            Dispatchers.Default,
            Dispatchers.Default.limitedParallelism(1, "dbLane"),
            Dispatchers.Default.limitedParallelism(1, "state"),
        )

    @Test
    fun currentIsNullOffAnyLane() {
        assertNull(disp.current(), "a thread that never entered a lane has no lane")
    }

    @Test
    fun onPublishesTheLaneToTheThread() =
        runBlocking {
            assertEquals(
                Lane.STATE,
                disp.on(Lane.STATE) { disp.current() },
                "on(STATE) must be observable from the lane it dispatched to",
            )
            assertEquals(Lane.DB, disp.on(Lane.DB) { disp.current() })
            assertEquals(Lane.IO, disp.on(Lane.IO) { disp.current() })
        }

    @Test
    fun theRawPropertiesAreLanesTooSoExistingCallSitesAreEnforceable() =
        runBlocking {
            val fromRawState = withContext(disp.on(Lane.STATE)) { disp.current() }
            assertEquals(
                Lane.STATE,
                fromRawState,
                "scope.launch(disp.on(Lane.STATE)) must be assertable with no call-site change",
            )
            val fromRawDb = withContext(disp.on(Lane.DB)) { disp.current() }
            assertEquals(Lane.DB, fromRawDb)
        }

    @Test
    fun onTagsTheCoroutineContext() {
        runBlocking {
            assertEquals(Lane.IO, disp.on(Lane.IO) { disp.currentLane() }, "on() attaches a LaneTag")
            assertNotNull(disp.on(Lane.IO) { kotlin.coroutines.coroutineContext[LaneTag] })
        }
    }

    @Test
    fun enteringALaneRestoresThePreviousOne() =
        runBlocking {
            disp.on(Lane.STATE) {
                assertEquals(Lane.STATE, disp.current())
                disp.on(Lane.IO) {
                    assertEquals(Lane.IO, disp.current(), "nested on() republishes the inner lane")
                }
                // Back on the state lane's thread after the inner hop.
                assertTrue(disp.current() == Lane.STATE || disp.current() == null, "got ${disp.current()}")
            }
        }

    @Test
    fun limitedParallelismGivesMutualExclusionNotThreadAffinity() {
        // The architecture is described as "single-threaded", but limitedParallelism(1)
        // promises one *task* at a time. The pool may still hand the lane to more than one
        // thread over time, which is why assert() is keyed on a per-thread lane slot rather
        // than on a single thread.
        val inLane = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val threads = Collections.synchronizedSet(mutableSetOf<Long>())
        runBlocking {
            repeat(400) {
                disp.on(Lane.DB) {
                    val now = inLane.incrementAndGet()
                    while (true) {
                        val prev = maxConcurrent.get()
                        if (now <= prev || maxConcurrent.compareAndSet(prev, now)) {
                            break
                        }
                    }
                    threads.add(Thread.currentThread().id)
                    kotlinx.coroutines.yield() // suspension point INSIDE the lane
                    inLane.decrementAndGet()
                }
            }
        }
        assertEquals(1, maxConcurrent.get(), "limitedParallelism(1) must allow one task at a time")
        assertTrue(threads.isNotEmpty())
    }

    @Test
    fun assertThrowsWhenTheLaneIsWrongInEveryBuild() {
        // A lane violation throws LaneViolation, and it does so in RELEASE too. The old name and
        // the old message promised the opposite: `kotlin.assert` compiles out when assertions are
        // disabled, so the invariant was absent from every release build while this test (which runs
        // with -ea) reported it as covered. The test name's "inert when assertions are off" is now
        // the opposite of the behaviour, so it is renamed below rather than left asserting a
        // property the code deliberately no longer has.
        val e = assertFailsWith<LaneViolation> { disp.assert(Lane.STATE) }
        assertTrue("expected STATE" in e.message.orEmpty(), e.message.orEmpty())
        // A thread that never entered a lane is a violation too, not a silent pass.
        assertFailsWith<LaneViolation> { disp.assert(Lane.IO) }
    }

    /**
     * A thread dump has to be able to name the lane a task is on, so the four dispatchers must be
     * distinguishable — and since `AppDispatchers` stores the *raw* dispatchers (the publishing
     * wrapper is gone, and its absence is what makes virtual time reachable), the name in a thread
     * dump is exactly the name the graph passed to `limitedParallelism`.
     *
     * The old assertion pinned `"dylan.STATE"`, which was the removed wrapper's `toString()`. No
     * production graph has ever named a lane that way: `IosGraph` uses `state`/`dbLane` and
     * `DylanApp` passes no name at all, so the string asserted a convention the app does not have.
     */
    @Test
    fun everyLaneIsDistinguishableInAThreadDump() {
        val seen = listOf(disp.state, disp.dbLane, disp.io, disp.main).map { it.toString() }
        assertEquals(seen.size, seen.distinct().size, "two lanes are indistinguishable in a thread dump: $seen")
        assertEquals("state", disp.state.toString(), "a named limitedParallelism view must report its name")
        assertEquals("dbLane", disp.dbLane.toString())
        // The two unnamed lanes still have to be told apart from each other, which only the
        // underlying dispatcher's own name can do.
        assertTrue(
            disp.io.toString() != disp.main.toString(),
            "the io and main lanes are the same dispatcher here: ${disp.io}",
        )
    }
}
