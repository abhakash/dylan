@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.TimeSource

enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

private const val DEFAULT_THRESHOLD = 3
private const val DEFAULT_BASE_COOLDOWN_MS = 2_000L
private const val DEFAULT_MAX_COOLDOWN_MS = 120_000L
private const val DEFAULT_MAX_HOSTS = 32
private const val COOLDOWN_DOUBLINGS = 10

/** A half-open probe that never settles would wedge a host open forever; this is the backstop. */
private const val PROBE_LEASE_MS = 30_000L

/**
 * A real per-host breaker: failure counting, a threshold, a half-open single probe, exponential
 * backoff, and a deadline measured on the caller's **monotonic** clock.
 *
 * What it replaced was a latch: no counter, no threshold, no states, and a `pauseUntil` that
 * early-returned whenever the new deadline was not later than the current one — so the longest
 * `Retry-After` ever seen on a host stuck for the life of the process. Nothing wrote to it except
 * the 429/503 branch, so 5xx, connection failures and stalls — the actual "this host is
 * unhealthy" signals — were never counted.
 *
 * Every transition goes through one atomic snapshot, so a reader can never observe a half-updated
 * breaker.
 *
 * @param jitter applied to the computed cooldown. Deterministic by construction: the same host and
 *   the same attempt always produce the same wait, so the pipeline contains no unseeded randomness
 *   and a test can assert an exact deadline. Inject `{ it }` to assert exactly.
 */
class Breaker(
    private val failureThreshold: Int = DEFAULT_THRESHOLD,
    private val baseCooldownMs: Long = DEFAULT_BASE_COOLDOWN_MS,
    private val maxCooldownMs: Long = DEFAULT_MAX_COOLDOWN_MS,
    private val jitter: (Long) -> Long = { it },
) {
    private val snap = AtomicReference(Snapshot())

    data class View(
        val state: BreakerState,
        val openUntilMs: Long,
        val failures: Int,
        val cooldownMs: Long,
    )

    private data class Snapshot(
        val state: BreakerState = BreakerState.CLOSED,
        val failures: Int = 0,
        val openUntilMs: Long = 0L,
        val cooldownMs: Long = 0L,
        val opens: Int = 0,
    )

    fun view(): View {
        val s = snap.load()
        return View(s.state, s.openUntilMs, s.failures, s.cooldownMs)
    }

    val state: BreakerState get() = snap.load().state

    /**
     * Admission for one request. CLOSED admits everybody; OPEN refuses until the deadline passes;
     * HALF_OPEN admits **exactly one** caller, which is the entire point of the state.
     */
    fun tryAcquire(nowMs: Long): Boolean {
        while (true) {
            val s = snap.load()
            when (s.state) {
                BreakerState.CLOSED -> return true
                BreakerState.HALF_OPEN -> return false
                BreakerState.OPEN -> {
                    if (nowMs < s.openUntilMs) return false
                    if (nowMs < s.openUntilMs + PROBE_LEASE_MS) return false
                    if (snap.compareAndSet(s, s.copy(state = BreakerState.HALF_OPEN))) return true
                }
            }
        }
    }

    /** The admitted caller ended without reporting an outcome (cancelled, preempted, stalled). */
    fun release() {
        while (true) {
            val s = snap.load()
            if (s.state != BreakerState.HALF_OPEN) return
            if (snap.compareAndSet(s, s.copy(state = BreakerState.OPEN))) return
        }
    }

    fun onSuccess() {
        while (true) {
            val s = snap.load()
            if (s.state == BreakerState.CLOSED && s.failures == 0) return
            val next =
                if (s.state == BreakerState.CLOSED) {
                    s.copy(failures = 0)
                } else {
                    s.copy(state = BreakerState.CLOSED, failures = 0, cooldownMs = 0L, opens = 0, openUntilMs = 0L)
                }
            if (snap.compareAndSet(s, next)) return
        }
    }

    /**
     * One unhealthy exchange — a 5xx, a connection failure, a stall. On reaching the threshold the
     * breaker opens, doubling the cooldown per consecutive open and capping it.
     */
    fun onFailure(nowMs: Long) = trip(nowMs, retryAfterMs = null, counted = true)

    /**
     * A 429/503: opens immediately, because a rate limit is a fact about the account rather than a
     * judgement about the host's health. `Retry-After` is honoured — but only for *this* window,
     * since the deadline is always `now + wait`.
     */
    fun onRateLimited(
        nowMs: Long,
        retryAfterMs: Long?,
    ) = trip(nowMs, retryAfterMs, counted = false)

    private fun trip(
        nowMs: Long,
        retryAfterMs: Long?,
        counted: Boolean,
    ) {
        while (true) {
            val s = snap.load()
            val failures = if (counted) s.failures + 1 else s.failures
            if (s.state == BreakerState.CLOSED && failures < failureThreshold) {
                if (snap.compareAndSet(s, s.copy(failures = failures))) return
                continue
            }
            val cooldown = nextCooldown(s)
            val wait = maxOf(jitter(cooldown), retryAfterMs ?: 0L).coerceIn(0L, maxCooldownMs)
            val next =
                s.copy(
                    state = BreakerState.OPEN,
                    failures = failures,
                    cooldownMs = cooldown,
                    opens = s.opens + 1,
                    openUntilMs = nowMs + wait,
                )
            if (snap.compareAndSet(s, next)) return
        }
    }

    private fun nextCooldown(s: Snapshot): Long {
        val level = baseCooldownMs shl s.opens.coerceAtMost(COOLDOWN_DOUBLINGS)
        return level.coerceAtMost(maxCooldownMs)
    }
}

/**
 * Per-host breakers over one **monotonic** timeline. The wall clock is deliberately not used for a
 * block deadline: an NTP correction must not be able to extend or collapse a backoff.
 *
 * The host map is bounded at [maxHosts]. It used to grow once per distinct host for the life of
 * the process with no eviction at all.
 */
class Breakers(
    private val maxHosts: Int = DEFAULT_MAX_HOSTS,
    private val failureThreshold: Int = DEFAULT_THRESHOLD,
    private val baseCooldownMs: Long = DEFAULT_BASE_COOLDOWN_MS,
    private val maxCooldownMs: Long = DEFAULT_MAX_COOLDOWN_MS,
    private val jitter: (Long) -> Long = { it },
) {
    private val epoch = TimeSource.Monotonic.markNow()
    private val map = AtomicReference<Map<String, Breaker>>(emptyMap())

    /** Monotonic milliseconds since this registry was built: the only "now" a breaker ever sees. */
    fun nowMs(): Long = epoch.elapsedNow().inWholeMilliseconds

    fun forHost(host: String): Breaker {
        map.load()[host]?.let { return it }
        while (true) {
            val cur = map.load()
            cur[host]?.let { return it }
            val fresh = Breaker(failureThreshold, baseCooldownMs, maxCooldownMs, jitter)
            val grown = if (cur.size >= maxHosts) evictOne(cur) + (host to fresh) else cur + (host to fresh)
            if (map.compareAndSet(cur, grown)) return fresh
        }
    }

    fun size(): Int = map.load().size

    fun stateOf(host: String): BreakerState = map.load()[host]?.state ?: BreakerState.CLOSED

    /**
     * Drop the host whose loss costs least: never an OPEN breaker if a healthy one exists, and
     * among equals the one with the fewest recorded failures, ties broken by name so eviction is
     * deterministic. If every host is blocked, drop the one that unblocks last.
     */
    private fun evictOne(cur: Map<String, Breaker>): Map<String, Breaker> {
        val healthy =
            cur.entries
                .filter { it.value.view().state != BreakerState.OPEN }
                .minWithOrNull(compareBy({ it.value.view().failures }, { it.key }))
        val victim = healthy ?: cur.entries.minByOrNull { it.value.view().openUntilMs }
        return if (victim == null) cur else cur - victim.key
    }
}
