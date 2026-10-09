package dylan.support

import dylan.util.Connectivity
import dylan.util.NetClass
import dylan.util.NetMonitor
import dylan.util.NetMonitorPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Explicit network control for tests. Defaults to the documented failure policy
 * ([dylan.util.NetMonitorPolicy]: METERED, offline) so no test can inherit "the network is
 * baked in" the way the old hardcoded JVM `NetMonitor` stub did.
 *
 * The state is one [Connectivity] because that is what a real monitor publishes: the two halves are
 * set independently by the platform, and a fake that held them as two unlinked values could not
 * produce the mixed state F-23 was about (offline, metered — and then back online on the *same*
 * metered path).
 */
class FakeNetMonitor(
    netClass: NetClass = NetMonitorPolicy.UNKNOWN_NET_CLASS,
    online: Boolean = NetMonitorPolicy.UNKNOWN_ONLINE,
) : NetMonitor {
    private val state = MutableStateFlow(Connectivity(online, netClass))

    /** Mirrors the platform push API (Android/iOS) so one fake drives both shapes. */
    fun pushMetered(isMetered: Boolean) {
        state.value = state.value.copy(netClass = if (isMetered) NetClass.METERED else NetClass.UNMETERED)
    }

    fun pushOnline(isOnline: Boolean) {
        state.value = state.value.copy(online = isOnline)
    }

    fun set(
        online: Boolean,
        netClass: NetClass,
    ) {
        state.value = Connectivity(online, netClass)
    }

    override fun connectivity(): Flow<Connectivity> = state

    override fun current(): NetClass = state.value.netClass

    override fun isOnline(): Boolean = state.value.online
}
