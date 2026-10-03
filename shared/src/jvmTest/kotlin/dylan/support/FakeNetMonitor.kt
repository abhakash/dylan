package dylan.support

import dylan.util.NetClass
import dylan.util.NetMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Explicit network control for tests. Defaults to the documented failure policy
 * ([dylan.util.NetMonitorPolicy]: METERED, offline) so no test can inherit "the network is
 * baked in" the way the old hardcoded JVM `NetMonitor` stub did.
 */
class FakeNetMonitor(
    netClass: NetClass = dylan.util.NetMonitorPolicy.UNKNOWN_NET_CLASS,
    online: Boolean = dylan.util.NetMonitorPolicy.UNKNOWN_ONLINE,
) : NetMonitor {
    private val netClassState = MutableStateFlow(netClass)
    private val onlineState = MutableStateFlow(online)

    /** Mirrors the platform push API (Android/iOS) so one fake drives both shapes. */
    fun pushMetered(isMetered: Boolean) {
        netClassState.value = if (isMetered) NetClass.METERED else NetClass.UNMETERED
    }

    fun pushOnline(isOnline: Boolean) {
        onlineState.value = isOnline
    }

    fun set(
        online: Boolean,
        netClass: NetClass,
    ) {
        pushOnline(online)
        netClassState.value = netClass
    }

    override fun current(): NetClass = netClassState.value

    override fun isOnline(): Boolean = onlineState.value

    override fun changes(): Flow<NetClass> = netClassState
}
