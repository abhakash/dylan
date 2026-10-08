package dylan

import dylan.diag.LogBuffer
import dylan.support.LogSinkPerfHarness
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prints the log-path timings (see [LogSinkPerfHarness] for the method and its limits) and asserts
 * a loose ceiling on the one cost that is actually load-bearing: the ring alone.
 *
 * The bound is deliberately ~100x the measured cost. It is a smoke guard against a *pathological*
 * regression in the hot path — a ring that stopped being a ring, a `removeAt(0)` that went
 * quadratic, redaction that started running its two regexes on every ordinary line — and not a
 * performance gate: a shared CI box is far too noisy for a tight bound. The previous body was
 * `assertTrue(true)`, which asserted nothing while claiming to, so any of those regressions would
 * have shipped green.
 */
class LogSinkPerfTest {
    @Test
    fun logPathCosts() {
        LogSinkPerfHarness().run(n = 100_000)

        // Irreducible floor: `LogBuffer.log` with no sink at all — level filter, injected clock,
        // redaction's two `contains` pre-filters, then the COW add + evict. This is what the
        // busiest producer (the single-threaded state lane) pays per line.
        val buf = LogBuffer(capacity = 512, minLevel = dylan.diag.LogLevel.DEBUG)
        val msgs = Array(N) { "done saavn:s$it bits=320 ext=m4a bytes=4194304 ms=$it rate=4194304" }
        repeat(WARMUP) { buf.i("dl", msgs[it]) }
        val ns = measureNanoTime { for (i in 0 until N) buf.i("dl", msgs[i]) }
        val perLine = ns / N
        println("[bench] LogBuffer.log floor: %.2f ms total, %d ns/line".format(ns / 1_000_000.0, perLine))
        assertTrue(
            perLine < PER_LINE_CEILING_NS,
            "the ring's per-line cost regressed to ${perLine}ns (ceiling ${PER_LINE_CEILING_NS}ns)",
        )
    }

    private companion object {
        const val N = 100_000
        const val WARMUP = 5_000

        /** 100 us/line, ~100x the measured cost: pathological-regression guard, not a perf gate. */
        const val PER_LINE_CEILING_NS = 100_000L
    }
}
