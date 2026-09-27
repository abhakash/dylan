package dylan.support

import dylan.playback.EngineEvent
import dylan.playback.LocalTrack
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
