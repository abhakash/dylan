package dylan.support

import dylan.util.Clock

/**
 * Deterministic wall clock for tests. Every window that used to need `File.setLastModified`
 * against the real clock (the reconciler's 60 s orphan sweep and 1 h part grace, cache TTLs,
 * nav debounce) becomes a plain [advanceMs] away.
 *
 * Note the class only moves when a test moves it: nothing in the production graph holds one of
 * these, so a real time source can never be substituted by accident.
 */
class MutableClock(
    start: Long = 1_756_000_000_000L,
) : Clock {
    @Volatile
    private var t: Long = start

    override fun nowMs(): Long = t

    fun advanceMs(ms: Long) {
        t += ms
    }

    fun advanceSeconds(s: Long) = advanceMs(s * 1_000L)

    fun advanceHours(h: Long) = advanceMs(h * 3_600_000L)

    fun set(ms: Long) {
        t = ms
    }
}
