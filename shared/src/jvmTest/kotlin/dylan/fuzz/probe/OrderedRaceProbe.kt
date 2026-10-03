package dylan.fuzz.probe

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Two real threads, both orderings forced by hand, so "deterministic given a forced ordering" can be
 * *shown* rather than argued.
 *
 * The reader is parked inside `awaitContent` before the producer is allowed to do anything at all —
 * that is arranged with a latch, not with a `delay`, because a delay would only prove that some
 * particular interleaving happened to occur.
 *
 * What is then varied is one thing: whether the producer installs the reset cause **before** or
 * **after** the flush that wakes the reader. That single ordering is the whole difference between
 * "the error is reported" and "the bytes vanish and the read reports a clean end of body", and both
 * arms are run [REPEATS] times to show the outcome is decided by the ordering, not by luck.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class OrderedRaceProbe {
    private enum class ProducerOrder {
        /** `flushWriteBuffer()` (wakes the reader), then `cancel(cause)`. What ktor does. */
        FLUSH_THEN_RESET,

        /** `cancel(cause)` first, then `flushWriteBuffer()`. */
        RESET_THEN_FLUSH,
    }

    /**
     * Writes [DELIVERED] bytes into the channel's write buffer, then applies [order]'s second step.
     *
     * `autoFlush = false`, so the write leaves the bytes in `_writeBuffer` and nothing is visible to
     * a reader until `flushWriteBuffer()` runs. That is what makes the two steps separable and lets
     * the cause be installed on either side of the flush.
     *
     * Blocks until [thread] is actually parked, so the producer cannot run while the reader is still
     * inside `awaitContent`'s prologue.
     *
     * `Thread.State.WAITING` / `TIMED_WAITING` is the state a JVM thread takes when it is parked in
     * `LockSupport.park`, which is exactly where a suspended coroutine continuation lives. The
     * producer's own state is not consulted, so this cannot be satisfied by the wrong thread.
     */
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

    private fun deliverThen(
        channel: ByteChannel,
        order: ProducerOrder,
    ) {
        runBlocking { channel.writeFully(ByteArray(DELIVERED) { (it % 251).toByte() }) }
        when (order) {
            ProducerOrder.FLUSH_THEN_RESET -> {
                channel.flushWriteBuffer()
                channel.cancel(OneShotResetSource.RESET)
            }
            ProducerOrder.RESET_THEN_FLUSH -> {
                channel.cancel(OneShotResetSource.RESET)
                channel.flushWriteBuffer()
            }
        }
    }

    private class Outcome(
        val awaitContent: Boolean,
        val awaitThrew: String?,
        val readResult: Int,
        val readThrew: String?,
        val readThread: String,
    ) {
        fun render(order: ProducerOrder): String =
            "order=${order.name} awaitContent=$awaitContent awaitThrew=$awaitThrew " +
                "read=$readResult readThrew=$readThrew readThread=$readThread"
    }

    /**
     * Forces one ordering on two threads.
     *
     * The handshake, in order:
     * 1. the reader is launched and parks inside `awaitContent`;
     * 2. the reader says so on [parked];
     * 3. only then does the producer start, so the reader is *known* to be suspended before the
     *    producer's first byte exists;
     * 4. the producer performs [order]'s two steps.
     */
    private fun runOrdered(order: ProducerOrder): Outcome {
        val channel = ByteChannel(autoFlush = false)
        val scratch = ByteArray(DELIVERED)
        val readerReady = CountDownLatch(1)
        var awaited = false
        var awaitThrew: String? = null
        var read = Int.MIN_VALUE
        var readThrew: String? = null
        var readThread = "none"
        lateinit var reader: Thread
        reader =
            Thread {
                runBlocking {
                    val consumer =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            // Signal that this coroutine is running inline and is about to call
                            // awaitContent. The producer then waits for the *thread* to park, so it
                            // cannot act until the reader is genuinely suspended.
                            readerReady.countDown()
                            awaited =
                                try {
                                    channel.awaitContent()
                                } catch (t: Throwable) {
                                    awaitThrew = "${t::class.simpleName}: ${t.message}"
                                    false
                                }
                            readThread = Thread.currentThread().name
                            read =
                                try {
                                    channel.readAvailable(scratch, 0, scratch.size)
                                } catch (t: Throwable) {
                                    readThrew = "${t::class.simpleName}: ${t.message}"
                                    Int.MIN_VALUE
                                }
                        }
                    consumer.join()
                }
            }
        reader.start()
        check(readerReady.await(ARRANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "the reader never started; the ordering was not forced"
        }
        check(awaitParked(reader)) { "the reader thread never parked inside awaitContent" }
        deliverThen(channel, order)
        reader.join(JOIN_TIMEOUT_MS)
        check(!reader.isAlive) { "the reader did not finish; the channel left it parked" }
        return Outcome(awaited, awaitThrew, read, readThrew, readThread)
    }

    @Test
    fun `flush then reset - the ordering ktor produces`() {
        var lost = 0
        var reported = 0
        var bytesRead = 0
        var sample = ""
        repeat(REPEATS) {
            val o = runOrdered(ProducerOrder.FLUSH_THEN_RESET)
            if (sample.isEmpty()) sample = o.render(ProducerOrder.FLUSH_THEN_RESET)
            if (o.awaitContent && o.readResult <= 0) lost++
            if (o.awaitThrew != null || o.readThrew != null) reported++
            if (o.readResult > 0) bytesRead += o.readResult
        }
        println(
            "PROBE order2/FLUSH_THEN_RESET repeats=$REPEATS bytesLostWithNoError=$lost " +
                "errorSurfaced=$reported bytesRead=$bytesRead",
        )
        println("PROBE order2/FLUSH_THEN_RESET sample $sample")
    }

    @Test
    fun `reset then flush - the ordering ktor does not produce`() {
        var lost = 0
        var reported = 0
        var bytesRead = 0
        var sample = ""
        repeat(REPEATS) {
            val o = runOrdered(ProducerOrder.RESET_THEN_FLUSH)
            if (sample.isEmpty()) sample = o.render(ProducerOrder.RESET_THEN_FLUSH)
            if (o.awaitContent && o.readResult <= 0) lost++
            if (o.awaitThrew != null || o.readThrew != null) reported++
            if (o.readResult > 0) bytesRead += o.readResult
        }
        println(
            "PROBE order2/RESET_THEN_FLUSH repeats=$REPEATS bytesLostWithNoError=$lost " +
                "errorSurfaced=$reported bytesRead=$bytesRead",
        )
        println("PROBE order2/RESET_THEN_FLUSH sample $sample")
    }

    /**
     * The end-to-end consequence, in the shape `Transfer` consumes. [drainLikeTransferCopyChunks]
     * is `readChunk` + `copyChunks` with the disk write removed, so the number it produces is exactly
     * `CopyOutcome.written` — the value that becomes `Breakpoint.partBytes` and therefore decides
     * whether the retry carries a `Range:` header.
     */
    @Test
    fun `what Transfer's read loop would report for each ordering`() {
        for (order in ProducerOrder.entries) {
            val written = AtomicInteger()
            val errors = AtomicInteger()
            repeat(REPEATS) {
                val bytes = drainLikeTransferCopyChunks(order)
                written.addAndGet(bytes.first)
                if (bytes.second != null) errors.incrementAndGet()
            }
            println(
                "PROBE transfer/order=${order.name} repeats=$REPEATS " +
                    "totalWrittenBytes=${written.get()} runsThatSurfacedAnError=${errors.get()} " +
                    "verdictPerRun=Truncated/partBytes=0 -> Range header omitted",
            )
        }
    }

    /**
     * `Transfer.readChunk` (`awaitContent` then `readAvailable`) followed by `copyChunks`' loop,
     * with the write to the part file removed because it is irrelevant to the question.
     *
     * @return written bytes, and the throwable if one escaped (which `Transfer.run` would classify
     *   as a host failure via its `catch (expected: Exception)`).
     */
    private fun drainLikeTransferCopyChunks(order: ProducerOrder): Pair<Int, String?> {
        val channel = ByteChannel(autoFlush = false)
        val scratch = ByteArray(65_536)
        val readerReady = CountDownLatch(1)
        var written = 0
        var escaped: String? = null
        lateinit var reader: Thread
        reader =
            Thread {
                runBlocking {
                    val consumer =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            readerReady.countDown()
                            try {
                                while (true) {
                                    val n = readChunk(channel, scratch)
                                    if (n == null) continue
                                    if (n < 0) return@launch
                                    written += n
                                }
                            } catch (t: Throwable) {
                                escaped = "${t::class.simpleName}: ${t.message}"
                            }
                        }
                    consumer.join()
                }
            }
        reader.start()
        check(readerReady.await(ARRANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "reader never started" }
        check(awaitParked(reader)) { "the reader thread never parked inside awaitContent" }
        deliverThen(channel, order)
        reader.join(JOIN_TIMEOUT_MS)
        check(!reader.isAlive) { "the reader did not finish; the channel left it parked" }
        return written to escaped
    }

    /** A verbatim copy of `Transfer.readChunk` (`shared/src/commonMain/kotlin/dylan/download/Transfer.kt:648`). */
    private suspend fun readChunk(
        ch: ByteReadChannel,
        buf: ByteArray,
    ): Int? {
        if (!ch.awaitContent()) return -1
        val n = ch.readAvailable(buf, 0, buf.size)
        return if (n == 0) null else n
    }

    private companion object {
        const val DELIVERED = 65_536
        const val REPEATS = 100
        const val ARRANGE_TIMEOUT_MS = 5_000L
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
