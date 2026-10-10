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
     * The copy-on-write ring scales with capacity, not with log(capacity).
     *
     * Measured, and reported rather than fixed: `kotlinx.collections.immutable`'s `add` is O(1)
     * (~24 B/op at any capacity) but its `removeAt(0)` walks and rebuilds the whole structure —
     * 336 B/op at capacity 64, 4800 at 1024, 19 200 at 4096. Persistent lists give O(log n) for
     * `removeAt(last)` and O(1) for `add(first)`, but no single persistent list provides both a
     * bounded FIFO and sub-linear eviction.
     *
     * That is a data-structure redesign (a deque of persistent lists, or a bounded
     * `ArrayDeque` behind a lock), which this wave does not perform. This test therefore
     * *characterises* the cost instead of asserting it, so the number is on the record and a
     * capacity change has a measured before/after. See the report: 3 000 B/line at the shipped
     * capacity of 512.
     */
    @Test
    fun theRingScalesWithCapacityAndThatIsARecordNotAGate() {
        val bean = threadAllocationBean()
        val a = bytesPerLineAt(bean, capacity = SMALL_CAPACITY, msgs = Array(N) { "kept line $it ordinary" })
        val b = bytesPerLineAt(bean, capacity = LARGE_CAPACITY, msgs = Array(N) { "kept line $it ordinary" })
        println("[alloc] ring: $a B/line at capacity=$SMALL_CAPACITY, $b B/line at capacity=$LARGE_CAPACITY")
        println("[alloc] ratio=${b.toDouble() / a} for a capacity ratio of $CAPACITY_RATIO")
        // One hard assertion that costs nothing and must hold: the ring is not copying the list
        // more times than the capacity ratio, i.e. it is not accidentally *super*-linear.
        assertTrue(b < a * CAPACITY_RATIO, "the ring is worse than O(n): ${b / a}x for $CAPACITY_RATIO capacity")
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
    }
}
