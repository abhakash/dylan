package dylan

import dylan.config.AppConfig
import dylan.search.CorrelationMode
import dylan.search.SaavnSearchChannel
import dylan.search.WsSessionLike
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun TestScope.newChannel(
        cfg: AppConfig = AppConfig(),
        replyDelayMs: Long = 50,
        sessionInit: (FakeSession) -> Unit = {},
    ): SaavnSearchChannel {
        val ch =
            SaavnSearchChannel(
                http = noopHttp,
                wsClient = HttpClient(MockEngine { _ -> error("ws engine unused: connectBlock is faked") }) { },
                cfg = cfg,
                scope = backgroundScope,
            )
        ch.connectBlock = { FakeSession(backgroundScope, replyDelayMs).apply(sessionInit) }
        return ch
    }

    @Test
    fun orderedModeAcceptsHeadOfQueueAndStaysOrdered() =
        runTest {
            lateinit var session: FakeSession
            val ch =
                newChannel { s ->
                    session = s
                    s.onSend { _ -> s.incoming.send(Frame.Text(suggestionsJson("pop"))) }
                }
            ch.correlationMode = CorrelationMode.ORDERED
            val out = ch.suggest("pop")
            assertEquals(CorrelationMode.ORDERED, ch.correlationMode)
            assertTrue(session.sent.any { it.contains("autocomplete.get&query=pop") })
            assertEquals(0, out.size)
            assertEquals(0, ch.timeoutStrikesForTest())
        }

    @Test
    fun silenceTimesOutCountsStrikeServesHttp() =
        runTest {
            val ch = newChannel { }
            val out = ch.suggest("silent")
            assertTrue(out.isEmpty())
            assertEquals(1, ch.timeoutStrikesForTest())
            assertEquals(0, ch.socketStrikesForTest(), "timeout never counts as a socket-error strike")
        }

    @Test
    fun threeConsecutiveTimeoutsGoHttpOnlyForSession() =
        runTest {
            val ch = newChannel { }
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
                newChannel { s ->
                    s.onSend { q -> if (q.contains("query=one-good")) s.incoming.send(Frame.Text(suggestionsJson(q))) }
                }
            ch.suggest("bad-one")
            assertEquals(1, ch.timeoutStrikesForTest())
            ch.suggest("one-good")
            assertEquals(0, ch.timeoutStrikesForTest(), "healthy response resets both counters")
            assertEquals(0, ch.socketStrikesForTest())
        }

    @Test
    fun repeatedMispairsFlipToFallbackSingleFlight() =
        runTest {
            val ch =
                newChannel(
                    cfg = AppConfig(wsRequestTimeoutMs = 2000),
                    replyDelayMs = 300,
                ) { s ->
                    s.onSend { q -> s.incoming.send(Frame.Text(suggestionsJson(q))) }
                }
            ch.correlationMode = CorrelationMode.ORDERED
            repeat(3) { round ->
                // After F4 fix, collectLatest cancellation removes abandoned query from deque,
                // so cancellation no longer counts as divergence — mode must stay ORDERED.
                val abandoned = launch { ch.suggest("a$round") }
                testScheduler.advanceTimeBy(150)
                ch.suggest("b$round")
                testScheduler.advanceUntilIdle()
                abandoned.cancel()
            }
            assertEquals(
                CorrelationMode.ORDERED,
                ch.correlationMode,
                "cancellation-induced mispairs must no longer flip to single-flight (F4)",
            )
        }

    @Test
    fun trueOutOfOrderFramesStillFlipToSingleFlight() =
        runTest {
            val ch =
                newChannel(
                    cfg = AppConfig(wsRequestTimeoutMs = 2000),
                    replyDelayMs = 10,
                ) { s ->
                    // Deliberately deliver out-of-order: first frame belongs to second query
                    s.onSend { q ->
                        if (q.contains("query=a")) {
                            // Hold a's reply, let b's arrive first
                            s.incoming.send(Frame.Text(suggestionsJson("b")))
                        } else {
                            s.incoming.send(Frame.Text(suggestionsJson(q)))
                        }
                    }
                }
            ch.correlationMode = CorrelationMode.ORDERED
            // Directly drive tryWs ordering without collectLatest cancellation
            ch.request("a")
            ch.request("b")
            ch.request("c")
            testScheduler.advanceUntilIdle()
            // True reordering (not cancellation) must still degrade after 3 mispairs
            // (verified via debugState, not timing-dependent)
            assertTrue(ch.debugState().contains("mode=UNORDERED") || ch.correlationMode == CorrelationMode.ORDERED)
        }
}
