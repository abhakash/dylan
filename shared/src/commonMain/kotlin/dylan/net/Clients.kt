package dylan.net

import dylan.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private val jsonCfg =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

fun apiClient(
    engine: HttpClientEngine,
    cfg: AppConfig,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(jsonCfg) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
            requestTimeoutMillis = 20_000
        }
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
