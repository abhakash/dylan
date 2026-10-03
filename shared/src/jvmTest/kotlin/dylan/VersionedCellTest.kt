package dylan

import dylan.util.VersionedCell
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [VersionedCell]'s contract: a producer that captured generation *g* and finished its I/O after
 * generation *g+1* existed cannot publish.
 *
 * This is the class H3 needed. `DylanMediaService` had `resumeFuture` and `resumeGeneration` as two
 * plain fields written from the `dylan-media` HandlerThread, the `state` lane (the collector and
 * the `onCreate` pre-warm, which are different coroutines and which suspend on I/O between their
 * two halves) and the main thread, guarded by "bumped on every state emission so a pre-warm cannot
 * cache the table it read before the move". That guard is a check-then-act over two unsynchronised
 * fields, so it does not hold: the pre-warm reads generation *n*, the collector bumps to *n+1* and
 * clears the future, and the pre-warm's re-read — a load free to still observe *n* — stores the
 * pre-move table after the invalidation. `androidApp` has no test source set, so the barrier is
 * pinned here, on the cell the service now calls.
 */
class VersionedCellTest {
    private val pool = Executors.newFixedThreadPool(THREADS)

    @AfterTest
    fun teardown() {
        pool.shutdownNow()
    }

    @Test
    fun aValuePublishedIntoTheCurrentGenerationIsVisible() {
        val cell = VersionedCell<String>()
        val g = cell.generation()
        assertTrue(cell.publishIfCurrent(g, "table"))
        assertEquals("table", cell.peek())
    }

    /**
     * THE interleaving, made into a schedule rather than a hope.
     *
     * Three steps, and every one of them is a real ordering the service can produce:
     *  1. the pre-warm reads the generation and starts its settings read + dbLane pass;
     *  2. the state collector runs `invalidate` for the emission that just happened;
     *  3. the pre-warm's I/O returns and it tries to publish what it read.
     *
     * Step 3 must fail. Before the cell there was nothing to make it fail — a re-read of
     * `resumeGeneration` after step 2 is a plain load, and the pre-warm's own store is a plain
     * store, so nothing ordered them and a stale table could be cached.
     */
    @Test
    fun aProducerThatCapturedTheGenerationBeforeAnInvalidationCannotPublishAfterIt() {
        val cell = VersionedCell<String>()
        val captured = cell.generation()
        val ioInFlight = CountDownLatch(1)
        val ioReturned = CountDownLatch(1)
        val published = AtomicInteger(-1)

        val prewarm =
            pool.submit {
                ioInFlight.countDown()
                ioReturned.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                // "the table it read before the move"
                cell.publishIfCurrent(captured, "pre-move table").let { published.set(if (it) 1 else 0) }
            }

        assertTrue(ioInFlight.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "precondition: the pre-warm started")
        // The state moved, while the pre-warm's I/O was still out.
        cell.invalidate()
        ioReturned.countDown()
        prewarm.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        assertEquals(0, published.get(), "publishing into a superseded generation must be refused")
        assertNull(cell.peek(), "a stale table must not be cached: the next controller would resume on it")
    }

    /** The mirror: an invalidation that lands *after* the store still wins, so the value is not kept. */
    @Test
    fun anInvalidationAfterThePublishStillRetiresTheValue() {
        val cell = VersionedCell<String>()
        assertTrue(cell.publishIfCurrent(cell.generation(), "table"))
        cell.invalidate()
        assertNull(cell.peek())
        assertFalse(cell.publishIfCurrent(0L, "late table"), "generation 0 is two behind now")
    }

    /**
     * A bumped generation is never lost. Two invalidations on the same cell must advance it twice,
     * or a producer holding the intermediate generation would be told it was still current.
     */
    @Test
    fun concurrentInvalidationsAllAdvanceTheGeneration() {
        val cell = VersionedCell<String>()
        val start = CyclicBarrier(THREADS)
        val done =
            (0 until THREADS).map {
                pool.submit {
                    start.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    repeat(INVALIDATES_EACH) { cell.invalidate() }
                }
            }
        done.forEach { it.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        assertEquals(
            (THREADS * INVALIDATES_EACH).toLong(),
            cell.generation(),
            "each invalidate is a CAS from the state it read, so none can be overwritten",
        )
    }

    /**
     * Racing callers must agree on ONE value, or `resumeNow` hands two `onPlaybackResumption`
     * callbacks two different tables and only the one it returns is filled.
     *
     * The discriminating assertion is the *identity* agreement, not the number of `make` calls: a
     * get-then-put loses the race by definition, so a loser may legitimately have built a value
     * ([VersionedCell.getOrPut] says so, and the loser must release it). What a get-then-put cannot
     * do is return its *own* value to a caller while the cell holds someone else's.
     */
    @Test
    fun racingCallersOfGetOrPutAllReceiveTheSameValue() {
        repeat(GET_OR_PUT_ROUNDS) {
            val cell = VersionedCell<Any>()
            val start = CyclicBarrier(THREADS)
            val seen = CopyOnWriteArrayList<Any>()
            val futures =
                (0 until THREADS).map {
                    pool.submit {
                        start.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        cell.getOrPut { Any() }.also { seen += it }
                    }
                }
            futures.forEach { it.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            assertEquals(THREADS, seen.size, "every caller must return")
            assertSame(seen[0], cell.peek(), "the cell must hold the value the callers were given")
            assertEquals(1, seen.distinct().size, "callers disagreed: ${seen.map { System.identityHashCode(it) }}")
        }
    }

    /**
     * `clearIf` retires one producer's own placeholder without invalidating — bumping the generation
     * there would expire an unrelated in-flight pre-warm for a state move that never happened. So
     * it must leave the generation alone, and must refuse to clear a different value.
     */
    @Test
    fun clearIfRetiresOnlyTheNamedValueAndLeavesTheGenerationAlone() {
        val cell = VersionedCell<Any>()
        val generation = cell.generation()
        val mine = Any()
        val other = Any()
        cell.publishIfCurrent(generation, other)
        assertFalse(cell.clearIf(generation, mine), "a value that is not cached must not be cleared")
        assertSame(other, cell.peek())
        assertTrue(cell.clearIf(generation, other))
        assertNull(cell.peek())
        assertEquals(generation, cell.generation(), "clearing a placeholder is not an invalidation")
    }

    private companion object {
        const val THREADS = 8
        const val INVALIDATES_EACH = 200
        const val GET_OR_PUT_ROUNDS = 50
        const val TIMEOUT_MS = 10_000L
    }
}
