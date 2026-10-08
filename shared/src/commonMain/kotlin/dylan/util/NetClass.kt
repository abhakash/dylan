package dylan.util

import kotlinx.coroutines.flow.Flow

enum class NetClass { METERED, UNMETERED }

/** `freeDiskBytes` failure sentinel. Never a real byte count: 0 would read as "no space". */
const val DISK_UNKNOWN: Long = -1L

/**
 * Connectivity seam. Implementations MUST agree on all three points, which is why they are
 * an interface and not three unrelated `expect` classes:
 *
 *  - **Unmetered** means the platform's own "cheap" verdict: Android
 *    `NOT_METERED && NOT_RESTRICTED`, iOS `!isExpensive && !isConstrained`. The badge the UI
 *    shows and the bitrate the download policy picks read this one answer.
 *  - **Unknown connectivity fails closed** ([NetClass.METERED], `isOnline() == false`): never
 *    claim a connection or an unmetered path we cannot verify. Cost of being wrong is a 128 kbps
 *    download instead of a 320 kbps one.
 *  - `changes()` uses the same predicate as [current], so a flow emission can never contradict
 *    a poll.
 */
interface NetMonitor {
    fun current(): NetClass

    /** True when any usable network path exists (false = airplane/offline, or unknown). */
    fun isOnline(): Boolean

    fun changes(): Flow<NetClass>
}

/** Documents the shared failure default so a test fake cannot quietly disagree with production. */
object NetMonitorPolicy {
    val UNKNOWN_NET_CLASS: NetClass = NetClass.METERED

    const val UNKNOWN_ONLINE: Boolean = false
}

expect fun freeDiskBytes(path: String): Long
