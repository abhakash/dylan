package dylan.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Persistent, size-rotated log file appender — the "diagnose a week later" trail.
 *
 * Design:
 *  - Async: [LogBuffer.Entry]s land in a DROP_OLDEST channel; a single writer drains + batches.
 *    [accept] is a `trySend` of an already-immutable entry — line formatting, the UTC instant
 *    and the UTF-8 byte accounting all happen on the writer coroutine, never on whichever lane
 *    logged. That matters because the busiest producer is the single-threaded `state` lane.
 *  - Rotation: current file [dir/dylan.log.0] rolls to .1, .2 … up to [filesToKeep];
 *    oldest deleted. On disk at most (filesToKeep + 1) files ≈
 *    (filesToKeep + 1) × maxBytesPerFile.
 *  - Lifecycle: [flush] drains the queue with a timeout (call from stop/background
 *    paths); [close] closes the file handle (reopened lazily on next write).
 *  - Append across restarts: a new session continues the current file (every line
 *    timestamped UTC), so pre-crash lines survive.
 *  - Crash window: flush after each drained batch ⇒ at most ~50 ms of tail lost.
 *
 * Files live under <baseDir>/logs/ — scrape with:
 *   adb shell run-as app.dylan.player cat files/logs/dylan.log.0
 */
class FileLogSink(
    private val fs: FileSystem,
    private val dir: Path,
    scope: CoroutineScope,
    private val maxBytesPerFile: Long = FILE_BYTES_DEFAULT,
    private val filesToKeep: Int = 2,
) {
    private val queue = Channel<LogBuffer.Entry>(capacity = 1_024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var out: okio.BufferedSink? = null
    private var written = 0L

    // Reused staging buffer: exact UTF-8 byte accounting with no per-line ByteArray. okio's
    // fluent writeUtf8 returns the sink, not the byte count, so this is the allocation-free
    // replacement for `line.encodeToByteArray().size`.
    private val staging = okio.Buffer()

    // Serializes drainLoop writes vs explicit flush() vs close() so background/stop/teardown
    // paths can never interleave bytes with the writer coroutine on the shared sink.
    private val writeMutex = Mutex()

    init {
        runCatching { fs.createDirectories(dir) }
        scope.launch { drainLoop() }
    }

    /**
     * Called by LogBuffer for every entry that passed minLevel. Never throws, never formats,
     * never allocates more than the channel slot: this runs on the logging lane.
     */
    fun accept(e: LogBuffer.Entry) {
        queue.trySend(e)
    }

    /**
     * Best-effort drain: empties whatever is queued, writes it, and flushes the
     * file handle — bounded by [timeoutMs] so background/stop paths never hang.
     * Never throws; returns silently on timeout. Safe to call concurrently with
     * the writer coroutine ([writeMutex]).
     */
    suspend fun flush(timeoutMs: Long = 2_000) {
        withTimeoutOrNull(timeoutMs) {
            writeMutex.withLock {
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    writeEntryLocked(next)
                    // The loop's only cancellation point. `tryReceive` and `writeEntryLocked`
                    // are both non-suspending, so without this the block above never suspends
                    // and `withTimeoutOrNull` cannot fire until the whole backlog has been
                    // written: a `flush(2_000)` issued from `onBackground`/`stop` on a slow or
                    // stuck volume took as long as the queue was deep, which is the opposite of
                    // the background budget the timeout exists to spend. The in-flight entry is
                    // not interruptible — that is a blocking write on this lane — so the bound
                    // holds at entry boundaries, which is where 1024 entries actually live.
                    yield()
                }
                runCatching { out?.flush() }
            }
        }
    }

    /**
     * Closes the file handle; reopened lazily on the next write. Never throws.
     *
     * Takes [writeMutex], which is the point: `out` and `written` are plain fields and
     * `writeEntryLocked` is the only code allowed to touch them, so a close that skips the lock can
     * shut the handle while the writer is between its read of `out` and its `s.write` on that very
     * sink. The write then throws, and the `runCatching` in `writeEntryLocked` swallows it — the
     * line is simply missing from the trail, with nothing in the log to say so.
     *
     * `suspend` for that reason: taking the lock is a suspension, and a non-suspending `close()` could
     * only take it by blocking, which is the same bug wearing a different hat.
     */
    suspend fun close() {
        writeMutex.withLock {
            runCatching { out?.close() }
            out = null
        }
    }

    private suspend fun drainLoop() {
        while (true) {
            val first = queue.receive()
            writeMutex.withLock {
                writeEntryLocked(first)
                // Batch drain: swallow whatever piled up within the window, then flush once.
                while (true) {
                    val next = withTimeoutOrNull(FLUSH_IDLE_MS) { queue.receive() } ?: break
                    writeEntryLocked(next)
                }
                runCatching { out?.flush() }
            }
        }
    }

    private fun writeEntryLocked(e: LogBuffer.Entry) {
        val s =
            out ?: runCatching {
                fs.appendingSink(currentFile()).buffer().also {
                    out = it
                    written = currentSize()
                }
            }.getOrNull() ?: return
        runCatching {
            val line = format(e)
            staging.writeUtf8(line)
            written += staging.size
            s.write(staging, staging.size)
            if (written >= maxBytesPerFile) rotate()
        }.onFailure {
            runCatching { out?.close() }
            out = null
        }
        // `finally`, not inline in the happy path above. `staging` is a *reused* buffer, so a
        // write that throws between `writeUtf8` and `s.write` — a full disk, an unlinked file, a
        // handle closed underneath us — used to leave the failed line's bytes sitting in it. The
        // next entry then appended to that residue: `staging.size` counted old+new, so `written`
        // inflated by the stale line on every subsequent failure (accelerating rotation), and
        // `s.write(staging, …)` re-emitted the dead line into the trail. One I/O error therefore
        // permanently corrupted both the byte accounting and the log itself — which is the worst
        // possible moment for it, since an I/O error is exactly when the trail is being read.
        staging.clear()
    }

    /**
     * Roll: live .0 → .1, .1 → .2 … oldest archive deleted. On disk at most
     * (filesToKeep + 1) files ≈ (filesToKeep + 1) × maxBytesPerFile.
     */
    private fun rotate() {
        runCatching { out?.close() }
        out = null
        written = 0
        runCatching { fs.delete(dir / "$BASE.$filesToKeep") }
        for (i in filesToKeep - 1 downTo 1) {
            val from = dir / "$BASE.$i"
            if (fs.exists(from)) {
                runCatching { fs.atomicMove(from, dir / "$BASE.${i + 1}") }
            }
        }
        if (fs.exists(currentFile())) {
            runCatching { fs.atomicMove(currentFile(), dir / "$BASE.1") }
        }
    }

    private fun currentFile(): Path = dir / "$BASE.0"

    private fun currentSize(): Long = fs.metadataOrNull(currentFile())?.size ?: 0L

    internal companion object {
        const val BASE = "dylan.log"
        const val FILE_BYTES_DEFAULT = 512_000L
        const val FLUSH_IDLE_MS = 50L

        /**
         * Every timestamp in the trail is written with exactly this many fractional digits —
         * padded for a whole-second instant, truncated for a finer one. Fixed width on purpose:
         * a scrape that sorts or lexically compares lines needs the instant at a fixed offset.
         */
        const val MILLIS_DIGITS = 3

        fun format(e: LogBuffer.Entry): String {
            val meta = e.metaJson?.let { " $it" } ?: ""
            return "${isoUtc(e.ts)} ${e.level.name.first()}/${e.tag}: ${e.msg}$meta\n"
        }

        fun isoUtc(epochMs: Long): String {
            val s =
                kotlinx.datetime.Instant
                    .fromEpochMilliseconds(epochMs)
                    .toString()
            val withoutZ = s.removeSuffix("Z")
            val withMillis =
                if ('.' !in withoutZ) {
                    "$withoutZ.000"
                } else {
                    val dot = withoutZ.lastIndexOf('.')
                    val base = withoutZ.substring(0, dot)
                    val frac = withoutZ.substring(dot + 1).padEnd(MILLIS_DIGITS, '0').take(MILLIS_DIGITS)
                    "$base.$frac"
                }
            return "${withMillis}Z"
        }
    }
}
