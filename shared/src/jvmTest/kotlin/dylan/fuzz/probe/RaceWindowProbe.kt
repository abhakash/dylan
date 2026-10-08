package dylan.fuzz.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.write
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * How wide is the window? A two-thread measurement, because the answer decides whether the
 * production read path can be *fixed* on the producer side or only defended on the consumer side.
 *
 * ## The arrangement
 *
 * `ktor`'s `copyTo` loop is (`ByteReadChannelOperations.kt:201-219`):
 *
 * ```
 * while (!isClosedForRead && remaining > 0) {
 *     if (readBuffer.exhausted()) awaitContent()
 *     val count = minOf(remaining, readBuffer.remaining)
 *     readBuffer.readTo(channel.writeBuffer, count)
 *     remaining -= count
 *     channel.flush()                     // <- wakes the reader; bytes now in _readBuffer
 * }
 * } catch (cause: Throwable) {
 *     cancel(cause); channel.close(cause) // <- installs the cause; no flush
 * }
 * ```
 *
 * Between `channel.flush()` and `channel.close(cause)` sits one **blocking upstream read** — the
 * `awaitContent()` in the next iteration, which suspends on the socket. That gap is the window. The
 * sweep below widens it deliberately by sleeping that long, and reports the loss rate at each width.
 *
 * ## What each outcome means
 *
 * * **bytes lost, no error** — the reader was woken by the flush, observed `awaitContent == true`,
 *   then found `isClosedForRead` true and got `-1`. `Transfer` writes nothing and reports a
 *   complete body. This is the defect.
 * * **bytes delivered** — the reader won the race and read them. Not a bug.
 * * **error surfaced** — the reader's `awaitContent` observed the cause first and threw. Correct
 *   behaviour; `Transfer.run`'s `catch (expected: Exception)` classifies it as the host's problem.
 *
 * If *any* window width produced a materially different outcome, the loss would be a narrow race.
 * If loss is total at every width, the reader is structurally always woken before the cause lands,
 * and no producer-side ordering can prevent it.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class RaceWindowProbe {
    private enum class Classified { LOST_SILENTLY, DELIVERED, ERROR_SURFACED }

    /**
     * One two-thread run with the producer's upstream-read gap widened to [upstreamGapMillis].
     *
     * The reader is parked inside `awaitContent` before the producer writes anything (established
     * from the reader thread's own state, not a sleep), so the reader cannot miss the flush.
     */
    private fun runOnce(upstreamGapMillis: Long): Classified {
        val channel = ByteChannel(autoFlush = false)
        val buf = ByteArray(DELIVERED)
        val readerReady = CountDownLatch(1)
        var awaited = false
        var threw = false
        var read = Int.MIN_VALUE
        val reader =
            Thread {
                runBlocking {
                    val consumer =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            readerReady.countDown()
                            awaited =
                                try {
                                    channel.awaitContent()
                                } catch (expected: Throwable) {
                                    threw = true
                                    false
                                }
                            read =
                                try {
                                    channel.readAvailable(buf, 0, buf.size)
                                } catch (expected: Throwable) {
                                    threw = true
                                    Int.MIN_VALUE
                                }
                        }
                    consumer.join()
                }
            }
        reader.start()
        check(readerReady.await(ARRANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "reader never started" }
        check(awaitParked(reader)) { "the reader never parked inside awaitContent" }

        // Producer: deliver + flush (wakes the reader), then the next upstream read, then the reset.
        runBlocking { channel.writeFully(ByteArray(DELIVERED) { (it % 251).toByte() }) }
        channel.flushWriteBuffer()
        blockingGap(upstreamGapMillis)
        channel.cancel(OneShotResetSource.RESET)

        reader.join(JOIN_TIMEOUT_MS)
        check(!reader.isAlive) { "the reader was left parked" }
        return when {
            threw -> Classified.ERROR_SURFACED
            read > 0 -> Classified.DELIVERED
            awaited && read == -1 -> Classified.LOST_SILENTLY
            else -> error("unclassified: awaited=$awaited threw=$threw read=$read")
        }
    }

    /** A real block on the producer thread, so the gap cannot be optimised away. */
    private fun blockingGap(millis: Long) {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) {
            Thread.onSpinWait()
        }
    }

    private fun awaitParked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + ARRANGE_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            when (thread.state) {
                Thread.State.WAITING, Thread.State.TIMED_WAITING -> return true
                Thread.State.TERMINATED -> return false
                else -> Thread.onSpinWait()
            }
        }
        return false
    }

    @Test
    fun `loss rate against the width of the producer's upstream-read gap`() {
        for (gap in listOf(0L, 1L, 5L, 25L, 100L)) {
            val runs = 40
            val lost = AtomicInteger()
            val delivered = AtomicInteger()
            val errored = AtomicInteger()
            repeat(runs) {
                when (runOnce(gap)) {
                    Classified.LOST_SILENTLY -> lost.incrementAndGet()
                    Classified.DELIVERED -> delivered.incrementAndGet()
                    Classified.ERROR_SURFACED -> errored.incrementAndGet()
                }
            }
            println(
                "PROBE window/gapMs=$gap runs=$runs lostSilently=${lost.get()} " +
                    "bytesDelivered=${delivered.get()} errorSurfaced=${errored.get()}",
            )
        }
    }

    /**
     * The same question asked of the *unforced* production wiring: a real `MockEngine` body over a
     * scripted `RawSource`, `bodyAsChannel()`, `Transfer`'s two calls. Repeated enough to see the
     * rate rather than a single sample.
     */
    @Test
    fun `loss rate on the real ktor stack, unforced`() {
        val runs = 150
        val lost = AtomicInteger()
        val delivered = AtomicInteger()
        val errored = AtomicInteger()
        repeat(runs) {
            val upstream = OneChunkThenReset()
            val mock =
                MockEngine {
                    respond(
                        content = ByteReadChannel(upstream.buffered()),
                    )
                }
            val client = HttpClient(mock)
            try {
                runBlocking {
                    client.prepareGet("http://mock/audio").execute { r ->
                        val ch: ByteReadChannel = r.bodyAsChannel()
                        val buf = ByteArray(DELIVERED)
                        var awaited = false
                        var threw = false
                        var read = Int.MIN_VALUE
                        awaited =
                            try {
                                ch.awaitContent()
                            } catch (expected: Throwable) {
                                threw = true
                                false
                            }
                        read =
                            try {
                                ch.readAvailable(buf, 0, buf.size)
                            } catch (expected: Throwable) {
                                threw = true
                                Int.MIN_VALUE
                            }
                        when {
                            threw -> errored.incrementAndGet()
                            read > 0 -> delivered.incrementAndGet()
                            awaited && read == -1 -> lost.incrementAndGet()
                            else -> error("unclassified: awaited=$awaited read=$read")
                        }
                    }
                }
            } finally {
                client.close()
            }
        }
        println(
            "PROBE window/stack runs=$runs lostSilently=${lost.get()} " +
                "bytesDelivered=${delivered.get()} errorSurfaced=${errored.get()}",
        )
    }

    private class OneChunkThenReset : kotlinx.io.RawSource {
        private var served = false

        override fun readAtMostTo(
            sink: kotlinx.io.Buffer,
            byteCount: Long,
        ): Long {
            if (served) throw OneShotResetSource.RESET
            served = true
            sink.write(ByteArray(DELIVERED) { (it % 251).toByte() })
            return DELIVERED.toLong()
        }

        override fun close() = Unit
    }

    private companion object {
        const val DELIVERED = 65_536
        const val ARRANGE_TIMEOUT_MS = 5_000L
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
