package dylan.util

import android.content.Context
import android.net.ConnectivityManager
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
 * The one predicate for "is this path unmetered" — `changes()` and `current()` both go through
 * it, so the metered badge and the download policy cannot disagree.
 */
private fun NetworkCapabilities.isUnmeteredPath(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

class AndroidNetMonitor(
    private val ctx: Context,
) : NetMonitor {
    private val service: ConnectivityManager? by lazy { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }

    override fun current(): NetClass = activeCapabilities()?.classOf() ?: NetMonitorPolicy.UNKNOWN_NET_CLASS

    override fun isOnline(): Boolean = activeCapabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: NetMonitorPolicy.UNKNOWN_ONLINE

    override fun changes(): Flow<NetClass> =
        callbackFlow {
            val cm = service
            if (cm == null) {
                trySend(NetMonitorPolicy.UNKNOWN_NET_CLASS)
                awaitClose { }
                return@callbackFlow
            }
            val cb =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        trySend(caps.classOf())
                    }

                    override fun onLost(network: Network) {
                        trySend(NetMonitorPolicy.UNKNOWN_NET_CLASS)
                    }
                }
            cm.registerNetworkCallback(
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
            awaitClose { cm.unregisterNetworkCallback(cb) }
        }.distinctUntilChanged()

    private fun activeCapabilities(): NetworkCapabilities? {
        val cm = service ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(net)
    }

    private fun NetworkCapabilities.classOf(): NetClass = if (isUnmeteredPath()) NetClass.UNMETERED else NetClass.METERED
}

/** Same-named factory so the pre-existing `NetMonitor(this)` construction sites keep working. */
fun NetMonitor(ctx: Context): NetMonitor = AndroidNetMonitor(ctx)

actual fun freeDiskBytes(path: String): Long = runCatching { StatFs(path).availableBytes }.getOrDefault(DISK_UNKNOWN)
