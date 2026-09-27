package dylan.util

/**
 * Wall-clock seam. Every comparison of "now" against a *stored* timestamp (LRU ordering, the
 * reconciler's grace windows, cache TTLs, download intents) reads a [Clock] so those windows
 * are testable without `File.setLastModified` racing the real clock.
 *
 * Durations still use `kotlin.time.TimeSource.Monotonic` inline: a wall-clock correction must
 * never reorder a measured elapsed time, and a forward jump must not make every `.part` look
 * older than the grace window.
 */
interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = platformNowMs()
}

internal expect fun platformNowMs(): Long
