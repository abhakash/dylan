package dylan.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.timeIntervalSince1970

// Since the new memory model `kotlin.native.concurrent.ThreadLocal` is an annotation, not a
// generic holder: a top-level property carrying it is materialised per thread.
@kotlin.native.concurrent.ThreadLocal
private var nativeLane: Lane? = null

internal actual class LaneThreadLocal actual constructor() {
    actual fun get(): Lane? = nativeLane

    actual fun set(lane: Lane?) {
        nativeLane = lane
    }
}

internal actual fun platformNowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * D14 semantics: METERED ⇔ `path.isExpensive || path.isConstrained`, evaluated by the Swift
 * layer's `NWPathMonitor` and pushed in through [pushMetered] / [pushOnline].
 *
 * Both values live in [MutableStateFlow]s because Swift writes them from the `NWPathMonitor`
 * dispatch queue while the state lane reads them — a plain `var` was a data race. Both start at
 * the documented failure default ([NetMonitorPolicy]) rather than at "connected and cheap", so
 * before the first push the app behaves exactly as it does on a device with no path.
 */
class IosNetMonitor : NetMonitor {
    private val netClass = MutableStateFlow(NetMonitorPolicy.UNKNOWN_NET_CLASS)
    private val onlineState = MutableStateFlow(NetMonitorPolicy.UNKNOWN_ONLINE)

    override fun current(): NetClass = netClass.value

    override fun isOnline(): Boolean = onlineState.value

    override fun changes(): Flow<NetClass> = netClass

    fun pushMetered(isMetered: Boolean) {
        netClass.value = if (isMetered) NetClass.METERED else NetClass.UNMETERED
    }

    fun pushOnline(isOnline: Boolean) {
        onlineState.value = isOnline
    }
}

/** Same-named factory so the pre-existing `NetMonitor()` construction sites keep working. */
fun NetMonitor(): NetMonitor = IosNetMonitor()

@OptIn(ExperimentalForeignApi::class)
actual fun freeDiskBytes(path: String): Long =
    runCatching {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null) ?: return@runCatching DISK_UNKNOWN
        (attrs[platform.Foundation.NSFileSystemFreeSize] as? platform.Foundation.NSNumber)?.longValue ?: DISK_UNKNOWN
    }.getOrDefault(DISK_UNKNOWN)
