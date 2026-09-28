package dylan.search

import dylan.config.AppConfig
import dylan.model.ErrorCode
import dylan.model.MiniEntity
import dylan.provider.CatalogResult
import dylan.provider.ResilientClient
import dylan.provider.errorCodeOrNull
import dylan.provider.saavn.mapSuggestionPayload
import dylan.provider.saavn.mapSuggestions
import dylan.provider.valueOrNull
import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ORDERED` is the only mode this class enters on its own.
 *
 * `UNORDERED` accepts whatever frame arrives next for whatever demand is live, which is exactly how
 * a payload gets rendered for the wrong query. The old code degraded into it automatically after
 * three "divergences" — but that path was unreachable (at most one demand is ever outstanding, so
 * the counter never moved) and, had it been reachable, it would have traded a wrong answer for a
 * plausible one. A mispair now closes the socket and answers from HTTP, which correlates
 * implicitly. `UNORDERED` survives as an explicit operator override for a session someone has
 * reason to trust; `ECHO` is what the live probe reports when the origin *does* start echoing.
 */
enum class CorrelationMode { ECHO, ORDERED, UNORDERED }

interface WsSessionLike {
    val incoming: ReceiveChannel<Frame>

    suspend fun send(frame: Frame)

    suspend fun close()
}

/** A suggestion answer, and the demand epoch it answers. */
data class Answer(
    val query: String,
    val epoch: Long,
    val items: List<MiniEntity>,
    val via: AnswerSource,
    val error: ErrorCode? = null,
)

enum class AnswerSource { WS, HTTP }

/**
 * A keystroke, with a monotonic id.
 *
 * The epoch is the whole correlation story on the render side. The origin's frame carries **no
 * query echo and no request id** (`fixtures/autocomplete_ws_frame.json`; `ProbeMain` P4 finds 0/3
 * echoes), so an answer cannot be matched to its query by content and the only safe rule left is:
 * *never render an answer whose epoch is not the current demand*. Nothing carried an epoch before,
 * so a frame answering an abandoned `a` was published as the answer to `ab`.
 */
data class Demand(
    val query: String,
    val epoch: Long,
)

/**
 * Suggestions over the WebSocket, with HTTP as the **authority**.
 *
 * The reliability ordering used to be inverted: the WS was authoritative and HTTP the degraded
 * fallback, when the WS has strictly *weaker* correlation (FIFO position only) than HTTP (implicit
 * correlation by construction). So the WS is now purely a latency optimisation: one frame, inside a
 * deadline, from a socket whose upgrade had its own budget — and **any** doubt (offline, cooldown,
 * timeout, socket error, undecodable frame, a non-`search` action) falls back to HTTP.
 *
 * All of the engine's mutable state is behind [engineLock], and the socket behind [socketOwner], so
 * nothing here needs lane affinity any more and no `onBackground()` can race a running read.
 */
class SaavnSearchChannel(
    private val http: HttpClient,
    private val wsClient: HttpClient,
    private val cfg: AppConfig,
    private val scope: CoroutineScope,
    private val disp: AppDispatchers,
    private val log: dylan.diag.LogBuffer,
    private val net: NetMonitor,
) : SearchChannel {
    var correlationMode = CorrelationMode.ORDERED

    internal var connectBlock: suspend () -> WsSessionLike? = {
        withTimeout(cfg.wsHandshakeTimeoutMs) { wsClient.webSocketSession(cfg.wsSearchUrl)?.toLike() }
    }

    private val demand = MutableStateFlow(Demand("", 0L))
    private val answer = MutableStateFlow<Answer?>(null)

    private val engineLock = Mutex()
    private val socketOwner = Mutex()

    private var session: WsSessionLike? = null

    /**
     * Send-order stamps, one per demand that reached the wire.
     *
     * A stamp is **never removed on cancellation** — that removal was the bug: keystroke `a` sends,
     * `ab` cancels it, the deque is emptied, the frame answering `a` pops `ab` and is rendered as
     * `ab`'s suggestions. The stamp's only job is to make a late frame pop against the query it
     * actually answers, so it must outlive the demand that created it.
     */
    private val outstanding = ArrayDeque<Demand>()

    private var timeoutStrikes = 0
    private var socketStrikes = 0
    private var divergence = 0

    /** Cooldown, not a latch. Expiry makes the next demand a half-open re-probe. */
    private var cooldownUntilMs = 0L
    private var cooldownNetClass = net.current()
    private var cooldownMs = cfg.wsCooldownBaseMs
    private var consecutiveDegradations = 0
    private var backoffMs = cfg.wsBackoffBaseMs

    /**
     * The HTTP fallback goes through the same [ResilientClient] the catalog uses, so it inherits the
     * one [dylan.provider.dylanJson], the one status→code table, the bot-wall sniff, the offline
     * short-circuit and the endpoint negative cache. Duplicating any of those in the fallback is
     * how the two paths end up disagreeing about what a failure is.
     */
    private val httpSuggest = HttpSuggest(http, cfg, scope, disp, log, net)

    init {
        // The engine used to run on the app scope, which IS the single-threaded `state` lane in both
        // production graphs: the same lane as the orchestrator inbox and the 10 Hz position ticker.
        scope.launch(disp.on(Lane.IO)) { engine() }
    }

    /** Opens the socket ahead of the first keystroke. Shares the owner, so it cannot double-open. */
    fun warmUp() {
        scope.launch(disp.on(Lane.IO)) { session() }
    }

    private fun DefaultClientWebSocketSession.toLike(): WsSessionLike =
        object : WsSessionLike {
            override val incoming: ReceiveChannel<Frame> = this@toLike.incoming

            override suspend fun send(frame: Frame) = this@toLike.send(frame)

            override suspend fun close() = this@toLike.close(CloseReason(CloseReason.Codes.NORMAL, "cycle"))
        }

    /**
     * iOS calls this from `.inactive`, so it runs on the caller's (main) thread. Nothing here needs
     * a lane any more: the channel's state is behind [engineLock] and the socket behind
     * [socketOwner], so the body cannot race the engine. (The previous fix was
     * `scope.launch(disp.state)`, which parked a main-thread event on the orchestrator's lane.)
     */
    fun onBackground() {
        scope.launch(disp.on(Lane.IO)) { dropSocket() }
    }

    override suspend fun suggest(query: String): List<MiniEntity> {
        val d = publish(query)
        if (d.query.isEmpty()) return emptyList()
        val answered = withTimeoutOrNull(cfg.wsSearchBudgetMs) { answer.first { it != null && it.epoch == d.epoch } }
        if (answered != null) return answered.items
        // The engine did not answer inside the budget. Answer from HTTP directly rather than
        // reporting "no suggestions" for a query the origin can certainly answer.
        val rows = httpSuggest.suggest(d.query)
        publishAnswer(d, rows.valueOrNull().orEmpty(), AnswerSource.HTTP, rows.errorCodeOrNull())
        return rows.valueOrNull().orEmpty()
    }

    /** Fire-and-forget demand update; answers arrive on [suggestions]. */
    fun request(query: String) {
        publish(query)
    }

    /** §6.4 render-on-arrival surface: the latest answered demand, or null. */
    val suggestions: StateFlow<Answer?> get() = answer

    /**
     * Publish a demand, bumping the epoch. Normalisation happens here, once, at the boundary:
     * Android sent untrimmed queries and iOS trimmed them, so the same two keystrokes were two
     * demands and two HTTP requests.
     *
     * A blank query is a real demand, not an early return. It used to return before touching the
     * channel, which left the previous query's socket read running to its full deadline on a
     * session the user had already moved on from.
     */
    private fun publish(raw: String): Demand {
        val q = raw.trim()
        while (true) {
            val cur = demand.value
            if (cur.query == q) return cur
            val next = Demand(q, cur.epoch + 1)
            if (demand.compareAndSet(cur, next)) {
                if (q.isEmpty()) {
                    // Clearing must reach the engine: the epoch bump already makes anything in
                    // flight unrenderable, and the socket is released rather than held to a deadline.
                    scope.launch(disp.on(Lane.IO)) { dropSocket() }
                }
                return next
            }
        }
    }

    // ── engine ───────────────────────────────────────────────────────────────────────────────

    private suspend fun engine() {
        demand.collectLatest { d ->
            if (d.query.isEmpty()) {
                engineLock.withLock { outstanding.removeAll { it.epoch < d.epoch } }
                return@collectLatest
            }
            delay(cfg.wsTypingDebounceMs.toLong())
            if (d.epoch != demand.value.epoch) return@collectLatest
            answerDemand(d)
        }
    }

    private suspend fun answerDemand(d: Demand) {
        if (!net.isOnline()) {
            publishAnswer(d, emptyList(), AnswerSource.HTTP, ErrorCode.OFFLINE)
            return
        }
        if (suppressed()) {
            answerFromHttp(d)
            return
        }
        when (val outcome = tryWs(d)) {
            null, FrameOutcome.TimedOut, FrameOutcome.Desynced -> answerFromHttp(d)
            is FrameOutcome.Got -> {
                val rows = mapSuggestions(outcome.text)
                if (rows.drift.isNotEmpty() && rows.items.isEmpty()) {
                    // A frame we could not use. Any doubt falls back to HTTP, which correlates
                    // implicitly, so a keepalive frame is a log line and not an empty result set.
                    log.w("search", "ws frame unusable drift=${rows.drift.map { it.reason }}")
                    answerFromHttp(d)
                } else {
                    healthy()
                    publishAnswer(d, rows.items, AnswerSource.WS)
                }
            }
        }
    }

    private suspend fun answerFromHttp(d: Demand) {
        val rows = httpSuggest.suggest(d.query)
        publishAnswer(d, rows.valueOrNull().orEmpty(), AnswerSource.HTTP, rows.errorCodeOrNull())
    }

    /**
     * The single render gate. An answer whose epoch is stale is dropped with a log line rather than
     * shown. The previous design had this check duplicated in two UI layers and, crucially, in
     * neither the place where the wrong answer was produced.
     */
    private fun publishAnswer(
        d: Demand,
        items: List<MiniEntity>,
        via: AnswerSource,
        error: ErrorCode? = null,
    ) {
        if (d.epoch != demand.value.epoch) {
            log.d("search", "dropped stale answer q='${d.query}' epoch=${d.epoch} current=${demand.value.epoch}")
            return
        }
        answer.value = Answer(d.query, d.epoch, items, via, error)
    }

    // ── websocket ────────────────────────────────────────────────────────────────────────────

    private sealed interface FrameOutcome {
        data class Got(
            val text: String,
        ) : FrameOutcome

        /** Timeout with nothing usable inside [AppConfig.wsAnswerTimeoutMs]. */
        data object TimedOut : FrameOutcome

        /** A frame popped a different demand's stamp: the FIFO can no longer be trusted. */
        data object Desynced : FrameOutcome
    }

    /**
     * One attempt at a single answer. `null` means "no session", never "an empty result".
     *
     * A retry ladder does not belong here: the cooldown in [degrade] is the retry ladder, and a
     * per-demand retry would only spend the answer budget twice.
     */
    private suspend fun tryWs(d: Demand): FrameOutcome? {
        // Not `runCatching`: [session] already handles connect failures internally, and swallowing
        // here would swallow a CancellationException with them.
        val s = session() ?: return null
        engineLock.withLock { outstanding.addLast(d) }
        val outcome =
            try {
                s.send(Frame.Text(requestFor(d.query)))
                readAnswer(s, d)
            } catch (e: CancellationException) {
                // The stamp stays in the deque and the socket stays open; see [outstanding]. If a
                // frame was consumed but not processed the FIFO is off by one, which the next read
                // sees as a stale frame (Desynced) or the answer deadline sees (self-heal).
                throw e
            } catch (io: WebSocketException) {
                socketStrike("websocket: ${io.message}")
                dropSocket()
                return null
            } catch (closed: ClosedReceiveChannelException) {
                socketStrike("socket closed by peer: ${closed.message}")
                dropSocket()
                return null
            } catch (catchAll: Exception) {
                // The suggestion engine is a long-lived loop; an unclassified throw must not end it
                // and leave every later keystroke unanswered. The fallback below is the correct
                // degradation, and the throw is logged rather than swallowed.
                socketStrike("${catchAll::class.simpleName}: ${catchAll.message}")
                dropSocket()
                return null
            }
        when (outcome) {
            is FrameOutcome.Got -> log.d("search", "ws ok q='${d.query}' mode=$correlationMode")
            FrameOutcome.TimedOut -> {
                timeoutStrike(d)
                dropSocket()
            }
            FrameOutcome.Desynced -> {
                log.w("search", "ws mispair on '${d.query}' — the frame answered a different demand")
                socketStrike("fifo mispair")
                dropSocket()
            }
        }
        return outcome
    }

    /**
     * Read at most [AppConfig.wsFrameBudget] frames, and accept one only if it pops **our** stamp.
     *
     * A frame that pops a different stamp is a mispair, and there is no reading of a mispair that is
     * safe: the payload carries no query echo, so if we "kept reading and discarding under the same
     * deadline" the next frame — which may be the *earlier* query's answer, delivered late — would
     * pop our stamp, match, and be rendered as our suggestions. That is precisely the wrong-answer
     * bug this whole redesign exists to remove. So the socket is **closed** and the demand is
     * answered from HTTP, which correlates implicitly.
     *
     * The only way this path is reached at all is the retained stamp: without it the mispair is
     * invisible and the wrong payload is rendered silently.
     */
    private suspend fun readAnswer(
        s: WsSessionLike,
        stamp: Demand,
    ): FrameOutcome {
        var frames = 0
        val text =
            withTimeoutOrNull<String?>(cfg.wsAnswerTimeoutMs) {
                while (frames < cfg.wsFrameBudget) {
                    val frame = s.incoming.receive() as? Frame.Text ?: continue
                    frames++
                    if (correlationMode == CorrelationMode.UNORDERED) return@withTimeoutOrNull frame.readText()
                    val head = engineLock.withLock { outstanding.removeFirstOrNull() }
                    if (head != null && head.epoch == stamp.epoch) return@withTimeoutOrNull frame.readText()
                    divergence++
                    return@withTimeoutOrNull null
                }
                null
            }
        return when {
            text != null -> FrameOutcome.Got(text)
            frames == 0 -> FrameOutcome.TimedOut
            else -> FrameOutcome.Desynced
        }
    }

    private fun requestFor(query: String): String {
        val qEnc = query.encodeURLParameter()
        return buildJsonObject {
            put("url", "/api.php?__call=autocomplete.get&query=$qEnc&_format=json&_marker=0&ctx=web6dot0")
        }.toString()
    }

    // ── socket ownership ─────────────────────────────────────────────────────────────────────

    /**
     * The one function that opens a socket. Serialised, so `warmUp()` and [tryWs] can never each
     * open one; a live socket is reused rather than replaced, so none is ever left unclosed. The
     * attempt cap lives here because the *handshake* is what retries — retrying the answer would
     * only spend [AppConfig.wsAnswerTimeoutMs] twice.
     */
    private suspend fun session(): WsSessionLike? =
        socketOwner.withLock {
            session?.let { return@withLock it }
            var attempt = 0
            while (attempt < cfg.wsHandshakeAttempts) {
                attempt++
                val opened = runCatching { connectBlock() }.getOrNull()
                if (opened != null) {
                    session = opened
                    backoffMs = cfg.wsBackoffBaseMs
                    return@withLock opened
                }
                socketStrike("handshake attempt $attempt/$cfg.wsHandshakeAttempts failed")
                if (attempt < cfg.wsHandshakeAttempts) delay(backoffMs)
            }
            null
        }

    private suspend fun dropSocket() {
        socketOwner.withLock {
            val live = session ?: return@withLock
            session = null
            try {
                live.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The peer may already be gone; there is nothing to recover here, but it is a line
                // worth having because a socket that will not close is a socket that leaks.
                log.w("search", "ws close failed: ${e::class.simpleName}: ${e.message}")
            }
        }
        engineLock.withLock {
            outstanding.clear()
            divergence = 0
        }
    }

    // ── strikes and cooldown ─────────────────────────────────────────────────────────────────

    private fun suppressed(): Boolean {
        val now = cfg.clock.nowMs()
        if (now >= cooldownUntilMs) return false
        // A connectivity change is new information: re-probe rather than sit out the cooldown.
        return cooldownNetClass == net.current()
    }

    private suspend fun timeoutStrike(stamp: Demand) {
        timeoutStrikes++
        log.w("search", "ws timeout strike=$timeoutStrikes/${cfg.wsStrikesBeforeCooldown} q='${stamp.query}'")
        // Our stamp may sit behind stamps of demands that were cancelled and never answered, so pop
        // *ours*: the head is not necessarily the query that timed out.
        engineLock.withLock { outstanding.removeAll { it.epoch == stamp.epoch } }
        degrade()
    }

    private fun socketStrike(reason: String) {
        socketStrikes++
        log.w("search", "ws socket strike=$socketStrikes/${cfg.wsStrikesBeforeCooldown} $reason")
        degrade()
    }

    private fun healthy() {
        timeoutStrikes = 0
        socketStrikes = 0
        backoffMs = cfg.wsBackoffBaseMs
        cooldownUntilMs = 0L
        cooldownMs = cfg.wsCooldownBaseMs
        consecutiveDegradations = 0
        cooldownNetClass = net.current()
    }

    /**
     * Degradation is a **cooldown, not a latch**.
     *
     * `httpOnly` was assigned at its declaration and nowhere else, so three 800 ms socket timeouts
     * disabled WebSocket search for the life of the process, with one WARN line and no recovery. The
     * cooldown expires; the next demand after expiry is a half-open re-probe, and a network-class
     * change re-probes without waiting.
     */
    private fun degrade() {
        if (timeoutStrikes < cfg.wsStrikesBeforeCooldown && socketStrikes < cfg.wsStrikesBeforeCooldown) return
        val now = cfg.clock.nowMs()
        consecutiveDegradations++
        var widened = cfg.wsCooldownBaseMs
        repeat((consecutiveDegradations - 1).coerceAtMost(MAX_COOLDOWN_DOUBLINGS)) { widened *= 2 }
        cooldownMs = widened.coerceAtMost(cfg.wsCooldownCapMs)
        cooldownUntilMs = now + cooldownMs
        cooldownNetClass = net.current()
        timeoutStrikes = 0
        socketStrikes = 0
        backoffMs = (backoffMs * 2).coerceAtMost(cfg.wsBackoffCapMs)
        log.w("search", "ws degraded → HTTP for ${cooldownMs}ms, half-open re-probe after")
    }

    // ── test surface ─────────────────────────────────────────────────────────────────────────

    /** A consistent read of the engine's mutable state, so a test never asserts a torn view. */
    internal data class SockState(
        val mode: CorrelationMode,
        val divergence: Int,
        val pendingStamps: Int,
        val timeoutStrikes: Int,
        val socketStrikes: Int,
        val cooldownRemainingMs: Long,
        val suppressed: Boolean,
        val epoch: Long,
    )

    internal fun stateForTest(): SockState =
        SockState(
            mode = correlationMode,
            divergence = divergence,
            pendingStamps = outstanding.size,
            timeoutStrikes = timeoutStrikes,
            socketStrikes = socketStrikes,
            cooldownRemainingMs = cooldownRemainingMs(),
            suppressed = suppressed(),
            epoch = demand.value.epoch,
        )

    /**
     * Live view of the FIFO, on the engine's own lock. The stamp list is the whole correlation
     * contract, so a test must be able to read it *between* two demands.
     */
    internal suspend fun stampsForTest(): List<Long> = engineLock.withLock { outstanding.map { it.epoch } }

    internal fun answerForTest(): Answer? = answer.value

    internal fun debugState(): String = with(stateForTest()) { "mode=$mode div=$divergence pending=$pendingStamps t=$timeoutStrikes s=$socketStrikes cooldownMs=$cooldownRemainingMs" }

    private fun cooldownRemainingMs(): Long = (cooldownUntilMs - cfg.clock.nowMs()).coerceAtLeast(0L)

    private companion object {
        /** 2^6 × the 30 s base is 32 min, well inside the 10 min cap. */
        const val MAX_COOLDOWN_DOUBLINGS = 6
    }
}

/**
 * The HTTP autocomplete call: one GET, classified by the shared [ResilientClient].
 *
 * It exists as its own class only so the WS path has no inline network code and the two transports
 * can be measured and tested separately.
 */
internal class HttpSuggest(
    http: HttpClient,
    cfg: AppConfig,
    scope: CoroutineScope,
    disp: AppDispatchers,
    log: dylan.diag.LogBuffer,
    net: NetMonitor,
) {
    private val rc = ResilientClient(http, cfg, net, scope, disp, log)

    suspend fun suggest(query: String): CatalogResult<List<MiniEntity>> {
        val q = query.trim()
        if (q.isEmpty()) return CatalogResult.Ok(emptyList())
        return when (val body = rc.get(listOf(ResilientClient.CALL_PARAM to ENDPOINT, "query" to q))) {
            is CatalogResult.Err -> body
            is CatalogResult.Ok -> {
                val rows = mapSuggestionPayload(body.value.text, ENDPOINT)
                if (rows.items.isEmpty() && rows.drift.isNotEmpty()) CatalogResult.Ok(emptyList(), rows.drift)
                else CatalogResult.Ok(rows.items, rows.drift)
            }
        }
    }

    private companion object {
        const val ENDPOINT = "autocomplete.get"
    }
}
