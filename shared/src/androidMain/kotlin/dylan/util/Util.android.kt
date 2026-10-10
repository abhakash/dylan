package dylan.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.StatFs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal actual class LaneThreadLocal actual constructor() {
    private val tl = ThreadLocal<Lane?>()

    actual fun get(): Lane? = tl.get()

    actual fun set(lane: Lane?) {
        tl.set(lane)
    }
}

internal actual fun platformNowMs(): Long = System.currentTimeMillis()

/**
 * The one predicate for "is this path unmetered" — `current()` and every emission both go through
 * it, so the metered badge and the download policy cannot disagree.
 */
private fun NetworkCapabilities.isUnmeteredPath(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

class AndroidNetMonitor(
    private val ctx: Context,
) : NetMonitor {
    private val service: ConnectivityManager? by lazy {
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    override fun current(): NetClass = snapshot().netClass

    override fun isOnline(): Boolean = snapshot().online

    /**
     * Both halves, on **every** network event.
     *
     * Two things this must not do, both of which the previous `changes()` did:
     *
     *  1. it must not filter on metered-ness — an event that flips only [Connectivity.online] is
     *     exactly the event the UI's offline gate has to see, which is the whole of F-23; and
     *  2. it must not treat [Connectivity.online] as derivable from [Connectivity.netClass], which
     *     is what `remember(netClass) { activeNetwork != null }` in the Compose layer did.
     *
     * The callback structure is [ConnectivityManager.NetworkCallback] rather than a poll because the
     * only other option — polling `isOnline()` on a timer — makes the gate lag by the poll interval,
     * and "offline for two seconds after the network came back" is the same defect with a smaller
     * window.
     */
    override fun connectivity(): Flow<Connectivity> =
        callbackFlow {
            val cm = service
            if (cm == null) {
                trySend(NetMonitorPolicy.UNKNOWN_CONNECTIVITY)
                awaitClose { }
                return@callbackFlow
            }
            val cb =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(snapshot())
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        trySend(snapshot())
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties,
                    ) {
                        trySend(snapshot())
                    }

                    override fun onBlockedStatusChanged(
                        network: Network,
                        blocked: Boolean,
                    ) {
                        trySend(snapshot())
                    }

                    override fun onLost(network: Network) {
                        trySend(snapshot())
                    }

                    override fun onUnavailable() {
                        trySend(NetMonitorPolicy.UNKNOWN_CONNECTIVITY)
                    }
                }
            cm.registerNetworkCallback(
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
            // The callback only fires on a *change*, so without this the first collector would wait
            // for one. Registration does queue `onAvailable`/`onCapabilitiesChanged` for the current
            // network, but relying on the order of those relative to this line is not something a
            // reader can verify; sending the snapshot explicitly makes the first value deterministic.
            trySend(snapshot())
            awaitClose { cm.unregisterNetworkCallback(cb) }
        }.distinctUntilChanged()

    /**
     * The one place both halves are read, so a poll and an emission cannot disagree.
     *
     * [Connectivity.online] is `NET_CAPABILITY_INTERNET`, which is what the request path has always
     * gated on; [Connectivity.netClass] is `isUnmeteredPath`, which is what the badge and the
     * download policy have always read.
     */
    private fun snapshot(): Connectivity {
        val caps = activeCapabilities() ?: return NetMonitorPolicy.UNKNOWN_CONNECTIVITY
        val klass = if (caps.isUnmeteredPath()) NetClass.UNMETERED else NetClass.METERED
        return Connectivity(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), klass)
    }

    private fun activeCapabilities(): NetworkCapabilities? {
        val cm = service ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(net)
    }
}

/** Same-named factory so the pre-existing `NetMonitor(this)` construction sites keep working. */
fun NetMonitor(ctx: Context): NetMonitor = AndroidNetMonitor(ctx)

actual fun freeDiskBytes(path: String): Long = runCatching { StatFs(path).availableBytes }.getOrDefault(DISK_UNKNOWN)
