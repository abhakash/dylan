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
    /**
     * Maximum cached renditions, and the offline cache's PRIMARY knob: the byte budget is derived
     * from it (see [cacheMaxBytes]), so raising the file cap raises the byte cap by the same
     * factor and the two can never disagree about how full "full" is.
     *
     * What it bounds: how many tracks the Downloads screen can offer offline, and therefore how
     * many the LRU pass may destroy per new download.
     *
     * Failure mode it prevents: a Downloads screen listing more tracks than the cache can hold,
     * so every completed download silently evicts a track the user can still see listed.
     */
    val cacheMaxFiles: Int = 300,
    /**
     * Byte budget for the cache — DERIVED as `cacheMaxFiles x CACHE_MEAN_TRACK_BYTES`, not an
     * independent knob.
     *
     * It used to be an independent 2 GB, enforced as `min(cacheMaxBytes, cacheMaxFiles x 1 MB)`.
     * At 300 files that `min` could only ever pick the derived term, so `cacheMaxBytes` was dead
     * config that *looked* live: both platform UIs print and divide by it
     * ("Cached audio 41 MB of 2.0 GB", a progress bar over 2 GB), so the user was shown a budget
     * the cache could never reach and never told which of the two was real. Deriving removes the
     * second number, so what the UI shows and what the LRU pass enforces are the same value by
     * construction — and a user (or a test) who raises `cacheMaxFiles` automatically raises the
     * bytes those files are allowed to occupy.
     *
     * Which of the two caps binds is a property of the *content*, not of the caps: this one trips
     * first whenever renditions are larger than the assumed mean (a 320 kbps track is several MB,
     * so in practice the byte budget is the binding constraint and the file cap is the backstop
     * for a cache of unusually small tracks), and the file cap trips first when they are smaller.
     * Both statements stay true under derivation.
     *
     * Failure mode it prevents: a corrupt `bytes` column or a pathological provider filling the
     * user's disk, since the file cap alone cannot bound anything byte-sized.
     */
    val cacheMaxBytes: Long = cacheMaxFiles * CACHE_MEAN_TRACK_BYTES,
    /**
     * Share of the cache a favourite is guaranteed against the pinned sub-pool's own eviction.
     * Both pinned budgets come from it and from [cacheMaxBytes]: `pinnedMaxFraction` of the file
     * cap in rows, and the same fraction of the byte budget in bytes.
     *
     * The pin-demotion path is LIVE, not vestigial: with the defaults a pool trips the pinned byte
     * budget at ~38 six-megabyte renditions, long before the 225-row budget — so `demotePins` has
     * work to do well inside the file cap. (It was reported dead because the unreachable 2 GB byte
     * cap was read as the real budget; it never was.)
     *
     * Failure mode it prevents: favourites accumulating an unbounded share of the cache, where the
     * LRU pass may never touch them because they are protected, so nothing else ever shrinks them.
     */
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
    /**
     * FLOOR on one attempt's wall-clock lifetime. The real ceiling is
     * `max(this, expectedBytes / 8)` — see `stallWallCapMs` — i.e. the time the track would take
     * at a deliberately pessimistic 8 KB/s. Rate-based on purpose: only a transfer trickling below
     * that average rate is killed, and a flowing-but-slow link never trips it. `stallTimeoutMs`
     * covers the other case (no fresh bytes at all).
     *
     * Failure mode it prevents: an unbounded trickle holding a transfer permit and the user's
     * cellular for as long as the origin keeps sending bytes.
     */
    val stallWallFloorMs: Long = READY_TIMEOUT_MS,
    /**
     * How long playback waits for a USER_NOW download before reporting NETWORK_TIMEOUT.
     *
     * THIS BOUNDS THE USER-VISIBLE WAIT ONLY — IT DOES NOT CANCEL THE ATTEMPT.
     * `Orchestrator.awaitDownload` is a bare `withTimeoutOrNull`; when it fires, the transfer keeps
     * running until its own ceiling above. So the honest bound on cellular spent AFTER the user was
     * told the download failed is `max(0, max(stallWallFloorMs, expectedBytes / 8) - readyTimeoutMs)`:
     * zero below 960 KB, ~12 min after a 6 MB track, ~28 min after a 14.5 MB one — and it also
     * restarts once per transient retry. Bounding that window is the Orchestrator's job (cancel the
     * attempt it was handed on timeout), NOT a config value: no constant can bound it, because the
     * ceiling scales with a track size that no config ever sees. This constant can only guarantee
     * the window is never *shorter* than the floor — i.e. playback never gives up before a merely
     * trickling transfer has reached the ceiling it was always going to get.
     *
     * It shares one literal with [stallWallFloorMs] so those two roles cannot drift apart.
     */
    val readyTimeoutMs: Long = READY_TIMEOUT_MS,
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
        /**
         * 120 s: how long a user is prepared to wait for a tap to become playback. Used twice, on
         * purpose — as the floor on an attempt's lifetime and as the wait that gives up — so that
         * "playback gave up" and "the transfer may be killed" cannot be two unrelated literals that
         * silently disagree. It is the FLOOR of an attempt's ceiling, not its bound: a track larger
         * than 960 KB outlives it (see [readyTimeoutMs]).
         */
        const val READY_TIMEOUT_MS = 120_000L

        /**
         * Assumed mean size of one cached rendition, in bytes. 1 MB is a deliberately conservative
         * stand-in for "a track" — a 320 kbps rendition is really several MB — which puts the
         * derived byte cap in the safe direction: too small means the cache fills to fewer files
         * than [cacheMaxFiles] (evicting early, recoverable), too large means it overruns the disk
         * the file cap was promised. It moves the enforced byte budget 1:1, so changing it is a
         * product decision about on-device cache size, not a tuning detail.
         */
        const val CACHE_MEAN_TRACK_BYTES = 1_000_000L

        /** 15 minutes: a snapshot older than this describes a paused track, not a playing one. */
        const val RESUME_MAX_AGE_MINUTES = 15
        const val MS_PER_MINUTE = 60_000L
    }
}
