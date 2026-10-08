package dylan

import dylan.diag.FileLogSink
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.buffer
import okio.use
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The week-later bug-report trail: files exist, parse cleanly, rotate, and stay bounded. */
class FileLogSinkTest {
    private lateinit var dir: String
    private val fs = FileSystem.SYSTEM
    private val sinkScope = CoroutineScope(Dispatchers.Default)

    @BeforeTest
    fun setup() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-flog-${System.nanoTime()}"
        fs.createDirectories(dir.toPath())
    }

    @AfterTest
    fun teardown() {
        runCatching { fs.deleteRecursively(dir.toPath()) }
    }

    private fun entry(
        msg: String,
        level: LogLevel = LogLevel.INFO,
        tag: String = "dl",
        ts: Long = 1_756_000_000_000L,
    ) = LogBuffer.Entry(ts, level, tag, msg)

    /**
     * Bounded wait for a condition. The sink writes on a real background scope, so this polls on a
     * suspending `delay` against a failure ceiling. It returns the instant the condition holds, and
     * throws rather than falling through, so a bounded wait can never silently assert nothing.
     */
    private suspend fun await(
        what: String,
        cond: () -> Boolean,
    ) {
        val ok =
            withTimeoutOrNull(AWAIT_CEILING_MS) {
                while (!cond()) delay(POLL_MS)
                true
            }
        if (ok != true) throw AssertionError("timed out waiting for $what")
    }

    private fun read(name: String): String? =
        (dir.toPath() / name).let {
            runCatching {
                if (fs.exists(it)) fs.source(it).buffer().use { s -> s.readUtf8() } else null
            }.getOrNull()
        }

    private fun exists(name: String) = fs.exists(dir.toPath() / name)

    @Test
    fun writesIsoTimestampedParseableLines() =
        kotlinx.coroutines.runBlocking {
            FileLogSink(fs, dir.toPath(), sinkScope).accept(entry("enqueue saavn:s1 bits=128"))
            await("dylan.log.0 to exist") { read("dylan.log.0") != null }
            val text = awaitText("dylan.log.0") { it.contains("enqueue saavn:s1") }
            val line =
                text
                    .lineSequence()
                    .first { "enqueue" in it }
            assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z I/dl: ").containsMatchIn(line), line)
            assertEquals(
                "2025-08-24T01:46:40.000Z",
                FileLogSink.isoUtc(1_756_000_000_000L),
                "epoch → UTC wall clock must match known instant",
            )
        }

    @Test
    fun rotatesAndStaysBoundedUnderFlood() =
        kotlinx.coroutines.runBlocking {
            val sink = FileLogSink(fs, dir.toPath(), sinkScope, maxBytesPerFile = 400L, filesToKeep = 2)
            repeat(120) { i -> sink.accept(entry("victim line $i payload-padding-aaaaaaaaaaaaaaaaaaaa")) }
            // Rotation done when at least one archive exists and live file is under budget.
            await("rotation") { exists("dylan.log.1") && (read("dylan.log.0")?.length ?: 0) < 400 }
            await("retention to drop the oldest archive") { !exists("dylan.log.3") }
            for (i in 0..3) {
                val size = read("dylan.log.$i")?.encodeToByteArray()?.size
                if (size != null) assertTrue(size <= 500, "dylan.log.$i exceeded per-file cap: $size")
            }
            assertFalse(exists("dylan.log.3"), "retention must delete the oldest archive")
        }

    private suspend fun awaitText(
        name: String,
        pred: (String) -> Boolean,
    ): String {
        var text = read(name) ?: ""
        await("text in $name matching the predicate") {
            text = read(name) ?: ""
            pred(text)
        }
        return text
    }

    /**
     * M6: `close()` must be serialised with the writer, not merely concurrent with it.
     *
     * `out` and `written` are plain fields and `writeEntryLocked` is the only code allowed to touch
     * them, so every caller of it takes `writeMutex`. `flush` and `drainLoop` did; `close` did not —
     * it shut the handle and nulled `out` from whatever thread called it. A close landing between the
     * writer's read of `out` and its `s.write` closed the handle the writer was about to use; the
     * write threw, and the `runCatching` in `writeEntryLocked` swallowed it, so the line is simply
     * absent from the trail.
     *
     * The interleaving is a schedule, not a timing hope. [BlockingOpenFileSystem] parks the writer
     * *inside* `appendingSink` — the first statement of a line's write, and a point where the writer
     * holds `writeMutex` and no okio-internal monitor, so a lock-taking `close()` genuinely has to
     * wait and a lock-ignoring one returns at once. The assertion that `close()` has not completed is
     * the finding; the line still landing afterwards is the control that the fix costs nothing.
     */
    @Test
    fun closeWaitsForALineBeingWrittenRatherThanClosingTheHandleUnderIt() =
        kotlinx.coroutines.runBlocking {
            val gate = OpenGate()
            val sink = FileLogSink(BlockingOpenFileSystem(fs, gate), dir.toPath(), sinkScope)
            sink.accept(entry("the line that must not be lost"))

            assertTrue(
                gate.opening.await(AWAIT_CEILING_MS, TimeUnit.MILLISECONDS),
                "precondition: the writer is inside a line, holding writeMutex",
            )
            val closing = sinkScope.async { sink.close() }

            // Ample opportunity to (wrongly) complete while the writer is parked.
            withTimeoutOrNull(CLOSE_SETTLE_MS) { closing.await() }
            assertTrue(
                closing.isActive,
                "close() returned while a line was being written — it does not take writeMutex",
            )

            gate.release.countDown()
            withTimeoutOrNull(AWAIT_CEILING_MS) { closing.await() }
            assertTrue(closing.isCompleted, "close() must complete once the writer is done")
            await("the line in dylan.log.0") { read("dylan.log.0")?.contains("the line that must not be lost") == true }
            assertEquals(
                1,
                read("dylan.log.0")!!.lineSequence().count { "the line that must not be lost" in it },
                "the parked write must complete against the live handle, exactly once",
            )
        }
}

/**
 * Parks the writer at the first statement of a line's write until the test releases it, so a
 * concurrent `close()` has a genuinely in-flight line to collide with and the collision is decided by
 * `writeMutex` rather than by okio's own sink lock.
 */
private class BlockingOpenFileSystem(
    private val backing: FileSystem,
    private val gate: OpenGate,
) : ForwardingFileSystem(backing) {
    override fun appendingSink(
        path: Path,
        mustExist: Boolean,
    ): Sink {
        gate.opening.countDown()
        check(gate.release.await(AWAIT_CEILING_MS, TimeUnit.MILLISECONDS)) { "the test never released the writer" }
        return backing.appendingSink(path, mustExist)
    }
}

private class OpenGate {
    val opening = CountDownLatch(1)
    val release = CountDownLatch(1)
}

private const val AWAIT_CEILING_MS = 10_000L
private const val POLL_MS = 25L
private const val CLOSE_SETTLE_MS = 300L
