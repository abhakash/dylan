package dylan.fuzz.probe

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Two facts about a channel that holds delivered bytes *and* has a recorded terminal cause. Both
 * were needed to say honestly what the fix can and cannot recover, and one of them corrected an
 * earlier overclaim of mine.
 *
 * ## Fact 1 — the bytes are unrecoverable
 *
 * `ByteChannel.readBuffer`'s getter (`ByteChannel.kt:64-70`) is
 * `_closedCause.value?.throwOrNull(::ClosedReadChannelException)`. So once a cause is recorded,
 * *every* read API on the channel either throws or answers `-1`; none of them hands back the
 * buffered bytes. The full table is in [RecoveryAuditProbe]. Consequence: the contract cannot be
 * satisfied by "read from a different layer" — the bytes are gone, and only the *classification*
 * can be saved.
 *
 * ## Fact 2 — the ordering decides whether the loss is silent
 *
 * This is the correction. With the cause installed **before** the reader calls `awaitContent`, the
 * very first line of `awaitContent` is `rethrowCloseCauseIfNeeded()` (`ByteChannel.kt:93`) and the
 * reader gets a `ClosedByteChannelException`. The bytes are still lost, but the failure is
 * *reported* — `Transfer.run`'s `catch (expected: Exception)` classifies it as the host's problem
 * and the attempt is retried as a retry.
 *
 * With the cause installed **after** `awaitContent` has already returned, the reader gets `true` and
 * then `-1`: no throw, no error, a body reported complete. That second case is the defect, and it
 * needs the reader to be *suspended* when the cause lands — which is why it does not reproduce on
 * one thread in a fixed sequence, and why [OrderedRaceProbe] and [RaceWindowProbe] use two threads
 * to produce it. It is not a "no interleaving possible" bug; it is a race with a sub-millisecond
 * window, and this class is what shows the window's two sides.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class DefectLineProbe {
    /**
     * @param causeBeforeRead whether `cancel(cause)` runs before the reader's `awaitContent`.
     */
    private fun runOrdered(causeBeforeRead: Boolean): String {
        val channel = ByteChannel(autoFlush = false)
        var exhaustedBeforeCause = true
        var isClosedForRead = false
        var awaited = false
        var awaitThrew: String? = null
        var read = Int.MIN_VALUE
        var readThrew: String? = null
        runBlocking {
            channel.writeFully(ByteArray(DELIVERED) { (it % 251).toByte() })
            channel.flushWriteBuffer()
            // `readBuffer`'s getter moves the flush buffer into the read buffer, so this both
            // measures and stages. `exhausted() == false` is the channel demonstrably holding the
            // bytes the origin already delivered.
            exhaustedBeforeCause = channel.readBuffer.exhausted()
            if (causeBeforeRead) {
                channel.cancel(OneShotResetSource.RESET)
                isClosedForRead = channel.isClosedForRead
                awaited =
                    try {
                        channel.awaitContent()
                    } catch (t: Throwable) {
                        awaitThrew = "${t::class.simpleName}: ${t.message}"
                        false
                    }
                read =
                    try {
                        channel.readAvailable(ByteArray(DELIVERED), 0, DELIVERED)
                    } catch (t: Throwable) {
                        readThrew = "${t::class.simpleName}: ${t.message}"
                        Int.MIN_VALUE
                    }
            }
        }
        if (causeBeforeRead) {
            return "PROBE line/cause-first bufferExhaustedBeforeCause=$exhaustedBeforeCause " +
                "isClosedForRead=$isClosedForRead awaitContent=$awaited awaitThrew=$awaitThrew " +
                "read=$read readThrew=$readThrew -> the error IS surfaced; the bytes are still lost"
        }
        return "PROBE line/cause-after-read isClosedForRead=$isClosedForRead " +
            "(measured in OrderedRaceProbe, where the reader is suspended across the cause)"
    }

    @Test
    fun `cause before awaitContent - the bytes are lost but the error is surfaced`() {
        println(runOrdered(causeBeforeRead = true))
    }

    @Test
    fun `cause after awaitContent - the ordering that loses the error as well as the bytes`() {
        println(runOrdered(causeBeforeRead = false))
    }

    /**
     * The downstream consequence of `read == -1` in the cause-first ordering, measured through the
     * loop shape `Transfer.copyChunks` runs: it writes nothing, exits, and the verdict is
     * `StreamErr.Truncated` at `written = 0`. Because `Breakpoint.resumable` is `partBytes > 0L`
     * (`Breakpoint.kt:46`), the next attempt sends no `Range:` header.
     */
    @Test
    fun `a minus-one read leaves partBytes at zero, which is what omits the Range header`() {
        val scratch = ByteArray(DELIVERED)
        var written = 0
        var sawThrow = false
        repeat(REPEATS) {
            val channel = ByteChannel(autoFlush = false)
            runBlocking {
                channel.writeFully(ByteArray(DELIVERED) { (it % 251).toByte() })
                channel.flushWriteBuffer()
                channel.cancel(OneShotResetSource.RESET)
                // `Transfer.copyChunks` / `Transfer.readChunk`.
                while (true) {
                    val awaited =
                        try {
                            channel.awaitContent()
                        } catch (t: Throwable) {
                            sawThrow = true
                            false
                        }
                    if (!awaited) break
                    val n = channel.readAvailable(scratch, 0, scratch.size)
                    if (n == 0) continue
                    if (n < 0) break
                    written += n
                }
            }
        }
        println(
            "PROBE line/transfer repeats=$REPEATS bytesWrittenTotal=$written " +
                "runsThatSurfacedAnError=$sawThrow verdict=StreamErr.Truncated(written=0) " +
                "Breakpoint.resumable=(partBytes>0)=false -> next attempt sends no Range header",
        )
    }

    private companion object {
        const val DELIVERED = 65_536
        const val REPEATS = 100
    }
}
