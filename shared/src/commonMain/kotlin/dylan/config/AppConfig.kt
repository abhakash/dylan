package dylan.config

import dylan.model.Quality
import dylan.util.Clock
import dylan.util.SystemClock

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
    val wsPingIntervalMs: Int = 25_000,
    val wsBackoffBaseMs: Long = 1_000,
    val wsBackoffCapMs: Long = 16_000,
    // ── WS budget, split in two (audit SE-2 / §3.5) ───────────────────────────────────────
    // The socket is a *latency optimisation only*: the frame carries no query echo and no
    // request id, so FIFO position is strictly weaker correlation than HTTP's implicit
    // correlation. Budget therefore has two independent parts, because a cold TLS connect
    // (hundreds of ms to seconds on mobile) must not be charged against the answer window.
    // Ktor deliberately skips `requestTimeoutMillis` for `wss://`, so the upgrade needs its own.
    val wsHandshakeTimeoutMs: Long = 5_000,
    /** Hard ceiling on `webSocketSession()`; a blackholed upgrade otherwise pins the engine. */
    val wsHandshakeAttempts: Int = 2,
    /** Per-answer window. Probe P12 measures a healthy handshake+round-trip at <2 s. */
    val wsAnswerTimeoutMs: Long = 1_500,
    /** Frames accepted per demand. The server answers one request per frame; 1 is the budget. */
    val wsFrameBudget: Int = 1,
    /** Total wall budget for one suggestion answer (WS attempt(s) + HTTP fallback). */
    val wsSearchBudgetMs: Long = 2_500,
    /** Consecutive WS failures before the cooldown opens. */
    val wsStrikesBeforeCooldown: Int = 3,
    /** Cooldown after degradation. It EXPIRES — the next demand is a half-open re-probe. */
    val wsCooldownBaseMs: Long = 30_000,
    /** Ceiling for a repeatedly-failing cooldown (each post-cooldown failure doubles it). */
    val wsCooldownCapMs: Long = 600_000,
    /**
     * ── catalog resilience (audit §3.5: null-returning provider) ──
     * In-memory LRU entries for album/artist/home/topSearches.
     */
    val catalogLruEntries: Int = 24,
    /**
     * How long a failed endpoint is answered from cache instead of re-requested. This is the
     * catalog's *only* backoff: the origin's `Retry-After` is parsed nowhere in production
     * (`ResilientClient.retryAfterMs` has test-only callers), so a 429 costs exactly this long
     * regardless of what the origin asked for.
     */
    val catalogNegativeTtlMs: Long = 30_000,
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
    // Resume artifact: how often a playing session may rewrite it, and how far the position has to
    // move before a tick considers it changed. The write is one dbLane round-trip plus a whole-queue
    // serialisation, so a ticker that fired unconditionally contended with every download step.
    val snapshotIntervalMs: Long = 30_000,
    val snapshotPosStepMs: Long = 5_000,
    // A resume snapshot older than this describes a track that was paused, not one that was playing
    // when the process died, so its position is not honoured.
    val resumeMaxAgeMs: Long = RESUME_MAX_AGE_MINUTES * MS_PER_MINUTE,
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
    /**
     * Wall-clock source for every "now" comparison against a stored timestamp (LRU ordering,
     * the reconciler grace windows, cache TTLs). Injected so those windows are deterministic in
     * tests; production graphs leave it at [SystemClock].
     */
    val clock: Clock = SystemClock,
) {
    private companion object {
        /** 15 minutes: a snapshot older than this describes a paused track, not a playing one. */
        const val RESUME_MAX_AGE_MINUTES = 15
        const val MS_PER_MINUTE = 60_000L
    }
}
