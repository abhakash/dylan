package dylan

import dylan.diag.LogBuffer
import dylan.diag.LogBuffer.Companion.redact
import dylan.diag.LogLevel
import dylan.support.MutableClock
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hot path, measured rather than reasoned about.
 *
 * `LogBuffer.log` runs on the single-threaded `state` lane — the same lane that carries the
 * orchestrator inbox and the 10 Hz position ticker — so its cost *is* the state lane's cost. Three
 * properties matter, and each one is something a later edit could silently break:
 *
 *  1. a line filtered out by `minLevel` allocates nothing, so a release build on INFO pays nothing
 *     for the DEBUG lines its `d()` call sites would have built;
 *  2. `redact` allocates nothing on a line that carries no secret;
 *  3. the ring's copy-on-write `add`/`removeAt(0)` pair scales with log(capacity), not with it —
 *     a ring that quietly became "copy the whole list" would allocate megabytes per line on the
 *     one lane that must not stall.
 *
 * Messages are **pre-built** in every case: a Kotlin string template allocates a `StringBuilder`
 * and a `String` on the *caller's* side, and counting that would attribute the call site's
 * interpolation to the logging function. `LogSinkPerfHarness` documents the same choice.
 */
class LogBufferAllocationTest {
    private val clock = MutableClock()

    @BeforeTest
    fun setup() = Unit

    @AfterTest
    fun teardown() = Unit

    @Test
    fun aFilteredOutLineAllocatesNothing() {
        val bean = threadAllocationBean()
        val buf = LogBuffer(capacity = 512, minLevel = LogLevel.WARN, clock = clock)
        // Pre-built: the caller's interpolation is the caller's cost, not this function's.
        val msgs = Array(N) { "debug line $it that the level gate must discard" }

        repeat(WARMUP_ROUNDS) { for (m in msgs) buf.d("dl", m) }
        val before = bean.getThreadAllocatedBytes(id())
        for (m in msgs) buf.d("dl", m)
        val bytesPerLine = (bean.getThreadAllocatedBytes(id()) - before) / N

        println("[alloc] DEBUG line under a WARN gate: $bytesPerLine B/line")
        // The gate is the first statement, so `Entry` is never built, the clock is never read and
        // `redact` never runs its two `contains`. Moving the level check below the Entry
        // construction — an easy "simplification" — would make this number non-zero.
        assertEquals(0L, bytesPerLine, "a level-filtered line allocated: minLevel is not the first gate")
    }

    @Test
    fun redactionAllocatesNothingOnALineWithNoSecret() {
        val bean = threadAllocationBean()
        val plain = Array(N) { "enqueue saavn:s$it prio=USER_NOW bits=320 url=http://127.0.0.1:8080/a.m4a" }

        repeat(WARMUP_ROUNDS) { for (s in plain) redact(s) }
        val before = bean.getThreadAllocatedBytes(id())
        for (s in plain) redact(s)
        val plainPerLine = (bean.getThreadAllocatedBytes(id()) - before) / N

        println("[alloc] redact on a plain line: $plainPerLine B/line")
        // The two `contains` pre-filters decide, so the regexes never run and the original String
        // reference is returned. This is why an ordinary log line costs nothing to redact.
        assertEquals(0L, plainPerLine, "redact allocated on a line with no secret: the pre-filter is leaking")
    }

    @Test
    fun redactionCostsOneStringPerLineThatCarriesASecret() {
        val bean = threadAllocationBean()
        val secret = Array(N) { "resolve url=https://cdn.example/a.m4a?encrypted_media_url=S$it&x=1" }

        repeat(WARMUP_ROUNDS) { for (s in secret) redact(s) }
        val before = bean.getThreadAllocatedBytes(id())
        for (s in secret) redact(s)
        val perLine = (bean.getThreadAllocatedBytes(id()) - before) / N

        println("[alloc] redact on a signed URL line: $perLine B/line")
        // One Regex.replace result per line that carries a secret, and nothing more. The
        // substitution keeps the tail of the diagnostic rather than truncating it, which is what
        // this cost buys.
        assertTrue(perLine in 1 until ONE_SECRET_LINE_CEILING, "unexpected redaction cost: $perLine B/line")
    }

    /**
     * The ring's per-line allocation is now *bounded*, and the numbers are on the record.
     *
     * Measured, not asserted, before the chunked rewrite (same test, same machine):
     *
     * ```
     * [alloc] ring: 3000 B/line at capacity=512, 77032 B/line at capacity=16384
     * [alloc] ratio=25.7 for a capacity ratio of 32
     * ```
     *
     * The old shape was `if (next.size > capacity) next = next.removeAt(0)`, and
     * `PersistentVector.removeAt(0)` copies the 32-way root and shifts a full segment at *every*
     * level of the trie, so per-line allocation grew with `capacity` — 3 KB per retained line at
     * the shipped 512, which is ~100x a channel `trySend`, paid by the state lane for every INFO
     * line.
     *
     * After the rewrite to a deque of fixed-size chunks: eviction is `headDead++` (a counter, no
     * allocation) and the real removal is a wholesale `sealed.removeAt(0)` once per chunk
     * boundary, so the per-line cost is a constant regardless of capacity. The ratio below is the
     * measurement that proves it: 1.5x for a 32x capacity ratio, where the old code was 25x.
     *
     * The assertion is a genuine gate with room to move, not a tautology: it fails if eviction
     * becomes per-line and capacity-proportional again, which is the regression this wave removed.
     */
    @Test
    fun theRingEvictsInChunksSoCapacityCostsAConstant() {
        val bean = threadAllocationBean()
        val a = bytesPerLineAt(bean, capacity = SMALL_CAPACITY, msgs = Array(N) { "kept line $it ordinary" })
        val b = bytesPerLineAt(bean, capacity = LARGE_CAPACITY, msgs = Array(N) { "kept line $it ordinary" })
        println("[alloc] ring: $a B/line at capacity=$SMALL_CAPACITY, $b B/line at capacity=$LARGE_CAPACITY")
        println("[alloc] ratio=${b.toDouble() / a} for a capacity ratio of $CAPACITY_RATIO")
        println("[alloc] before the chunked rewrite: 3000 B/line at $SMALL_CAPACITY, 77032 B/line at $LARGE_CAPACITY")
        // Gate 1 — the shipped capacity must be cheap in absolute terms. 1024 B/line is ~1/3 of
        // the old 3000: anything that reintroduces a per-line, capacity-proportional rebuild
        // blows through this immediately, and a legitimate further optimisation still passes.
        assertTrue(
            a <= SHIPPED_CAPACITY_CEILING,
            "the ring allocates $a B/line at capacity=$SMALL_CAPACITY " +
                "(ceiling $SHIPPED_CAPACITY_CEILING): eviction is per-line again",
        )
        // Gate 2 — the cost must not scale with capacity. A flat ring with removeAt(0) gives
        // ~25x here; a chunked ring gives ~1.5x. 4x is far above the measured value and far
        // below the old one, so this catches a reintroduced O(capacity) path without being a
        // machine-specific perf bound.
        assertTrue(
            b < a * SCALING_CEILING,
            "the ring scales with capacity: ${b / a}x for a $CAPACITY_RATIO capacity ratio",
        )
    }

    /**
     * The level gate must stay in front of the ring, so a filtered line allocates nothing.
     *
     * This is the guard for the chunking rewrite: the chunk arithmetic runs after the level check,
     * so a discarded line must not build an `Entry`, must not read the clock, and must not touch
     * the ring. The chunk bookkeeping is exactly the kind of thing that could drift above the gate,
     * so it is measured rather than reasoned about — and 0 B/line here is only possible if the gate
     * really is the first statement, because a line that passes the gate costs 170 B/line in the
     * ring on its own.
     */
    @Test
    fun aLevelFilteredLineStillAllocatesNothingUnderTheChunkedRing() {
        val bean = threadAllocationBean()
        val buf = LogBuffer(capacity = SMALL_CAPACITY, minLevel = LogLevel.WARN, clock = clock)
        // Pre-built: the caller's interpolation is the caller's cost, not this function's.
        val msgs = Array(N) { "debug line $it that the level gate must discard" }
        repeat(WARMUP_ROUNDS) { for (m in msgs) buf.d("dl", m) }
        val before = bean.getThreadAllocatedBytes(id())
        for (m in msgs) buf.d("dl", m)
        val bytesPerLine = (bean.getThreadAllocatedBytes(id()) - before) / N
        println("[alloc] DEBUG line under a WARN gate, chunked ring: $bytesPerLine B/line")
        assertEquals(0L, bytesPerLine, "a level-filtered line allocated: minLevel is not the first gate")
    }

    /** An exception message must be redacted, not truncated: the tail is the part you cannot rebuild. */
    @Test
    fun exceptionMessagesCarryTheirDiagnosticTailPastTheSecret() {
        val t = IllegalArgumentException("Fail to parse url: 'https://cdn.example/a.m4a?encrypted_media_url=SECRET'")
        val out = redact("hostOf threw: $t")
        assertFalse("SECRET" in out, "the exception message leaked the signed URL: $out")
        assertTrue("cdn.example/a.m4a" in out, "the host+path must survive: $out")
        assertTrue("hostOf threw" in out, "the message head must survive: $out")
    }

    /** `Throwable.toString()` still carries its class name and message through the redaction. */
    @Test
    fun aThrowableToStringStillCarriesItsClassNameAndMessage() {
        val t = IllegalStateException("boom")
        val out = redact("ensureReady failed: $t")
        assertTrue("IllegalStateException" in out, "the type name must survive: $out")
        assertTrue("boom" in out, "the message must survive: $out")
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun threadAllocationBean(): com.sun.management.ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean()
        assertTrue(
            bean is com.sun.management.ThreadMXBean,
            "this JVM reports no per-thread allocation: these measurements cannot assert anything",
        )
        return bean as com.sun.management.ThreadMXBean
    }

    private fun bytesPerLineAt(
        bean: com.sun.management.ThreadMXBean,
        capacity: Int,
        msgs: Array<String>,
    ): Long {
        val buf = LogBuffer(capacity = capacity, minLevel = LogLevel.DEBUG, clock = clock)
        repeat(WARMUP_ROUNDS) { for (m in msgs) buf.i("dl", m) }
        val before = bean.getThreadAllocatedBytes(id())
        for (m in msgs) buf.i("dl", m)
        return (bean.getThreadAllocatedBytes(id()) - before) / msgs.size
    }

    private fun id(): Long = Thread.currentThread().id

    private companion object {
        const val N = 100_000
        const val WARMUP_ROUNDS = 3
        const val SMALL_CAPACITY = 512
        const val LARGE_CAPACITY = 16_384
        const val CAPACITY_RATIO = LARGE_CAPACITY / SMALL_CAPACITY
        const val ONE_SECRET_LINE_CEILING = 4_096L

        /**
         * Ceiling on per-line allocation at the shipped capacity of 512. Measured 170 B/line after
         * the chunked rewrite and 3 000 B/line before it, so this is ~1/3 of the old cost: a
         * regression to the flat `removeAt(0)` ring fails at once, a further optimisation passes.
         */
        const val SHIPPED_CAPACITY_CEILING = 1_024L

        /**
         * How much the per-line cost may grow from capacity 512 to capacity 16 384. Measured 1.5x
         * after the chunked rewrite and 25x before it, so 4x is a wide margin for JVM noise that
         * still fails on any capacity-proportional eviction path.
         */
        const val SCALING_CEILING = 4L
    }
}
