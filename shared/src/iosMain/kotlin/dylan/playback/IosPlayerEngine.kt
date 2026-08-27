package dylan.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Kotlin-owned reactive surface over the Swift [NativeAudioOutput] (§9.4/R1-B.2).
 * The engine owns events + position; Swift stays imperative only.
 *
 * Event wiring: the Swift output impl receives AVFoundation notifications and pushes them
 * through [EngineEventSink.onEvent] — this class registers itself via `out.bindEvents(this)`
 * in init, avoiding any chicken-and-egg on the Swift side.
 */
class IosPlayerEngine(
    private val out: NativeAudioOutput,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val log: dylan.diag.LogBuffer = dylan.diag.LogBuffer.SILENT,
) : PlayerEngine,
    EngineEventSink {
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<EngineEvent> = mutableEvents

    private val mutablePosition = MutableStateFlow(0L)
    override val positionFlow: StateFlow<Long> = mutablePosition

    override fun currentTimeMs(): Long = mutablePosition.value

    private var pollJob: Job? = null
    private var released = false

    init {
        out.bindEvents(this)
    }

    override fun prepare(window: List<LocalTrack>) {
        out.prepare(window)
        stopPolling()
        mutablePosition.value = 0L
    }

    override fun replaceUpNext(track: LocalTrack?) {
        out.replaceUpNext(track)
    }

    override fun play() {
        out.play()
        startPolling()
    }

    override fun pause() {
        out.pause()
        stopPolling()
    }

    override fun seekTo(ms: Long) {
        out.seekTo(ms)
        mutablePosition.value = ms.coerceAtLeast(0L)
    }

    override fun release() {
        if (released) return
        released = true
        stopPolling()
        out.dispose()
    }

    override fun onEvent(e: EngineEvent) {
        when (e) {
            is EngineEvent.Prepared, is EngineEvent.TrackChanged -> mutablePosition.value = 0L
            is EngineEvent.Interrupted ->
                if (e.shouldResume) startPolling() else stopPolling()
            is EngineEvent.RouteLost, EngineEvent.QueueExhausted -> stopPolling()
            else -> {}
        }
        if (!mutableEvents.tryEmit(e)) log.w("ios", "events buffer full, dropped $e")
    }

    /**
     * Position truth (C14): a rate-gated sampler at 10 Hz while playing. Plan prefers
     * addPeriodicTimeObserver; the seam carries no position push, so polling currentTimeMs
     * from Main is the equivalent — flagged in PROGRESS confidence notes.
     */
    private fun startPolling() {
        if (pollJob != null || released) return
        pollJob =
            scope.launch(main) {
                while (isActive && !released) {
                    mutablePosition.value = out.currentTimeMs().coerceAtLeast(0L)
                    delay(100)
                }
            }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
