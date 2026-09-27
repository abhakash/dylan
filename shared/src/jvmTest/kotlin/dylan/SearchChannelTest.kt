package dylan

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.search.CorrelationMode
import dylan.search.SaavnSearchChannel
import dylan.search.WsSessionLike
import dylan.support.TestLanes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SearchChannelTest {
    private class FakeSession(
        private val launchScope: kotlinx.coroutines.CoroutineScope,
        private val replyDelayMs: Long,
    ) : WsSessionLike {
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        private var autoReply: (suspend (String) -> Unit)? = null

        fun onSend(reply: suspend (String) -> Unit) {
            autoReply = reply
        }

        override suspend fun send(frame: Frame) {
            val q = (frame as Frame.Text).readText()
            sent += q
            // async like a real socket: reply lands AFTER send() returns, so an abandoned
            // request can be cancelled between queueing and consuming its answer
            autoReply?.let { cb ->
                launchScope.launch {
                    delay(replyDelayMs)
                    cb(q)
                }
            }
        }

        override suspend fun close() {
            incoming.close()
        }
    }

    private fun suggestionsJson(q: String) = """{"action":"search","resp":"{\"modules\":[{\"title\":\"Songs\",\"position\":0,\"source\":\"data_0\"}],\"data_0\":[],\"query\":\"$q\"}"}"""

    private val noopHttp = HttpClient(MockEngine { _ -> respondOk("{}") }) { }

    // onBackground() must land on the same lane the WS engine runs on, or the channel's own
    // state is racy in the test exactly as it was on device. Production lanes, not Dispatchers.Default.
    private val lanes = TestLanes()

    private fun newChannel(
        scope: kotlinx.coroutines.CoroutineScope,
        cfg: AppConfig = AppConfig(),
        replyDelayMs: Long = 50,
        sessionInit: (FakeSession) -> Unit = {},
    ): SaavnSearchChannel {
        val ch =
            SaavnSearchChannel(
                http = noopHttp,
                wsClient = HttpClient(MockEngine { _ -> error("ws engine unused: connectBlock is faked") }) { },
                cfg = cfg,
                scope = scope,
                disp = lanes.disp,
                log = LogBuffer(),
            )
        ch.connectBlock = { FakeSession(scope, replyDelayMs).apply(sessionInit) }
        return ch
    }

    @Test
    fun orderedModeAnswersTheQueryItAskedFor() =
        runTest {
            lateinit var session: FakeSession
            val ch =
                newChannel(backgroundScope) { s ->
                    session = s
                    s.onSend { q -> s.incoming.send(Frame.Text(suggestionsJson(q))) }
                }
            ch.correlationMode = CorrelationMode.ORDERED
            val out = ch.suggest("pop")
            assertEquals(CorrelationMode.ORDERED, ch.correlationMode)
            assertTrue(
                session.sent.any { it.contains("autocomplete.get&query=pop") },
                "the query must actually reach the wire, sent=${session.sent}",
            )
            assertEquals(0, out.size)
            assertEquals(0, ch.timeoutStrikesForTest())
        }

    @Test
    fun silenceTimesOutCountsStrikeServesHttp() =
        runTest {
            val ch = newChannel(backgroundScope) { }
            val out = ch.suggest("silent")
            assertTrue(out.isEmpty())
            assertEquals(1, ch.timeoutStrikesForTest())
            assertEquals(0, ch.socketStrikesForTest(), "timeout never counts as a socket-error strike")
        }

    @Test
    fun threeConsecutiveTimeoutsGoHttpOnlyForSession() =
        runTest {
            val ch = newChannel(backgroundScope) { }
            repeat(3) { ch.suggest("dead$it") }
            assertTrue(ch.httpOnlyForTest())
            val strikesBefore = ch.timeoutStrikesForTest()
            ch.suggest("still-dead")
            assertEquals(strikesBefore, ch.timeoutStrikesForTest(), "degraded channel never re-arms the WS")
        }

    @Test
    fun healthyResponseResetsBothCounters() =
        runTest {
            val ch =
                newChannel(backgroundScope) { s ->
                    s.onSend { q -> if (q.contains("query=one-good")) s.incoming.send(Frame.Text(suggestionsJson(q))) }
                }
            ch.suggest("bad-one")
            assertEquals(1, ch.timeoutStrikesForTest())
            ch.suggest("one-good")
            assertEquals(0, ch.timeoutStrikesForTest(), "healthy response resets both counters")
            assertEquals(0, ch.socketStrikesForTest())
        }

    /**
     * Inverted from the pre-wave version, which was named `repeatedMispairsFlipToFallbackSingleFlight`
     * while asserting the opposite (`correlationMode == ORDERED`). The behaviour it actually
     * protects is worth stating plainly: `collectLatest` cancellation removes an abandoned query
     * from the FIFO deque, so a cancelled request must *not* be counted as a divergence.
     */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledRequestsNeverCountAsMispairsSoTheModeStaysOrdered() =
        runTest {
            val ch =
                newChannel(
                    backgroundScope,
                    cfg = AppConfig(wsRequestTimeoutMs = 2000),
                    replyDelayMs = 300,
                ) { s ->
                    s.onSend { q -> s.incoming.send(Frame.Text(suggestionsJson(q))) }
                }
            ch.correlationMode = CorrelationMode.ORDERED
            repeat(3) { round ->
                val abandoned = launch { ch.suggest("a$round") }
                testScheduler.advanceTimeBy(150)
                ch.suggest("b$round")
                testScheduler.advanceUntilIdle()
                abandoned.cancel()
                abandoned.join()
            }
            assertEquals(
                CorrelationMode.ORDERED,
                ch.correlationMode,
                "cancellation-induced abandonment is not a mispair (F4)",
            )
            assertTrue(
                !ch.debugState().contains("div=3"),
                "divergence must not accumulate from cancellations: ${ch.debugState()}",
            )
        }

    /**
     * Replaces the deleted `trueOutOfOrderFramesStillFlipToSingleFlight`, whose assertion was
     * `mode=UNORDERED || mode == ORDERED` — always true, while its comment claimed the opposite.
     * This states what actually happens, and it is falsifiable.
     *
     * The reason is structural, not accidental: `tryWs` correlates by *FIFO position*, not by
     * frame content, and `engine()`'s `collectLatest` guarantees at most one outstanding query. So
     * the deque head is always the current query and `divergence` can never increment — the
     * UNORDERED degradation path is unreachable. Frames delivered in any order are therefore
     * accepted, and the frame *content* is never checked against the query it answers.
     */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun outOfOrderFramesAreUnreachableSoDivergenceNeverIncrements() =
        runTest {
            val ch =
                newChannel(
                    backgroundScope,
                    cfg = AppConfig(wsRequestTimeoutMs = 2000),
                    replyDelayMs = 10,
                ) { s ->
                    s.onSend { q ->
                        if (q.contains("query=a")) {
                            // Wrong query's payload for a's request: accepted anyway.
                            s.incoming.send(Frame.Text(suggestionsJson("b")))
                        } else {
                            s.incoming.send(Frame.Text(suggestionsJson(q)))
                        }
                    }
                }
            ch.correlationMode = CorrelationMode.ORDERED
            ch.request("a")
            ch.request("b")
            ch.request("c")
            testScheduler.advanceUntilIdle()
            assertEquals(
                CorrelationMode.ORDERED,
                ch.correlationMode,
                "reordering cannot be detected: the deque head is always the current query",
            )
            assertTrue(
                ch.debugState().contains("div=0"),
                "so the divergence counter — the only thing that flips the mode — never moves: ${ch.debugState()}",
            )
            testScheduler.advanceUntilIdle()
        }

    /**
     * The consequence of correlating by position instead of by content, and the real defect this
     * area actually has. A duplicated or late frame stays in `incoming` after its query was
     * answered; the next query pops its own FIFO head and is then handed the *stale* frame.
     */
    @Test
    @Ignore(
        "SaavnSearchChannel.tryWs correlates by FIFO position, not by frame content " +
            "(SaavnSearchChannel.kt:167-172), so a frame that outlives its query is served as the " +
            "answer to the next one. Counterexample: the session answers the query 'one' with two " +
            "frames; suggest('one') consumes the first, then suggest('two') pops head='two' and is " +
            "answered with 'one' payload. Fix: carry the query (or a correlation id) in the request " +
            "and match it in the response instead of relying on send order. " +
            "See docs/codebase-audit.md section 3.5.",
    )
    @OptIn(ExperimentalCoroutinesApi::class)
    fun aLateFrameIsServedAsTheAnswerToTheNextQuery() =
        runTest {
            val ch =
                newChannel(
                    backgroundScope,
                    cfg = AppConfig(wsRequestTimeoutMs = 2000),
                    replyDelayMs = 10,
                ) { s ->
                    s.onSend { q ->
                        if (q.contains("query=one")) {
                            s.incoming.send(Frame.Text(suggestionsJson("one")))
                            s.incoming.send(Frame.Text(suggestionsJson("one")))
                        } else {
                            s.incoming.send(Frame.Text(suggestionsJson(q)))
                        }
                    }
                }
            ch.correlationMode = CorrelationMode.ORDERED
            ch.suggest("one")
            testScheduler.advanceUntilIdle()
            ch.suggest("two")
            testScheduler.advanceUntilIdle()
            val answer = ch.suggestions.value
            assertNotNull(answer, "the second query must have been answered at all")
            assertTrue(
                answer.second.isEmpty(),
                "the stale frame was answered to the second query but never should have been: " +
                    "query=${answer.first} results=${answer.second.size}",
            )
        }
}
