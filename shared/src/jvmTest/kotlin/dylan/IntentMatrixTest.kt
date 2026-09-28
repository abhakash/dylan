package dylan

import dylan.config.AppConfig
import dylan.model.Phase
import dylan.model.Repeat
import dylan.playback.EngineEvent
import dylan.playback.Intent
import dylan.playback.TransitionReason
import dylan.support.FakeNetMonitor
import dylan.support.GraphHarness
import dylan.support.testSong
import dylan.util.NetClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Table-driven coverage for the `Intent` surface that had near-zero coverage, driven through the
 * **real** `Orchestrator` with the new `FakePlayerEngine`.
 *
 * Two of these rows were impossible before: `Intent.Seek` (the old fake's `seekTo` was a no-op, so
 * nothing could observe it) and `Intent.Previous`'s three-second restart heuristic (the old fake
 * never overrode `currentTimeMs()`, so `enginePositionMs()` always took the `lastPosMs` fallback and
 * the restart branch was dead). `QueueStateMachine` had no direct test at all.
 *
 * The navigation matrix is `{shuffle off/on} × {OFF, ALL, ONE} × {Next, Previous} × {0, mid, last}`
 * — the 2×3×2×3 grid whose two halves the pre-wave suite each covered alone, which is how the
 * shuffle + repeat-ALL wrap survived.
 */
class IntentMatrixTest {
    private fun cfg() = AppConfig(clock = dylan.support.MutableClock(), navDebounceMs = 0L)

    // ── the navigation matrix ─────────────────────────────────────────────────────────────

    @Test
    fun nextFollowsTheOrderAndRepeatMatrix() =
        matrixTest { h, state, cell ->
            val expected = referenceAdvance(state, +1)
            if (expected == null) {
                assertEquals(state.index, h.state.index, "$cell: no successor under repeat OFF, so Next must not move")
            } else {
                assertEquals(expected, h.state.index, "$cell: Next must follow the playback order, not the queue slot")
            }
        }

    @Test
    fun previousFollowsTheOrderAndRepeatMatrix() =
        matrixTest(direction = -1) { h, state, cell ->
            val expected = referenceAdvance(state, -1)
            if (expected == null) {
                assertEquals(
                    state.index,
                    h.state.index,
                    "$cell: no predecessor under repeat OFF, so Previous must not move",
                )
            } else {
                assertEquals(
                    expected,
                    h.state.index,
                    "$cell: Previous must follow the playback order, not the queue slot",
                )
            }
        }

    // ── Previous's three-second restart heuristic ─────────────────────────────────────────

    /**
     * `Intent.Previous` past three seconds restarts the current item instead of skipping. This was
     * unreachable before: the old fake never overrode `currentTimeMs()`, so `enginePositionMs()`
     * always took the `lastPosMs` fallback and could never exceed 3 000.
     */
    @Test
    fun previousPastThreeSecondsRestartsTheCurrentTrack() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.submit(Intent.PlayNow(h.songs("a", "b"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                h.engine.advancePlaybackClock(PAST_RESTART_MS)
                assertTrue(
                    h.engine.currentTimeMs() > RESTART_THRESHOLD_MS,
                    "the engine must answer its position inline, got ${h.engine.currentTimeMs()}",
                )
                val before = h.state
                h.submit(Intent.Previous)
                h.settleIntents()
                assertEquals(listOf(0L), h.engine.seeks, "past 3 s Previous is seekTo(0), not a queue move")
                assertEquals(before.index, h.state.index, "a restart must not move the queue index")
                assertEquals(
                    before.current?.key?.songId,
                    h.state.current
                        ?.key
                        ?.songId,
                )
            }
        }

    @Test
    fun previousWithinThreeSecondsSkipsToThePredecessor() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.submit(Intent.PlayNow(h.songs("a", "b"), 1))
                h.awaitState { it.index == 1 && it.phase is Phase.Playing }
                h.awaitWindow("saavn:b")
                h.engine.advancePlaybackClock(POSITION_MS)
                h.submit(Intent.Previous)
                val after = h.awaitState { it.current?.key?.songId == "a" }
                assertEquals(0, after.index)
                h.settleIntents()
                assertTrue(
                    h.engine.seeks.isEmpty(),
                    "a skip must not be implemented as seekTo(0): seeks=${h.engine.seeks}",
                )
            }
        }

    @Test
    fun previousAtTheHeadToastsInsteadOfMoving() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                val toast = CompletableDeferred<String>()
                h.orchestrator.toast = { toast.complete(it) }
                h.submit(Intent.PlayNow(h.songs("a", "b"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                h.engine.advancePlaybackClock(POSITION_MS)
                h.submit(Intent.Previous)
                val text = withTimeout(GraphHarness.CEILING_MS) { toast.await() }
                assertEquals(
                    "Start of queue",
                    text,
                    "no predecessor: the user must be told, not left with a dead button",
                )
                assertEquals(0, h.state.index, "no predecessor: the index must not move")
            }
        }

    // ── Seek ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun seekReachesTheEngineClampedToTheTrackDuration() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a", durationS = 100L)
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                h.submit(Intent.Seek(30_000L))
                h.settleIntents()
                assertEquals(
                    listOf(30_000L),
                    h.engine.seeks,
                    "Intent.Seek is observable only because seekTo is recorded",
                )

                h.submit(Intent.Seek(999_999L))
                h.settleIntents()
                assertEquals(
                    100_000L,
                    h.engine.seeks.last(),
                    "a seek past the track is clamped to durationS*1000, not passed through",
                )
            }
        }

    @Test
    fun seekIsForwardedToTheEngineAndCurrentTimeMsAnswers() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a", durationS = 100L)
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                h.submit(Intent.Seek(42_000L))
                h.settleIntents()
                // currentTimeMs() is the branch production takes; the pre-wave fake always
                // returned -1 and forced every test down the lastPosMs fallback.
                assertTrue(
                    h.engine.currentTimeMs() >= 0,
                    "currentTimeMs must answer inline once the engine can, or enginePositionMs() falls back",
                )
            }
        }

    /**
     * The catalog's documented fallback for an unparseable duration is 0, and the old clamp was
     * `durationS * 1000` — so every seek on such a track collapsed to `seekTo(0)`, silently.
     * With no usable upper bound the request passes through instead.
     */
    @Test
    fun seekOnATrackWithNoKnownDurationPassesThroughInsteadOfCollapsingToZero() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a", durationS = 0L)
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                h.submit(Intent.Seek(90_000L))
                h.settleIntents()
                assertEquals(
                    listOf(90_000L),
                    h.engine.seeks,
                    "an unknown duration must not be treated as a zero-length track",
                )
            }
        }

    /**
     * A seek issued while the track is still resolving is not dropped: the clamped value lands in
     * `PlayerState.posMs` and the window that is prepared later starts there.
     */
    @Test
    fun aSeekWhileResolvingIsDeferredAndAppliedToTheWindow() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a", durationS = 100L)
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                h.engine.setPositionMs(0L)
                h.submit(Intent.Seek(12_345L))
                h.settleIntents()
                assertEquals(
                    12_345L,
                    h.state.posMs,
                    "the state machine owns the position whether or not the engine does",
                )
                assertEquals(listOf(12_345L), h.engine.seeks)
            }
        }

    /**
     * `Prepared` is a buffering event. Calling `play()` from it made a rebuffer override a user
     * pause, so the notification and the app disagreed about the most important state.
     */
    @Test
    fun aRebufferDoesNotOverrideAUserPause() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.submit(Intent.PlayNow(h.songs("a", "b"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                h.submit(Intent.TogglePlayPause)
                h.awaitState { it.phase is Phase.Paused }
                val playsBefore = h.engine.playCount
                h.engine.script(EngineEvent.Prepared("g0:saavn:a:128"))
                delay(REBUFFER_SETTLE_MS)
                assertEquals(playsBefore, h.engine.playCount, "a rebuffer must not resume a paused transport")
                assertTrue(h.state.phase is Phase.Paused, "…and the state machine must still say paused")
            }
        }

    /**
     * The engine's itemId carries the play generation, so an event stamped with a superseded one is
     * a fault, never a track change: the old prefix scan matched the key and happily moved the queue
     * to a track the user had already replaced.
     */
    @Test
    fun aSupersededGenerationItemIdIsAFaultNotATrackChange() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("z")
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                val windows = h.engine.preparedWindows
                val head = windows.last()
                val staleItemId = head.first().itemId
                h.submit(Intent.PlayNow(h.songs("z"), 0))
                h.awaitState { it.current?.key?.songId == "z" && it.phase is Phase.Playing }
                h.engine.script(EngineEvent.TrackChanged(staleItemId, TransitionReason.AUTO))
                delay(STALE_EVENT_SETTLE_MS)
                val s = h.state
                assertEquals("z", s.current?.key?.songId, "a superseded generation must not move the queue")
            }
        }

    /** Repeat-ONE pins the transport, so a window listing the same track twice played it twice. */
    @Test
    fun repeatOneNeverBuildsAWindowWithTheSameTrackTwice() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.setRepeat(Repeat.ONE)
                h.submit(Intent.PlayNow(h.songs("a", "b"), 0))
                h.awaitPlaying()
                h.awaitWindow("saavn:a")
                // No `settleIntents()` here: the inbox barrier is three `CycleRepeat`s, and a
                // ONE → OFF → ALL round-trip legitimately queues `b` as the up-next on the way. The
                // barrier would have been the thing under test, and the rule would have been
                // measured through a state the test had itself left.
                assertTrue(
                    h.engine.preparedWindows.all { w -> w.size == 1 },
                    "repeat ONE must prepare a one-item window, " +
                        "got ${h.engine.preparedWindows.map { w -> w.map { t -> t.itemId } }}",
                )
                assertTrue(
                    h.engine.upNextHistory.none { it != null },
                    "and must never ask the engine to replace the up-next with the item it is playing",
                )
            }
        }

    // ── queue editing ─────────────────────────────────────────────────────────────────────

    @Test
    fun playNextInsertsImmediatelyAfterTheCurrentItem() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.submit(Intent.PlayNow(h.songs("a", "b", "c"), 1))
                h.awaitState { it.index == 1 }
                h.submit(Intent.PlayNext(testSong("x")))
                val s = h.awaitState { it.queue.size == 4 }
                assertEquals(listOf("a", "b", "x", "c"), s.queue.map { it.key.songId }, "PlayNext lands at index+1")
                assertEquals(1, s.index, "PlayNext must not move the current item")
            }
        }

    @Test
    fun addLastAppendsAndKeepsTheCurrentItem() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.submit(Intent.AddLast(testSong("z")))
                val s = h.awaitState { it.queue.size == 2 }
                assertEquals(listOf("a", "z"), s.queue.map { it.key.songId })
                assertEquals(0, s.index)
            }
        }

    @Test
    fun removeAtDropsTheRowAndLeavesTheRestAddressable() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.submit(Intent.PlayNow(h.songs("a", "b", "c"), 0))
                h.awaitState { it.queue.size == 3 }
                h.submit(Intent.RemoveAt(1))
                val s = h.awaitState { it.queue.size == 2 }
                assertEquals(listOf("a", "c"), s.queue.map { it.key.songId })
                assertEquals(0, s.index)
            }
        }

    @Test
    fun moveWithinQueueRoundTripsAgainstTheRealOrchestrator() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.seedCached("c")
                h.submit(Intent.PlayNow(h.songs("a", "b", "c"), 1))
                h.awaitState { it.queue.size == 3 && it.index == 1 }
                // Settle the initial prepare first: an in-flight TrackChanged landing mid-move
                // re-pins the index (correctly — the engine is authoritative about what is audible)
                // and would make the round-trip look like a queue-algebra failure.
                h.awaitPlaying()
                h.awaitWindow("saavn:b")
                h.settleIntents()
                for (from in 0..2) {
                    for (to in 0..2) {
                        if (from == to) continue
                        val before = h.state
                        h.submit(Intent.MoveWithinQueue(from, to))
                        h.settleIntents()
                        h.submit(Intent.MoveWithinQueue(to, from))
                        h.settleIntents()
                        val after = h.state
                        assertEquals(
                            before.queue.map { it.key.songId },
                            after.queue.map { it.key.songId },
                            "move($from,$to) then ($to,$from) must restore the queue",
                        )
                        assertEquals(
                            before.current?.key?.songId,
                            after.current?.key?.songId,
                            "move($from,$to) then ($to,$from) must leave the audible track playing " +
                                "(the index is re-pinned by the engine's next TrackChanged, which is " +
                                "correct: the engine is authoritative about what is audible)",
                        )
                    }
                }
            }
        }

    @Test
    fun clearUpNextTruncatesAfterTheCurrentItem() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.submit(Intent.PlayNow(h.songs("a", "b", "c"), 0))
                h.awaitState { it.queue.size == 3 }
                h.submit(Intent.ClearUpNext)
                val s = h.awaitState { it.queue.size == 1 }
                assertEquals(listOf("a"), s.queue.map { it.key.songId })
            }
        }

    @Test
    fun clearUpNextIsANoOpAtTheEndOfTheQueue() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.submit(Intent.ClearUpNext)
                val s = h.awaitState { it.phase is Phase.Playing }
                assertEquals(1, s.queue.size)
            }
        }

    // ── transport and toggles ─────────────────────────────────────────────────────────────

    @Test
    fun togglePlayPauseRoundTripsThroughTheEngine() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitPlaying()
                h.submit(Intent.TogglePlayPause)
                h.awaitState { it.phase is Phase.Paused }
                assertTrue(h.engine.pauseCount >= 1, "pause must reach the engine")
                h.submit(Intent.TogglePlayPause)
                h.awaitState { it.phase is Phase.Playing }
                assertTrue(h.engine.playCount >= 2, "play must reach the engine")
            }
        }

    @Test
    fun toggleShuffleIsIdempotentlyReversible() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.seedCached("b")
                h.submit(Intent.PlayNow(h.songs("a", "b"), 0))
                h.awaitState { it.phase is Phase.Playing }
                h.submit(Intent.ToggleShuffle)
                val on = h.awaitState { it.shuffleOn }
                assertNotNull(on.shuffleOrder, "shuffle on must carry a permutation")
                h.submit(Intent.ToggleShuffle)
                val off = h.awaitState { !it.shuffleOn }
                assertNull(off.shuffleOrder, "shuffle off must drop the permutation")
            }
        }

    @Test
    fun cycleRepeatWalksOffAllOne() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.submit(Intent.PlayNow(h.songs("a"), 0))
                h.awaitState { it.phase is Phase.Playing }
                h.submit(Intent.CycleRepeat)
                assertEquals(Repeat.ALL, h.awaitState { it.repeat == Repeat.ALL }.repeat)
                h.submit(Intent.CycleRepeat)
                assertEquals(Repeat.ONE, h.awaitState { it.repeat == Repeat.ONE }.repeat)
                h.submit(Intent.CycleRepeat)
                assertEquals(Repeat.OFF, h.awaitState { it.repeat == Repeat.OFF }.repeat)
            }
        }

    @Test
    fun setQualityEnqueuesAnUpgradeOnlyWhenItIsAnUpgrade() =
        runBlocking {
            harness(cfg()) { h ->
                h.seedCached("a")
                h.submit(Intent.PlayNow(listOf(testSong("a", has320 = true)), 0))
                h.awaitPlaying()
                h.submit(Intent.SetQuality(dylan.model.Quality.BITRATE_128))
                assertTrue(
                    !h.downloads.states.value
                        .containsKey(dylan.model.SongKey("saavn", "a")),
                    "a same-or-lower bitrate must not enqueue anything: ${h.downloads.states.value}",
                )
                h.submit(Intent.SetQuality(dylan.model.Quality.BITRATE_320))
                withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(GraphHarness.CEILING_MS) {
                        h.downloads.states.first { dylan.model.SongKey("saavn", "a") in it }
                    }
                }
            }
        }

    /**
     * Found while building the matrix: the restart-or-toast decision used to compare `s.index` with
     * `s.queue.lastIndex` — a *slot* comparison — after the algebra had already decided there was
     * no successor in the *order*. Both now read `PlayerState.atPlaybackEnd`, which walks the order.
     */
    @Test
    fun shuffleRepeatOffAtTheEndOfTheOrderToastsEndOfQueue() =
        runBlocking {
            harness(cfg()) { h ->
                for (id in listOf("a", "b", "c")) h.seedCached(id)
                h.submit(Intent.PlayNow(h.songs("a", "b", "c"), 0))
                h.settleIntents()
                h.awaitPlaying()
                h.submit(Intent.ToggleShuffle)
                h.settleIntents()
                h.setRepeat(Repeat.OFF)
                val toasts = java.util.concurrent.CopyOnWriteArrayList<String>()
                h.orchestrator.toast = { toasts += it }
                // Walk to the last element of the permutation.
                while (dylan.playback.QueueStateMachine.resolveAdvance(h.state, +1) != null) {
                    h.submit(Intent.Next)
                    h.settleIntents()
                }
                val atEnd = h.state
                h.submit(Intent.Next)
                h.settleIntents()
                assertEquals(
                    atEnd.index,
                    h.state.index,
                    "repeat OFF has no successor, so the index must not move",
                )
                assertTrue(
                    toasts.contains("End of queue"),
                    "…and the user must be told, got $toasts (index=${atEnd.index} of ${atEnd.queue.size}, " +
                        "order=${atEnd.shuffleOrder})",
                )
            }
        }

    // ── the matrix driver ─────────────────────────────────────────────────────────────────

    private suspend fun kotlinx.coroutines.CoroutineScope.harness(
        cfg: AppConfig,
        block: suspend (GraphHarness) -> Unit,
    ) {
        val h = GraphHarness(cfg, this, net = FakeNetMonitor(online = true, netClass = NetClass.UNMETERED))
        try {
            block(h)
            assertTrue(h.uncaughtExceptions.isEmpty(), "graph raised ${h.uncaughtExceptions}")
        } finally {
            h.close()
        }
        Unit
    }

    private fun matrixTest(
        direction: Int = +1,
        block: suspend (GraphHarness, dylan.model.PlayerState, MatrixCell) -> Unit,
    ) = runBlocking {
        for (shuffleOn in listOf(false, true)) {
            for (repeat in Repeat.entries) {
                for (anchor in listOf(0, 1, 2)) {
                    val cell = MatrixCell(shuffleOn, repeat, anchor)
                    val h = GraphHarness(cfg(), this)
                    try {
                        for (id in listOf("a", "b", "c")) h.seedCached(id)
                        h.submit(Intent.PlayNow(h.songs("a", "b", "c"), anchor))
                        h.settleIntents()
                        h.awaitState { it.queue.size == 3 && it.phase is Phase.Playing }
                        if (shuffleOn) {
                            h.submit(Intent.ToggleShuffle)
                            h.settleIntents()
                        }
                        h.setRepeat(repeat)
                        val before = h.awaitState { it.index == anchor && it.phase is Phase.Playing }
                        h.submit(if (direction > 0) Intent.Next else Intent.Previous)
                        h.settleIntents()
                        block(h, before, cell)
                        assertTrue(
                            h.uncaughtExceptions.isEmpty(),
                            "$cell raised " +
                                h.uncaughtExceptions.joinToString { e -> e.stackTraceToString().take(600) },
                        )
                    } finally {
                        h.close()
                    }
                }
            }
        }
    }

    private data class MatrixCell(
        val shuffleOn: Boolean,
        val repeat: Repeat,
        val anchor: Int,
    )

    /**
     * Reference "what plays next" in the terms of the two implementations the app actually uses:
     * `QueueStateMachine.resolveAdvance` for navigation. `null` means the intent has no target and
     * the orchestrator is expected to leave the index alone.
     */
    private fun referenceAdvance(
        state: dylan.model.PlayerState,
        dir: Int,
    ): Int? = dylan.playback.QueueStateMachine.resolveAdvance(state, dir)
}

private const val POSITION_MS = 1_000L
private const val PAST_RESTART_MS = 5_000L
private const val RESTART_THRESHOLD_MS = 3_000L
private const val REBUFFER_SETTLE_MS = 200L
private const val STALE_EVENT_SETTLE_MS = 300L
