package dylan.fuzz.probe

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * A **forced ordering** of the exact two calls `Transfer.readChunk` makes, on the exact object
 * `Transfer.copyBody` is handed.
 *
 * ## Why this is the object
 *
 * `HttpResponse.bodyAsChannel()` does **not** return the `SourceByteReadChannel` the engine built.
 * ktor's `defaultTransformers` (io.ktor.client.plugins.DefaultTransform.kt:105) runs
 * `content.copyTo(channel)` into an `io.ktor.utils.io.ByteChannel` in a producer coroutine and hands
 * *that* to the caller. Measured, not assumed — see [EngineReadPathProbe].
 *
 * ## Why the ordering is forced rather than raced
 *
 * `ByteChannel` is a *sequential* channel, so no ordering here is illegal; the point is that one
 * specific legal ordering loses bytes. It is reproduced without scheduler luck because every step is
 * either a synchronous call on the calling thread or a `CoroutineStart.UNDISPATCHED` launch, which
 * runs its body on the calling thread until the first suspension point and then parks. The producer
 * therefore *cannot* make progress until the consumer is already parked inside `awaitContent`.
 *
 * ## How general this ordering is — read with [RaceWindowProbe]
 *
 * This harness pins the consumer inside `awaitContent` before the producer writes anything, so it
 * always reproduces. That is a *forced* ordering and it is the one `ktor`'s `copyTo` produces, but it
 * is not the only one: [RaceWindowProbe] widens the producer's gap between its flush and its reset
 * and measures the rate at each width. With the gap held at 0 the loss is total (40/40); from 1 ms
 * upward it is zero (0/40). So this is a real race with a sub-millisecond window, not a
 * deterministic corruption — and the window is narrow precisely because the reset has to land
 * between the flush waking the reader and the reader returning from `awaitContent`.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class ByteChannelCloseOrderingProbe {
    /** What the consumer observed, with the ordering steps recorded alongside. */
    private data class Observation(
        val closeAbnormally: Boolean,
        val awaitContent: Boolean,
        val closedForReadBetween: Boolean,
        val readResult: Int,
        val readThrew: String?,
    ) {
        fun render(): String =
            "close=${
                if (closeAbnormally) "reset(IOException)" else "clean()"
            } awaitContent=$awaitContent isClosedForReadBetween=$closedForReadBetween read=$readResult" +
                (readThrew?.let { " threw=$it" } ?: "") +
                " LOST_BYTES=${LOST_BYTES}"
    }

    /**
     * The forcing sequence. [closeAbnormally] selects `cancel(cause)` (what ktor's `copyTo` does when
     * the upstream read throws) versus `close()` (the clean end of body).
     */
    private fun run(
        closeAbnormally: Boolean,
        payload: ByteArray,
    ): Observation {
        val scratch = ByteArray(payload.size)
        val channel = ByteChannel(autoFlush = false)
        var closedBetween = false
        var thrown: String? = null
        var readResult = Int.MIN_VALUE
        var awaited = false
        runBlocking {
            // Producer step 1 — the origin delivered a chunk. `autoFlush = false`, so it is still in
            // the channel's *write* buffer; nothing has reached the read side yet.
            channel.writeFully(payload)

            // Consumer — `Transfer.readChunk`'s two calls, verbatim.
            val consumer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaited = channel.awaitContent()
                    closedBetween = channel.isClosedForRead
                    readResult =
                        try {
                            channel.readAvailable(scratch, 0, scratch.size)
                        } catch (t: Throwable) {
                            thrown = "${t::class.simpleName}: ${t.message}"
                            Int.MIN_VALUE
                        }
                }

            // Producer step 2 — flush: the chunk moves to the flush buffer, `flushBufferSize` becomes
            // non-zero and the parked reader is resumed onto the (single-threaded) event-loop queue.
            // It cannot run yet: we are still on this thread.
            channel.flushWriteBuffer()

            // Producer step 3 — the next upstream read throws, so `copyTo` calls `cancel(cause)`.
            // `cancel` installs the close token WITHOUT flushing, which is the whole asymmetry:
            // `ByteChannel.close()` calls `flushWriteBuffer()` first and this does not.
            if (closeAbnormally) {
                channel.cancel(OneShotResetSource.RESET)
            } else {
                channel.close()
            }

            consumer.join()
        }
        return Observation(closeAbnormally, awaited, closedBetween, readResult, thrown)
    }

    @Test
    fun `forced ordering, clean close - the bytes must arrive`() {
        val o = run(closeAbnormally = false, payload = PAYLOAD)
        println("PROBE order/clean  ${o.render()}")
    }

    @Test
    fun `forced ordering, reset close - the bytes are lost and clean EOF is reported`() {
        val o = run(closeAbnormally = true, payload = PAYLOAD)
        println("PROBE order/reset  ${o.render()}")
    }

    @Test
    fun `forced ordering repeated, both endings`() {
        var cleanBytes = 0
        var cleanEof = 0
        var resetBytes = 0
        var resetEof = 0
        var resetThrew = 0
        var resetAwaitTrue = 0
        repeat(REPEATS) {
            val c = run(closeAbnormally = false, payload = PAYLOAD)
            if (c.readResult > 0) {
                cleanBytes++
            } else if (c.readResult == -1) {
                cleanEof++
            }
            val r = run(closeAbnormally = true, payload = PAYLOAD)
            if (r.awaitContent) resetAwaitTrue++
            if (r.readResult > 0) {
                resetBytes++
            } else if (r.readResult == -1) {
                resetEof++
            }
            if (r.readThrew != null) resetThrew++
        }
        println(
            "PROBE order/repeats=$REPEATS clean(bytes=$cleanBytes eof=$cleanEof) " +
                "reset(awaitContentTrue=$resetAwaitTrue bytes=$resetBytes eof=$resetEof threw=$resetThrew)",
        )
    }

    private companion object {
        /**
         * `awaitContent()` returning true proves at least this many bytes were in the read buffer at
         * that instant, and the read that follows is the only thing that could consume them.
         */
        const val LOST_BYTES = 1
        const val REPEATS = 200
        val PAYLOAD = ByteArray(65_536) { (it % 251).toByte() }
    }
}
