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
    // Concatenated rather than one literal: the whole UA is 148 chars, over the 120 limit, and
    // the split is at the `Mozilla/5.0` / product boundary a real UA has. Joining is exact — no
    // space is added or lost — because both halves are complete tokens.
    val userAgent: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
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
     * Nominal size of the offline audio cache, in bytes. **This is the cache's primary knob** — the
     * row cap is derived from it ([cacheMaxFiles]), not the other way round.
     *
     * The direction of that derivation is the whole point, and it was previously backwards. The
     * cache used to be sized by a row count multiplied by an assumed 1 MB mean rendition, so the
     * enforced budget was `300 x 1 MB` = **300 MB** while both UIs printed and divided by a nominal
     * 2 GB ("Cached audio 41 MB of 2.0 GB"). The advertised number was unreachable dead config: the
     * user was shown a limit the cache could never reach and never told which of the two was real.
     * Sizing from bytes makes the number on screen and the number the LRU pass enforces the same
     * value by construction.
     *
     * Failure mode it prevents: a Downloads screen listing more tracks than the cache can hold, so
     * every completed download silently evicts a track the user can still see listed.
     */
    val cacheTargetBytes: Long = CACHE_TARGET_BYTES,
    /**
     * The ceiling the LRU pass actually enforces: [cacheTargetBytes] plus a headroom margin.
     *
     * The headroom is deliberate. A budget enforced at exactly the advertised number makes the cache
     * feel like it is full the moment the user looks at the figure, and every subsequent download
     * evicts one immediately — the wall and the headline coincide, so the number reads as a lie by a
     * single download. Enforcing above the target means the cache settles *above* the stated size,
     * and the Downloads screen has visible room before anything is destroyed.
     *
     * Which of this and [cacheMaxFiles] binds is a property of the *content*, not of the caps: the
     * byte budget trips first whenever renditions are larger than the assumed mean (a 320 kbps track
     * is several MB, so in practice bytes bind and the row cap is the backstop for a cache of
     * unusually small tracks), and the row cap trips first when they are smaller. Both statements
     * stay true.
     *
     * Failure mode it prevents: a corrupt `bytes` column or a pathological provider filling the
     * user's disk, since the row cap alone cannot bound anything byte-sized.
     */
    val cacheMaxBytes: Long = cacheTargetBytes + cacheTargetBytes / CACHE_HEADROOM_DIVISOR,
    /**
     * Row cap for the cache, DERIVED from [cacheMaxBytes] at the assumed mean rendition size.
     *
     * What it bounds: how many tracks the Downloads screen can offer offline, and therefore how many
     * the LRU pass may destroy per new download. It is a *backstop*, not the budget — see
     * [cacheMaxBytes] for which of the two actually binds in practice and why.
     *
     * Derived rather than independent so the row cap and the byte cap can never disagree about how
     * full "full" is, which is the defect that made the old 2 GB figure unreachable.
     */
    val cacheMaxFiles: Int = (cacheMaxBytes / CACHE_MEAN_TRACK_BYTES).toInt(),
    /**
     * Share of the cache a favourite is guaranteed against the pinned sub-pool's own eviction.
     * Both pinned budgets come from it and from [cacheMaxBytes]: `pinnedMaxFraction` of the row cap
     * in rows, and the same fraction of the byte budget in bytes.
     *
     * The pin-demotion path is LIVE, not vestigial: the pinned byte budget trips well before the row
     * budget whenever renditions are multi-megabyte, so `demotePins` has work to do inside both caps.
     *
     * Failure mode it prevents: favourites accumulating an unbounded share of the cache, where the
     * LRU pass may never touch them because they are protected, so nothing else ever shrinks them.
     */
    val pinnedMaxFraction: Double = 0.75,
    val imageCacheBytes: Long = IMAGE_CACHE_MIB * BYTES_PER_MIB,
    val diskFloorBytes: Long = DISK_FLOOR_MIB * BYTES_PER_MIB,
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
    val homeCacheTtlMs: Long = HOME_CACHE_TTL_HOURS * MS_PER_HOUR,
    val albumCacheTtlMs: Long = ALBUM_CACHE_TTL_DAYS * MS_PER_DAY,
    val homeCacheRowCap: Int = 200,
    val imageMemoryCacheBytes: Long = IMAGE_MEMORY_MIB * BYTES_PER_MIB,
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

        /**
         * Nominal offline-audio target: 2 GiB.
         *
         * 2 GiB is roughly 500 six-megabyte 320 kbps renditions, or ~2000 at the assumed 1 MB mean,
         * which is a realistic ceiling for a phone music cache rather than an arbitrary one.
         */
        const val CACHE_TARGET_BYTES = 2L * 1024 * 1024 * 1024

        /**
         * Headroom divisor for the enforced ceiling: `target + target / 8`, i.e. +12.5%.
         *
         * Without it the wall and the advertised figure are the same number, so the cache reads as
         * full the instant the user looks at it and every later download evicts one.
         */
        const val CACHE_HEADROOM_DIVISOR = 8L

        /** 15 minutes: a snapshot older than this describes a paused track, not a playing one. */
        const val RESUME_MAX_AGE_MINUTES = 15

        /**
         * Unit arithmetic for the calendar-shaped values above ([homeCacheTtlMs], [albumCacheTtlMs]).
         * They are 6-hour and 7-day TTLs, not milliseconds that happen to look like them, so the
         * calendar part is spelled as a calendar part.
         */
        const val MS_PER_MINUTE = 60_000L
        const val MS_PER_HOUR = 60L * MS_PER_MINUTE
        const val MS_PER_DAY = 24L * MS_PER_HOUR

        /** TTLs, expressed in the unit they are written in rather than as pre-multiplied milliseconds. */
        const val HOME_CACHE_TTL_HOURS = 6L
        const val HOME_PREFETCH_INTERVAL_HOURS = 7L
        const val ALBUM_CACHE_TTL_DAYS = 7L

        /** Bytes in a mebibyte: the unit every on-disk budget in this config is quoted in. */
        const val BYTES_PER_MIB = 1024L * 1024L

        /** 150 MiB of artwork on disk ([imageCacheBytes]). */
        const val IMAGE_CACHE_MIB = 150L

        /** 500 MiB of free space a download refuses to go below ([diskFloorBytes]). */
        const val DISK_FLOOR_MIB = 500L

        /** 48 MiB of decoded bitmaps held in memory ([imageMemoryCacheBytes]). */
        const val IMAGE_MEMORY_MIB = 48L
    }
}
