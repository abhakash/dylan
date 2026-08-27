package dylan.config

import dylan.model.Quality

data class AppConfig(
    val apiBaseUrl: String = "https://www.jiosaavn.com/api.php",
    val commonParams: Map<String, String> =
        mapOf(
            "api_version" to "4",
            "_format" to "json",
            "ctx" to "web6dot0",
            "_marker" to "0",
        ),
    val userAgent: String = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
    val wsSearchUrl: String = "wss://ws.jiosaavn.com/",
    val wsTypingDebounceMs: Int = 120,
    val wsRequestTimeoutMs: Int = 800,
    val wsPingIntervalMs: Int = 25_000,
    val wsBackoffBaseMs: Long = 1_000,
    val wsBackoffCapMs: Long = 16_000,
    val submitPageSize: Int = 20,
    val cacheMaxFiles: Int = 300,
    val cacheMaxBytes: Long = 2L * 1024 * 1024 * 1024,
    val pinnedMaxFraction: Double = 0.75,
    val imageCacheBytes: Long = 150L * 1024 * 1024,
    val diskFloorBytes: Long = 500L * 1024 * 1024,
    val partGraceHours: Int = 1,
    val maxConcurrentParts: Int = 3,
    val estimatePadding: Double = 1.25,
    val dlRetries: Int = 2,
    val dlBackoffBaseMs: Long = 800,
    val resolveCapPerJob: Int = 3,
    val rangeRestartsCap: Int = 1,
    val stallTimeoutMs: Long = 20_000,
    val stallWatchdogTickMs: Long = 2_000,
    // Wall-clock cap floor: real cap = max(this, expectedB / 8) — i.e. time-to-download at a
    // conservative 8 KB/s. Only a trickling transfer (below that average rate) is killed;
    // a flowing-but-slow link never trips it. Configurable for tests.
    val stallWallFloorMs: Long = 120_000,
    // Max wait for a USER_NOW download before ensureReadyAndPlay gives up into Phase.Error.
    val readyTimeoutMs: Long = 120_000,
    val skipSettleMs: Int = 350,
    // Rapid Next/Previous taps inside this window are dropped (first tap always allowed).
    val navDebounceMs: Long = 300,
    // ensureReady watchdog: log-only tripwire, never fails playback (readyTimeoutMs still owns failure).
    val ensureReadyWatchdogMs: Long = 15_000,
    val prefetchEnabled: Boolean = true,
    val prefetchCellularTracks: Int = 1,
    val defaultQuality: Quality = Quality.BITRATE_320,
    val meteredQuality: Quality = Quality.BITRATE_128,
    val posHzFull: Int = 10,
    val posHzMini: Int = 4,
    val homeCacheTtlMs: Long = 6L * 60 * 60 * 1000,
    val albumCacheTtlMs: Long = 7L * 24 * 60 * 60 * 1000,
    val homeCacheRowCap: Int = 200,
    val imageMemoryCacheBytes: Long = 48L * 1024 * 1024,
    val historyLimit: Int = 500,
    val searchHistoryLimit: Int = 20,
    val songsGcAgeDays: Int = 60,
)
