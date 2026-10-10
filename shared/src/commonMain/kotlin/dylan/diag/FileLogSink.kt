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
            if (written >= maxBytesPerFile) {
                val failure = rotate()
                if (failure != null) {
                    noteRotationFailure(e.ts, failure)
                } else {
                    lastRotationFailure = null
                }
            }
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
     *
     * All-or-nothing, and it aborts on the first failure.
     *
     * The promotions form a chain: each one's *target* is the next promotion's *source*. So a
     * promotion that fails leaves its archive at the old path, and the next move in the loop
     * lands on exactly that path. Continuing after a failure therefore lets a healthy move
     * clobber the archive the failed move was supposed to preserve — the generation is destroyed
     * seconds later by ordinary traffic, and `runCatching` around the step guarantees the trail,
     * the one artefact a week-later triage reads, never says any of it happened. A partially
     * rolled set is recoverable (the next roll redoes the steps that did not happen); a destroyed
     * generation is not. So: stop, record, leave the live file writing.
     *
     * Nothing is undone. A promotion that already succeeded stays done — there is no portable
     * way to reverse a rename, and losing data to make the state look tidy would be strictly
     * worse. What the abort buys is that the steps *after* the failure never run, so no archive
     * is overwritten and no generation is lost. The next successful roll finishes the set.
     *
     * @return null when the roll completed, otherwise why it stopped. The caller writes the
     *   reason into the trail. Never throws.
     */
    private fun rotate(): String? {
        // The handle goes first: the live file is about to change name, and the buffered sink
        // holds the old inode. Closing it here rather than at the next write is what keeps the
        // promotions from renaming a file under an open descriptor.
        runCatching { out?.close() }
        out = null
        written = 0

        // Free the oldest slot before anything shifts. `mustExist = false` makes the eviction
        // idempotent: a `.filesToKeep` that was never created is the normal case on the first
        // roll, and must not read as a failure.
        if (fs.exists(oldestArchive())) {
            val evicted = runCatching { fs.delete(oldestArchive(), mustExist = false) }
            if (evicted.isFailure) {
                return "could not evict ${oldestArchive().name}: ${describe(evicted.exceptionOrNull())}"
            }
        }

        for (i in filesToKeep - 1 downTo 1) {
            val from = dir / "$BASE.$i"
            if (!fs.exists(from)) continue
            val to = dir / "$BASE.${i + 1}"
            val moved = runCatching { fs.atomicMove(from, to) }
            if (moved.isFailure) {
                return "could not move ${from.name} to ${to.name}: ${describe(moved.exceptionOrNull())}; " +
                    "the archive stays at ${from.name} and the live log keeps writing into ${currentFile().name}"
            }
        }

        if (fs.exists(currentFile())) {
            val target = dir / "$BASE.1"
            val moved = runCatching { fs.atomicMove(currentFile(), target) }
            if (moved.isFailure) {
                return "could not move ${currentFile().name} to ${target.name}: " +
                    "${describe(moved.exceptionOrNull())}; the live generation stays at ${currentFile().name}"
            }
        }
        return null
    }

    private fun oldestArchive(): Path = dir / "$BASE.$filesToKeep"

    /**
     * Records a failed rotation in the trail itself, once per distinct reason.
     *
     * The trail is the artefact this whole class exists to produce, so a rotation failure is only
     * worth recording there: nothing else reads it, and a week-later triage that finds a short
     * archive set with no explanation for it cannot act on what it cannot see.
     *
     * Once per reason, not once per line: an aborted rotation leaves the live file at or over
     * [maxBytesPerFile], so the very next line attempts the roll again. Without the dedupe, a
     * volume that stays broken would write one "rotation failed" line per log line and bury the
     * real content under noise — and the trail is what is being scraped.
     *
     * Best effort by nature and the one place in this class that cannot report upward: if this
     * write fails too, the volume is down and there is no consumer left to tell. It never
     * throws, because a failure to *record* a failure must not cost the next real log line.
     */
    private fun noteRotationFailure(
        ts: Long,
        reason: String,
    ) {
        if (reason == lastRotationFailure) return
        lastRotationFailure = reason
        val line =
            "${isoUtc(ts)} ${LogLevel.ERROR.name.first()}/$ROTATION_FAILURE_TAG: " +
                "rotation failed - $reason; the live generation is not lost and logging continues\n"
        // A dedicated handle rather than the shared `out`: `rotate()` has already nulled it, and
        // reusing it here would mean remembering a handle that is closed on the success path.
        runCatching {
            val sink = fs.appendingSink(currentFile()).buffer()
            try {
                sink.writeUtf8(line)
                sink.flush()
            } finally {
                runCatching { sink.close() }
            }
        }
    }

    /**
     * A single-line, length-bounded rendering of [t] for the trail. A message is preferred; the
     * class name is the fallback, because an exception with no message would otherwise record
     * nothing at all. Newlines are collapsed: the trail is one record per line and a multi-line
     * message would break every scraper that reads it by line.
     */
    private fun describe(t: Throwable?): String {
        val raw = t?.message?.takeIf { it.isNotBlank() } ?: t?.let { it::class.simpleName } ?: "unknown"
        return raw
            .replace(LINE_BREAK_RE, " ")
            .trim()
            .take(REASON_MAX_CHARS)
    }

    private fun currentFile(): Path = dir / "$BASE.0"

    private fun currentSize(): Long = fs.metadataOrNull(currentFile())?.size ?: 0L

    /**
     * The reason of the rotation failure already recorded in the trail, or null when the last
     * roll succeeded. Written only from [writeEntryLocked], which every caller reaches through
     * [writeMutex], so a plain field is safe here and costs nothing on the writer lane.
     */
    private var lastRotationFailure: String? = null

    internal companion object {
        const val BASE = "dylan.log"
        const val FILE_BYTES_DEFAULT = 512_000L
        const val FLUSH_IDLE_MS = 50L

        /** Tag on the trail line that records a failed rotation, so it is greppable by tag. */
        const val ROTATION_FAILURE_TAG = "FileLogSink"

        /** Any run of whitespace becomes one space, so a reason never spans two trail lines. */
        private val LINE_BREAK_RE = Regex("\\s+")

        /** Long enough for a filesystem's own message, short enough to stay a one-line record. */
        private const val REASON_MAX_CHARS = 160

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
