package dylan.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.timeIntervalSince1970

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * D14 semantics: METERED ⇔ path.isExpensive || path.isConstrained, evaluated by the Swift app
 * layer (NWPathMonitor) which pushes updates into [pushMetered]. Default UNMETERED until first push.
 */
actual class NetMonitor {
    private val state = MutableStateFlow(NetClass.UNMETERED)

    actual fun current(): NetClass = state.value

    actual fun changes(): Flow<NetClass> = state

    /** Called by the Swift app whenever NWPathMonitor reports a change. */
    fun pushMetered(isMetered: Boolean) {
        state.value = if (isMetered) NetClass.METERED else NetClass.UNMETERED
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun freeDiskBytes(path: String): Long =
    runCatching {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null) ?: return@runCatching -1L
        (attrs[platform.Foundation.NSFileSystemFreeSize] as? NSNumberBridge)?.longValue ?: -1L
    }.getOrDefault(-1L)

private typealias NSNumberBridge = platform.Foundation.NSNumber
