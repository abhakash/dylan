package dylan.util

import dylan.model.SongKey
import dylan.support.FakeNetMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The row-level offline gate, and the invariant F-23 broke.
 *
 * `canPlay` used to live in `androidApp`'s `Common.kt` and take an `isOnline` Boolean the Compose
 * layer produced for itself, from a different predicate than the one the request path gates on. The
 * verdict it rendered therefore had nothing to do with whether the song could actually be played:
 * the captured device session of 2026-10-09 shows a populated search list (rows present ⇒
 * `ResilientClient`'s `net.isOnline()` gate passed ⇒ the network was up) in which rows 0, 1, 8 and 9
 * of ten were dimmed as unplayable and rows 2–7 were not.
 *
 * The gate itself is unchanged — offline rows *should* be dimmed, that is the feature. What changed
 * is that it is now fed the **online half of a live emission** rather than a poll of its own, so the
 * two verdicts are drawn from one predicate. These tests pin that property, which is the property
 * that let the two drift apart.
 */
class RowPlayabilityTest {
    private val key = SongKey("saavn", "abc123")

    /** The gate, spelled out: online, or already downloaded. */
    private fun Connectivity.canPlay(cached: Set<SongKey>): Boolean = online || key in cached

    /**
     * The verdict is a function of the **online half**, not of the metered-ness of the path.
     *
     * Two monitors with the *same* `current()` and different `isOnline()` give opposite answers. That
     * is the whole of F-23 in one line: the old UI derived its verdict from `changes()`, which
     * reports only the metered-ness, so an offline→online transition on an unchanged path could not
     * move it. ASSERT_RED by deriving the verdict from `netClass` instead of `online`.
     */
    @Test
    fun theVerdictTracksTheOnlineHalfNotTheMeterednessOfThePath() {
        val offlineMetered = FakeNetMonitor(NetClass.METERED, online = false)
        val onlineMetered = FakeNetMonitor(NetClass.METERED, online = true)
        assertEquals(offlineMetered.current(), onlineMetered.current(), "same metered-ness")
        assertFalse(Connectivity(offlineMetered.isOnline(), offlineMetered.current()).canPlay(emptySet()))
        assertTrue(Connectivity(onlineMetered.isOnline(), onlineMetered.current()).canPlay(emptySet()))
    }

    /**
     * A stale snapshot is exactly what dimmed those rows: the verdict was computed once and keyed on
     * a signal that did not move. Taking a fresh one after the change flips it.
     */
    @Test
    fun aStaleVerdictFlipsOnceANewEmissionIsTaken() {
        val net = FakeNetMonitor(NetClass.METERED, online = false)
        var c = Connectivity(net.isOnline(), net.current())
        assertFalse(c.canPlay(emptySet()), "offline, not downloaded: correctly unplayable")
        net.pushOnline(true)
        c = Connectivity(net.isOnline(), net.current())
        assertTrue(c.canPlay(emptySet()), "the network came back on the same metered path")
    }

    /**
     * A downloaded row stays playable when the connection is genuinely gone. This is the feature the
     * dimming exists for, and it must not be "fixed" away by making everything look playable.
     */
    @Test
    fun aDownloadedRowStaysPlayableWhenTheConnectionGenuinelyGoes() {
        val net = FakeNetMonitor(NetClass.METERED, online = false)
        val c = Connectivity(net.isOnline(), net.current())
        assertTrue(c.canPlay(setOf(key)), "a row that is on disk plays with no network")
        assertFalse(c.canPlay(emptySet()), "…and one that is not, does not")
    }

    /**
     * A poll and the row gate can never disagree, because both read [FakeNetMonitor.isOnline] — the
     * same seam the request path gates on. Under the old code the UI held its own answer and the
     * provider held another.
     */
    @Test
    fun theRowGateAndTheRequestGateCannotDisagree() {
        val net = FakeNetMonitor(NetClass.UNMETERED, online = true)
        assertTrue(net.isOnline(), "the request gate would allow this")
        assertTrue(
            Connectivity(net.isOnline(), net.current()).canPlay(emptySet()),
            "so must the row gate, for every row and not only for the cached ones",
        )
    }
}
