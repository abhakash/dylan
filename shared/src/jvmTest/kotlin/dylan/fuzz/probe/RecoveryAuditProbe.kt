package dylan.fuzz.probe

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readAtMostTo
import kotlin.test.Test

/**
 * Can the delivered bytes be recovered *after* the reset has been recorded?
 *
 * This matters for the fix, not just the diagnosis. `Transfer.readChunk` is written as
 * `awaitContent()` then `readAvailable(...)`, and if **no** API can still hand back the buffered
 * bytes once `_closedCause` is set, then the correct fix is not "read from a different layer" — it
 * is to classify the `-1` as what it is. If some API *can* recover them, the fix is to call that one.
 *
 * Every attempt runs against an identical, freshly built channel state, and the builder *checks* that
 * the bytes were present before the reset went on. So "the buffer held 65 536 bytes and then a
 * cause appeared" is measured by each run, not assumed by the narrative.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class RecoveryAuditProbe {
    /**
     * A channel holding [DELIVERED] bytes, then reset. `check` inside the builder is what makes
     * every downstream row of the table trustworthy: if the bytes were not actually staged, the
     * test fails at the builder rather than reporting a misleading "no API can recover them".
     */
    private fun freshResetChannel(): ByteChannel {
        val channel = ByteChannel(autoFlush = false)
        runBlocking {
            channel.writeFully(ByteArray(DELIVERED) { (it % 251).toByte() })
            channel.flushWriteBuffer()
        }
        check(!channel.readBuffer.exhausted()) { "the channel must be holding bytes before the reset" }
        channel.cancel(OneShotResetSource.RESET)
        return channel
    }

    /** The control: the same bytes, closed cleanly. */
    private fun freshCleanChannel(): ByteChannel {
        val channel = ByteChannel(autoFlush = false)
        runBlocking {
            channel.writeFully(ByteArray(DELIVERED) { (it % 251).toByte() })
            channel.flushWriteBuffer()
        }
        check(!channel.readBuffer.exhausted()) { "the control channel must be holding bytes" }
        channel.close()
        return channel
    }

    /** Runs [read] against a fresh channel and renders the result or the throw as one line. */
    private fun attempt(
        label: String,
        reset: Boolean,
        read: suspend (ByteReadChannel) -> String,
    ): String {
        val channel = if (reset) freshResetChannel() else freshCleanChannel()
        val outcome =
            runBlocking {
                try {
                    read(channel)
                } catch (t: Throwable) {
                    "THREW ${t::class.simpleName}: ${t.message}"
                }
            }
        return "PROBE recovery/$label ${if (reset) "reset" else "clean"} -> $outcome"
    }

    @Test
    fun `every read API, against a channel holding bytes plus a recorded reset`() {
        val scratch = ByteArray(DELIVERED)
        println(
            attempt("awaitContent-then-readAvailable", reset = true) { ch ->
                val ac = ch.awaitContent()
                "awaitContent=$ac readAvailable=${ch.readAvailable(scratch, 0, scratch.size)}"
            },
        )
        println(
            attempt("readAvailable-alone", reset = true) { ch ->
                "returned ${ch.readAvailable(scratch, 0, scratch.size)}"
            },
        )
        println(
            attempt("readBuffer-exhausted", reset = true) { ch ->
                "exhausted=${ch.readBuffer.exhausted()}"
            },
        )
        println(
            attempt("readBuffer-readAtMostTo-byteArray", reset = true) { ch ->
                "returned ${ch.readBuffer.readAtMostTo(scratch, 0, scratch.size)}"
            },
        )
        println(
            attempt("readBuffer-readAtMostTo-Buffer", reset = true) { ch ->
                val sink = Buffer()
                "read ${ch.readBuffer.readAtMostTo(sink, DELIVERED.toLong())} into a Buffer of ${sink.size}"
            },
        )
        println(
            attempt("readByte", reset = true) { ch ->
                "returned 0x${ch.readByte().toInt().and(0xFF).toString(16)}"
            },
        )
        println(attempt("closedCause", reset = true) { ch -> "is ${ch.closedCause}" })
        println(attempt("isClosedForRead", reset = true) { ch -> "is ${ch.isClosedForRead}" })
    }

    @Test
    fun `the same APIs against a clean close - the control`() {
        val scratch = ByteArray(DELIVERED)
        println(
            attempt("readAvailable-alone", reset = false) { ch ->
                "returned ${ch.readAvailable(scratch, 0, scratch.size)}"
            },
        )
        println(
            attempt("readBuffer-readAtMostTo-byteArray", reset = false) { ch ->
                "returned ${ch.readBuffer.readAtMostTo(scratch, 0, scratch.size)}"
            },
        )
        println(attempt("isClosedForRead", reset = false) { ch -> "is ${ch.isClosedForRead}" })
    }

    /**
     * The consequence the application actually feels. A read loop that treats `-1` as "body
     * complete" writes nothing and never learns there was an error; a loop that inspects
     * [ByteReadChannel.closedCause] after the `-1` learns immediately. Both run against identical
     * channel state, so the difference is attributable to the read loop and to nothing else.
     */
    @Test
    fun `a loop that treats minus-one as success writes nothing, one that reads closedCause knows why`() {
        var silentWrites = 0
        var silentZeroRuns = 0
        var informedWrites = 0
        var informedSawCause = 0
        repeat(REPEATS) {
            val silentScratch = ByteArray(DELIVERED)
            var wrote = 0
            runBlocking {
                val silent = freshResetChannel()
                while (true) {
                    val n = silent.readAvailable(silentScratch, 0, silentScratch.size)
                    if (n <= 0) break
                    wrote += n
                }
            }
            silentWrites += wrote
            if (wrote == 0) silentZeroRuns++

            var got = 0
            var sawCause = false
            runBlocking {
                val informed = freshResetChannel()
                val buf = ByteArray(DELIVERED)
                while (true) {
                    val n = informed.readAvailable(buf, 0, buf.size)
                    if (n > 0) {
                        got += n
                    } else {
                        // What `Transfer.readChunk` does not do today.
                        if (informed.closedCause != null) sawCause = true
                        break
                    }
                }
            }
            informedWrites += got
            if (sawCause) informedSawCause++
        }
        println(
            "PROBE recovery/loop repeats=$REPEATS silentReadWroteBytes=$silentWrites " +
                "silentRunsWithZeroBytes=$silentZeroRuns informedReadWroteBytes=$informedWrites " +
                "informedRunsThatSawTheCause=$informedSawCause",
        )
    }

    private companion object {
        const val DELIVERED = 65_536
        const val REPEATS = 100
    }
}
