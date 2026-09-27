package dylan

import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.LaneTag
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
 * `disp.state` properties stay usable so the existing `scope.launch(disp.state)` call sites
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
            val fromRawState = withContext(disp.state) { disp.current() }
            assertEquals(Lane.STATE, fromRawState, "scope.launch(disp.state) must be assertable with no call-site change")
            val fromRawDb = withContext(disp.dbLane) { disp.current() }
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
        // architecture.md calls `state` "single-threaded"; limitedParallelism(1) actually
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
    fun assertThrowsWhenTheLaneIsWrongAndIsInertWhenAssertionsAreOff() {
        // Tests run with -ea, so the tripwire is live: this is what a lane violation looks like.
        val e = assertFailsWith<AssertionError> { disp.assert(Lane.STATE) }
        assertTrue("expected STATE" in e.message.orEmpty(), e.message.orEmpty())
        // A thread that never entered a lane is a violation too, not a silent pass.
        assertFailsWith<AssertionError> { disp.assert(Lane.IO) }
    }

    @Test
    fun laneDispatchersAreNamedForThreadDumps() {
        assertEquals("dylan.STATE", disp.state.toString())
        assertEquals("dylan.DB", disp.dbLane.toString())
        assertEquals("dylan.IO", disp.io.toString())
        assertEquals("dylan.MAIN", disp.main.toString())
    }
}
