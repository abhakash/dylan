package dylan.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

data class LocalTrack(
    val itemId: String,
    val path: String,
    val durationHintMs: Long?,
    val title: String? = null,
    val artist: String? = null,
    val artworkUri: String? = null,
)

enum class TransitionReason { AUTO, SEEK, EXPLICIT }

sealed interface EngineEvent {
    data class Prepared(
        val itemId: String,
    ) : EngineEvent

    data class TrackChanged(
        val itemId: String,
        val reason: TransitionReason,
    ) : EngineEvent

    data class ItemEnded(
        val itemId: String,
    ) : EngineEvent

    data object QueueExhausted : EngineEvent

    data class Error(
        val itemId: String?,
        val kind: EngineErr,
    ) : EngineEvent

    data object RouteLost : EngineEvent

    data class Interrupted(
        val shouldResume: Boolean,
    ) : EngineEvent
}

enum class EngineErr { DECODE, SOURCE, SESSION_ACTIVATION }

/** Slowest rate the seam accepts. Below this a track is unintelligible rather than "slower". */
const val MIN_PLAYBACK_RATE: Float = 0.5f

/** Fastest rate the seam accepts. Past ~2x the artefacts dominate the time saving. */
const val MAX_PLAYBACK_RATE: Float = 2.0f

/**
 * Clamp an absolute seek target to `[0, durationMs]` — but only when the duration is *known*.
 *
 * `durationMs <= 0` means UNKNOWN, not zero-length. Both ends of the seam answer -1 for "I cannot
 * tell you" and the catalog's documented fallback for an unparseable duration is 0, so clamping a
 * seek against either turns every seek on such a track into a permanent `seekTo(0)` — the request
 * is swallowed, not clamped. An unknown duration therefore passes the target through untouched;
 * the engine's own decoder is the only thing that can know the real end.
 */
fun clampSeekTargetMs(
    targetMs: Long,
    durationMs: Long,
): Long {
    val upper = durationMs.takeIf { it > 0L } ?: return targetMs
    return targetMs.coerceIn(0L, upper)
}

/**
 * Clamp a requested rate into the range the seam supports, rejecting non-finite input.
 *
 * NaN survives every comparison, so `coerceIn` alone hands NaN straight through and an engine that
 * multiplies its clock by it never advances again. `setRate` is therefore *ignore* on NaN/±Inf and
 * *clamp* on finite out-of-range values.
 */
fun clampPlaybackRate(
    rate: Float,
): Float? {
    if (!rate.isFinite()) return null
    return rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
}

interface PlayerEngine {
    fun prepare(window: List<LocalTrack>)

    fun replaceUpNext(track: LocalTrack?)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    val events: SharedFlow<EngineEvent>
    val positionFlow: Flow<Long>

    /** Synchronous engine-known position; -1 when the engine cannot answer inline. */
    fun currentTimeMs(): Long = -1L

    /**
     * Real media duration of the current item in ms, or -1 when the engine cannot answer inline.
     * The orchestrator clamps a seek against this rather than against the catalog's duration, whose
     * documented fallback for an unparseable value is 0.
     */
    fun durationMs(): Long = -1L

    /**
     * Playback speed multiplier: 1.0 is normal, [MIN_PLAYBACK_RATE] half speed, [MAX_PLAYBACK_RATE]
     * double. Input is normalised by [clampPlaybackRate] — finite out-of-range values are clamped,
     * non-finite ones are dropped, so no implementor has to defend against NaN itself.
     *
     * A rate is a property of the transport, not a play request: it MUST NOT start playback. An
     * engine that is paused stays paused and applies the rate the next time it is asked to play.
     * (`AVPlayer.rate` breaks exactly this rule — assigning a positive rate to a paused player
     * begins playback — so the iOS output assigns it only while already playing.)
     *
     * The default is a no-op, so an engine with no rate control keeps playing at 1.0 and existing
     * implementors still compile.
     */
    @Suppress("EmptyFunctionBlock")
    fun setRate(rate: Float) {
        // No-op by design: see the KDoc. Override to make the rate observable.
    }

    /** Relative seek: [ms] milliseconds forward from wherever the engine actually is. */
    fun skipForward(ms: Long) {
        skipBy(ms.coerceAtLeast(0L))
    }

    /** Relative seek: [ms] milliseconds backward from wherever the engine actually is. */
    fun skipBackward(ms: Long) {
        skipBy(-ms.coerceAtLeast(0L))
    }

    /**
     * The shared body of [skipForward] / [skipBackward], and the reason they are relative:
     * [currentTimeMs] is the base, never a caller's cached position. The orchestrator's `posMs`
     * is a 10 Hz sample of this same clock, so a skip computed from it is already up to 100 ms
     * stale — and on a rate-changed transport the two diverge by the rate, not by the sample lag.
     *
     * An engine that cannot answer [currentTimeMs] has no base to be relative to, so the request
     * is dropped rather than guessed at 0 (a guessed 0 is a seek to the start, not a skip).
     */
    fun skipBy(deltaMs: Long) {
        val base = currentTimeMs()
        if (base < 0L) return
        seekTo(clampSeekTargetMs(base + deltaMs, durationMs()))
    }

    fun release()
}

interface NativeAudioOutput {
    fun prepare(items: List<LocalTrack>)

    fun replaceUpNext(item: LocalTrack?)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    /**
     * Playback speed multiplier; see [PlayerEngine.setRate]. Same rule on this side of the bridge:
     * the Swift impl must not let a rate assignment start a paused player.
     */
    fun setRate(rate: Float)

    /** Relative seek forward by [ms] from the output's own current position. */
    fun skipForward(ms: Long)

    /** Relative seek backward by [ms] from the output's own current position. */
    fun skipBackward(ms: Long)

    fun currentTimeMs(): Long

    fun bindEvents(sink: EngineEventSink)

    fun dispose()
}

/** Implemented by the Kotlin engine; Swift's output impl pushes AVFoundation events through it. */
interface EngineEventSink {
    fun onEvent(e: EngineEvent)
}
