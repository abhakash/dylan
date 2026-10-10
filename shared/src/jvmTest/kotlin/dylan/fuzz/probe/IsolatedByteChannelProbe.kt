package dylan.fuzz.probe

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Same forced ordering as [ByteChannelCloseOrderingProbe], with the corruption removed from the
 * *arrangement* so only the mechanism remains.
 *
 * ## The distinction this file draws
 *
 * The engine probe loses bytes 150 runs in a row, which leaves two live hypotheses that a single
 * observation cannot separate:
 *
 *  * **race** — the consumer must observe `awaitContent == true` and only *then* the close cause,
 *    so the outcome depends on who wins;
 *  * **structure** — the producer's two steps (deliver, then install the terminal cause) are
 *    adjacent, so the consumer is resumed in between *by construction*, and the outcome does not
 *    depend on anything.
 *
 * [runArranged] adds a step between them that is deliberately slow — a blocking upstream read
 * inside `copyTo`'s loop. Nothing else changes: the channel, the two consumer calls, and the
 * close-with-cause are identical. If the corruption rate is unchanged, timing is not the variable.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class IsolatedByteChannelProbe {
    private class Outcome(
        val delivered: Int,
        val awaitContent: Boolean,
        val read: Int,
        val readThrew: String?,
        val elapsedMillis: Long,
    )

    /**
     * @param upstreamDelayMillis how long the producer blocks in its *next* upstream read, i.e.
     *   between delivering the chunk and installing the close cause. `0` reproduces ktor exactly.
     * @param delayBeforeCancel sleep in the producer between the flush and the close. A value that
     *   would have to be larger than the whole request to matter is a red flag for a race; if a
     *   delay of any size changes nothing, the ordering is structural.
     */
    private fun runArranged(
        upstreamDelayMillis: Long,
        delayBeforeCancel: Long = 0L,
    ): Outcome {
        var delivered = 0
        var awaited = false
        var read = Int.MIN_VALUE
        var threw: String? = null
        val started = System.nanoTime()
        runBlocking {
            val channel = ByteChannel(autoFlush = false)
            val consumer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaited = channel.awaitContent()
                    read =
                        try {
                            channel.readAvailable(Scratch, 0, Scratch.size)
                        } catch (t: Throwable) {
                            threw = "${t::class.simpleName}: ${t.message}"
                            Int.MIN_VALUE
                        }
                }
            // Producer step 1: deliver the origin's chunk.
            channel.writeFully(PAYLOAD)
            delivered = PAYLOAD.size
            // Producer step 2: the flush that makes the bytes visible to the reader.
            channel.flushWriteBuffer()
            // Producer step 3: the next upstream read. ktor's `copyTo` does exactly this — a
            // blocking read on the engine channel — and a reset throws out of it.
            if (upstreamDelayMillis > 0) blockingDelay(upstreamDelayMillis)
            // Producer step 4: `copyTo`'s catch, `cancel(cause)`.
            if (delayBeforeCancel > 0) blockingDelay(delayBeforeCancel)
            channel.cancel(OneShotResetSource.RESET)
            consumer.join()
        }
        return Outcome(delivered, awaited, read, threw, (System.nanoTime() - started) / 1_000_000)
    }

    /** A real block on the calling thread, so the delay cannot be optimised away or skipped. */
    private fun blockingDelay(millis: Long) {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) {
            Thread.onSpinWait()
        }
    }

    private fun sweep(
        label: String,
        runs: Int,
        upstreamDelayMillis: Long,
        delayBeforeCancel: Long = 0L,
    ) {
        var lost = 0
        var readBytes = 0
        var threw = 0
        var awaitFalse = 0
        var slowest = 0L
        repeat(runs) {
            val o = runArranged(upstreamDelayMillis, delayBeforeCancel)
            if (o.awaitContent && o.read == -1) lost++
            if (o.read > 0) readBytes += o.read
            if (o.readThrew != null) threw++
            if (!o.awaitContent) awaitFalse++
            if (o.elapsedMillis > slowest) slowest = o.elapsedMillis
        }
        println(
            "PROBE iso/$label runs=$runs upstreamDelayMs=$upstreamDelayMillis " +
                "delayBeforeCancelMs=$delayBeforeCancel awaitTrueThenEof=$lost bytesRead=$readBytes " +
                "readThrew=$threw awaitFalse=$awaitFalse slowestMs=$slowest",
        )
    }

    @Test
    fun `producer blocks a long time in its next upstream read, as ktor does`() {
        sweep(label = "upstream-block-200ms", runs = 20, upstreamDelayMillis = 200)
    }

    @Test
    fun `producer sleeps before installing the close cause`() {
        sweep(label = "pre-cancel-sleep-5ms", runs = 20, upstreamDelayMillis = 0, delayBeforeCancel = 5)
        sweep(label = "pre-cancel-sleep-50ms", runs = 10, upstreamDelayMillis = 0, delayBeforeCancel = 50)
    }

    @Test
    fun `no delay at all, repeated`() {
        sweep(label = "no-delay", runs = 300, upstreamDelayMillis = 0)
    }

    private companion object {
        val PAYLOAD = ByteArray(65_536) { (it % 251).toByte() }
        val Scratch = ByteArray(65_536)
    }
}
