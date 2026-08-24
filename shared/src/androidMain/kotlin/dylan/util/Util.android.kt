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

actual fun nowMs(): Long = System.currentTimeMillis()

actual class NetMonitor(
    private val ctx: Context,
) {
    actual fun current(): NetClass {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetClass.METERED
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetClass.METERED
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val restricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        return if (unmetered && restricted) NetClass.UNMETERED else NetClass.METERED
    }

    actual fun changes(): Flow<NetClass> =
        callbackFlow {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                trySend(NetClass.METERED)
                awaitClose { }
                return@callbackFlow
            }
            val cb =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        trySend(
                            if (caps.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                                )
                            ) {
                                NetClass.UNMETERED
                            } else {
                                NetClass.METERED
                            },
                        )
                    }
                }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            awaitClose { cm.unregisterNetworkCallback(cb) }
        }
}

actual fun freeDiskBytes(path: String): Long = runCatching { StatFs(path).availableBytes }.getOrDefault(-1L)
