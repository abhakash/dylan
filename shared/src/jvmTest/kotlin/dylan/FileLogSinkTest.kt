package dylan

import dylan.diag.FileLogSink
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
     * A flush must honour its timeout even when it is the only thing running.
     *
     * `flush`'s drain loop is non-suspending from the first entry to the last: `tryReceive` is
     * non-blocking and `writeEntryLocked` is plain code. Its outer `withTimeoutOrNull` fires at
     * the *first* suspension point, and the only one in the function is `writeMutex.withLock`.
     * A contended mutex is a suspension, so a flush racing a live writer is already bounded. The
     * unbounded case is the one where the mutex is free and never contended — no writer at all.
     * An uncontended `Mutex.lock()` is a non-suspending fast path, so [withLock] returns without
     * ever suspending, the loop below it has nothing to cancel at, and the timeout is inert for
     * the whole backlog.
     *
     * That is not a hypothetical schedule. It is the state [AppContainer.shutdown] leaves the
     * sink in — `cancelAndJoin(componentJobs)` stops the writer, then the terminal lifecycle line
     * is logged and the trail flushed again — and any flush issued after the writer's scope has
     * been cancelled.
     *
     * The schedule is fixed rather than hoped for. A sink whose scope is already cancelled never
     * starts `drainLoop`, so `writeMutex` is free and the queue holds everything accepted;
     * `maxBytesPerFile = 1` rotates after every single entry, so each one re-opens the file and
     * pays a real blocking open on this lane. That is the slow-volume case the timeout exists
     * for, and it is not an okio `BufferedSink` in-memory no-op.
     */
    @Test
    fun flushHonoursItsTimeoutWhenNoWriterIsRunning() =
        kotlinx.coroutines.runBlocking {
            // A scope that is cancelled before construction: `launch` creates the coroutine and
            // cancels it, so `drainLoop` never reaches `queue.receive()` and nothing consumes.
            val noWriter = CoroutineScope(SupervisorJob()).also { it.cancel() }
            val sink =
                FileLogSink(
                    fs = SlowOpenFileSystem(fs),
                    dir = dir.toPath(),
                    scope = noWriter,
                    maxBytesPerFile = 1L,
                    filesToKeep = 1,
                )
            // More than the budget can ever drain, so the timeout is the only thing that can
            // end the flush early.
            repeat(BACKLOG) { sink.accept(entry("queued $it")) }

            val begun = System.nanoTime()
            kotlinx.coroutines.withTimeoutOrNull(FORCED_CEILING_MS) { sink.flush(timeoutMs = FLUSH_BUDGET_MS) }
            val elapsedMs = (System.nanoTime() - begun) / 1_000_000
            // Without the fix the loop never suspends, so the inner timeout cannot fire: the
            // caller asked for 300 ms and waits for the whole backlog.
            println("[flush-timeout] flush(timeoutMs=$FLUSH_BUDGET_MS) returned after ${elapsedMs}ms")
            assertTrue(
                elapsedMs < BUDGET_EXCEEDED_MS,
                "flush(timeoutMs=$FLUSH_BUDGET_MS) ran for ${elapsedMs}ms with no writer running: " +
                    "the timeout is never observed",
            )
        }

    /**
     * A volume that takes a fixed slice of wall time to open for append. [FileLogSink] opens the
     * file once and then writes through an okio `BufferedSink`, whose internal buffer absorbs a
     * short line without ever reaching the underlying `Sink.write` — so a slow *write* is
     * bypassed and the drain looks fast. A slow *open* is not: rotation after every entry forces
     * a real `appendingSink` call per line.
     */
    private class SlowOpenFileSystem(
        private val backing: FileSystem,
    ) : ForwardingFileSystem(backing) {
        override fun appendingSink(
            path: Path,
            mustExist: Boolean,
        ): Sink {
            Thread.sleep(OPEN_SLICE_MS)
            return backing.appendingSink(path, mustExist)
        }
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

/** Entries queued for the flush-timeout test: more than the budget can ever drain. */
private const val BACKLOG = 1_024

/** The timeout `flush` is asked for, and the ceiling that proves the outer caller cannot escape. */
private const val FLUSH_BUDGET_MS = 300L
private const val FORCED_CEILING_MS = 5_000L

/** One entry's worth of wall time on the slow volume (the file open, not the write). */
private const val OPEN_SLICE_MS = 5L

/** The budget plus room for the in-flight entry, and no more. */
private const val BUDGET_EXCEEDED_MS = 1_500L
