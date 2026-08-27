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
import dylan.playback.sanitizeSnapshot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun song(id: String) =
    Song(
        key = SongKey("saavn", id),
        title = id,
        subtitle = "",
        albumId = null,
        albumName = null,
        artUrl150 = "",
        artUrl500 = "",
        durationS = 100,
        has320 = true,
        cacheable = true,
        resolveRef = "ref-$id",
        permaToken = null,
    )

class NextUpTest {
    private fun state(
        queue: List<Song>,
        index: Int,
        shuffle: Boolean = false,
        order: List<Int>? = null,
        repeat: Repeat = Repeat.OFF,
    ) = PlayerState(
        phase = Phase.Playing(queue[index].key),
        current = queue[index],
        queue = queue.toPersistentList(),
        index = index,
        shuffleOn = shuffle,
        shuffleOrder = order?.toPersistentList(),
        repeat = repeat,
    )

    private val q = listOf(song("a"), song("b"), song("c"))

    @Test
    fun linearNext() {
        assertEquals("b", state(q, 0).nextUp?.key?.songId)
    }

    @Test
    fun linearEndIsNull() {
        assertNull(state(q, 2).nextUp)
    }

    @Test
    fun repeatOneNextIsCurrent() {
        assertEquals("b", state(q, 1, repeat = Repeat.ONE).nextUp?.key?.songId)
    }

    @Test
    fun repeatAllWraps() {
        assertEquals("a", state(q, 2, repeat = Repeat.ALL).nextUp?.key?.songId)
    }

    @Test
    fun shuffleWalksOrderNotSlotZero() {
        val s = state(q, 0, shuffle = true, order = listOf(2, 0, 1))
        assertEquals("b", s.nextUp?.key?.songId, "next follows playback order, not order[0]")
    }

    @Test
    fun staleShuffleOrderYieldsNullNotSlotZero() {
        val s = state(q, 0, shuffle = true, order = listOf(1, 2))
        assertNull(s.nextUp, "current missing from permutation must yield null")
    }

    @Test
    fun removeSuccessorUnderShuffleKeepsCorrectSlot() {
        val q2 = listOf(song("a"), song("c"))
        val s =
            PlayerState(
                phase = Phase.Playing(q2[0].key),
                current = q2[0],
                queue = q2.toPersistentList(),
                index = 0,
                shuffleOn = true,
                shuffleOrder = persistentListOf(0, 1),
                repeat = Repeat.OFF,
            )
        assertEquals("c", s.nextUp?.key?.songId)
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
}
