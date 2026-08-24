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
        }
        install(HttpRequestRetry) {
            maxRetries = 1
            exponentialDelay(baseDelayMs = 400)
            retryIf { _, response ->
                response.status.value in 500..599 && response.status.value != 503
            }
        }
        install(UserAgent) { agent = cfg.userAgent }
    }

fun bulkClient(
    engine: HttpClientEngine,
    cfg: AppConfig,
): HttpClient =
    HttpClient(engine) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }
