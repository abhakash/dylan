package dylan.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Persistent, size-rotated log file appender — the "diagnose a week later" trail.
 *
 * Design:
 *  - Async: entries land in a DROP_OLDEST channel; a single writer drains + batches.
 *    Logging never blocks or backpressures a hot path.
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
    private val queue = Channel<String>(capacity = 1_024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var out: okio.BufferedSink? = null
    private var written = 0L

    // Serializes drainLoop writes vs explicit flush() so background/stop flushes
    // can never interleave bytes with the writer coroutine on the shared sink.
    private val writeMutex = Mutex()

    init {
        runCatching { fs.createDirectories(dir) }
        scope.launch { drainLoop() }
    }

    /** Called by LogBuffer for every entry that passed minLevel. Never throws. */
    fun accept(e: LogBuffer.Entry) {
        queue.trySend(format(e))
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
                    writeLineLocked(next)
                }
                runCatching { out?.flush() }
            }
        }
    }

    /** Closes the file handle; reopened lazily on the next write. Never throws. */
    fun close() {
        runCatching { out?.close() }
        out = null
    }

    private suspend fun drainLoop() {
        while (true) {
            val first = queue.receive()
            writeMutex.withLock {
                writeLineLocked(first)
                // Batch drain: swallow whatever piled up within the window, then flush once.
                while (true) {
                    val next = withTimeoutOrNull(FLUSH_IDLE_MS) { queue.receive() } ?: break
                    writeLineLocked(next)
                }
                runCatching { out?.flush() }
            }
        }
    }

    private fun writeLineLocked(line: String) {
        val s =
            out ?: runCatching {
                fs.appendingSink(currentFile()).buffer().also {
                    out = it
                    written = currentSize()
                }
            }.getOrNull() ?: return
        runCatching {
            s.writeUtf8(line)
            written += line.encodeToByteArray().size
            if (written >= maxBytesPerFile) rotate()
        }.onFailure {
            runCatching { out?.close() }
            out = null
        }
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
                    val frac = withoutZ.substring(dot + 1).padEnd(3, '0').take(3)
                    "$base.$frac"
                }
            return "${withMillis}Z"
        }
    }
}
