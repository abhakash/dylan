package dylan.search

import dylan.config.AppConfig
import dylan.model.MiniEntity
import dylan.provider.saavn.mapSuggestionPayload
import dylan.provider.saavn.mapSuggestions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

enum class CorrelationMode { ECHO, ORDERED, UNORDERED }

interface WsSessionLike {
    val incoming: kotlinx.coroutines.channels.ReceiveChannel<Frame>

    suspend fun send(frame: Frame)

    suspend fun close()
}

class SaavnSearchChannel(
    private val http: HttpClient,
    private val wsClient: HttpClient,
    private val cfg: AppConfig,
    private val scope: CoroutineScope,
    private val log: dylan.diag.LogBuffer = dylan.diag.LogBuffer.SILENT,
) : SearchChannel {
    var correlationMode = CorrelationMode.ORDERED

    internal var connectBlock: suspend () -> WsSessionLike? = {
        wsClient.webSocketSession(cfg.wsSearchUrl)?.toLike()
    }

    private val demand = MutableStateFlow("")
    private val answer = MutableStateFlow<Pair<String, List<MiniEntity>>?>(null)

    private var session: WsSessionLike? = null
    private var sentQueries = ArrayDeque<String>()
    private var timeoutStrikes = 0
    private var socketStrikes = 0
    private var divergence = 0
    private var httpOnly = false
    private var backoffMs = cfg.wsBackoffBaseMs

    init {
        scope.launch { engine() }
    }

    fun warmUp() {
        scope.launch { connect() }
    }

    private fun DefaultClientWebSocketSession.toLike(): WsSessionLike =
        object : WsSessionLike {
            override val incoming = this@toLike.incoming

            override suspend fun send(frame: Frame) = this@toLike.send(frame)

            override suspend fun close() = this@toLike.close(CloseReason(CloseReason.Codes.NORMAL, "cycle"))
        }

    fun onBackground() {
        scope.launch {
            runCatching { session?.close() }
        }
        session = null
    }

    override suspend fun suggest(query: String): List<MiniEntity> {
        if (query.isBlank()) return emptyList()
        demand.value = query
        return withTimeoutOrNull(2_500) {
            answer.first { it != null && it.first == query }?.second
        } ?: httpFallback(query)
    }

    /**
     * §6.4 render-on-arrival surface: latest answered (query, results) or null.
     * UI fires [request] per debounced keystroke and collects this — no per-keystroke blocking.
     */
    val suggestions: kotlinx.coroutines.flow.StateFlow<Pair<String, List<MiniEntity>>?> get() = answer

    /** Fire-and-forget demand update; answers arrive on [suggestions]. */
    fun request(query: String) {
        if (query.isBlank()) return
        demand.value = query
    }

    private suspend fun engine() {
        demand.collectLatest { q ->
            if (q.isBlank()) return@collectLatest
            delay(cfg.wsTypingDebounceMs.toLong())
            if (httpOnly) {
                answer.value = q to httpFallback(q)
                return@collectLatest
            }
            val frameText = tryWs(q)
            if (frameText == null) {
                answer.value = q to httpFallback(q)
            } else {
                val parsed = runCatching { mapSuggestions(frameText) }.getOrNull()
                if (parsed == null) {
                    answer.value = q to httpFallback(q)
                } else {
                    timeoutStrikes = 0
                    socketStrikes = 0
                    backoffMs = cfg.wsBackoffBaseMs
                    answer.value = q to parsed
                }
            }
        }
    }

    // ORDERED correlation per plan §6.4: each send stamps the FIFO deque; a received text frame
    // pops the head; render iff popped == the awaited query. Mismatched frames are DISCARDED
    // (never rendered for the wrong query) and count toward divergence ≥3 ⇒ single-flight.
    // Divergence resets only on reconnect/background — not on individual good pairs — so three
    // recent mispairs degrade the session even with good pairs interleaved.
    private suspend fun tryWs(query: String): String? {
        val s = connect() ?: return null
        val added = correlationMode != CorrelationMode.UNORDERED
        if (added) sentQueries.addLast(query)
        return try {
            val sendJson =
                buildJsonObject {
                    put("url", "/api.php?__call=autocomplete.get&query=$query&_format=json&_marker=0&ctx=web6dot0")
                }.toString()
            s.send(Frame.Text(sendJson))
            val text =
                withTimeoutOrNull(cfg.wsRequestTimeoutMs.toLong()) {
                    var accepted: String? = null
                    while (accepted == null) {
                        val frame = s.incoming.receive() as? Frame.Text ?: continue
                        if (correlationMode == CorrelationMode.UNORDERED) {
                            accepted = frame.readText()
                            break
                        }
                        val head = sentQueries.removeFirstOrNull()
                        if (head == query) {
                            accepted = frame.readText()
                        } else if (++divergence >= 3) {
                            correlationMode = CorrelationMode.UNORDERED
                            divergence = 0
                        }
                    }
                    accepted
                }
            if (text == null) {
                timeoutStrikes++
                log.w("search", "ws timeout strike=$timeoutStrikes/3 q='$query'")
                degrade()
                closeQuietly()
            } else {
                log.d("search", "ws ok q='$query' mode=$correlationMode")
            }
            text
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (added) sentQueries.remove(query)
            throw e
        } catch (e: Exception) {
            socketStrikes++
            log.w("search", "ws socket strike=$socketStrikes/3 err=${e.message ?: e::class.simpleName}")
            degrade()
            closeQuietly()
            null
        }
    }

    private fun degrade() {
        if ((timeoutStrikes >= 3 || socketStrikes >= 3) && !httpOnly) {
            httpOnly = true
            log.w("search", "ws degraded → HTTP-only for session")
        }
    }

    internal fun timeoutStrikesForTest() = timeoutStrikes

    internal fun socketStrikesForTest() = socketStrikes

    internal fun httpOnlyForTest() = httpOnly

    internal fun debugState(): String = "mode=$correlationMode div=$divergence pending=${sentQueries.size} t=$timeoutStrikes s=$socketStrikes httpOnly=$httpOnly"

    private suspend fun closeQuietly() {
        runCatching { session?.close() }
        session = null
        sentQueries.clear()
        divergence = 0
    }

    private suspend fun connect(): WsSessionLike? {
        session?.let { return it }
        while (true) {
            try {
                val s = connectBlock() ?: return null
                session = s
                backoffMs = cfg.wsBackoffBaseMs
                return s
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                socketStrikes++
                degrade()
                if (httpOnly) return null
                delay(backoffMs + Random.nextLong(backoffMs / 2))
                backoffMs = (backoffMs * 2).coerceAtMost(cfg.wsBackoffCapMs)
            }
        }
    }

    private suspend fun httpFallback(query: String): List<MiniEntity> =
        try {
            val resp =
                http.get(cfg.apiBaseUrl) {
                    parameter("__call", "autocomplete.get")
                    parameter("query", query)
                    cfg.commonParams.forEach { (k, v) -> parameter(k, v) }
                    header(HttpHeaders.UserAgent, cfg.userAgent)
                }
            if (!resp.status.isSuccess()) {
                emptyList()
            } else {
                mapSuggestionPayload(resp.bodyAsText())
            }
        } catch (e: Exception) {
            emptyList()
        }
}
