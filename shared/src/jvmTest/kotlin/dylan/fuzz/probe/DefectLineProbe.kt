package dylan.fuzz.probe

import dylan.probe.truthfulLengthFailure
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                // `Transfer.copyChunks` / `Transfer.readChunk`. One exit condition instead of
                // three jumps, same three outcomes: `awaitContent` false or a `-1` read ends the
                // loop, a `0` read means "nothing yet" and spins again, a positive read is
                // written out and the loop carries on.
                var reading = true
                while (reading) {
                    val awaited =
                        try {
                            channel.awaitContent()
                        } catch (expected: Throwable) {
                            sawThrow = true
                            false
                        }
                    if (!awaited) {
                        reading = false
                    } else {
                        val n = channel.readAvailable(scratch, 0, scratch.size)
                        when {
                            n > 0 -> written += n
                            n < 0 -> reading = false
                            else -> Unit
                        }
                    }
                }
            }
        }
        // Every token below the counters is DERIVED from them. It used to be three literals
        // (`written=0`, `=false`, "sends no Range header") typed next to live measurements, and a
        // literal next to a measurement is a claim that cannot follow the measurement: the day a
        // change let one read through, this line would still have printed `written=0` and a false
        // green on the one harness guarding issue #5, with nothing on screen to tell a re-derived
        // verdict from a remembered one. `written` only grows, so a total of 0 means every run in
        // the loop wrote nothing, which is exactly what `StreamErr.Truncated(written=0)` asserts.
        val resumable = written > 0
        val verdict = "StreamErr.Truncated(written=$written)"
        val nextAttempt = if (resumable) NEXT_ATTEMPT_RESUMES else NEXT_ATTEMPT_NO_RANGE_HEADER
        println(
            "PROBE line/transfer repeats=$REPEATS bytesWrittenTotal=$written " +
                "runsThatSurfacedAnError=$sawThrow verdict=$verdict " +
                "Breakpoint.resumable=(partBytes>0)=$resumable $nextAttempt",
        )
    }

    /**
     * Issue #12's guard: P3 must not be able to invent a drift finding out of its own read cap.
     *
     * It lives here, beside the other "a probe's conclusion must survive its own measurement"
     * check, because this is the same failure mode as the line above: a verdict asserted from the
     * probe's plumbing rather than derived from what the probe read. `ProbeMain`'s `bodyBytes` stops
     * at 8 MB, which at 128 kbps is ~8.4 minutes, so before the fix a perfectly honest 9-minute
     * track produced `truthful-length violated: header=8640000 actual=8060928` — drift that was not
     * there, produced by the reader's own ceiling. P3 picks the shortest sampled song to stay under
     * that cap, which makes the false positive unlikely rather than impossible.
     *
     * Offline on purpose: this is the one piece of probe judgement that can be decided without a
     * network, so it is the one piece a test can pin. Numbers are the 9-minute case above.
     */
    @Test
    fun `a capped read is reported as unmeasurable, never as truthful-length violated`() {
        val header = NINE_MINUTE_128KBPS
        val capped = BODY_READ_CAP_BYTES + 1L
        val note = truthfulLengthFailure(contentLength = header, actual = capped, truncated = true)

        assertNotNull(note, "a capped read must still say something - silence would read as a pass")
        assertFalse(
            note.contains(TRUTHFUL_LENGTH_VIOLATED),
            "P3 fabricated a drift finding from its own read cap: $note",
        )
        assertTrue(
            note.contains("not a drift finding"),
            "the note must disclaim the drift reading explicitly, was: $note",
        )
    }

    /**
     * The other direction, so the guard above cannot be satisfied by simply never failing: a
     * *complete* read whose count disagrees with the header is real drift and must still be
     * reported, with the note `tools/probe-results.md` already has rows for.
     */
    @Test
    fun `a complete read that disagrees with the header is still a truthful-length violation`() {
        val note =
            truthfulLengthFailure(
                contentLength = 3_723_827L,
                actual = 429L,
                truncated = false,
            )

        assertEquals(
            "$TRUTHFUL_LENGTH_VIOLATED: header=3723827 actual=429",
            note,
        )
    }

    /** And the passing case: a complete read that matches the header fails nothing. */
    @Test
    fun `a complete read that matches the header reports no failure`() {
        assertNull(truthfulLengthFailure(contentLength = 3_723_827L, actual = 3_723_827L, truncated = false))
    }

    private companion object {
        const val DELIVERED = 65_536
        const val REPEATS = 100

        /** 9 min at ~16 kB/s — the shortest track P3 could plausibly pick that still exceeds the cap. */
        const val NINE_MINUTE_128KBPS = 8_640_000L

        /** Must track `ProbeMain`'s `BODY_READ_CAP_BYTES`, or this test stops being about the real cap. */
        const val BODY_READ_CAP_BYTES = 8_000_000L

        /** The exact wording P3 has always printed for a real finding. */
        const val TRUTHFUL_LENGTH_VIOLATED = "truthful-length violated"

        /**
         * The two consequences of `partBytes > 0`, as the probe has always spelled them. Kept as
         * literals so the printed wording is unchanged — but *selected* by the measurement, never
         * asserted by it. Adding a third case here means adding a measurement, not editing a string.
         */
        const val NEXT_ATTEMPT_RESUMES = "-> next attempt sends a Range header"
        const val NEXT_ATTEMPT_NO_RANGE_HEADER = "-> next attempt sends no Range header"
    }
}
