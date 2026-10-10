package dylan.support

import dylan.diag.FileLogSink
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime

/**
 * Not JMH: the module has no benchmark source set and adding one is a build-file change this
 * wave does not own. Plain `measureNanoTime` over a fixed workload after 3 warmup rounds, with
 * a `blackhole` accumulator so the JIT cannot delete the work being measured, plus
 * `getThreadAllocatedBytes` for the allocation-per-line claim.
 *
 * Every producer variant runs the *real* `LogBuffer.log` path and the only difference is what
 * the bound sink does on the logging lane. Messages are pre-built so string interpolation is
 * not counted as logging cost. Disk I/O is excluded from the producer variants so the number
 * is CPU cost on that lane rather than disk latency.
 */
class LogSinkPerfHarness {
    private val fs = FileSystem.SYSTEM
    private val scope = CoroutineScope(Dispatchers.Default)
    private var blackhole = 0L

    private fun sinkDir(tag: String): Path {
        val d = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-bench-$tag-${System.nanoTime()}"
        fs.createDirectories(d.toPath())
        return d.toPath()
    }

    private fun msg(i: Int) = "done saavn:s$i bits=320 ext=m4a bytes=4194304 ms=$i q=0.9 rate=4194304"

    private val allocatedBytes: (Long) -> Long =
        run {
            val bean = ManagementFactory.getThreadMXBean()
            if (bean is com.sun.management.ThreadMXBean) {
                { id: Long -> bean.getThreadAllocatedBytes(id) }
            } else {
                { _: Long -> -1L }
            }
        }

    private class Result(
        val ns: Long,
        val bytes: Long,
    )

    private fun bench(
        label: String,
        n: Int,
        block: (Int) -> Unit,
    ): Result {
        repeat(3) {
            for (i in 0 until n) block(i)
        }
        val id = Thread.currentThread().id
        val before = allocatedBytes(id)
        val ns =
            measureNanoTime {
                for (i in 0 until n) block(i)
            }
        val bytes = allocatedBytes(id) - before
        blackhole += ns
        println(
            "[bench] %-46s %8.2f ms  %5d ns/msg  %6d B/msg".format(
                label,
                ns / 1_000_000.0,
                ns / n,
                if (bytes < 0) -1 else bytes / n,
            ),
        )
        return Result(ns, bytes)
    }

    /** @return accumulated blackhole so nothing measured can be optimised away. */
    fun run(n: Int = 100_000): Long {
        val entries = Array(n) { LogBuffer.Entry(1_756_000_000_000L + it, LogLevel.INFO, "dl", msg(it)) }
        val msgs = Array(n) { msg(it) }

        // 1. The ring alone: level filter, timestamp, redact, COW add + evict.
        val ringBuf = LogBuffer(capacity = 512, minLevel = LogLevel.DEBUG)
        val ringOnly = bench("LogBuffer.log, no sink (the floor)", n) { i -> ringBuf.i("dl", msgs[i]) }

        // 2. OLD accept(): format the line and copy it again for byte accounting, on the
        //    logging lane. Reproduced verbatim — this is the cost being moved.
        var legacyBytes = 0L
        val legacyBuf = LogBuffer(capacity = 512, minLevel = LogLevel.DEBUG)
        legacyBuf.bindSink("legacy") { e ->
            val line = FileLogSink.format(e)
            legacyBytes += line.encodeToByteArray().size
        }
        val oldProducer = bench("OLD producer: + format + encodeToByteArray", n) { i -> legacyBuf.i("dl", msgs[i]) }

        // 3. NEW accept(): trySend of an immutable Entry. Nothing else on this thread.
        val newBuf = LogBuffer(capacity = 512, minLevel = LogLevel.DEBUG)
        val newSink = FileLogSink(fs, sinkDir("new"), scope, maxBytesPerFile = Long.MAX_VALUE, filesToKeep = 1)
        newBuf.bindSink("file", newSink::accept)
        val newProducer = bench("NEW producer: trySend(Entry) (saturated)", n) { i -> newBuf.i("dl", msgs[i]) }

        // 4. The handoff on its own, unsaturated: what the logging lane pays in steady state,
        //    where the writer keeps up and the channel never has to drop.
        val fast = Channel<LogBuffer.Entry>(Channel.UNLIMITED)
        scope.launch { for (e in fast) blackhole += e.msg.length }
        val handoff = bench("trySend(Entry) into an unsaturated channel", n) { i -> fast.trySend(entries[i]) }

        // 5. The two things that left the producer, measured on their own.
        var lineSum = 0
        bench("  format(e): Instant parse + string concats", n) { i ->
            lineSum += FileLogSink.format(entries[i]).length
        }
        var byteSum = 0L
        bench("  line.encodeToByteArray().size (removed)", n) { i ->
            byteSum += entries[i].msg.encodeToByteArray().size
        }
        val staged = okio.Buffer()
        var stagedBytes = 0L
        bench("  staging Buffer accounting (replacement)", n) { i ->
            staged.writeUtf8(entries[i].msg)
            stagedBytes += staged.size
            staged.clear()
        }
        blackhole += lineSum + byteSum + legacyBytes + stagedBytes

        // 6. The writer must absorb what the producer no longer does.
        val drainNs =
            measureNanoTime {
                runBlocking { newSink.flush(timeoutMs = 120_000) }
            }
        blackhole += drainNs
        println(
            "[bench] %-46s %8.2f ms (total, incl. format + file write)".format(
                "NEW writer: full drain of the tail",
                drainNs / 1_000_000.0,
            ),
        )

        println(
            "[bench] %-46s %8.2f ms  %5d ns/msg".format(
                "=> saved on the logging lane (OLD - NEW, saturated)",
                (oldProducer.ns - newProducer.ns) / 1_000_000.0,
                (oldProducer.ns - newProducer.ns) / n,
            ),
        )
        println(
            "[bench] %-46s %8.2f ms  %5d ns/msg".format(
                "=> saved on the logging lane (OLD - handoff, steady)",
                (oldProducer.ns - handoff.ns) / 1_000_000.0,
                (oldProducer.ns - handoff.ns) / n,
            ),
        )
        println(
            "[bench] %-46s %8.2f ms  %5d ns/msg".format(
                "   irreducible ring cost (for scale)",
                ringOnly.ns / 1_000_000.0,
                ringOnly.ns / n,
            ),
        )
        scope.cancel()
        return blackhole
    }
}
