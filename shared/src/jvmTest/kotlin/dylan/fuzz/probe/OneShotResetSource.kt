package dylan.fuzz.probe

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.write

/**
 * A [RawSource] with a fully scripted life: it hands over [chunk] on the first
 * `readAtMostTo`, and then behaves according to [ending] on every call after that.
 *
 * It exists so the buffered-source contract can be examined with **no engine, no download engine
 * and no scheduler luck**: every call is a straight-line function call on the calling thread, so
 * any interleaving the probe reports is one the probe *forced*, not one the dispatcher chose.
 *
 * [record] is appended to on every `readAtMostTo` and every `close`, which is what lets a probe
 * prove *how many times* upstream was touched — the quantity that separates "the buffer is empty"
 * from "nobody has called upstream yet".
 */
class OneShotResetSource(
    private val chunk: ByteArray,
    private val ending: Ending,
    val record: MutableList<String> = mutableListOf(),
) : RawSource {
    /** What upstream does once [chunk] is gone. */
    enum class Ending {
        /** `-1` forever: a clean, complete EOF. */
        CLEAN_EOF,

        /** A new `IOException` every call: a mid-stream reset that never recovers. */
        RESET,

        /** A *new* reset only on the first post-chunk call, then a clean `-1`. */
        RESET_THEN_EOF,
    }

    var reads: Int = 0
        private set

    var closes: Int = 0
        private set

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        reads++
        if (reads == 1) {
            record += "upstream#1 delivers ${chunk.size}B into sink(byteCount=$byteCount)"
            sink.write(chunk)
            return chunk.size.toLong()
        }
        return when (ending) {
            Ending.CLEAN_EOF -> {
                record += "upstream#$reads -> -1 (clean EOF)"
                -1L
            }
            Ending.RESET -> {
                record += "upstream#$reads -> throw"
                throw RESET
            }
            Ending.RESET_THEN_EOF ->
                if (reads == 2) {
                    record += "upstream#2 -> throw"
                    throw RESET
                } else {
                    record += "upstream#$reads -> -1 (clean EOF after reset)"
                    -1L
                }
        }
    }

    override fun close() {
        closes++
        record += "close #$closes"
    }

    companion object {
        /** The mid-stream reset this probe scripts. Message asserted on by name in the report. */
        val RESET: java.io.IOException = java.io.IOException("scripted connection reset")
    }
}
