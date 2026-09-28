package dylan

import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.ItemRef
import dylan.playback.ResumeSnapshot
import dylan.playback.decodeSnapshot
import dylan.playback.encodeSnapshot
import dylan.playback.restoreState
import dylan.playback.sanitizeSnapshot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun song(
    id: String,
    durationS: Long = 100,
) = Song(
    key = SongKey("saavn", id),
    title = id,
    subtitle = "",
    albumId = null,
    albumName = null,
    artUrl150 = "",
    artUrl500 = "",
    durationS = durationS,
    has320 = true,
    resolveRef = "ref-$id",
    permaToken = null,
)

/**
 * `nextUp` is `queue[nextIndex]`, and `nextIndex` exists only on a state that went through
 * `withQueueMutation` — a hand-built state has no maintained `nextIndex` by construction, so every
 * fixture here is built the one way the app builds one.
 */
private fun stateOf(
    queue: List<Song>,
    index: Int,
    shuffle: Boolean = false,
    order: List<Int>? = null,
    repeat: Repeat = Repeat.OFF,
): PlayerState =
    PlayerState()
        .withQueueMutation(
            queue = queue.toPersistentList(),
            index = index,
            current = queue[index],
            shuffleOn = shuffle,
            shuffleOrder = order?.toPersistentList(),
            repeat = repeat,
        ).copy(phase = Phase.Playing(queue[index].key))

class NextUpTest {
    private val q = listOf(song("a"), song("b"), song("c"))

    @Test
    fun linearNext() {
        assertEquals("b", stateOf(q, 0).nextUp?.key?.songId)
    }

    @Test
    fun linearEndIsNull() {
        assertNull(stateOf(q, 2).nextUp)
    }

    @Test
    fun repeatOneNextIsCurrent() {
        assertEquals("b", stateOf(q, 1, repeat = Repeat.ONE).nextUp?.key?.songId)
    }

    @Test
    fun repeatAllWraps() {
        assertEquals("a", stateOf(q, 2, repeat = Repeat.ALL).nextUp?.key?.songId)
    }

    @Test
    fun shuffleWalksOrderNotSlotZero() {
        val s = stateOf(q, 0, shuffle = true, order = listOf(2, 0, 1))
        assertEquals("b", s.nextUp?.key?.songId, "next follows playback order, not order[0]")
    }

    /**
     * v0 kept a stale permutation and answered "nothing follows" — End of queue, on a queue of
     * three. v1 rebuilds the permutation around the current item instead, so the answer is a real
     * successor. What must never come back either way is slot zero standing in for "next".
     */
    @Test
    fun aStaleShuffleOrderIsRebuiltAndNeverYieldsSlotZero() {
        val s = stateOf(q, 0, shuffle = true, order = listOf(1, 2))
        assertEquals(listOf(0, 1, 2), s.shuffleOrder?.sorted(), "a stale order is rebuilt over the whole queue")
        assertNotNull(s.nextIndex, "a rebuilt permutation is navigable, not end-of-queue")
        assertNotEquals(0, s.nextIndex, "the current item is not its own successor")
    }

    @Test
    fun removeSuccessorUnderShuffleKeepsCorrectSlot() {
        val q2 = listOf(song("a"), song("c"))
        val s = stateOf(q2, 0, shuffle = true, order = listOf(0, 1))
        assertEquals("c", s.nextUp?.key?.songId)
    }

    /**
     * The defect the audit found, hand-checkable: `nextUp`'s shuffle branch had no repeat-ALL wrap,
     * so the last element of the permutation had no successor even though the engine, the window
     * and the prefetcher all agreed there was one.
     */
    @Test
    fun shuffleRepeatAllWrapsAtTheEndOfThePermutation() {
        val s = stateOf(listOf(song("a"), song("b")), 1, shuffle = true, order = listOf(0, 1), repeat = Repeat.ALL)
        assertEquals(0, s.nextIndex)
        assertEquals("a", s.nextUp?.key?.songId, "the wrap target is the first slot of the permutation")
    }

    @Test
    fun repeatAllWalkOverAPermutationIsABijection() {
        val order = listOf(2, 0, 1)
        // `nextIndex` is nullable. A null is a failure, not a gap, so it is carried in as -1 — a slot
        // no permutation contains — rather than dropped by a filterNotNull that would hide it.
        val walked = order.map { stateOf(q, it, shuffle = true, order = order, repeat = Repeat.ALL).nextIndex ?: -1 }
        assertEquals(order.sorted(), walked.sorted(), "every slot is reached exactly once, wrap included")
    }

    @Test
    fun aHandBuiltStateCarriesNoNextIndex() {
        val bare = PlayerState(phase = Phase.Playing(q[0].key), current = q[0], queue = q.toPersistentList(), index = 0)
        assertNull(bare.nextIndex, "nextIndex is maintained, not defaulted: an unmaintained state must be visible")
        assertFalse(bare.maintained)
        assertEquals(1, bare.withQueueMutation().nextIndex)
    }

    @Test
    fun anInvariantViolationIsAThrow() {
        val st = stateOf(q, 0)
        val broken = st.copy(index = 7, nextIndex = 1)
        val failure = runCatching { PlayerState.validate(broken) }.exceptionOrNull()
        assertTrue(
            failure is dylan.model.QueueInvariantViolation,
            "index=7 over a queue of 3 must be rejected, got $failure",
        )
    }
}

class SnapshotSanitizeTest {
    private val refs = listOf(ItemRef("saavn", "a"), ItemRef("saavn", "b"), ItemRef("saavn", "c"))

    @Test
    fun roundTrip() {
        val snap = ResumeSnapshot(items = refs, index = 1, posMs = 5000, shuffleOn = true, order = listOf(2, 1, 0))
        val decoded = decodeSnapshot(encodeSnapshot(snap))
        assertEquals(snap, decoded)
    }

    @Test
    fun tolerantParseGarbage() {
        assertNull(decodeSnapshot("not json"))
        assertNull(decodeSnapshot("""{"v":1,"items":[]}"""))
    }

    @Test
    fun missingRowFiltersAndClampsIndex() {
        val r = sanitizeSnapshot(ResumeSnapshot(items = refs, index = 2)) { it.songId != "b" }
        assertEquals(listOf("a", "c"), r!!.refs.map { it.songId })
        assertEquals(1, r.index)
    }

    @Test
    fun staleOrderDroppedWhenUnresolvable() {
        val r = sanitizeSnapshot(ResumeSnapshot(items = refs, index = 0, shuffleOn = true, order = listOf(9, 0))) { true }
        assertNull(r!!.order)
    }

    @Test
    fun validOrderRemapsToFilteredPositions() {
        val r = sanitizeSnapshot(ResumeSnapshot(items = refs, index = 0, shuffleOn = true, order = listOf(2, 0, 1))) { it.songId != "b" }
        assertEquals(listOf(1, 0), r!!.order)
    }

    @Test
    fun duplicateOrderEntriesDropPermutation() {
        val r = sanitizeSnapshot(ResumeSnapshot(items = refs, index = 0, shuffleOn = true, order = listOf(0, 0, 1))) { true }
        assertNull(r!!.order, "a permutation must be a bijection — duplicates crown a song as its own successor")
    }

    @Test
    fun emptyResultGivesNull() {
        assertNull(sanitizeSnapshot(ResumeSnapshot(items = refs)) { false })
    }

    /**
     * The `restore()` defect: `shuffleOn` survived a sanitize that had to drop a stale permutation,
     * leaving a queue that could not be navigated at all.
     */
    @Test
    fun aRestoredShuffleWithoutAPermutationIsRestoredUnshuffled() {
        val snap = ResumeSnapshot(items = refs, index = 0, shuffleOn = true, order = emptyList())
        val byKey = refs.associate { SongKey(it.provider, it.songId) to song(it.songId) }
        val st = assertNotNull(restoreState(snap, byKey, 0L), "a resolvable snapshot always restores")
        assertFalse(st.shuffleOn, "a shuffle with no surviving permutation is not a shuffle")
        assertNull(st.shuffleOrder)
        assertEquals("b", st.nextUp?.key?.songId, "…and the restored queue is navigable again")
    }

    @Test
    fun aRestoredPermutationIsCarried() {
        val snap = ResumeSnapshot(items = refs, index = 0, shuffleOn = true, order = listOf(1, 0, 2))
        val byKey = refs.associate { SongKey(it.provider, it.songId) to song(it.songId) }
        val st = assertNotNull(restoreState(snap, byKey, 0L))
        assertTrue(st.shuffleOn)
        assertEquals(listOf(1, 0, 2), st.shuffleOrder?.toList())
        // The snapshot's index is a QUEUE SLOT, so slot 0 sits at position 1 of the permutation and
        // what follows it is slot 2 — the naive index + 1 would have answered 1.
        assertEquals(2, st.nextIndex, "next follows the restored permutation, not slot 1")
    }

    @Test
    fun aRestoredStateIsMaintainedAndCarriesItsPosition() {
        val snap = ResumeSnapshot(items = refs, index = 1, posMs = 42_000)
        val byKey = refs.associate { SongKey(it.provider, it.songId) to song(it.songId) }
        val st = assertNotNull(restoreState(snap, byKey, snap.posMs))
        assertTrue(st.maintained, "a restored state must be validated and carry a nextIndex")
        assertEquals(42_000L, st.posMs)
        assertTrue(st.phase is Phase.Paused, "restore never autoplays")
    }

    @Test
    fun unresolvableSnapshotRestoresToNull() {
        val snap = ResumeSnapshot(items = refs, index = 0)
        assertNull(restoreState(snap, emptyMap(), 0L))
    }
}

class ShuffleOrderTest {
    @Test
    fun anEditThatGrowsTheQueueRebuildsTheOrderAroundTheAnchor() {
        val queue = listOf(song("a"), song("b"), song("c"))
        val base =
            PlayerState().withQueueMutation(
                queue = queue.toPersistentList(),
                index = 1,
                current = queue[1],
                shuffleOn = true,
            )
        val grown =
            base.withQueueMutation(
                queue = (queue + song("z")).toPersistentList(),
                index = 1,
                current = base.current,
            )
        assertEquals(1, grown.shuffleOrder?.first(), "the item the user is on keeps playing first")
        assertEquals(listOf(0, 1, 2, 3), grown.shuffleOrder?.sorted())
        assertNotNull(grown.nextIndex)
    }

    @Test
    fun aStaleOrderIsRebuiltRatherThanKept() {
        val stale = persistentListOf(7, 8, 9)
        val st =
            PlayerState().withQueueMutation(
                queue = listOf(song("a"), song("b"), song("c")).toPersistentList(),
                index = 0,
                current = song("a"),
                shuffleOn = true,
                shuffleOrder = stale,
            )
        assertEquals(listOf(0, 1, 2), st.shuffleOrder?.sorted(), "a stale order is not navigable")
    }
}
