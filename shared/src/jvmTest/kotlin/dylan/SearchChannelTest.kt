package dylan

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.model.MiniEntity
import dylan.search.AnswerSource
import dylan.search.SaavnSearchChannel
import dylan.search.WsSessionLike
import dylan.support.MutableClock
import dylan.support.TestLanes
import dylan.util.Connectivity
import dylan.util.NetClass
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Correlation tests for the suggestion socket.
 *
 * Two properties changed the shape of this suite:
 *
 *  1. The origin's frame carries **no query echo and no request id** (`fixtures/autocomplete_ws_frame.json`;
 *     `ProbeMain` P4 finds 0/3 echoes). The previous fake replied after a delay *with* a query echo,
 *     so every correlation assertion in the old suite ran against a fiction the server never sent.
 *     These tests script the frames instead and never rely on an echo.
 *  2. A mispair cannot be repaired by reading more frames — the next frame may be the earlier
 *     demand's late answer, which would then pop our stamp and be rendered as ours. So a mispair
 *     closes the socket and the demand is answered from HTTP. A test that cannot see a mispair
 *     therefore cannot see a wrong answer either.
 *
 * No wall-clock dependence: the scripted session delivers frames when the test says so, and the only
 * real time in the suite is the engine's own debounce and the (deliberately tiny) answer deadline,
 * which are configuration values, not assertions about elapsed time.
 */
class SearchChannelTest {
    /**
     * A frame whose payload is unmistakably the answer to [token], and which carries **no query
     * echo** — the real shape (`fixtures/autocomplete_ws_frame.json`).
     */
    private fun frameFor(
        token: String,
        action: String = "search",
    ): Frame.Text {
        val inner =
            "{\"modules\":[{\"title\":\"Songs\"}]," +
                "\"data_0\":[{\"id\":\"${token}id\",\"title\":\"$token\",\"type\":\"song\"}]}"
        // `resp` is a nested JSON *string* in the real frame, so the payload must be escaped into
        // it. Interpolating it raw produced a frame that is not valid JSON at all, and the decoder
        // rejected the whole thing with FRAME_NOT_JSON.
        return Frame.Text("""{"action":"$action","resp":${JsonPrimitive(inner)}}""")
    }

    /**
     * A session the test drives. Frames are pushed explicitly, so no test depends on a reply delay
     * and the ordering is a property of the test, not of the scheduler.
     */
    private class ScriptedSession : WsSessionLike {
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var closeCount = 0

        override suspend fun send(frame: Frame) {
            sent += (frame as Frame.Text).readText()
        }

        override suspend fun close() {
            closeCount++
        }

        fun queryOf(sent: String): String =
            Regex("query=([^&]+)")
                .find(sent)
                ?.groupValues
                ?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: sent
    }

    private val clock = MutableClock()
    private val lanes = TestLanes()
    private val httpCalls = mutableListOf<String>()

    /** The HTTP fallback answers with a payload that is *also* unmistakably HTTP's. */
    private val httpAnswer =
        """{"songs":{"data":[{"id":"httpid","title":"http","type":"song"}]}}"""

    private val net =
        object : NetMonitor {
            override fun connectivity(): Flow<Connectivity> = MutableStateFlow(Connectivity(true, NetClass.UNMETERED))

            override fun current(): NetClass = NetClass.UNMETERED

            override fun isOnline(): Boolean = true
        }

    private fun http(): HttpClient =
        HttpClient(
            MockEngine { req ->
                httpCalls += req.url.toString()
                respond(
                    content = httpAnswer,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", listOf("application/json")),
                )
            },
        )

    private val sessions = mutableListOf<ScriptedSession>()
    private var scope: CoroutineScope? = null

    private fun channel(
        cfg: AppConfig =
            AppConfig(
                clock = clock,
                wsTypingDebounceMs = TEST_DEBOUNCE_MS,
                wsAnswerTimeoutMs = TEST_ANSWER_MS,
                wsSearchBudgetMs = TEST_BUDGET_MS,
            ),
    ): SaavnSearchChannel {
        val ch =
            SaavnSearchChannel(
                http = http(),
                wsClient = HttpClient(MockEngine { error("ws engine unused: connectBlock is scripted") }),
                cfg = cfg,
                scope = CoroutineScope(lanes.disp.io + SupervisorJob()).also { scope = it },
                disp = lanes.disp,
                log = LogBuffer(),
                net = net,
            )
        ch.connectBlock = { ScriptedSession().also { sessions += it } }
        return ch
    }

    private fun closeChannel() {
        scope?.cancel()
        scope = null
    }

    /**
     * Bounded wait for a condition. Not an assertion about timing — a failing condition fails.
     * The channel's engine runs on a real lane (that is the thing under test), so this polls on a
     * suspending `delay` rather than a `Thread.sleep`, and the ceiling is a failure bound only.
     */
    private suspend fun waitFor(
        what: String,
        ceilingMs: Long = CEILING_MS,
        cond: () -> Boolean,
    ) {
        withTimeoutOrNull(ceilingMs) {
            while (!cond()) delay(POLL_MS)
            true
        } ?: throw AssertionError("timed out waiting for $what")
    }

    private fun titles(items: List<MiniEntity>) = items.map { it.title }

    // ── the epoch, not the query string ─────────────────────────────────────────────────────

    /**
     * The defect: keystroke `a` sends; 130 ms later the user types `ab`, cancelling the `a` block;
     * the socket then delivers **the answer for `a`**, which the old FIFO popped against `ab`,
     * matched (`head == query` was vacuously true), and rendered as the suggestions for `ab`.
     *
     * A frame for `a` delivered while `ab` is outstanding must never be rendered for `ab`.
     */
    @Test
    fun aFrameForAnAbandonedQueryIsNeverRenderedForTheCurrentOne() {
        val ch = channel()
        runBlocking {
            ch.request("a")
            waitFor("the a query on the wire") { sessions.any { s -> s.sent.isNotEmpty() } }
            val aSent = sessions.first()
            val epochA = ch.stateForTest().epoch

            ch.request("ab")
            waitFor("the ab query on the wire") { aSent.sent.size == 2 }
            val epochB = ch.stateForTest().epoch

            // The socket now delivers `a`'s answer, late, while `ab` is the live demand.
            aSent.incoming.trySend(frameFor("aaa"))

            waitFor("an answer for ab") { ch.answerForTest()?.epoch == epochB }
            val answer = ch.answerForTest()!!
            assertNotEquals(
                listOf("aaa"),
                titles(answer.items),
                "the answer for the abandoned query 'a' was rendered for 'ab'",
            )
            assertEquals(
                AnswerSource.HTTP,
                answer.via,
                "a mispair must be answered from HTTP, which correlates implicitly, not guessed at",
            )
            // The mispair must leave a trace. `SaavnSearchChannel.dropSocket` zeroes `divergence`
            // as it tears the socket down, so the counter cannot be read after the fact — the
            // durable evidence that the FIFO was distrusted is that the socket was *closed*, which
            // is the whole remedy the class documents.
            assertTrue(aSent.closeCount > 0, "a mispair must close the socket, not absorb it: ${ch.debugState()}")
        }
        closeChannel()
    }

    /** The stamp is the only reason the mispair above is *visible*. It must outlive cancellation. */
    @Test
    fun theCancelledStampIsRetainedSoTheLateFrameCannotPopTheWrongQuery() {
        val ch = channel()
        runBlocking {
            ch.request("a")
            waitFor("the a query on the wire") { sessions.any { s -> s.sent.isNotEmpty() } }
            val s = sessions.first()
            val epochA = ch.stateForTest().epoch

            ch.request("ab")
            waitFor("the ab query on the wire") { s.sent.size == 2 }
            val epochB = ch.stateForTest().epoch

            assertEquals(
                listOf(epochA, epochB),
                ch.stampsForTest(),
                "cancelling the 'a' demand must NOT remove its stamp — that removal is the bug",
            )
        }
        closeChannel()
    }

    /** A true reorder: the later query's frame arrives before the earlier query's. */
    @Test
    fun aReorderIncrementsDivergenceAndIsNotGuessedAt() {
        val ch = channel()
        runBlocking {
            ch.request("a")
            waitFor("the a query on the wire") { sessions.any { s -> s.sent.isNotEmpty() } }
            val s = sessions.first()
            ch.request("ab")
            waitFor("the ab query on the wire") { s.sent.size == 2 }
            val epochB = ch.stateForTest().epoch

            // Reverse order: ab's answer first.
            s.incoming.trySend(frameFor("b-response"))
            waitFor("an answer for ab") { ch.answerForTest()?.epoch == epochB }

            assertTrue(
                s.closeCount > 0,
                "reordering must close the socket, not absorb it: ${ch.debugState()}",
            )
            assertEquals(
                AnswerSource.HTTP,
                ch.answerForTest()!!.via,
                "a reordered frame must not be accepted: FIFO said it belonged to the earlier demand",
            )
            assertNotEquals(
                listOf("b-response"),
                titles(ch.answerForTest()!!.items),
                "the reordered payload was rendered as ab's suggestions",
            )
        }
        closeChannel()
    }

    // ── the happy path still works ──────────────────────────────────────────────────────────

    @Test
    fun aFrameForTheCurrentDemandIsRenderedOverTheWebSocket() {
        val ch = channel()
        runBlocking {
            ch.request("arijit")
            waitFor("the query on the wire") { sessions.any { s -> s.sent.isNotEmpty() } }
            val s = sessions.first()
            val epoch = ch.stateForTest().epoch
            assertTrue(s.sent.first().contains("autocomplete.get"), "the query must reach the wire: ${s.sent}")
            s.incoming.trySend(frameFor("arijit-result"))
            waitFor("the ws answer") { ch.answerForTest()?.epoch == epoch }
            val a = ch.answerForTest()!!
            assertEquals(AnswerSource.WS, a.via)
            assertEquals(listOf("arijit-result"), titles(a.items))
        }
        closeChannel()
    }

    @Test
    fun aNonSearchActionIsNotRenderedAsResults() {
        val ch = channel()
        runBlocking {
            ch.request("arijit")
            waitFor("the query on the wire") { sessions.any { s -> s.sent.isNotEmpty() } }
            val s = sessions.first()
            val epoch = ch.stateForTest().epoch
            s.incoming.trySend(frameFor("keepalive-frame", action = "keepalive"))
            waitFor("an answer for that demand") { ch.answerForTest()?.epoch == epoch }
            assertEquals(
                AnswerSource.HTTP,
                ch.answerForTest()!!.via,
                "a keepalive must fall back, not render as a result set",
            )
        }
        closeChannel()
    }

    // ── degradation is a cooldown, not a latch ──────────────────────────────────────────────

    @Test
    fun threeTimeoutsOpenACooldownThatExpires() {
        val ch =
            channel(
                AppConfig(
                    clock = clock,
                    wsTypingDebounceMs = TEST_DEBOUNCE_MS,
                    wsAnswerTimeoutMs = TEST_ANSWER_MS,
                    wsSearchBudgetMs = TEST_BUDGET_MS,
                ),
            )
        runBlocking {
            repeat(3) { i ->
                ch.request("dead$i")
                waitFor("dead$i to be answered") { ch.answerForTest()?.query == "dead$i" }
            }
            waitFor("the cooldown to open") { ch.stateForTest().suppressed }
            assertTrue(ch.stateForTest().cooldownRemainingMs > 0, "${ch.debugState()}")

            ch.request("during-cooldown")
            waitFor("an answer during the cooldown") { ch.answerForTest()?.query == "during-cooldown" }
            val sessionsDuring = sessions.size
            assertEquals(
                sessionsDuring,
                sessions.size,
                "no socket is opened while suppressed (opened ${sessions.size} in total)",
            )

            // Half-open: the cooldown expires, so the next demand probes again.
            clock.advanceMs(AppConfig().wsCooldownBaseMs + 1)
            assertTrue(!ch.stateForTest().suppressed, "the cooldown must expire, not latch for the process")
            ch.request("after-cooldown")
            waitFor("a re-probe") { sessions.size > sessionsDuring }
        }
        closeChannel()
    }

    @Test
    fun aHealthyAnswerClearsTheDegradationState() {
        val ch = channel()
        runBlocking {
            repeat(3) { i ->
                ch.request("dead$i")
                waitFor("dead$i to be answered") { ch.answerForTest()?.query == "dead$i" }
            }
            waitFor("the cooldown to open") { ch.stateForTest().suppressed }
            clock.advanceMs(AppConfig().wsCooldownBaseMs + 1)
            // The three timeouts each dropped their socket, so the re-probe opens a NEW one:
            // waiting on `sessions.last().sent` alone is satisfied by the dead session above.
            val sessionsBeforeProbe = sessions.size
            ch.request("good")
            waitFor("the re-probe socket after the cooldown") { sessions.size > sessionsBeforeProbe }
            val s = sessions.last()
            val epoch = ch.stateForTest().epoch
            s.incoming.trySend(frameFor("good-result"))
            waitFor("the ws answer") {
                ch.answerForTest()?.epoch == epoch && ch.answerForTest()?.via == AnswerSource.WS
            }
            assertEquals(
                0,
                ch.stateForTest().cooldownRemainingMs,
                "a good answer must clear the cooldown: ${ch.debugState()}",
            )
        }
        closeChannel()
    }

    // ── one owner for the socket ────────────────────────────────────────────────────────────

    @Test
    fun warmUpAndADemandNeverOpenTwoSockets() {
        val ch = channel()
        runBlocking {
            ch.warmUp()
            waitFor("the warm-up socket") { sessions.isNotEmpty() }
            val first = sessions.first()
            ch.request("arijit")
            waitFor("the demand to be sent") { first.sent.isNotEmpty() }
            ch.warmUp()
            // No quiet period needed: a second demand that *is* answered proves the engine finished
            // with the first, and `session()` can only run again if the socket had been replaced.
            ch.request("ari2")
            waitFor("the second demand on the same socket") { first.sent.size == 2 }
            assertEquals(1, sessions.size, "a live socket must be reused, not replaced: ${sessions.size} opened")
            assertEquals(0, first.closeCount, "a replaced socket is leaked; a reused one is not closed")
        }
        closeChannel()
    }

    // ── normalisation, clearing, and the request surface ────────────────────────────────────

    @Test
    fun queriesAreNormalisedOnceAtTheBoundary() {
        val ch = channel()
        runBlocking {
            // `publish` trims and CASes a StateFlow, so the second `request` hands back the *same*
            // Demand without launching anything. Once the first frame is on the wire there is
            // therefore no sender left that could put a second one there, and a quiet period would
            // be waiting on nothing.
            ch.request("  ari  ")
            ch.request("ari")
            waitFor("the query on the wire") { sessions.any { s -> s.sent.isNotEmpty() } }
            assertEquals(
                1,
                sessions.sumOf { it.sent.size },
                "an untrimmed and a trimmed version of the same keystrokes are one demand: " +
                    sessions.flatMap { it.sent },
            )
            assertTrue(
                sessions
                    .first()
                    .sent
                    .first()
                    .contains("query=ari"),
                sessions.first().sent.first(),
            )
        }
        closeChannel()
    }

    /**
     * `request("")` used to return before touching the channel, so the previous query's socket read
     * kept the session busy to its full deadline after the user had cleared the box.
     */
    @Test
    fun clearingTheQueryReachesTheChannelAndReleasesTheSocket() {
        val ch = channel()
        runBlocking {
            ch.request("arijit")
            waitFor("the query on the wire") { sessions.any { s -> s.sent.isNotEmpty() } }
            val s = sessions.first()
            val epochBefore = ch.stateForTest().epoch
            ch.request("")
            waitFor("the socket to be released") { s.closeCount > 0 }
            assertNotEquals(
                epochBefore,
                ch.stateForTest().epoch,
                "clearing must advance the epoch, or an in-flight answer is still renderable",
            )
            assertTrue(ch.stampsForTest().isEmpty(), "the FIFO must not keep stamps for a cleared demand")
        }
        closeChannel()
    }

    @Test
    fun suggestOnABlankQueryIsEmptyAndCostsNothing() {
        val ch = channel()
        runBlocking {
            assertEquals(emptyList(), ch.suggest("   "))
            assertEquals(0, httpCalls.size, "a blank query must not reach the network")
        }
        closeChannel()
    }

    @Test
    fun anOfflineDeviceAnswersOfflineWithoutOpeningASocket() {
        val offline =
            object : NetMonitor {
                private val state = MutableStateFlow(Connectivity(false, NetClass.METERED))

                override fun connectivity(): Flow<Connectivity> = state

                override fun current(): NetClass = NetClass.METERED

                override fun isOnline(): Boolean = false
            }
        val ch =
            SaavnSearchChannel(
                http = http(),
                wsClient = HttpClient(MockEngine { error("unused") }),
                cfg =
                    AppConfig(
                        clock = clock,
                        wsTypingDebounceMs = TEST_DEBOUNCE_MS,
                        wsSearchBudgetMs = TEST_BUDGET_MS,
                    ),
                scope = CoroutineScope(lanes.disp.io + SupervisorJob()).also { scope = it },
                disp = lanes.disp,
                log = LogBuffer(),
                net = offline,
            )
        ch.connectBlock = { ScriptedSession().also { sessions += it } }
        runBlocking {
            ch.request("arijit")
            waitFor("an answer") { ch.answerForTest() != null }
            val a = ch.answerForTest()!!
            assertEquals(dylan.model.ErrorCode.OFFLINE, a.error)
            assertTrue(sessions.isEmpty(), "an offline device must not open a socket")
        }
        closeChannel()
    }

    private companion object {
        /** Small but non-zero: 0 would make `delay(0)` a busy loop in the engine. */
        const val TEST_DEBOUNCE_MS = 20
        const val TEST_ANSWER_MS = 250L
        const val TEST_BUDGET_MS = 3_000L
        const val CEILING_MS = 5_000L
        const val POLL_MS = 5L
    }
}
