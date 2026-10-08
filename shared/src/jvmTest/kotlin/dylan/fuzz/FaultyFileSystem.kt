package dylan.fuzz

import kotlinx.coroutines.CompletableDeferred
import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingSink
import okio.Path
import okio.Sink
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A delegating `okio.FileSystem` that decides what the disk does.
 *
 * okio 3.18.1 ships `ForwardingFileSystem`, so this is delegation rather than reimplementation —
 * verified with `javap` against `okio-jvm-3.18.1.jar`. The one thing a decorator cannot reach is
 * `FileSystem.write`, which is **final** and delegates to the abstract `sink`, so overriding `sink`
 * controls `PartMeta.write` (the `.part.meta` sidecar) as well as every other buffered write path.
 * `dylan.util.fsRename` goes through `java.nio` directly and is *not* reachable this way — hence the
 * defaulted `rename` seam on `DownloadEngine`.
 *
 * **What the quota can and cannot see — the load-bearing limitation.** The download engine writes
 * the audio body through `openReadWrite(...)` and then `FileHandle.write(pos, buf, off, n)`
 * (`Transfer.kt:501`, `:608`). `okio.FileHandle.write` is **final** (verified: `public final void
 * write(long, byte[], int, int)` with `protectedWrite` abstract), so a decorator cannot intercept a
 * single byte of that stream, and the only reachable ENOSPC on the audio path is *at the open*.
 * [quotaBytes] therefore counts only the buffered `sink`/`write` traffic — the `.part.meta` sidecar
 * is its only customer in this codebase — and an audio-directory quota expressed through it would
 * measure nothing. For the body, [failNextWrites] (or [failAllWrites]) on the `.part` suffix is the
 * honest ENOSPC: the open throws, `Transfer.drain` maps it to `StreamErr.Storage`, and the verdict
 * is `STORAGE`.
 *
 * **The counters are the point.** §5.8 makes "the injector was actually reached" a *test*
 * (`S-STG-04`), because a fault injection that is silently not reached produces a green scenario
 * that proves nothing. Every operation the app performs increments a counter, and
 * [countersNonZeroFor] is the control that asserts a named operation was actually seen.
 */
class FaultyFileSystem(
    private val delegate: FileSystem = FileSystem.SYSTEM,
) : FileSystem() {
    /** op name → number of times the app called it through this decorator. */
    val counters: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

    /** op name → deferreds a caller is waiting to *observe* that op happen. See [awaitOp]. */
    private val waiters = ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableDeferred<Unit>>>()

    /**
     * A deferred that completes the next time [op] is intercepted.
     *
     * This exists because of an ordering fact that cost an afternoon: `DownloadEngine.publishTerminal`
     * runs from `fail()`, and `parts.persist` — the only caller of `PartMeta.write` — runs in
     * `runJob`'s **`finally`**, i.e. *after* the terminal state a waiter is woken by. So a scenario
     * that wants to observe the attempt's own cleanup cannot stop at `states.first { … }` without
     * racing it, and polling the directory would be a sleep with extra steps.
     *
     * The interception itself is the event, so the decorator publishes it. Note the small race that
     * is inherent and harmless: a waiter registered *while* an occurrence is being delivered waits
     * for the next one. Both uses in this suite register before the action.
     */
    fun awaitOp(op: String): CompletableDeferred<Unit> {
        val signal = CompletableDeferred<Unit>()
        waiters.computeIfAbsent(op) { CopyOnWriteArrayList() }.add(signal)
        return signal
    }

    /** Paths (suffix-matched) whose unlink must fail with EACCES. */
    val undeletable = CopyOnWriteArrayList<String>()

    /** Paths (suffix-matched) whose reads must fail with EIO, i.e. `metadataOrNull` throwing. */
    val unreadable = CopyOnWriteArrayList<String>()

    /** suffix → remaining throws for `sink` / `openReadWrite`. */
    private val armedWrites = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Byte budget across every *buffered* write this decorator hands out. `Long.MAX_VALUE` (the
     * default) means no quota at all, so an unconfigured decorator is a pure pass-through. `0` is
     * "the volume is full": the budget is spent before a byte has been written, which is the shape
     * `aQuotaOnTheAudioDirectoryIsReportedAsStorage` needs.
     *
     * Counted on `sink`/`write` only, and it is **not scoped to a path** — so it cannot fault the
     * sidecar without also faulting the audio body. For "this one write fails" use [failNextWrites].
     * See the class KDoc for why the audio body is not reachable from here at all.
     */
    var quotaBytes: Long = Long.MAX_VALUE

    private val written = AtomicLong(0)

    private fun count(op: String) {
        counters.computeIfAbsent(op) { AtomicInteger() }.incrementAndGet()
        waiters.remove(op)?.forEach { it.complete(Unit) }
    }

    /**
     * op → count for [ops]. The **control** required by §5.8: a fault scenario that never calls this
     * has proved only that the harness compiles.
     */
    fun countersNonZeroFor(vararg ops: String): Map<String, Int> = ops.associateWith { counters[it]?.get() ?: 0 }

    /** Arm the next [times] writes to a path ending in [suffix] to throw ENOSPC. */
    fun failNextWrites(
        suffix: String,
        times: Int,
    ) {
        armedWrites[suffix] = AtomicInteger(times)
    }

    /**
     * Every write to a path ending in [suffix] throws ENOSPC, until [disarmWrites] is called.
     *
     * This is the only arming that is safe to set up *before* the action under test: a scenario
     * cannot know which write the app will make next, so a count is a race against production's
     * own ordering unless the fault is unconditional.
     */
    fun failAllWrites(suffix: String) = failNextWrites(suffix, Int.MAX_VALUE)

    /** Disarm a suffix armed by [failNextWrites] / [failAllWrites]. */
    fun disarmWrites(suffix: String) {
        armedWrites.remove(suffix)
    }

    private fun armedWrite(path: String): Boolean {
        val key = armedWrites.keys.firstOrNull { path.endsWith(it) } ?: return false
        val n = armedWrites[key] ?: return false
        return n.get() > 0 && n.decrementAndGet() >= 0
    }

    private fun matches(
        list: CopyOnWriteArrayList<String>,
        path: Path,
    ): Boolean = list.any { path.toString().endsWith(it) }

    private fun overQuota(): Boolean = written.get() >= quotaBytes

    override fun canonicalize(path: Path): Path = delegate.canonicalize(path).also { count("canonicalize") }

    override fun metadataOrNull(path: Path): FileMetadata? {
        count("metadataOrNull")
        if (matches(unreadable, path)) throw IOException("EIO: injected unreadable $path")
        return delegate.metadataOrNull(path)
    }

    override fun list(dirOrFile: Path): List<Path> = delegate.list(dirOrFile).also { count("list") }

    override fun listOrNull(dirOrFile: Path): List<Path>? = delegate.listOrNull(dirOrFile).also { count("listOrNull") }

    override fun openReadOnly(file: Path): FileHandle {
        count("openReadOnly")
        if (matches(unreadable, file)) throw IOException("EIO: injected unreadable $file")
        return delegate.openReadOnly(file)
    }

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        count("openReadWrite")
        if (armedWrite(file.toString()) || overQuota()) {
            throw IOException("ENOSPC: no space left on device (injected)")
        }
        return delegate.openReadWrite(file, mustCreate, mustExist)
    }

    override fun source(file: Path) = delegate.source(file).also { count("source") }

    /**
     * The interception point for `FileSystem.write`: `write` is final in okio and *delegates here*,
     * so counting and quota-ing the bytes is all it takes to make `PartMeta.write` faultable.
     */
    override fun sink(
        file: Path,
        mustCreate: Boolean,
    ): Sink {
        count("sink")
        if (armedWrite(file.toString()) || overQuota()) {
            throw IOException("ENOSPC: no space left on device (injected)")
        }
        return QuotaSink(delegate.sink(file, mustCreate))
    }

    override fun appendingSink(
        file: Path,
        mustCreate: Boolean,
    ): Sink = delegate.appendingSink(file, mustCreate).also { count("appendingSink") }

    override fun createDirectory(
        dir: Path,
        mustCreate: Boolean,
    ) = delegate.createDirectory(dir, mustCreate).also { count("createDirectory") }

    override fun atomicMove(
        source: Path,
        target: Path,
    ) = delegate.atomicMove(source, target).also { count("atomicMove") }

    override fun delete(
        fileOrDirectory: Path,
        mustCheck: Boolean,
    ) {
        count("delete")
        if (matches(undeletable, fileOrDirectory)) {
            throw IOException("EACCES: injected undeletable $fileOrDirectory")
        }
        delegate.delete(fileOrDirectory, mustCheck)
    }

    override fun createSymlink(
        link: Path,
        target: Path,
    ) = delegate.createSymlink(link, target).also { count("createSymlink") }

    override fun close() = delegate.close()

    private inner class QuotaSink(
        private val delegateSink: Sink,
    ) : ForwardingSink(delegateSink) {
        override fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            if (overQuota()) throw IOException("ENOSPC: no space left on device (injected)")
            delegateSink.write(source, byteCount)
            written.addAndGet(byteCount)
        }
    }

    override fun toString(): String = "FaultyFileSystem($delegate)"
}
