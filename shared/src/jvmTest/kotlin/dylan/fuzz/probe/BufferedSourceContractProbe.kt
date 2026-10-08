package dylan.fuzz.probe

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.io.InternalIoApi
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test

/**
 * Engine-free isolation of the `awaitContent() == true` -> `readAvailable() == -1` contradiction.
 *
 * Nothing here touches an HTTP engine, the download engine, a dispatcher or a clock. The only
 * moving parts are kotlinx.io's buffered source (the object `kotlinx.io.buffered()` returns) and
 * the `SourceByteReadChannel` that `io.ktor.utils.io.ByteReadChannel(source)` builds over it — which
 * is exactly the pairing `Transfer.readChunk` is handed in the application.
 *
 * Every line printed is a *recorded observation*: each case runs the sequence N times and only
 * reports lines that actually violated the invariant.
 */
@OptIn(InternalIoApi::class)
class BufferedSourceContractProbe {
    /**
     * One shot of the exclusive sequence the task specifies, over [ending].
     *
     * Returns a log line rather than asserting, so the caller can count violations across many
     * iterations and print only the ones that actually occurred.
     */
    private fun oneShot(
        ending: OneShotResetSource.Ending,
        min: Long,
        wanted: Int,
    ): String {
        val upstream = OneShotResetSource(CHUNK, ending)
        val source: Source = upstream.buffered()
        val out = StringBuilder()
        out.append("src=@").append(System.identityHashCode(source))
        out.append(" buf=@").append(System.identityHashCode(source.buffer))
        out.append(" up=@").append(System.identityHashCode(upstream))
        out.append(" thread=").append(Thread.currentThread().name)

        val before = source.buffer.size
        val ready =
            try {
                source.request(min)
            } catch (t: Throwable) {
                out
                    .append(" request THREW ")
                    .append(t::class.simpleName)
                    .append(": ")
                    .append(t.message)
                out.append(" upstreamReads=").append(upstream.reads)
                return out.toString()
            }
        val afterReq = source.buffer.size
        val exhausted =
            try {
                source.exhausted()
            } catch (t: Throwable) {
                out
                    .append(" exhausted THREW ")
                    .append(t::class.simpleName)
                    .append(": ")
                    .append(t.message)
                out.append(" upstreamReads=").append(upstream.reads)
                return out.toString()
            }
        val afterExh = source.buffer.size
        val read =
            try {
                source.readAtMostTo(Scratch, 0, wanted)
            } catch (t: Throwable) {
                "THREW ${t::class.simpleName}: ${t.message}"
            }
        out.append(" min=").append(min)
        out.append(" ready=").append(ready)
        out.append(" bufBefore=").append(before)
        out.append(" bufAfterReq=").append(afterReq)
        out.append(" exhausted=").append(exhausted)
        out.append(" bufAfterExh=").append(afterExh)
        out.append(" read=").append(read)
        out.append(" upstreamReads=").append(upstream.reads)
        return out.toString()
    }

    /** Runs [oneShot] [iterations] times; returns every line where the exclusive invariant broke. */
    private fun violations(
        ending: OneShotResetSource.Ending,
        min: Long,
        wanted: Int,
        iterations: Int,
    ): List<String> =
        (0 until iterations).mapNotNull { i ->
            val line = oneShot(ending, min, wanted)
            val violated =
                line.contains("ready=true") &&
                    line.contains("bufAfterReq=0") &&
                    !line.contains("THREW")
            if (violated) "$i: $line" else null
        }

    @Test
    fun `exclusive request then read, CLEAN_EOF after one chunk`() {
        val lines =
            (0 until 6).map { oneShot(OneShotResetSource.Ending.CLEAN_EOF, min = 1L, wanted = 8192) }
        println("PROBE clean-eof / min=1")
        lines.forEach { println("  $it") }
    }

    @Test
    fun `exclusive request then read, RESET after one chunk`() {
        val lines =
            (0 until 6).map { oneShot(OneShotResetSource.Ending.RESET, min = 1L, wanted = 8192) }
        println("PROBE reset / min=1")
        lines.forEach { println("  $it") }
    }

    @Test
    fun `min is zero in the real path`() {
        val lines =
            (0 until 6).map { oneShot(OneShotResetSource.Ending.RESET_THEN_EOF, min = 0L, wanted = 8192) }
        println("PROBE reset-then-eof / min=0")
        lines.forEach { println("  $it") }
    }

    @Test
    fun `the exclusive invariant over many iterations`() {
        for (ending in OneShotResetSource.Ending.entries) {
            for (min in listOf(0L, 1L, 8192L)) {
                val bad = violations(ending, min, 8192, ITERATIONS)
                println("PROBE sweep ending=$ending min=$min iterations=$ITERATIONS violations=${bad.size}")
                bad.take(5).forEach { println("  VIOLATION $it") }
            }
        }
    }

    /**
     * The exact two calls `Transfer.readChunk` makes, on the exact object shape it is handed:
     * `io.ktor.utils.io.ByteReadChannel(rawSource.buffered())`.
     *
     * This is the shape the observed `PROBE pre ac=true closed=true / PROBE post n=-1 closed=true`
     * came from, so it is the shape that has to be pushed on.
     */
    @Test
    fun `Transfer readChunk shape over many iterations`() =
        runBlocking {
            for (ending in OneShotResetSource.Ending.entries) {
                var bad = 0
                var reported = 0
                val channelClasses = mutableSetOf<String>()
                val srcClasses = mutableSetOf<String>()
                repeat(ITERATIONS) { i ->
                    val upstream = OneShotResetSource(CHUNK, ending)
                    val buffered: Source = upstream.buffered()
                    srcClasses += buffered.bufferedClass()
                    val ch: ByteReadChannel = ByteReadChannel(buffered)
                    channelClasses += ch::class.qualifiedName.orEmpty()
                    val closedBefore = ch.isClosedForRead
                    val ready = ch.awaitContent()
                    val closedAfterAwait = ch.isClosedForRead
                    val n: Int = ch.readAvailable(Scratch, 0, Scratch.size)
                    val closedAfterRead = ch.isClosedForRead
                    if (ready && n == -1) {
                        bad++
                        if (reported < 3) {
                            reported++
                            println(
                                "PROBE ch-violation ending=$ending i=$i ac=$ready closedBefore=$closedBefore " +
                                    "closedAfterAwait=$closedAfterAwait n=$n closedAfterRead=$closedAfterRead " +
                                    "thread=${Thread.currentThread().name} upstreamReads=${upstream.reads} " +
                                    "record=${upstream.record}",
                            )
                        }
                    }
                }
                println(
                    "PROBE ch ending=$ending iterations=$ITERATIONS awaitTrueThenMinusOne=$bad " +
                        "channelClasses=$channelClasses sourceClasses=$srcClasses",
                )
            }
        }

    private fun Source.bufferedClass(): String = this::class.qualifiedName ?: this::class.java.name

    private companion object {
        /** Big enough that `Transfer.copyChunks`' 64 KiB buffer is represented. */
        const val CHUNK_BYTES = 65_536
        val CHUNK = ByteArray(CHUNK_BYTES) { (it % 251).toByte() }
        val Scratch = ByteArray(65_536)
        const val ITERATIONS = 2_000
    }
}
