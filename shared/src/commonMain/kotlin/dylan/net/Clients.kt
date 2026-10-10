package dylan.net

import dylan.config.AppConfig
import dylan.provider.dylanJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * The catalog client. **The catalog timeout lives here**, deliberately: `requestTimeoutMillis` on
 * [bulkClient] was removed because it truncated streaming downloads, and the one thing that must not
 * ride along on that decision is the catalog losing its own ceiling. 20 s covers a healthy
 * `api.php` GET by three orders of magnitude, so a hung origin surfaces as
 * [dylan.model.ErrorCode.NETWORK_TIMEOUT] instead of a spinner that never resolves.
 *
 * **The real worst case is ~40.4 s, not 20 s** — this paragraph used to claim the 20 s ceiling was
 * "well under the WS search budget's worst case" (2.5 s, `AppConfig.wsSearchBudgetMs`), which is
 * wrong by a factor of ~16, because it read [CATALOG_REQUEST_TIMEOUT_MS] as if it were the total.
 * It is not: [HttpRequestRetry] is installed with `maxRetries = 1`, and
 * `requestTimeoutMillis` applies **per attempt**, so a hung origin spends 20 s, one ~400 ms backoff,
 * and another 20 s. That doubling is deliberate (see the comment on the retry block) — but it is a
 * property of *this* client's retry config and not something the WS search budget can bound, so
 * nothing here should be read as claiming the catalog is bounded by 2.5 s. A caller that needs a
 * real ceiling must impose its own; `wsSearchBudgetMs` bounds the WebSocket search lane and this
 * client is not part of it.
 *
 * Its [Json] is [dylanJson] — the same instance `ResilientClient` decodes with. There were three
 * with divergent settings; this file's copy sat on a `ContentNegotiation` plugin no call site ever
 * decodes through (`ResilientClient` calls `bodyAsText()` and decodes explicitly), so it was a
 * second source of truth for nothing: it could drift, and a reader would reasonably believe the
 * catalog was parsed with it.
 */
fun apiClient(
    engine: HttpClientEngine,
    cfg: AppConfig,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(dylanJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = CATALOG_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = CATALOG_SOCKET_TIMEOUT_MS
            requestTimeoutMillis = CATALOG_REQUEST_TIMEOUT_MS
        }
        // `maxRetries = 1` plus `retryOnExceptionIf { … }` is a *doubling*, not a nudge: a hung
        // catalog call can spend 20 s, one backoff, and another 20 s. That is the deliberate trade
        // (one more shot on a flaky mobile link is worth a second 20 s of spinner) and the reason
        // `ResilientClient` has no timeout of its own to contradict it. This block is the *only*
        // reason the client ceiling is 2× rather than 1× the timeout above — change `maxRetries`
        // and the KDoc's worst case has to be recomputed, because `requestTimeoutMillis` is
        // per-attempt and does not see any of this.
        install(HttpRequestRetry) {
            maxRetries = 1
            exponentialDelay(baseDelayMs = CATALOG_RETRY_BASE_DELAY_MS)
            retryIf { _, response ->
                response.status.value in 500..599 && response.status.value != SERVICE_UNAVAILABLE
            }
            retryOnExceptionIf { _, cause ->
                cause !is kotlinx.coroutines.CancellationException
            }
        }
        install(UserAgent) { agent = cfg.userAgent }
    }

fun bulkClient(
    engine: HttpClientEngine,
    cfg: AppConfig,
): HttpClient =
    HttpClient(engine) {
        // Pinned for the same reason as apiClient, and it is load-bearing here: the engine's whole
        // HttpOutcome/Resume taxonomy is built on non-2xx responses *reaching* classify() and
        // resumeDecision(). With Ktor's default (true) a 429 or 503 would be thrown as an
        // exception and arrive as a generic transport failure — indistinguishable from a socket
        // reset, so the breaker would never count a rate limit and the retry policy would never
        // see the `Retry-After`.
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = BULK_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = BULK_SOCKET_TIMEOUT_MS
            // No requestTimeoutMillis. Ktor applies it to the WHOLE request including body
            // consumption, i.e. it caps the entire streaming ranged GET. At 45 s the cap allowed
            // only 360 KB at 8 KB/s, and a 6-minute 320 kbps track (~14.5 MB) needed 2.6 Mbit/s to
            // finish inside it — so on hotel Wi-Fi or cellular every track failed three times from
            // zero. It also made stallWallFloorMs (120 s) and the 8 KB/s rate-wall policy dead code
            // and left readyTimeoutMs incoherent.
            //
            // Split of responsibility: the timeouts here own *establishing and reading* a connection
            // (connect, and a single silent gap of socketTimeoutMillis), and the download engine's
            // rate-aware stall watchdog owns the *lifetime* of a transfer. Do not re-add a
            // whole-request cap; add a stall policy to the watchdog instead.
        }
        install(UserAgent) { agent = cfg.userAgent }
    }

/*
 * Timeouts. Both clients are configured inside an `install { … }` lambda rather than as call-site
 * named arguments, so these constants are the only names in the file for the numbers the KDoc above
 * argues about — and the only place a reader can check one against the other.
 */

// apiClient: connect + a silent gap + the whole catalog call. See the KDoc on apiClient for why this
// ceiling is not on bulkClient. CATALOG_REQUEST_TIMEOUT_MS is **per attempt**, so it is not the
// client's ceiling; the KDoc's ~40.4 s is this number times (maxRetries + 1) plus one backoff.
private const val CATALOG_CONNECT_TIMEOUT_MS = 5_000L
private const val CATALOG_SOCKET_TIMEOUT_MS = 15_000L
private const val CATALOG_REQUEST_TIMEOUT_MS = 20_000L

/** Backoff between the two attempts, from `exponentialDelay(baseDelayMs = …)` in [apiClient]. */
private const val CATALOG_RETRY_BASE_DELAY_MS = 400L

/**
 * 503 is carved out of the 5xx retry band on purpose: it is the origin saying "come back later",
 * usually with a `Retry-After`, not a transient fault, so an immediate re-issue ignores both.
 */
private const val SERVICE_UNAVAILABLE = 503

// bulkClient: establish and read only. A streaming ranged GET deliberately has no whole-request cap.
private const val BULK_CONNECT_TIMEOUT_MS = 10_000L
private const val BULK_SOCKET_TIMEOUT_MS = 30_000L
