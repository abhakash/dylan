package dylan.provider

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.model.ErrorCode
import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The one and only [Json] for the catalog.
 *
 * There were three with divergent settings — this one, one in `SaavnProvider`, one in `Mapper` —
 * and the two that actually decoded catalog payloads were both **missing `coerceInputValues`**,
 * the one setting that makes `"id": null` survivable instead of fatal. `net/Clients.kt` had it
 * right, on a [Json] instance no call site ever touched.
 *
 * `net/Clients.kt` should reference this instance (W3-E owns that file).
 */
val dylanJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        // A null where a non-nullable field is expected becomes that field's default instead of
        // throwing. Combined with element-wise decode (see CatalogRows) this is what keeps one bad
        // card from discarding nineteen good ones.
        coerceInputValues = true
        explicitNulls = false
    }

/** Response body that already passed the JSON/bot-wall sniff. */
class CatalogBody(
    val text: String,
)

/**
 * What a partial decode produced: the rows that survived, plus a [Drift] per row that did not.
 *
 * This is the type that makes "one bad card" and "no cards" different values. The old shape
 * (`List<T>` + a `runCatching` around the whole page) made them both an empty list.
 *
 * [T] is the *whole* decoded value, not a list of rows, so the same type carries a
 * `List<Song>` from `autocomplete.get` and a `Paged<Song>` from `search.getResults`.
 */
class Rows<out T>(
    val items: T,
    val drift: List<Drift> = emptyList(),
)

/** Whether a decode dropped anything. */
val Rows<*>.isPartial: Boolean get() = drift.isNotEmpty()

/**
 * Whether a song's duration is real.
 *
 * A `0` from the mapper means *unknown*, not zero-length, and the distinction is load-bearing: the
 * download engine multiplies `duration_s × bps` to size a transfer and to derive the wall-clock
 * cap, so an unknown duration produces a `CORRUPT_SIZE` failure that is blamed on storage. Any
 * caller that needs a size must branch on this and refuse rather than compute from 0.
 */
fun durationKnown(durationS: Long): Boolean = durationS > 0

internal const val UNKNOWN_DURATION_S = 0L

/**
 * The catalog HTTP seam: one [Json], one status→[ErrorCode] table, one LRU with a negative half,
 * one mutex-guarded map with per-key single-flight.
 *
 * Everything that used to answer `null` and force the caller to guess now answers a
 * [CatalogResult], so "no connectivity", "rate limited", "geo-blocked", "bot-walled", "timed out"
 * and "no matches" are six different values instead of one.
 */
class ResilientClient(
    private val http: HttpClient,
    private val cfg: AppConfig,
    private val net: NetMonitor,
    private val scope: CoroutineScope,
    private val disp: AppDispatchers,
    private val log: LogBuffer,
) {
    val json: Json get() = dylanJson

    // ── LRU (positive + negative) and single-flight ─────────────────────────────────────────

    private data class Entry(
        val result: CatalogResult<Any>,
        val at: Long,
    )

    /**
     * Immutable snapshot swapped wholesale under [lock]. The old `LinkedHashMap` was mutated from
     * several dispatchers with no mutex, so a `put` during `entries.iterator()` threw
     * `ConcurrentModificationException` *inside a `runCatching`* — an album that silently failed to
     * load — and the read-modify-write LRU reorder could corrupt the bucket chain. With a snapshot
     * there is no iterator to invalidate and no write to interleave, so the class of bug is gone.
     */
    private var entries: Map<String, Entry> = emptyMap()

    /** Per-key in-flight loads: three concurrent opens of one album produce one request. */
    private val inflight = mutableMapOf<String, Deferred<*>>()

    /** Endpoint → (failure, when it was observed). Replayed for [AppConfig.catalogNegativeTtlMs]. */
    private val negative = mutableMapOf<String, Pair<CatalogResult.Err, Long>>()

    private val lock = Mutex()

    private sealed interface Decision<T> {
        data class Hit<T>(
            val result: CatalogResult<T>,
        ) : Decision<T>

        data class Join<T>(
            val load: Deferred<CatalogResult<T>>,
        ) : Decision<T>

        data class Lead<T>(
            val fresh: Deferred<CatalogResult<T>>,
        ) : Decision<T>
    }

    /**
     * Reads [key] from the LRU (or replays a recent failure), else runs [load] — collapsing
     * concurrent callers for one key onto a single request. The load runs in a scope-owned child,
     * so a cancelled *caller* neither cancels the shared fetch nor loses its result.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> cached(
        key: String,
        ttlMs: Long,
        load: suspend () -> CatalogResult<T>,
    ): CatalogResult<T> {
        val plan = lock.withLock { planLocked<T>(key, ttlMs, load) }
        return when (plan) {
            is Decision.Hit -> plan.result
            is Decision.Join -> plan.load.await()
            is Decision.Lead -> {
                // No catch: a load that throws is a bug and the exception belongs to *every* joiner,
                // not just the leader. The inflight entry is self-cleaning — [planLocked] sweeps
                // completed deferreds — so a failed load can never become a permanent cache entry.
                val out = plan.fresh.await()
                lock.withLock { storeLocked(key, out) }
                out
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> planLocked(
        key: String,
        ttlMs: Long,
        load: suspend () -> CatalogResult<T>,
    ): Decision<T> {
        val now = cfg.clock.nowMs()
        // Self-cleaning: a load that threw leaves a *completed* deferred behind. Sweeping here means
        // a failure can never become a permanent cache entry, without a `finally` that would need a
        // suspending call in a non-suspending context.
        inflight.entries.removeAll { it.value.isCompleted }
        val hit = entries[key]
        if (hit != null) {
            val ttl = if (hit.result is CatalogResult.Ok) ttlMs else cfg.catalogNegativeTtlMs
            if (now - hit.at <= ttl) {
                // LRU reorder: drop then re-insert so iteration order stays least-recent-first.
                entries = LinkedHashMap(entries).also { it.remove(key); it[key] = hit }
                return Decision.Hit(hit.result as CatalogResult<T>)
            }
            entries = LinkedHashMap(entries).also { it.remove(key) }
        }
        inflight[key]?.let { return Decision.Join(it as Deferred<CatalogResult<T>>) }
        val fresh = scope.async(disp.on(Lane.IO)) { load() }
        inflight[key] = fresh
        return Decision.Lead(fresh)
    }

    private suspend fun storeLocked(
        key: String,
        result: CatalogResult<Any>,
    ) {
        inflight.remove(key)
        val next = LinkedHashMap(entries)
        next.remove(key)
        // Failures are the negative cache's business, keyed by endpoint (see [get]).
        if (result is CatalogResult.Ok) next[key] = Entry(result, cfg.clock.nowMs()) else next.remove(key)
        while (next.size > cfg.catalogLruEntries) {
            next.keys.firstOrNull()?.let { next.remove(it) } ?: break
        }
        entries = next
    }

    /**
     * Replays a recent endpoint failure, or null. OFFLINE is never stored (connectivity returns on
     * its own); the fact cached is about the origin, not about the device.
     */
    private suspend fun cachedError(endpoint: String): CatalogResult.Err? =
        lock.withLock {
            val hit = negative[endpoint] ?: return@withLock null
            if (cfg.clock.nowMs() - hit.second > cfg.catalogNegativeTtlMs) {
                negative.remove(endpoint)
                null
            } else {
                hit.first
            }
        }

    /** Drops every cached entry, positive and negative. */
    suspend fun invalidate() {
        lock.withLock {
            entries = emptyMap()
            negative.clear()
        }
    }

    // ── fetch ────────────────────────────────────────────────────────────────────────────────

    /**
     * One GET against [AppConfig.apiBaseUrl] with the common params and UA applied, classified into
     * a [CatalogResult].
     *
     * [request] is the **whole** query, `__call` included, so the routing functions
     * (`albumRequest`, `artistRequest`, …) stay the single source of truth for which endpoint a
     * logical operation talks to — and the negative-cache key is the same `__call` the wire saw.
     */
    suspend fun get(request: List<Pair<String, String>>): CatalogResult<CatalogBody> {
        val endpoint = request.firstOrNull { it.first == CALL_PARAM }?.second ?: "unknown"
        if (!net.isOnline()) return err(ErrorCode.OFFLINE, "no network path ($endpoint)", retryable = true)
        // Negative cache, keyed by *endpoint*: "autocomplete.get is rate limited" is a fact about the
        // origin, not about one query, and without this every keystroke during a rate-limit window
        // spends a request to be told the same thing.
        cachedError(endpoint)?.let { return it }
        val out = fetch(request, endpoint)
        lock.withLock {
            val failure = out as? CatalogResult.Err
            if (failure == null || failure.code == ErrorCode.OFFLINE) {
                negative.remove(endpoint)
            } else {
                negative[endpoint] = failure to cfg.clock.nowMs()
                val cut = cfg.clock.nowMs() - cfg.catalogNegativeTtlMs
                negative.entries.removeAll { it.value.second < cut }
            }
        }
        return out
    }

    private suspend fun fetch(
        request: List<Pair<String, String>>,
        endpoint: String,
    ): CatalogResult<CatalogBody> =
        try {
            val resp =
                http.request(cfg.apiBaseUrl) {
                    method = HttpMethod.Get
                    request.forEach { (k, v) -> parameter(k, v) }
                    cfg.commonParams.forEach { (k, v) -> parameter(k, v) }
                    header(HttpHeaders.UserAgent, cfg.userAgent)
                }
            classify(endpoint, resp.status, resp.headers[HttpHeaders.RetryAfter]) { resp.bodyAsText() }
        } catch (e: CancellationException) {
            throw e
        } catch (transport: IOException) {
            // No status was ever seen, so the only question is whether the failure was a timeout.
            // Ktor surfaces socket and connect timeouts as classes named *Timeout* on every
            // platform, so the name check is the portable test.
            val code = if (isTimeout(transport)) ErrorCode.NETWORK_TIMEOUT else ErrorCode.NETWORK
            log.w("catalog", "$endpoint ${code.name} ${transport::class.simpleName}: ${transport.message}")
            err(code, "${transport::class.simpleName}: ${transport.message}")
        }

    /**
     * Status → code. A suspendable body reader is passed in rather than the response so the table
     * is testable over a `MockEngine` with no transport plumbing.
     */
    suspend fun classify(
        endpoint: String,
        status: HttpStatusCode,
        retryAfter: String?,
        readBody: suspend () -> String,
    ): CatalogResult<CatalogBody> {
        if (status.isSuccess()) {
            val text = readBody()
            if (text.isBlank()) return err(ErrorCode.DRIFT, "$endpoint: empty body", retryable = false)
            // Magic before header: a bot-wall is a 200, and a body whose first non-whitespace
            // character is '<' is never the JSON we asked for, whatever Content-Type claims.
            if (looksLikeHtml(text)) {
                log.w("catalog", "$endpoint DRIFT html-body chars=${text.length}")
                return err(ErrorCode.DRIFT, "$endpoint: HTML interstitial (${text.length} chars)", retryable = false)
            }
            return CatalogResult.Ok(CatalogBody(text))
        }
        val e = statusError(endpoint, status.value, retryAfter)
        log.w("catalog", "$endpoint ${e.code.name} status=${status.value} detail=${e.detail}")
        return e
    }

    companion object {
        /** The query parameter that names the origin's operation, and the negative-cache key. */
        const val CALL_PARAM: String = "__call"

        /** The first non-whitespace character of a body that is certainly not JSON. */
        const val HTML_FIRST_CHAR: Char = '<'

        fun looksLikeHtml(text: String): Boolean = text.firstOrNull { !it.isWhitespace() } == HTML_FIRST_CHAR

        fun isTimeout(t: Throwable): Boolean = t::class.simpleName?.contains("Timeout", ignoreCase = true) == true

        /**
         * The mapping, in one place. `retryable` answers "can the app succeed by trying again
         * without the user doing anything", which is what both the negative cache and the download
         * retry policy key on.
         */
        fun statusError(
            endpoint: String,
            code: Int,
            retryAfter: String?,
        ): CatalogResult.Err =
            when (code) {
                TOO_MANY_REQUESTS ->
                    err(
                        ErrorCode.RATE_LIMITED,
                        "$endpoint: $TOO_MANY_REQUESTS retry-after=${retryAfter ?: "-"}",
                        retryable = true,
                    )
                UNAUTHORIZED ->
                    err(ErrorCode.EXPIRED, "$endpoint: $UNAUTHORIZED unauthenticated", retryable = true)
                FORBIDDEN -> err(ErrorCode.FORBIDDEN_REGION, "$endpoint: $FORBIDDEN", retryable = false)
                NOT_FOUND -> err(ErrorCode.NOT_FOUND, "$endpoint: $NOT_FOUND", retryable = false)
                REQUEST_TIMEOUT -> err(ErrorCode.NETWORK_TIMEOUT, "$endpoint: $REQUEST_TIMEOUT", retryable = true)
                in SERVER_ERROR_RANGE -> err(ErrorCode.NETWORK, "$endpoint: $code", retryable = true)
                in CLIENT_ERROR_RANGE -> err(ErrorCode.NETWORK, "$endpoint: $code", retryable = false)
                else -> err(ErrorCode.NETWORK, "$endpoint: $code", retryable = true)
            }

        /**
         * `Retry-After` in milliseconds, seconds form only (the HTTP-date form is not honoured;
         * the caller falls back to the negative-cache TTL). Capped, because an origin that asks for
         * an hour must not park the catalog for an hour.
         */
        fun retryAfterMs(
            header: String?,
            capMs: Long,
        ): Long? {
            val seconds = header?.trim()?.toLongOrNull() ?: return null
            return (seconds * MS_PER_SECOND).coerceIn(0L, capMs)
        }

        private const val TOO_MANY_REQUESTS = 429
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val REQUEST_TIMEOUT = 408
        private const val MS_PER_SECOND = 1_000L
        private val SERVER_ERROR_RANGE = 500..599
        private val CLIENT_ERROR_RANGE = 400..499
    }
}
