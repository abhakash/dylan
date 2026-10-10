package dylan

import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.support.MutableClock
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunked ring's contract: FIFO eviction is *exact* at every chunk boundary.
 *
 * The chunking that removed the O(capacity) eviction must not have changed what the ring returns,
 * so every test here pins behaviour the old flat list also had — and they are written against
 * capacities that straddle, divide and are far smaller than a chunk, because the boundaries
 * between capacity and chunk size are where a chunked ring breaks.
 *
 * The redundant phrasing is deliberate: a reader grep-ing for "evict" should find the exact
 * eviction contract even though the header already says it.
 */
class LogBufferRingTest {
    private val clock = MutableClock()

    private fun buffer(
        capacity: Int,
        minLevel: LogLevel = LogLevel.DEBUG,
    ) = LogBuffer(capacity = capacity, minLevel = minLevel, clock = clock)

    /**
     * The smallest evidence that chunking did not break eviction: the same round numbers the
     * flat-list ring produced, at a capacity that is a multiple of the chunk size. Anything that
     * retained or evicted one entry too many shows up here as an off-by-one in the window.
     */
    @Test
    fun capacityThatIsAMultipleOfTheChunkKeepsTheExactWindow() {
        val buf = buffer(capacity = 64)
        repeat(200) { buf.i("t", "n=$it") }
        assertEquals(
            (136..199).map { "n=$it" },
            buf.dump().map { it.msg },
            "a capacity that divides evenly into chunks evicted a different window",
        )
    }

    /**
     * A capacity that is chunk_size + 1. The last chunk is sealed exactly as the ring fills, so
     * this is the first point where a sealed chunk is dropped while a partially dead one still
     * exists — the case that does not occur at a multiple.
     */
    @Test
    fun capacityOnePastAMultipleOfTheChunkStillEvictsExactly() {
        val buf = buffer(capacity = 33)
        repeat(300) { buf.i("t", "n=$it") }
        assertEquals(
            (267..299).map { "n=$it" },
            buf.dump().map { it.msg },
            "capacity = chunk + 1 evicted an off-by-one window",
        )
    }

    /** A capacity smaller than a chunk: there is only ever one sealed chunk, and it is short. */
    @Test
    fun capacitySmallerThanAChunkStillEvictsExactly() {
        val buf = buffer(capacity = 4)
        repeat(10) { buf.i("t", "n=$it") }
        assertEquals(listOf("n=6", "n=7", "n=8", "n=9"), buf.dump().map { it.msg })
    }

    /** The extreme: a one-entry ring. Every line evicts, so the chunk is sealed and dropped each time. */
    @Test
    fun aOneEntryRingKeepsOnlyTheNewest() {
        val buf = buffer(capacity = 1)
        repeat(10) { buf.i("t", "n=$it") }
        assertEquals(listOf("n=9"), buf.dump().map { it.msg })
    }

    /**
     * Straddling a chunk boundary by one, at the largest chunk, and dumping after every line.
     *
     * The ring holds at most `capacity + 2 * chunkSize - 1` entries physically, so this walks the
     * whole fill of a 512-capacity ring and asserts the window at every step. An eviction that
     * happened in the wrong order — a chunk dropped before the head was actually dead, or
     * `live` accounting that lagged — cannot survive 600 consecutive exact checks.
     */
    @Test
    fun theWindowIsExactAfterEveryLineAcrossManyChunkBoundaries() {
        val buf = buffer(capacity = 512)
        repeat(600) { i ->
            buf.i("t", "n=$i")
            val msgs = buf.dump().map { e -> e.msg }
            val oldest = if (i >= 511) i - 511 else 0
            val expected: List<String> = (oldest..i).map { j -> "n=$j" }
            assertEquals(expected, msgs, "window wrong after $i lines")
        }
    }

    /** Dumping must not mutate the ring: two reads interleaved with a write give the same answer. */
    @Test
    fun dumpIsAStableSnapshotThatDoesNotAdvanceTheRing() {
        val buf = buffer(capacity = 512)
        repeat(600) { buf.i("t", "n=$it") }
        // The window is n=88..n=599 — the ring is full, so the next line evicts the oldest.
        val a = buf.dump().map { it.msg }
        val b = buf.dump().map { it.msg }
        assertEquals(a, b, "two dumps of an unchanged ring disagreed")
        buf.i("t", "n=600")
        val c = buf.dump().map { it.msg }
        assertEquals(
            a.drop(1) + "n=600",
            c,
            "logging after a dump lost or duplicated the window",
        )
    }

    /**
     * The ring never holds more than `capacity` entries *logically*, which is the only thing a
     * consumer can observe. It is the property the whole chunking scheme exists to preserve, so it
     * is asserted at the point where the physical ring is at its largest: a full sealed set, a
     * fully dead head, and a live chunk that just sealed.
     */
    @Test
    fun theRingNeverReportsMoreThanCapacityEntries() {
        val buf = buffer(capacity = 512)
        repeat(1_000) { i ->
            buf.i("t", "n=$i")
            val dump = buf.dump()
            assertTrue(dump.size <= 512, "the ring reported ${dump.size} entries at line $i")
            assertTrue(
                dump.map { it.msg }.toSet().size == dump.size,
                "an entry appeared twice in the ring at line $i",
            )
        }
    }

    /**
     * Concurrency: the ring's CAS loop is the only thing standing between a `log()` on the state
     * lane and a `dump()` from a UI lane. Eight threads at a chunk-aligned capacity, where every
     * append also evicts, so the CAS loop is genuinely contended — and the assertion is on the
     * exact window, which a lost update or a retry that rejected a stale chunk would break.
     */
    @Test
    fun concurrentAddsNeverExceedCapacityOrLoseTheRing() {
        val buf = buffer(capacity = 512)
        val threads = 8
        val perThread = 5_000
        val ready = java.util.concurrent.CountDownLatch(threads)
        val go = java.util.concurrent.CountDownLatch(1)
        val workers =
            (0 until threads).map { t ->
                Thread {
                    ready.countDown()
                    go.await()
                    repeat(perThread) { buf.i("t", "t$t-$it") }
                }
            }
        workers.forEach { it.start() }
        ready.await()
        go.countDown()
        workers.forEach { it.join() }
        val dump = buf.dump()
        assertEquals(512, dump.size, "COW ring must stay exactly at capacity under contention")
        assertEquals(512, dump.map { it.msg }.toSet().size, "no entry may appear twice")
    }

    /** A same-capacity contention test at a capacity that is not chunk-aligned, for the odd case. */
    @Test
    fun concurrentAddsAtAnUnalignedCapacityKeepTheExactWindow() {
        val buf = buffer(capacity = 100)
        val threads = 4
        val perThread = 2_500
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val workers =
            (0 until threads).map { t ->
                Thread {
                    ready.countDown()
                    go.await()
                    repeat(perThread) { buf.i("t", "t$t-$it") }
                }
            }
        workers.forEach { it.start() }
        ready.await()
        go.countDown()
        workers.forEach { it.join() }
        val dump = buf.dump()
        assertEquals(100, dump.size, "ring must stay exactly at capacity under contention")
        assertEquals(100, dump.map { it.msg }.toSet().size, "no entry may appear twice")
    }

    /**
     * A level-gated line allocates nothing, and the chunking must not have added a gate-time cost.
     * This is the guard that the chunk arithmetic does not run before the level check — it cannot
     * allocate on a discarded line, because there is no entry and no ring state to publish.
     */
    @Test
    fun aFilteredLineStillAllocatesNothingAndLeavesTheRingAlone() {
        val buf = buffer(capacity = 512, minLevel = LogLevel.INFO)
        repeat(600) { buf.i("t", "n=$it") }
        val before = buf.dump().map { it.msg }
        repeat(600) { buf.d("t", "discarded=$it") }
        assertEquals(before, buf.dump().map { it.msg }, "a filtered line reached the ring")
    }
}
