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

interface PlayerEngine {
    fun prepare(window: List<LocalTrack>)

    fun replaceUpNext(track: LocalTrack?)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    val events: SharedFlow<EngineEvent>
    val positionFlow: Flow<Long>

    fun release()
}

interface NativeAudioOutput {
    fun prepare(items: List<LocalTrack>)

    fun replaceUpNext(item: LocalTrack?)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    fun currentTimeMs(): Long

    fun bindEvents(sink: EngineEventSink)

    fun release()
}

/** Implemented by the Kotlin engine; Swift's output impl pushes AVFoundation events through it. */
interface EngineEventSink {
    fun onEvent(e: EngineEvent)
}
