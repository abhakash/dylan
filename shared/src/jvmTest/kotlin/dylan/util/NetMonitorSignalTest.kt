package dylan.util

import dylan.support.FakeNetMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The connectivity seam's one contract: the two halves are independent and neither can be
 * reconstructed from the other.
 *
 * This is where F-23 lived. `androidApp`'s `rememberIsOnline` derived the UI's offline gate from
 * `NetMonitor.changes()` — the **metered-ness** half — and re-evaluated it inside
 * `remember(netClass) { … }`. An outage reports `METERED` (unknown fails closed) and a returning
 * mobile-data path also reports `METERED`, so `changes()`' `distinctUntilChanged` swallowed the
 * transition and the UI's verdict latched `false` while the request path, which polls
 * [NetMonitor.isOnline], kept succeeding. Results on screen, every song row dimmed.
 *
 * [NetMonitor.online] is the fix, and these tests are the contract it has to keep.
 */
class NetMonitorSignalTest {
    /**
     * A monitor in the state that broke the app: offline **and** metered.
     *
     * Written out rather than taking arguments because [FakeNetMonitor]'s own defaults *are*
     * [NetMonitorPolicy]'s failure defaults — the same policy every platform monitor starts from.
     */
    private fun failedClosedMonitor(): FakeNetMonitor = FakeNetMonitor()

    /**
     * The red test. An offline→online transition that leaves the metered-ness of the path unchanged
     * must still reach the UI's offline gate. The live sequence was: Wi-Fi lost (`onLost` →
     * `METERED`), mobile data up (`onCapabilitiesChanged` → `METERED` again). Mutating `online()` to
     * derive from [NetMonitor.changes] makes this fail on the second element, which is the bug.
     */
    @Test
    fun anOfflineToOnlineTransitionOnAnUnchangedNetClassIsStillObserved() =
        runTest {
            val net = failedClosedMonitor()
            assertEquals(listOf(false, true), collected(net, { it.online() }, { it.pushOnline(true) }))
        }

    /**
     * The control for the test above: `changes()` really does see nothing new, which is why the old
     * UI derived a permanent answer from it. Pinned so that a future change to `changes()` that
     * makes it "carry" connectivity is a visible decision rather than an accident.
     */
    @Test
    fun changesReportsOnlyTheMeterednessSoItCannotCarryAConnectivityTransition() =
        runTest {
            val net = failedClosedMonitor()
            assertEquals(listOf(NetClass.METERED), collected(net, { it.changes() }, { it.pushOnline(true) }))
        }

    /** Metered-ness still has its own signal, unchanged, for the badge and the download policy. */
    @Test
    fun aMeterednessChangeIsStillReported() =
        runTest {
            val net = FakeNetMonitor(NetClass.METERED, online = true)
            assertEquals(
                listOf(NetClass.METERED, NetClass.UNMETERED),
                collected(net, { it.changes() }, { it.pushMetered(false) }),
            )
        }

    /** Unknown connectivity fails closed on both halves, and no flow claims otherwise. */
    @Test
    fun unknownConnectivityFailsClosedOnBothHalves() {
        val net = FakeNetMonitor()
        assertEquals(NetMonitorPolicy.UNKNOWN_NET_CLASS, net.current())
        assertFalse(net.isOnline())
    }

    /** A poll and an emission cannot disagree, because both derive from the same state. */
    @Test
    fun aPollAndAnEmissionAgree() =
        runTest {
            val net = failedClosedMonitor()
            val seen = collected(net, { it.online() }, { it.pushOnline(true) })
            assertTrue(seen.last(), "the last emission is the new truth")
            assertTrue(net.isOnline(), "and a poll after it cannot disagree")
        }

    /**
     * Both halves travel together, so no consumer ever sees a value where one half has moved and the
     * other has not — the shape the UI used to construct by pairing `changes()` with a poll.
     */
    @Test
    fun connectivityCarriesBothHalvesTogether() =
        runTest {
            val net = failedClosedMonitor()
            val both =
                collected(net, { it.connectivity() }, { it.pushOnline(true) }, { it.pushMetered(false) })
            assertEquals(
                listOf(
                    Connectivity(false, NetClass.METERED),
                    Connectivity(true, NetClass.METERED),
                    Connectivity(true, NetClass.UNMETERED),
                ),
                both,
            )
        }

    /** The online half is emitted, not derived from a path-freshness the monitor does not track. */
    @Test
    fun everyEmissionOfOnlineCarriesTheHalfItClaimsTo() =
        runTest {
            val net = failedClosedMonitor()
            val seen =
                collected(net, { it.online() }, { it.pushOnline(true) }, { it.pushOnline(false) })
            assertEquals(listOf(false, true, false), seen)
        }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /**
     * Everything the monitor's [flow] emits while each of [mutations] is applied in turn, in order,
     * starting from the replay of the monitor's current value.
     *
     * Advances between mutations because [kotlinx.coroutines.flow.StateFlow] conflates: two pushes
     * before the collector resumes would reach it as one value, and `distinctUntilChanged` would
     * then swallow the round trip. Advancing per mutation is what makes the sequence above mean what
     * it says.
     */
    private fun <T> TestScope.collected(
        net: FakeNetMonitor,
        flow: (FakeNetMonitor) -> Flow<T>,
        vararg mutations: (FakeNetMonitor) -> Unit,
    ): List<T> {
        val out = mutableListOf<T>()
        val job = launch { flow(net).collect { value -> out += value } }
        advanceUntilIdle()
        mutations.forEach { mutate ->
            mutate(net)
            advanceUntilIdle()
        }
        job.cancel()
        return out
    }
}
