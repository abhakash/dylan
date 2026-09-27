package dylan

import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.support.MutableClock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ring: redaction must lose the secret and nothing else, and capacity is exact. */
class LogBufferTest {
    private val clock = MutableClock()

    private fun buffer(
        capacity: Int = 8,
        minLevel: LogLevel = LogLevel.DEBUG,
    ) = LogBuffer(capacity = capacity, minLevel = minLevel, clock = clock)

    @Test
    fun redactionKeepsEverythingAfterTheSecret() {
        val msg =
            "resolve saavn:s1 bits=128 " +
                "url=https://cdn.example/a.m4a?encrypted_media_url=BASE64SECRET&x=1 " +
                "took=412ms attempts=1"
        val out = LogBuffer.redact(msg)
        assertFalse("BASE64SECRET" in out, "the secret itself must be gone: $out")
        assertTrue("encrypted_media_url=<redacted>" in out, "the key must survive for grepping: $out")
        // The whole point: the tail of a diagnostic line is the part you cannot reconstruct.
        assertTrue("bits=128" in out, "chosen quality must survive: $out")
        assertTrue("cdn.example/a.m4a" in out, "CDN path must survive: $out")
        assertTrue(out.endsWith("took=412ms attempts=1"), "timing suffix must survive verbatim: $out")
    }

    @Test
    fun redactionAppliesWhenTheUrlIsTheFirstToken() {
        val out = LogBuffer.redact("https://cdn.example/a.m4a?token=abc123 took=9ms")
        assertFalse("abc123" in out, "query secret must be gone: $out")
        assertTrue(out.startsWith("https://cdn.example/a.m4a?"), "host+path must survive: $out")
        assertTrue(out.endsWith("took=9ms"), "suffix must survive: $out")
    }

    @Test
    fun redactionIsANoOpForOrdinaryLines() {
        val msg = "enqueue saavn:s1 prio=USER_NOW bits=320 url=http://127.0.0.1:8080/a.m4a"
        assertEquals(msg, LogBuffer.redact(msg), "no query, no token ⇒ byte-identical, zero allocation")
    }

    @Test
    fun redactsMetaJsonAsWell() {
        val buf = buffer()
        buf.i("dl", "commit", """{"url":"https://cdn.example/a.m4a?encrypted_media_url=BASE64SECRET","bytes":10}""")
        val e = buf.dump().single()
        val meta = e.metaJson ?: ""
        assertFalse("BASE64SECRET" in meta, meta)
        assertTrue("encrypted_media_url=<redacted>" in meta, "the key must survive in metaJson too: $meta")
        assertTrue(meta.endsWith("""<redacted>","bytes":10}"""), "metaJson tail must survive: $meta")
    }

    @Test
    fun capacityIsExactAndOldestFallsOff() {
        val buf = buffer(capacity = 4)
        repeat(10) { buf.i("t", "n=$it") }
        val dump = buf.dump()
        assertEquals(4, dump.size, "ring must hold exactly capacity entries")
        assertEquals(listOf("n=6", "n=7", "n=8", "n=9"), dump.map { it.msg })
    }

    @Test
    fun minLevelFiltersBeforeAnythingElse() {
        val buf = buffer(minLevel = LogLevel.WARN)
        buf.d("t", "debug")
        buf.i("t", "info")
        buf.w("t", "warn")
        buf.e("t", "error")
        buf.c("t", "critical")
        assertEquals(listOf("warn", "error", "critical"), buf.dump().map { it.msg })
    }

    @Test
    fun timestampsComeFromTheInjectedClock() {
        val buf = buffer()
        buf.i("t", "first")
        clock.advanceSeconds(5)
        buf.i("t", "second")
        val dump = buf.dump()
        assertEquals(clock.nowMs() - 5_000, dump[0].ts, "entry ts must read the injected clock")
        assertEquals(clock.nowMs(), dump[1].ts)
    }

    @Test
    fun bindSinkIsIdempotentByKey() {
        val buf = buffer()
        val hits = AtomicInteger()
        val sink: (LogBuffer.Entry) -> Unit = { hits.incrementAndGet() }
        // The old guard was `if (f in cur)` on reference identity: a fresh `::accept` or
        // lambda each time, so it could never fire and every re-bind double-wrote.
        repeat(5) { buf.bindSink("file", sink) }
        buf.i("t", "once")
        assertEquals(1, hits.get(), "one line must reach a re-bound sink exactly once")
    }

    @Test
    fun bindSinkWithDifferentKeysAreBothAdditive() {
        val buf = buffer()
        val a = AtomicInteger()
        val b = AtomicInteger()
        buf.bindSink("a") { a.incrementAndGet() }
        buf.bindSink("b") { b.incrementAndGet() }
        buf.i("t", "m")
        assertEquals(1, a.get())
        assertEquals(1, b.get())
    }

    @Test
    fun aThrowingSinkNeverBreaksTheCaller() {
        val buf = buffer()
        val good = AtomicInteger()
        buf.bindSink("boom") { error("sink blew up") }
        buf.bindSink("good") { good.incrementAndGet() }
        buf.i("t", "m")
        assertEquals(1, good.get(), "sinks are additive: one failure cannot stop the others")
    }

    @Test
    fun concurrentAddsNeverExceedCapacityOrLoseTheRing() {
        val buf = buffer(capacity = 64)
        val threads = 8
        val perThread = 5_000
        val ready = java.util.concurrent.CountDownLatch(threads)
        val go = java.util.concurrent.CountDownLatch(1)
        val workers =
            (0 until threads).map { t ->
                Thread {
                    ready.countDown()
                    go.await()
                    repeat(perThread) { buf.i("t", "t$t-$it") }
                }
            }
        workers.forEach { it.start() }
        ready.await()
        go.countDown()
        workers.forEach { it.join() }
        val dump = buf.dump()
        assertEquals(64, dump.size, "COW ring must stay exactly at capacity under contention")
        assertEquals(64, dump.map { it.msg }.toSet().size, "no entry may appear twice")
    }
}
