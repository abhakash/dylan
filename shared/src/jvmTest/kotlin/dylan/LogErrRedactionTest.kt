package dylan

import dylan.diag.LogBuffer
import dylan.util.logErrRedacted
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stderr egress is the one output path with no [LogBuffer] in front of it, so it is the one
 * path that can write a signed URL the redaction never saw.
 *
 * The mechanism is not a call site forgetting: `logErr` is the raw `expect`/`actual` platform
 * sink, and every caller in the app feeds it throwable-derived text (`${t.message}`,
 * `${t}` = `Throwable.toString()`). ktor's `Url()` failure names the url it could not parse, so an
 * unparseable signed URL reaches this function as a *whole URL*, and — via `hostOf`'s
 * `url.take(120)` truncation — possibly with the query secret still inside it.
 *
 * This test drives [logErrRedacted] and reads back what actually reached the stream, because
 * asserting on `LogBuffer.redact` alone would pass even if the wrapper were dropped.
 */
class LogErrRedactionTest {
    private lateinit var real: PrintStream
    private lateinit var captured: ByteArrayOutputStream

    @BeforeTest
    fun setup() {
        real = System.err
        captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, StandardCharsets.UTF_8.name()))
    }

    @AfterTest
    fun teardown() {
        System.setErr(real)
    }

    @Test
    fun aSignedUrlInAThrowableIsRedactedOnTheStderrEgress() {
        val signed = "https://cdn.example/a.m4a?encrypted_media_url=BASE64SECRET&bitrate=320"
        // ktor's Url() failure message is "Fail to parse url: '<the entire url>'".
        val t = IllegalArgumentException("Fail to parse url: '$signed'")

        logErrRedacted("dylan-orchestrator: INVARIANT VIOLATION in ensureReady: $t")

        val line = captured.toString(StandardCharsets.UTF_8.name())
        assertFalse("BASE64SECRET" in line, "the secret reached stderr: $line")
        assertTrue("encrypted_media_url=<redacted>" in line, "the key must survive for grepping: $line")
        // Query values are redacted but keys are kept, so the line is still greppable AND the
        // failure name still reaches stderr.
        assertTrue("bitrate=320" in line || "bitrate=<redacted>" in line, "the key must survive: $line")
        assertTrue("IllegalArgumentException" in line, "the exception type must survive: $line")
        assertTrue("cdn.example/a.m4a" in line, "the host+path must survive: $line")
    }

    @Test
    fun aTruncatedSignedUrlIsRedactedOnTheStderrEgress() {
        // `hostOf` logs `url.take(120)`, which can cut the query in half. Whatever survives the
        // truncation must still be redacted, and truncation must never be what "protects" it.
        val signed =
            "https://cdn.example/" + "a".repeat(60) + "/song.mp3?encrypted_media_url=" +
                "Q0FOTkVWUkVRQUNLUEVSRg&x=1"

        logErrRedacted("dylan-dl: $signed".take(140))

        val line = captured.toString(StandardCharsets.UTF_8.name())
        assertFalse("Q0FOTkVWUkVRQUNLUEVSRg" in line, "the secret reached stderr: $line")
        assertTrue("encrypted_media_url=<redacted>" in line, "the key must survive for grepping: $line")
    }

    @Test
    fun redactionIsIdempotentSoDoubleApplicationIsHarmless() {
        // The wrapper runs `redact`, and every caller through `LogBuffer` runs it again on `msg`.
        // Running it twice must not mangle the already-redacted form.
        val once = LogBuffer.redact("url=https://cdn.example/a.m4a?token=SECRET took=1ms")
        val twice = LogBuffer.redact(once)
        assertTrue("token=<redacted>" in once, once)
        assertEquals(once, twice, "redaction must be idempotent: $once -> $twice")
    }
}
