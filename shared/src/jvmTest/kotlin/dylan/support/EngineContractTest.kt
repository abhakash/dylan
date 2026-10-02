package dylan.support

import dylan.playback.EngineEvent
import dylan.playback.LocalTrack
import dylan.playback.MAX_PLAYBACK_RATE
import dylan.playback.MIN_PLAYBACK_RATE
import dylan.playback.PlayerEngine
import dylan.playback.TransitionReason
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Conformance suite for [PlayerEngine]. Abstract on purpose: [FakeEngineContractTest] runs it
 * against [FakePlayerEngine] today, and when the real `ExoPlayerEngine` gets a Robolectric lane
 * (and `AVPlayerOutput` an integration lane) they drop into the *same* class. "The fake and the
 * real engine agree" then stops being a comment in a test file and becomes a compile-time
 * obligation.
 *
 * Every rule is transcribed from `ExoPlayerEngine`. Where a rule is really an assumption the
 * `Orchestrator` makes of *any* engine, it is stated in the orchestrator's terms — that is the
 * contract the app can actually rely on.
 */
abstract class EngineContractTest {
    /** Fresh engine per test method: engines are stateful, and one shared leaks state between rules. */
    protected abstract fun <T> withEngine(body: (EngineHarness) -> T): T

    /**
     * Observation + control surface. Deliberately small: a real-engine wrapper implements it, and
     * an engine that cannot is not testable and should not be trusted.
     */
    class EngineHarness(
        val engine: PlayerEngine,
        private val doPlay: () -> Unit,
        private val doAdvance: (Long) -> Unit,
        private val doPublish: (EngineEvent) -> Unit,
        private val doSettle: () -> Unit,
        private val doCurrentItemId: () -> String?,
    ) {
        private val seen = mutableListOf<EngineEvent>()
        private val lastPosition = MutableStateFlow(0L)

        internal fun record(e: EngineEvent) {
            seen += e
        }

        internal fun recordPosition(ms: Long) {
            lastPosition.value = ms
        }

        /** Every event since the harness attached, in emission order. */
        fun all(): List<EngineEvent> = seen.toList()

        /** Events since the previous [drain]. */
        fun drain(): List<EngineEvent> = seen.toList().also { seen.clear() }

        fun position(): Long = lastPosition.value

        /** ItemId the engine most recently *reported* as current (its event stream's view). */
        fun lastReportedItemId(): String? =
            seen
                .asReversed()
                .firstNotNullOfOrNull {
                    (it as? EngineEvent.TrackChanged)?.itemId ?: (it as? EngineEvent.Prepared)?.itemId
                }

        /** ItemId the engine itself considers current, independent of what it reported. */
        fun engineCurrentItemId(): String? = doCurrentItemId()

        fun window(
            vararg itemIds: String,
            durationMs: Long? = TRACK_MS,
        ): List<LocalTrack> =
            itemIds.map {
                LocalTrack(
                    itemId = it,
                    path = "/tmp/$it.m4a",
                    durationHintMs = durationMs,
                    title = it,
                )
            }

        /** Request play and let the engine register it. */
        fun play() = doPlay()

        /** Advance *playback* time by [ms]. Must not burn wall time. */
        fun advancePlaybackTime(ms: Long) = doAdvance(ms)

        /** Have the engine report [e], the way a real looper would. */
        fun publish(vararg events: EngineEvent) {
            events.forEach(doPublish)
        }

        /** Let everything the engine has queued reach its event stream. */
        fun settle() = doSettle()
    }

    @Test
    fun exactlyOnePreparedPerPrepare() =
        withEngine { c ->
            val w = c.window("a", "b")
            c.engine.prepare(w)
            c.settle()
            assertEquals(
                1,
                c.drain().count { it is EngineEvent.Prepared },
                "ExoPlayer fires onPlaybackStateChanged(READY) once per prepare; a second Prepared for " +
                    "one window is the same generation resolved twice",
            )
            c.engine.prepare(w)
            c.settle()
            assertEquals(1, c.drain().count { it is EngineEvent.Prepared }, "one per prepare, not one per call")
        }

    @Test
    fun aOneItemWindowEndsInQueueExhaustedAndNeverItemEnded() =
        withEngine { c ->
            // `if (idx > 0)` in ExoPlayerEngine.onMediaItemTransition: a single media item has no
            // predecessor, so ItemEnded is structurally impossible. A fake that emits it teaches
            // Orchestrator's ItemEnded 2 s fallback to fire on a queue that has not ended.
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(TRACK_MS)
            c.settle()
            val after = c.drain()
            assertTrue(after.none { it is EngineEvent.ItemEnded }, "1-item window has no predecessor: $after")
            assertTrue(
                after.any { it is EngineEvent.QueueExhausted },
                "ExoPlayer reports STATE_ENDED ⇒ QueueExhausted for a 1-item window: $after",
            )
        }

    @Test
    fun itemEndedIsEmittedOnlyWhenASuccessorExists() =
        withEngine { c ->
            c.engine.prepare(c.window("a", "b"))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(TRACK_MS)
            c.settle()
            val after = c.drain()
            val ended = after.filterIsInstance<EngineEvent.ItemEnded>()
            val changed = after.filterIsInstance<EngineEvent.TrackChanged>()
            assertEquals(listOf("a"), ended.map { it.itemId }, "the predecessor is the item that ended")
            assertEquals(1, changed.size, "one transition per roll: $after")
            assertEquals(TransitionReason.AUTO, changed.single().reason, "a natural roll is AUTO")
            assertEquals("b", changed.single().itemId)
        }

    @Test
    fun aSeekAcrossItemsIsDistinguishableFromAnAutoRoll() =
        withEngine { c ->
            c.engine.prepare(c.window("a", "b"))
            c.settle()
            c.drain()
            c.engine.seekTo(TRACK_MS + 1)
            c.settle()
            val after = c.drain()
            val changed = after.filterIsInstance<EngineEvent.TrackChanged>()
            assertEquals(1, changed.size, "one transition per seek: $after")
            assertEquals(TransitionReason.SEEK, changed.single().reason, "a seek is never AUTO")
            assertNotEquals(TransitionReason.AUTO, changed.single().reason)
            assertTrue(
                after.none { it is EngineEvent.ItemEnded },
                "MEDIA_ITEM_TRANSITION_REASON_SEEK skips the AUTO branch, so no ItemEnded: $after",
            )
        }

    @Test
    fun positionAdvancesWhilePlayingAndFreezesWhilePaused() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()
            c.settle()
            c.advancePlaybackTime(HALF_TRACK_MS)
            c.settle()
            val whilePlaying = c.position()
            assertTrue(whilePlaying > 0, "pollRunnable republishes every 100 ms while isPlaying; got $whilePlaying")

            c.engine.pause()
            c.advancePlaybackTime(HALF_TRACK_MS)
            c.settle()
            assertEquals(
                whilePlaying,
                c.position(),
                "pollRunnable stops re-posting when !isPlaying, so the position must not move",
            )
        }

    /**
     * A pause must *stop* the poll, not merely stop wanting its result.
     *
     * The shape that breaks this is a poll loop whose exit test reads the transport's playing flag
     * on its next tick: a `pause()` that only forgets the loop's handle leaves it running for one
     * more tick, and a `play()` inside that window cannot cancel what it can no longer see, so a
     * second loop starts and the media clock advances at 2x. `ExoPlayerEngine.pollPosition` does
     * `handler.removeCallbacks(pollRunnable)`, so a real engine cannot do this — which is exactly
     * why the rule belongs in the conformance suite a real engine is meant to be dropped into.
     *
     * Stated as a rate rather than an exact count, for the reason [clockBand] gives: the resume
     * lands mid-poll-grid, and 2x of [QUARTER_TRACK_MS] is 5000 ms, far outside any 1x band.
     */
    @Test
    fun pausingStopsThePollSoAResumeCannotDoubleIt() =
        withEngine { c ->
            // A long item on purpose. `settle()` is `advanceUntilIdle`, which on a self-rescheduling
            // poll loop runs until the item ENDS — so a short window plus a settle-while-playing
            // would consume the whole track and leave nothing to measure. Nothing here settles
            // while playing; every wait is a bounded `advancePlaybackTime`.
            c.engine.prepare(c.window("a", durationMs = LONG_MS))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val before = c.position()
            assertTrue(before > 0L, "precondition: playback advanced: $before")

            // Resume *within* one poll interval, which is the window the handle-losing shape hides in.
            c.engine.pause()
            c.play()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val gained = c.position() - before
            assertTrue(
                gained in clockBand(QUARTER_TRACK_MS, 1.0f),
                "one play() means one poll loop: a 2x clock would gain " +
                    "${clockBand(QUARTER_TRACK_MS, 2.0f)}, got $gained",
            )
        }

    @Test
    fun currentTimeMsIsThePublishedPosition() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.play()
            c.settle()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            c.settle()
            assertEquals(
                c.position(),
                c.engine.currentTimeMs(),
                "Orchestrator.enginePositionMs() takes the currentTimeMs() branch in production; a fake " +
                    "returning -1 sends every test down the lastPosMs fallback instead",
            )
            assertTrue(c.engine.currentTimeMs() >= 0, "currentTimeMs() must answer inline when it can")
        }

    /**
     * The band a media clock that covered [wallMs] of wall time at [rate]× must land in.
     *
     * An engine publishes the position from a poll that re-arms every [DEFAULT_POLL_INTERVAL_MS],
     * so the position is `ticks × interval × rate` and the ticks that fit inside `wallMs` cover at
     * least `wallMs` and at most `wallMs + interval` — hence the band. Pinning an exact tick count
     * instead would be a test of `advanceTimeBy`'s boundary, not of the engine: it runs scheduled
     * events *up to and including* the target, so the tick count depends on where the advance lands
     * relative to the poll grid. The bands are far apart enough to stay unambiguous — 1.0× of
     * [QUARTER_TRACK_MS] is 2 500…2 600 ms, 1.5× is 3 750…3 900 ms, and the clamps are 1 250…1 300
     * and 5 000…5 200.
     */
    private fun clockBand(
        wallMs: Long,
        rate: Float,
    ): LongRange {
        val covered = (wallMs * rate).toLong()
        return covered..(covered + (DEFAULT_POLL_INTERVAL_MS * rate).toLong())
    }

    /**
     * A rate is the one seam input whose only observable effect is on the media clock, so the rule
     * is stated in the clock's terms: the same wall time must buy 1.5× the track at 1.5×. An engine
     * that records the rate and never applies it, or that rounds the poll step, misses the band.
     */
    @Test
    fun setRateChangesHowFastTheMediaClockMoves() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val atNormalSpeed = c.position()
            assertTrue(
                atNormalSpeed in clockBand(QUARTER_TRACK_MS, 1.0f),
                "a 1.0x transport covers the elapsed wall time, which is what the rest of the " +
                    "position arithmetic in the app assumes: $atNormalSpeed",
            )

            c.engine.setRate(1.5f)
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val gained = c.position() - atNormalSpeed
            assertTrue(
                gained in clockBand(QUARTER_TRACK_MS, 1.5f),
                "the same wall time must buy 1.5x the track at 1.5x; the rate was recorded but not " +
                    "applied (gained $gained, wanted ${clockBand(QUARTER_TRACK_MS, 1.5f)})",
            )
        }

    /**
     * The AVPlayer trap, as a rule: assigning a positive `rate` to a paused `AVPlayer` *begins
     * playback*, so a rate change on a paused engine would be an unrequested play — the app's
     * position would start advancing with no `Playing` phase behind it.
     */
    @Test
    fun aRateSetWhilePausedDoesNotResumePlayback() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val before = c.position()
            assertTrue(before > 0L, "precondition: playback advanced: $before")

            c.engine.pause()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            assertEquals(
                before,
                c.position(),
                "the poll stops re-posting when paused, so the position must be frozen before the rate call",
            )

            c.engine.setRate(MAX_PLAYBACK_RATE)
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            assertEquals(
                before,
                c.position(),
                "setRate is a transport property, not a play request: a paused engine must stay paused",
            )
        }

    /**
     * Out-of-range input is clamped rather than rejected: an engine that throws on 0.01 or 50 turns
     * a UI slider into a crash, and one that passes them through hands `PlaybackParameters.withSpeed`
     * a value outside what the platform accepts. Measured at the clamped 0.5x and 2.0x, so an engine
     * that honoured the raw 0.01 (≈25 ms of track) or the raw 50 (≈125 000 ms) misses by orders of
     * magnitude rather than by a rounding error.
     */
    @Test
    fun aRateOutsideTheSupportedRangeIsClampedToIt() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()

            c.engine.setRate(0.01f)
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val slow = c.position()
            assertTrue(
                slow in clockBand(QUARTER_TRACK_MS, MIN_PLAYBACK_RATE),
                "0.01x is below $MIN_PLAYBACK_RATE and must be clamped up to it, not honoured: $slow",
            )

            c.engine.setRate(50f)
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val gained = c.position() - slow
            assertTrue(
                gained in clockBand(QUARTER_TRACK_MS, MAX_PLAYBACK_RATE),
                "50x is above $MAX_PLAYBACK_RATE and must be clamped down to it, not honoured: " +
                    "gained $gained, wanted ${clockBand(QUARTER_TRACK_MS, MAX_PLAYBACK_RATE)}",
            )
        }

    /**
     * NaN survives every comparison, so `coerceIn` hands it straight through and a clock multiplied
     * by NaN stops advancing forever — a silently dead transport that still reports `Playing`. The
     * seam drops non-finite input, so the clock must keep running.
     */
    @Test
    fun aNonFiniteRateIsIgnoredRatherThanCorruptingTheClock() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()

            c.engine.setRate(Float.NaN)
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val after = c.position()
            assertTrue(after > 0L, "a non-finite rate must not freeze the media clock at 0: $after")
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            assertTrue(
                c.position() > after,
                "the media clock must still be running after a non-finite rate: $after -> ${c.position()}",
            )
        }

    /**
     * A skip is *relative*, and the only honest base is the engine's own position: the caller's
     * cached `posMs` is a 10 Hz sample of this same clock, so it is stale by construction. Two
     * forwards and a backward must compose back to exactly one forward.
     */
    @Test
    fun aSkipIsRelativeToTheEnginesOwnPosition() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val before = c.position()
            assertTrue(before > 0L, "precondition: playback advanced: $before")

            c.engine.skipForward(SKIP_STEP_MS)
            c.advancePlaybackTime(0L)
            assertEquals(
                before + SKIP_STEP_MS,
                c.position(),
                "skipForward is measured from the engine's position, not from 0",
            )

            c.engine.skipForward(SKIP_STEP_MS)
            c.advancePlaybackTime(0L)
            assertEquals(
                before + 2 * SKIP_STEP_MS,
                c.position(),
                "a second skip stacks on the first, so the base really is the engine's position",
            )

            c.engine.skipBackward(SKIP_STEP_MS)
            c.advancePlaybackTime(0L)
            assertEquals(
                before + SKIP_STEP_MS,
                c.position(),
                "skipBackward is measured from the engine's position, not from 0",
            )
        }

    /**
     * Both ends of a relative seek can overshoot, and neither may be handed to the decoder: a
     * negative position and a position past the item are the two ways a skip becomes a silent
     * no-op or an audible jump to the wrong track.
     */
    @Test
    fun aSkipIsClampedToZeroAndToTheItemDuration() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(HALF_TRACK_MS)
            assertTrue(c.position() > 0L, "precondition: playback advanced: ${c.position()}")

            c.engine.skipBackward(TRACK_MS)
            c.advancePlaybackTime(0L)
            assertEquals(0L, c.position(), "a backward skip past the start lands at 0, never below it")

            c.engine.skipForward(TRACK_MS + HALF_TRACK_MS)
            c.advancePlaybackTime(0L)
            assertEquals(
                TRACK_MS,
                c.position(),
                "a forward skip past the end lands on the item duration, not past it",
            )
        }

    /**
     * The bug class this codebase already paid for: a seek clamped against a duration of 0 (or -1)
     * becomes `seekTo(0)` forever. An unknown duration must let the seek through so the engine's
     * own decoder is the one that decides — otherwise every skip on a track with an unparseable
     * duration is swallowed.
     */
    @Test
    fun aSkipOnATrackWithNoKnownDurationIsStillPerformed() =
        withEngine { c ->
            c.engine.prepare(c.window("a", durationMs = null))
            c.settle()
            c.drain()
            c.play()
            c.advancePlaybackTime(QUARTER_TRACK_MS)
            val before = c.position()
            assertTrue(before > 0L, "precondition: playback advanced: $before")

            c.engine.skipForward(QUARTER_TRACK_MS)
            c.advancePlaybackTime(0L)
            assertEquals(
                before + QUARTER_TRACK_MS,
                c.position(),
                "an unknown duration must not swallow the skip: clamping it to 0 would make every " +
                    "skip on such a track a permanent no-op",
            )
        }

    @Test
    fun replaceUpNextNeverBlanksTheCurrentItem() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.engine.replaceUpNext(c.window("a", "b")[1])
            c.settle()
            assertEquals("a", c.engineCurrentItemId(), "replaceUpNext inserts at index 1; slot 0 is the audible item")
            c.engine.replaceUpNext(null)
            c.settle()
            assertEquals(
                "a",
                c.engineCurrentItemId(),
                "dropping the up-next from a 2-item window must keep the current item",
            )
        }

    /**
     * The app's only defence against "the engine is playing something the state machine never asked
     * for" is that an unmappable itemId is a fault, not a track change. An engine must therefore be
     * *able* to report one; an engine that silently substitutes a known id hides the fault class
     * rather than fixing it.
     */
    @Test
    fun aStaleGenerationItemIdReachesTheOrchestratorAsAFault() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.drain()
            c.publish(EngineEvent.TrackChanged(STALE_ITEM_ID, TransitionReason.AUTO))
            c.settle()
            val after = c.drain()
            assertTrue(
                after.any { it is EngineEvent.TrackChanged && it.itemId == STALE_ITEM_ID },
                "an unmappable itemId must reach the orchestrator so it can strike it: $after",
            )
            assertEquals("a", c.engineCurrentItemId(), "the engine's own current item is still what was prepared")
        }

    @Test
    fun releaseIsIdempotentAndObservable() =
        withEngine { c ->
            c.engine.prepare(c.window("a"))
            c.settle()
            c.play()
            c.settle()
            c.drain()
            c.engine.release()
            c.engine.release()
            c.engine.release()
            c.settle()
            c.drain()
            c.engine.prepare(c.window("a", "b"))
            c.play()
            c.advancePlaybackTime(TRACK_MS)
            c.settle()
            assertTrue(
                c.drain().isEmpty(),
                "a released engine must be inert: AN-1 is that nothing ever called release(), and a " +
                    "release that is not idempotent makes the fix unsafe",
            )
        }

    companion object {
        const val TRACK_MS: Long = 10_000L
        const val HALF_TRACK_MS: Long = 5_000L
        const val QUARTER_TRACK_MS: Long = 2_500L

        /** Long enough that a bounded `advancePlaybackTime` never reaches the end of the item. */
        const val LONG_MS: Long = 600_000L

        /** A round relative-seek amount, comfortably inside [TRACK_MS]. */
        const val SKIP_STEP_MS: Long = 1_000L

        /** `"provider:songId:bits"` for a song that is in no queue. */
        const val STALE_ITEM_ID: String = "saavn:ghost:128"
    }
}

/**
 * [FakePlayerEngine] against the contract. When the real `ExoPlayerEngine` gets a Robolectric
 * lane, a sibling of this class runs the identical inherited rules against it — no rule edits.
 */
class FakeEngineContractTest : EngineContractTest() {
    override fun <T> withEngine(body: (EngineHarness) -> T): T {
        val scheduler = TestCoroutineScheduler()
        val scope = TestScope(StandardTestDispatcher(scheduler, "media") + scheduler)
        val engine =
            FakePlayerEngine(
                scope = scope,
                looper = StandardTestDispatcher(scheduler, "media"),
                clock = scheduler,
            )
        val harness =
            EngineHarness(
                engine = engine,
                doPlay = {
                    engine.play()
                    scheduler.runCurrent()
                },
                doAdvance = { ms ->
                    scheduler.advanceTimeBy(ms)
                    scheduler.runCurrent()
                },
                doPublish = { e -> engine.script(e) },
                doSettle = { scheduler.advanceUntilIdle() },
                doCurrentItemId = { engine.currentTrack()?.itemId },
            )
        // events is a SharedFlow with no replay: subscribe before the first prepare().
        scope.launch { engine.events.collect { harness.record(it) } }
        scope.launch { engine.positionFlow.collect { harness.recordPosition(it) } }
        scheduler.runCurrent()
        return try {
            body(harness)
        } finally {
            engine.release()
            scope.cancel()
        }
    }
}
