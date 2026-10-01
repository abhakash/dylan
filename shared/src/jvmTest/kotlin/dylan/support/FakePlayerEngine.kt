package dylan.support

import dylan.playback.EngineErr
import dylan.playback.EngineEvent
import dylan.playback.LocalTrack
import dylan.playback.PlayerEngine
import dylan.playback.TransitionReason
import dylan.playback.clampPlaybackRate
import dylan.playback.clampSeekTargetMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong

/** How long after a script is queued the engine's looper publishes it. Mirrors a real looper hop. */
const val DEFAULT_EMIT_DELAY_MS: Long = 1L

/** `ExoPlayerEngine.pollRunnable` re-posts every 100 ms. */
const val DEFAULT_POLL_INTERVAL_MS: Long = 100L

/** No queued delay: the looper still hops (a real engine's events are never inline), just not on a timer. */
const val ZERO_EMIT_DELAY_MS: Long = 0L

/**
 * A [PlayerEngine] that behaves the way a real engine behaves, which the pre-wave inline fake did
 * not. Every rule below is transcribed from `ExoPlayerEngine`; the matching conformance rules
 * live in `EngineContractTest` so a real engine can be dropped into the same suite.
 *
 * What the old inline fake got wrong, and what each of those mistakes cost:
 *
 *  - `prepare()` emitted `Prepared` + `TrackChanged` **synchronously**. No real engine does that, and
 *    it hid every "event arrives before the caller is ready" bug. Events are now queued and published
 *    by a looper after [emitDelayMs].
 *  - `ItemEnded` for a 1-item window is impossible in reality (`if (idx > 0)`). Here it is never emitted
 *    without a successor, and a 1-item window ends in `QueueExhausted`.
 *  - `TransitionReason.SEEK` was never emitted, though `ExoPlayerEngine.kt:165` is its only producer. A
 *    cross-item [seekTo] now emits `TrackChanged(SEEK)` and no `ItemEnded`.
 *  - `positionFlow` was frozen at 0 with `play() = Unit`, so the 30 s "actually listened" heuristic and
 *    the 95 % tail prefetch were unreachable. Position advances while playing and freezes while paused.
 *  - `seekTo(ms)` was a no-op, so `Intent.Seek` had zero test references. Every seek is now recorded,
 *    clamped and observable.
 *  - `currentTimeMs()` was not overridden, so `enginePositionMs()` always took the `lastPosMs`
 *    fallback. It is overridden, so the production branch is the one under test.
 *  - `release()` was a no-op that nothing called (audit blocker AN-1). It is idempotent and observable.
 *  - `setRate` was absent, so nothing could observe what a rate does to the media clock. It is now
 *    recorded, clamped ([rates]) and *applied*: the poll advances the position by
 *    `pollIntervalMs × rate`, exactly as a rate-changed AVPlayer/ExoPlayer does.

 * The engine owns a **looper**: a single-permit dispatcher that both publishes events and drives
 * the position poll, exactly as `HandlerThread("dylan-media")` does. Supply a virtual one
 * ([virtual]) to make the 10 Hz poll and long timeouts free.
 */
class FakePlayerEngine internal constructor(
    private val scope: CoroutineScope,
    private val looper: CoroutineContext,
    private val clock: TestCoroutineScheduler?,
    /** Delay between a queued event and the looper publishing it. `<= 0` means a bare yield. */
    val emitDelayMs: Long = DEFAULT_EMIT_DELAY_MS,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : PlayerEngine {
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<EngineEvent> = mutableEvents

    private val mutablePosition = MutableStateFlow(0L)
    override val positionFlow: Flow<Long> = mutablePosition

    private val windowHistory = MutableStateFlow<List<List<LocalTrack>>>(emptyList())
    private val upNextFlow = MutableStateFlow<LocalTrack?>(null)

    // ── observable surface ────────────────────────────────────────────────────────────────
    val preparedWindows: MutableList<List<LocalTrack>> = mutableListOf()
    val upNextHistory: MutableList<LocalTrack?> = mutableListOf()
    val seeks: MutableList<Long> = mutableListOf()

    /** Rates [setRate] actually accepted, already clamped. A dropped NaN/±Inf is not recorded. */
    val rates: MutableList<Float> = mutableListOf()
    private val _emitted = CopyOnWriteArrayList<EngineEvent>()

    val emitted: List<EngineEvent> get() = _emitted.toList()

    var preparedCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    /** How many times the 10 Hz poll ran — the unit of work the position contract is about. */
    var ticks: Int = 0
        private set
    var playCount: Int = 0
        private set
    var pauseCount: Int = 0
        private set
    var isReleased: Boolean = false
        private set

    /**
     * When true (the default, and the old fake's only mode) `prepare()` queues
     * `Prepared` + `TrackChanged(EXPLICIT)` for its own first item. Set false to drive the event
     * sequence by hand — which is how the "engine and state machine disagree" paths get tested.
     */
    @Volatile
    var autoEmit: Boolean = true

    private val script = Channel<EngineEvent>(Channel.UNLIMITED)
    private val queued = mutableListOf<EngineEvent>()

    // ── engine state ──────────────────────────────────────────────────────────────────────
    private var window: List<LocalTrack> = emptyList()
    private var currentIndex: Int = 0
    private var playing: Boolean = false
    private var positionMs: Long = 0L
    private var rate: Float = 1.0f
    private var pollJob: Job? = null
    private var pumpJob: Job? = null

    init {
        pumpJob =
            scope.launch(looper) {
                for (e in script) {
                    if (emitDelayMs > 0) delay(emitDelayMs) else yield()
                    publish(e)
                }
            }
    }

    // ── PlayerEngine ──────────────────────────────────────────────────────────────────────

    override fun prepare(window: List<LocalTrack>) {
        if (isReleased || window.isEmpty()) return
        preparedWindows += window
        windowHistory.value = windowHistory.value + listOf(window)
        this.window = window
        currentIndex = 0
        positionMs = 0L
        mutablePosition.value = 0L
        preparedCount++
        if (autoEmit) {
            script(EngineEvent.Prepared(window.first().itemId))
            script(EngineEvent.TrackChanged(window.first().itemId, TransitionReason.EXPLICIT))
        }
    }

    /** Mirrors `setMediaItems` + `replaceMediaItem(1, …)`: slot 0 is never touched. */
    override fun replaceUpNext(track: LocalTrack?) {
        if (isReleased) return
        upNextHistory += track
        upNextFlow.value = track
        if (track == null) {
            if (window.size >= 2) window = listOf(window.first())
        } else {
            window =
                when {
                    // ExoPlayer's addMediaItem(1, ·) on an empty player appends at 0.
                    window.isEmpty() -> listOf(track)
                    window.size >= 2 -> listOf(window[0], track) + window.drop(2)
                    else -> listOf(window.first(), track)
                }
        }
    }

    override fun play() {
        if (isReleased || window.isEmpty()) return
        playing = true
        playCount++
        startPolling()
    }

    override fun pause() {
        if (isReleased) return
        playing = false
        pauseCount++
        // pollRunnable publishes the current position once more, then stops re-posting.
        mutablePosition.value = positionMs
        pollJob = null
    }

    /**
     * Recorded, clamped to the current item, and applied. Seeking *into* a successor item emits
     * `TrackChanged(SEEK)` with **no** `ItemEnded`, which is the only way a real engine produces
     * `TransitionReason.SEEK` (`ExoPlayerEngine.kt:165`, `MEDIA_ITEM_TRANSITION_REASON_SEEK`).
     */
    override fun seekTo(ms: Long) {
        if (isReleased) return
        seeks += ms
        val idx = currentIndex.coerceIn(0, maxOf(window.size - 1, 0))
        val dur = window.getOrNull(idx)?.durationHintMs
        if (dur == null || window.size <= 1 || ms < dur) {
            positionMs = (if (dur == null) ms else ms.coerceIn(0, dur))
            mutablePosition.value = positionMs
            return
        }
        currentIndex = idx + 1
        positionMs = ms - dur
        mutablePosition.value = positionMs
        script(EngineEvent.TrackChanged(window[currentIndex].itemId, TransitionReason.SEEK))
    }

    override fun currentTimeMs(): Long = if (isReleased) -1L else positionMs

    /**
     * Recorded, clamped, and **applied** — the rate multiplies how fast the media clock moves, which
     * is the only way a seam-only contract rule can observe a rate at all. It never touches [playing]
     * and never restarts the poll, so `setRate` on a paused engine stays paused: the AVPlayer
     * implicit-play trap has a rule of its own (`aRateSetWhilePausedDoesNotResumePlayback`).
     */
    override fun setRate(rate: Float) {
        if (isReleased) return
        val applied = clampPlaybackRate(rate) ?: return
        this.rate = applied
        rates += applied
    }

    /**
     * Relative, from [currentTimeMs] — the engine's own clock, not a caller's cached position — and
     * clamped against the *item's* duration rather than the seam's [durationMs] default, mirroring
     * `ExoPlayerEngine.skipBy` reading `player.duration`. A null/absent `durationHintMs` is unknown,
     * so the seek is still performed (see `clampSeekTargetMs`); it then routes through [seekTo], so a
     * cross-item skip emits `TrackChanged(SEEK)` just as a cross-item seek does.
     */
    override fun skipBy(deltaMs: Long) {
        if (isReleased) return
        val base = currentTimeMs()
        if (base < 0L) return
        seekTo(clampSeekTargetMs(base + deltaMs, currentTrack()?.durationHintMs ?: -1L))
    }

    override fun release() {
        releaseCount++
        if (isReleased) return
        isReleased = true
        playing = false
        pollJob = null
        script.close()
    }

    // ── test control ──────────────────────────────────────────────────────────────────────

    /** Queue events for the looper. They are published after [emitDelayMs], never inline. */
    fun script(vararg events: EngineEvent) {
        queued += events
        events.forEach { script.trySend(it) }
    }

    fun script(events: List<EngineEvent>) {
        queued += events
        events.forEach { script.trySend(it) }
    }

    /** Events queued but not yet published. */
    val scripted: List<EngineEvent> get() = queued.toList()

    fun drainEmitted(): List<EngineEvent> = _emitted.toList().also { _emitted.clear() }

    /** Latest position the poll has published — what a UI would read. */
    fun publishedPosition(): Long = mutablePosition.value

    /** Latest value handed to [replaceUpNext]; awaitable instead of polled. */
    val upNext: StateFlow<LocalTrack?> get() = upNextFlow

    /** Suspend until some prepared window has contained an itemId starting with [prefix]. */
    suspend fun awaitWindow(matches: (itemId: String) -> Boolean): List<LocalTrack> =
        windowHistory
            .first { ws -> ws.any { w -> w.any { matches(it.itemId) } } }
            .first { w -> w.any { matches(it.itemId) } }

    fun currentTrack(): LocalTrack? = window.getOrNull(currentIndex)

    fun currentIndex(): Int = currentIndex

    fun windowSize(): Int = window.size

    fun isPlaying(): Boolean = playing

    fun position(): Long = positionMs

    /** The rate the engine is currently playing at — 1.0 until [setRate] says otherwise. */
    fun currentRate(): Float = rate

    /** Drive [block] against a bare engine whose looper is virtual, then tear it down. */
    fun advanceVirtualTime(ms: Long) {
        val c = clock ?: error("this engine has no virtual clock; construct it with FakePlayerEngine.virtual { }")
        c.advanceTimeBy(ms)
    }

    fun runCurrent() {
        clock?.runCurrent()
    }

    fun advanceUntilIdle() {
        clock?.advanceUntilIdle()
    }

    /**
     * Natural end of the current item. Mirrors `STATE_ENDED`: with a successor the engine
     * reports `ItemEnded(previous)` then `TrackChanged(next, AUTO)`; with none — the 1-item window
     * `ExoPlayer` makes impossible to mis-report — it reports `QueueExhausted` only.
     */
    fun endOfItem() {
        if (isReleased || window.isEmpty()) return
        val idx = currentIndex
        val ended = window[idx]
        val next = window.getOrNull(idx + 1)
        if (next == null) {
            positionMs = ended.durationHintMs ?: positionMs
            mutablePosition.value = positionMs
            playing = false
            pollJob = null
            script(EngineEvent.QueueExhausted)
            return
        }
        currentIndex = idx + 1
        positionMs = 0L
        mutablePosition.value = 0L
        script(EngineEvent.ItemEnded(ended.itemId))
        script(EngineEvent.TrackChanged(next.itemId, TransitionReason.AUTO))
    }

    fun fail(
        kind: EngineErr,
        itemId: String? = currentTrack()?.itemId,
    ) {
        if (isReleased) return
        script(EngineEvent.Error(itemId, kind))
    }

    private fun publish(e: EngineEvent) {
        if (isReleased) return
        _emitted += e
        mutableEvents.tryEmit(e)
    }

    /** `ExoPlayerEngine.pollRunnable`: republish while playing, publish once then stop. */
    private fun startPolling() {
        pollJob?.cancel()
        if (!playing || isReleased) return
        pollJob =
            scope.launch(looper) {
                while (isActive) {
                    delay(pollIntervalMs)
                    ticks++
                    if (!isActive || !playing) break
                    val dur = window.getOrNull(currentIndex)?.durationHintMs
                    // The media clock, not the wall clock: a 2x player covers 2x the ground per tick.
                    val step = (pollIntervalMs * rate).roundToLong().coerceAtLeast(1L)
                    val next = positionMs + step
                    if (dur != null && next >= dur) {
                        positionMs = dur
                        mutablePosition.value = positionMs
                        endOfItem()
                    } else {
                        positionMs = next
                        mutablePosition.value = positionMs
                    }
                }
            }
    }

    /** Set an absolute position, as if playback had reached it. */
    fun setPositionMs(ms: Long) {
        positionMs = ms
        mutablePosition.value = ms
    }

    /**
     * Advance the engine's media clock and publish, without waiting for a poll tick. Ticking itself
     * — including that it stops when paused — is verified by [EngineContractTest]; this exists for
     * graph tests that need a position past a threshold (e.g. `Intent.Previous`'s 3 s restart) and
     * must not burn three real seconds reaching it.
     */
    fun advancePlaybackClock(ms: Long) {
        positionMs += ms
        mutablePosition.value = positionMs
    }

    fun shutdown() {
        isReleased = true
        pollJob = null
        pumpJob = null
        script.close()
    }

    companion object {
        /**
         * Engine on a virtual looper. Everything the engine schedules — event publication, the
         * 10 Hz position poll, item auto-advance — becomes virtual, so a three-minute track costs
         * virtual milliseconds. Must be called from inside a `runTest` (or otherwise from something
         * that drives the scheduler), and the engine's scope must be cancellable.
         */
        fun virtual(
            scope: CoroutineScope,
            emitDelayMs: Long = DEFAULT_EMIT_DELAY_MS,
            pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        ): FakePlayerEngine {
            val scheduler = scope.coroutineContext[TestCoroutineScheduler]
            return FakePlayerEngine(
                scope = scope,
                looper = StandardTestDispatcher(scheduler ?: TestCoroutineScheduler(), "media"),
                clock = scheduler,
                emitDelayMs = emitDelayMs,
                pollIntervalMs = pollIntervalMs,
            )
        }

        /** Engine on a real single-permit looper, for graphs driven outside a `runTest`. */
        fun onRealLooper(
            scope: CoroutineScope,
            emitDelayMs: Long = ZERO_EMIT_DELAY_MS,
            pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        ): FakePlayerEngine =
            FakePlayerEngine(
                scope = scope,
                looper =
                    kotlinx.coroutines.Dispatchers.Default
                        .limitedParallelism(1, "media"),
                clock = null,
                emitDelayMs = emitDelayMs,
                pollIntervalMs = pollIntervalMs,
            )

        /** Standalone virtual engine for the contract suite: scope, engine and teardown in one call. */
        fun soloTest(
            emitDelayMs: Long = DEFAULT_EMIT_DELAY_MS,
            pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
            block: (FakePlayerEngine) -> Unit,
        ) {
            val scheduler = TestCoroutineScheduler()
            val scope = TestScope(StandardTestDispatcher(scheduler, "media") + scheduler)
            val engine =
                FakePlayerEngine(
                    scope = scope,
                    looper = StandardTestDispatcher(scheduler, "media"),
                    clock = scheduler,
                    emitDelayMs = emitDelayMs,
                    pollIntervalMs = pollIntervalMs,
                )
            try {
                block(engine)
            } finally {
                engine.release()
                scope.cancel()
            }
        }
    }
}
