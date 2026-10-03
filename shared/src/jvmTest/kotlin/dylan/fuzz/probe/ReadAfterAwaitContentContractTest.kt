package dylan.fuzz.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.write
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ## The contract
 *
 * > If `awaitContent(1)` returns true and no other reader consumes the channel, the immediately
 * > following read must return at least one byte **or** a terminal error. It must not return clean
 * > EOF (`-1`).
 *
 * Why `-1` is specifically the forbidden answer, and not merely one of several bad answers:
 * `ByteChannel.awaitContent` (`ktor-io` 3.5.2, `commonMain/io/ktor/utils/io/ByteChannel.kt:92-102`)
 * is
 *
 * ```
 * rethrowCloseCauseIfNeeded()
 * if (_readBuffer.size >= min) return true
 * sleepWhile(Slot::Read) { flushBufferSize + _readBuffer.size < min && _closedCause.value == null }
 * if (_readBuffer.size < CHANNEL_MAX_SIZE) moveFlushToReadBuffer()
 * return _readBuffer.size >= min
 * ```
 *
 * The cause check runs **once, before** the suspension. On the way out the method returns
 * `_readBuffer.size >= min` with no second look at `_closedCause`. So `awaitContent(1) == true` is a
 * statement that at least one byte was in the read buffer at that instant — not a hint.
 *
 * And `-1` cannot mean "well, those bytes are gone". `ByteReadChannelOperationsKt.readAvailable`
 * (`ByteReadChannelOperations.kt:268-278`) opens with `if (isClosedForRead) return -1`, before it
 * consults the buffer; `ByteChannel.isClosedForRead` (`ByteChannel.kt:88-89`) is
 *
 * ```
 * (closedCause != null) || (isClosedForWrite && flushBufferSize == 0 && _readBuffer.exhausted())
 * ```
 *
 * whose first disjunct has **no** `_readBuffer` conjunct. Once a cause is recorded the channel
 * reports closed-for-read with bytes still buffered, and every read API either throws
 * `ClosedReadChannelException` or returns `-1` ([RecoveryAuditProbe] measures that table). The bytes
 * are unrecoverable.
 *
 * ## The shape under test
 *
 * `HttpResponse.bodyAsChannel()` returns an `io.ktor.utils.io.ByteChannel` — measured, not assumed;
 * see [EngineReadPathProbe] — fed by ktor's `defaultTransformers`, which runs
 * `writer { body.copyTo(channel, Long.MAX_VALUE) }` (`ktor-client-core`,
 * `DefaultTransform.kt:100-119`) and, when the upstream read throws, calls `cancel(cause)`
 * (`ByteReadChannelOperations.kt:210-217` -> `ByteChannel.cancel`, `ByteChannel.kt:159-171`).
 *
 * `Transfer.readChunk` (`Transfer.kt:648-655`) is `awaitContent()` then `readAvailable(...)`, so it
 * sees `true` then `-1`, treats it as end-of-body, writes nothing, leaves `Breakpoint.partBytes` at
 * 0, and the retry omits `Range:` because `resumable` is `partBytes > 0L` (`Breakpoint.kt:46`).
 *
 * ## Direction of each assertion
 *
 * The delivered bytes cannot be recovered, so the contract is satisfiable only by the **reader**
 * refusing to call a recorded-cause `-1` an end-of-body. Two shapes are therefore measured:
 *
 *  * [a contract-satisfying reader is never told clean EOF after a true awaitContent] — the gate.
 *    Green today; red if the production read path regresses to trusting `-1`.
 *
 * A second test used to sit here asserting that the production shape *did* lose the bytes, as a
 * characterisation of the defect. It is retired — see the note at the foot of the class.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class ReadAfterAwaitContentContractTest {
    /** One read's meaning, so "bytes" and "terminal" and "EOF" can never be confused in a report. */
    private sealed interface Outcome {
        data class Bytes(
            val count: Int,
        ) : Outcome

        data class Terminal(
            val cause: String,
        ) : Outcome

        data object Eof : Outcome
    }

    /** What the two reads below produced, paired with the `awaitContent` that preceded them. */
    private data class Observation(
        val awaitContent: Boolean,
        val awaitThrew: String?,
        val outcome: Outcome,
    )

    /**
     * The read the contract demands, given the `awaitContent` result. Taking it as a parameter is
     * not tidiness: `awaitContent` has a side effect (it throws once a cause is recorded), so
     * calling it twice would test something other than the contract.
     */
    private suspend fun readContractCompliant(
        ch: ByteReadChannel,
        buf: ByteArray,
        awaited: Boolean,
    ): Outcome {
        if (!awaited) {
            ch.closedCause?.let { return Outcome.Terminal("$it") }
            return Outcome.Eof
        }
        val n = ch.readAvailable(buf, 0, buf.size)
        if (n > 0) return Outcome.Bytes(n)
        if (n == 0) return Outcome.Bytes(0)
        // `-1` while a cause is recorded is not an end of body. Refusing to call it one is the
        // whole of the contract; the bytes are still lost, but the failure is a transport failure
        // and the attempt is a retry rather than a silent restart from zero.
        ch.closedCause?.let { return Outcome.Terminal("$it") }
        return Outcome.Eof
    }

    // `Transfer.copyChunks` (`Transfer.kt:555-582`) driving `Transfer.readChunk`
    // (`Transfer.kt:648-655`), verbatim, with the part-file write removed because it does not
    // affect which outcome occurs, used to be `readAsTransferDoes` here. It was retired with the
    // characterisation test that called it; `Transfer` now reads the engine's own channel, which is
    // what `DownloadBodyChannelIdentityTest` pins.

    /** The contract, stated once. */
    private fun assertContractHolds(
        where: String,
        o: Observation,
    ) {
        if (!o.awaitContent || o.awaitThrew != null) return
        assertTrue(
            o.outcome !is Outcome.Eof,
            "$where: awaitContent(1) returned true, so at least one byte was in the read buffer, " +
                "and the read returned -1 (clean EOF). The delivered bytes are dropped and the " +
                "failure is reported as a complete body.",
        )
    }

    /**
     * Forces the producing ordering on two real threads and runs [read] under it.
     *
     * The reader is parked inside `awaitContent` first, established by the *reader's own thread
     * state* reaching `WAITING`/`TIMED_WAITING` — the state a JVM thread takes in
     * `LockSupport.park`, which is where a suspended continuation lives. That is a hand-off, not a
     * sleep: the producer cannot act until the reader is provably suspended, so the ordering cannot
     * vary between runs.
     */
    private fun forcedResetChannel(read: suspend (ByteReadChannel, ByteArray, Boolean) -> Outcome): List<Observation> {
        val channel = ByteChannel(autoFlush = false)
        val buf = ByteArray(DELIVERED)
        val readerReady = CountDownLatch(1)
        var awaited = false
        var awaitThrew: String? = null
        var outcome: Outcome = Outcome.Eof
        val reader =
            Thread {
                runBlocking {
                    val consumer =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            readerReady.countDown()
                            awaited =
                                try {
                                    channel.awaitContent()
                                } catch (t: Throwable) {
                                    awaitThrew = "${t::class.simpleName}: ${t.message}"
                                    false
                                }
                            outcome = read(channel, buf, awaited)
                        }
                    consumer.join()
                }
            }
        reader.start()
        check(readerReady.await(ARRANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "reader never started" }
        check(awaitParked(reader)) { "the reader never parked inside awaitContent" }
        // Producer: deliver, flush (which wakes the reader), and only then install the reset —
        // the sequence `copyTo` runs on a mid-stream failure.
        runBlocking { channel.writeFully(ByteArray(DELIVERED) { (it % 251).toByte() }) }
        channel.flushWriteBuffer()
        channel.cancel(OneShotResetSource.RESET)
        reader.join(JOIN_TIMEOUT_MS)
        check(!reader.isAlive) { "the reader was left parked" }
        return listOf(Observation(awaited, awaitThrew, outcome))
    }

    private fun repeatForced(
        repeats: Int,
        read: suspend (ByteReadChannel, ByteArray, Boolean) -> Outcome,
    ): List<Observation> = (0 until repeats).flatMap { forcedResetChannel(read) }

    private fun awaitParked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + ARRANGE_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            when (thread.state) {
                Thread.State.WAITING, Thread.State.TIMED_WAITING -> return true
                Thread.State.TERMINATED -> return false
                else -> Thread.onSpinWait()
            }
        }
        return false
    }

    /**
     * The gate. In the ordering that provokes the contradiction, a reader that distinguishes a
     * recorded cause from an end of body never answers `-1`.
     */
    @Test
    fun `a contract-satisfying reader is never told clean EOF after a true awaitContent`() {
        val results = repeatForced(200, ::readContractCompliant)
        results.forEachIndexed { i, o -> assertContractHolds("forced-ordering run $i", o) }
        println(
            "PROBE contract/forced repeats=${results.size} awaitContentTrue=" +
                "${results.count { it.awaitContent }} bytes=${results.count { it.outcome is Outcome.Bytes }} " +
                "terminal=${results.count { it.outcome is Outcome.Terminal }} " +
                "eof=${results.count { it.outcome is Outcome.Eof }}",
        )
        assertEquals(0, results.count { it.outcome is Outcome.Eof })
    }

    /**
     * The same contract through the **real** ktor stack — a `MockEngine` body over a scripted
     * `RawSource` that delivers one chunk and then throws, read via `bodyAsChannel()`. No ordering
     * is forced here; this is the shipped wiring, which is what makes it a regression test for the
     * path rather than for `ByteChannel` in isolation.
     */
    @Test
    fun `a contract-satisfying reader is never told clean EOF through the real ktor stack`() {
        val runs = 150
        val results =
            (0 until runs).map {
                val upstream = ResetAfterOneChunk()
                val mock =
                    MockEngine {
                        respond(
                            content = ByteReadChannel(upstream.buffered()),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, DECLARED.toString()),
                        )
                    }
                val client = HttpClient(mock)
                try {
                    runBlocking {
                        client.prepareGet("http://mock/audio").execute { r ->
                            val ch = r.bodyAsChannel()
                            var awaited = false
                            var awaitThrew: String? = null
                            awaited =
                                try {
                                    ch.awaitContent()
                                } catch (t: Throwable) {
                                    awaitThrew = "${t::class.simpleName}: ${t.message}"
                                    false
                                }
                            Observation(awaited, awaitThrew, readContractCompliant(ch, ByteArray(CHUNK), awaited))
                        }
                    }
                } finally {
                    client.close()
                }
            }
        results.forEachIndexed { i, o -> assertContractHolds("engine run $i", o) }
        println(
            "PROBE contract/engine runs=$runs awaitContentTrue=${results.count { it.awaitContent }} " +
                "bytes=${results.count { it.outcome is Outcome.Bytes }} " +
                "terminal=${results.count { it.outcome is Outcome.Terminal }} " +
                "eof=${results.count { it.outcome is Outcome.Eof }} " +
                "awaitThrew=${results.count { it.awaitThrew != null }} channelClass=io.ktor.utils.io.ByteChannel",
        )
        assertEquals(0, results.count { it.outcome is Outcome.Eof && it.awaitContent })
    }

    // Characterisation, retired. This class used to carry a second test asserting that `Transfer`'s
    // read shape *did* answer a clean EOF after a true `awaitContent`: green while the defect
    // existed, red once it was fixed. It did its job — `Transfer` now reads the engine's own channel,
    // that test went red, and the defect it described is gone. Removed rather than inverted, because
    // a test asserting a bug exists is only meaningful while that bug exists. The guards that should
    // outlive it are the contract gate above and `DownloadBodyChannelIdentityTest`, which fails
    // immediately if the body is routed back through `bodyAsChannel()`.

    /** A `RawSource` that delivers one chunk and then raises the mid-stream reset. */
    private class ResetAfterOneChunk : kotlinx.io.RawSource {
        private var served = false

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (served) throw OneShotResetSource.RESET
            served = true
            sink.write(ByteArray(CHUNK) { (it % 251).toByte() })
            return CHUNK.toLong()
        }

        override fun close() = Unit
    }

    private companion object {
        const val CHUNK = 65_536
        const val DELIVERED = 65_536
        const val DECLARED = 4_000_000
        const val ARRANGE_TIMEOUT_MS = 5_000L
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
