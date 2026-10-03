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
 * `api.php` GET by three orders of magnitude and is well under the WS search budget's worst case,
 * so a hung origin surfaces as [dylan.model.ErrorCode.NETWORK_TIMEOUT] instead of a spinner that
 * never resolves.
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
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
            requestTimeoutMillis = 20_000
        }
        // `maxRetries = 1` plus `retryOnExceptionIf { … }` is a *doubling*, not a nudge: a hung
        // catalog call can spend 20 s, one backoff, and another 20 s. That is the deliberate trade
        // (one more shot on a flaky mobile link is worth a second 20 s of spinner) and the reason
        // `ResilientClient` has no timeout of its own to contradict it.
        install(HttpRequestRetry) {
            maxRetries = 1
            exponentialDelay(baseDelayMs = 400)
            retryIf { _, response ->
                response.status.value in 500..599 && response.status.value != 503
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
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
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
