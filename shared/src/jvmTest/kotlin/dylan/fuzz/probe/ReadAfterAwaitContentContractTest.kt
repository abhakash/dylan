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
import io.ktor.utils.io.ClosedReadChannelException
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
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
 * A terminal error arrives in one of two shapes, and the second is the reason this class exists in
 * its present form. `readAvailable` (`ktor-io` 3.5.2, `commonMain/ByteReadChannelOperations.kt`)
 * is
 *
 * ```
 * if (isClosedForRead) return -1
 * if (readBuffer.exhausted()) awaitContent()
 * if (isClosedForRead) return -1
 * return readBuffer.readAvailable(buffer, offset, length)
 * ```
 *
 * The first `isClosedForRead` and the `readBuffer` getter are **separate** statements, and the
 * getter (`ByteChannel.kt:67`) opens with `_closedCause.value?.throwOrNil(::ClosedReadChannelException)`.
 * So a `cancel(cause)` that lands between them arrives as a **throw**, not as `-1` — and it arrives
 * nondeterministically, because the landing point is decided by a race. That is precisely why the
 * probe enumerates it as an [Outcome.Threw] rather than letting the exception escape: an escaping
 * throw is not a measurement, it is a coin that has to be caught somewhere. Measured before this
 * was done: the class failed roughly 1 run in 10 on exactly this throw, with nothing about the
 * contract having changed between runs.
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
 * `Transfer.readChunk` (`Transfer.kt:649-657`) is `awaitContent()` then `readAvailable(...)`, so it
 * sees `true` then `-1` **or** a throw, and the throw is the shape that reached here: before the
 * read site classified it, a `cancel(cause)` landing between `readAvailable`'s
 * `isClosedForRead` check and its `readBuffer` access made the probe itself fail roughly one run
 * in ten with `ClosedReadChannelException`. That is a *race*, not a defect in the contract, so the
 * contract's own test must not be a participant in it.
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

        /**
         * The read threw rather than answering.
         *
         * Not an alternative spelling of [Terminal]: `Terminal` is the channel *reporting* a
         * recorded cause on a later read, and this is the read *raising* it. Both satisfy the
         * contract and both lose the delivered bytes, so they are counted separately — which is
         * only possible because neither is an escaping exception.
         */
        data class Threw(
            val thrown: String,
        ) : Outcome
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
     *
     * The `try` is the whole point. Every answer this read can give is now an [Outcome] — bytes,
     * clean EOF, a recorded cause, or a throw — so the run's outcome is a *classification* rather
     * than whichever of two races the MockEngine's `cancel(cause)` happened to win.
     */
    private suspend fun readContractCompliant(
        ch: ByteReadChannel,
        buf: ByteArray,
        awaited: Boolean,
    ): Outcome {
        if (!awaited) return terminalOrEof(ch)
        return try {
            val n = ch.readAvailable(buf, 0, buf.size)
            if (n >= 0) return Outcome.Bytes(n)
            // `-1` while a cause is recorded is not an end of body. Refusing to call it one is the
            // whole of the contract; the bytes are still lost, but the failure is a transport failure
            // and the attempt is a retry rather than a silent restart from zero.
            terminalOrEof(ch)
        } catch (expected: Exception) {
            // A cancellation is not the channel's answer, so it is not this probe's to classify.
            if (expected is CancellationException) throw expected
            Outcome.Threw("${expected::class.simpleName}: ${expected.message}")
        }
    }

    /**
     * A recorded cause is a terminal failure to report; its absence means the body really did end.
     * Both branches of this decision used to be spelled out at every `return`, which is how the
     * function came to have six exits for two ideas.
     */
    private fun terminalOrEof(ch: ByteReadChannel): Outcome {
        val cause = ch.closedCause
        return cause?.let { Outcome.Terminal("$it") } ?: Outcome.Eof
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
     * A recorded throw must still be *this* seam's throw.
     *
     * Classifying the throw is only an improvement if what is classified is the cause the source
     * raised. ktor wraps a recorded cause in `ClosedByteChannelException(cause)`, whose message is
     * the cause's own — so the descriptor has to name both the wrapper class and the reset's
     * message. An assertion that merely counted `Threw` would pass just as happily on a fabricated
     * placeholder, which is how a gate stops measuring the thing it was written for.
     */
    private fun assertThrewCarriesTheCause(
        where: String,
        o: Observation,
    ) {
        if (o.outcome !is Outcome.Threw) return
        val thrown = o.outcome.thrown
        assertTrue(
            thrown.isNotBlank() && ':' in thrown && thrown.substringAfter(':').isNotBlank(),
            "$where: the read threw but the classification names neither a type nor a message: '$thrown'",
        )
        val reset = OneShotResetSource.RESET.message.orEmpty()
        assertTrue(
            reset.isNotEmpty() && reset in thrown,
            "$where: the read threw, but not with the reset the source raised — this run's failure " +
                "is not the seam under test: '$thrown'",
        )
    }

    /**
     * Every observation is one of the four answers, by construction.
     *
     * Guards the accounting rather than the contract: if a future edit adds a fifth answer and
     * forgets to count it, the percentages in the report silently stop summing to the run count and
     * a drop in `terminal=` reads as an improvement.
     */
    private fun assertOutcomesAreAccountedFor(
        where: String,
        total: Int,
        outcomes: List<Outcome>,
    ) {
        assertEquals(
            total,
            outcomes.size,
            "$where: the outcome list and the run count disagree.",
        )
        val classified =
            outcomes.count { it is Outcome.Bytes } +
                outcomes.count { it is Outcome.Terminal } +
                outcomes.count { it is Outcome.Threw } +
                outcomes.count { it is Outcome.Eof }
        assertEquals(total, classified, "$where: $classified of $total outcomes are one of the four answers.")
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
        results.forEachIndexed { i, o -> assertThrewCarriesTheCause("forced-ordering run $i", o) }
        assertOutcomesAreAccountedFor("forced-ordering", results.size, results.map { it.outcome })
        println("PROBE contract/forced ${tally("repeats", results)}")
        assertEquals(0, results.count { it.outcome is Outcome.Eof })
    }

    /**
     * One line of outcome distribution, so a change in the mix is visible in the log even when
     * nothing goes red. `awaitThrew` is counted beside `threw` because they are different frames
     * opening the same cause.
     */
    private fun tally(
        what: String,
        results: List<Observation>,
    ): String =
        "$what=${results.size} " +
            "awaitContentTrue=${results.count { it.awaitContent }} " +
            "bytes=${results.count { it.outcome is Outcome.Bytes }} " +
            "terminal=${results.count { it.outcome is Outcome.Terminal }} " +
            "threw=${results.count { it.outcome is Outcome.Threw }} " +
            "eof=${results.count { it.outcome is Outcome.Eof }} " +
            "awaitThrew=${results.count { it.awaitThrew != null }}"

    /**
     * The throw, forced rather than raced.
     *
     * `readAvailable` checks `isClosedForRead` and **then** touches `readBuffer`, whose getter
     * rethrows a recorded cause — two adjacent statements with no suspension between them, so
     * whether a `cancel(cause)` lands inside that window is a scheduling outcome, not a
     * programming one. This channel is the window itself, modelled: the closed check still answers
     * `false` (the reset has not landed from this side yet) and the buffer getter already raises.
     *
     * Why it exists: the race that used to make this class flaky fires in roughly one run in ten,
     * so a classification reached only by winning it is covered about as often as the flake was
     * noticed. This makes the branch unconditional, and it is honest — the two properties it sets
     * are exactly the ones the real `ByteChannel` holds at the instant of the throw.
     */
    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class BetweenTheCheckAndTheBuffer : ByteReadChannel {
        override val closedCause: Throwable? = OneShotResetSource.RESET

        override val isClosedForRead: Boolean = false

        override val readBuffer: kotlinx.io.Source
            get() = throw ClosedReadChannelException(OneShotResetSource.RESET)

        override suspend fun awaitContent(min: Int): Boolean = true

        override fun cancel(cause: Throwable?) = Unit
    }

    /**
     * The half of the fix that is deterministic: a read that throws comes back as an
     * [Outcome.Threw] carrying the cause, and does not leave the probe as an exception. The
     * production read site is held to the same thing by `Transfer.pump`.
     */
    @Test
    fun `a read that throws is classified rather than escaping the probe`() {
        val channel = BetweenTheCheckAndTheBuffer()
        val o = runBlocking { Observation(true, null, readContractCompliant(channel, ByteArray(CHUNK), true)) }
        assertTrue(
            o.outcome is Outcome.Threw,
            "a read that throws must be classified, not raised: got $o",
        )
        assertContractHolds("forced-throw", o)
        assertThrewCarriesTheCause("forced-throw", o)
        println("PROBE contract/forced-threw ${tally("runs", listOf(o))}")
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
        results.forEachIndexed { i, o -> assertThrewCarriesTheCause("engine run $i", o) }
        assertOutcomesAreAccountedFor("engine", results.size, results.map { it.outcome })
        println("PROBE contract/engine ${tally("runs", results)} channelClass=io.ktor.utils.io.ByteChannel")
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
