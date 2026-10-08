package dylan.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

internal actual class LaneThreadLocal actual constructor() {
    private val tl = ThreadLocal<Lane?>()

    actual fun get(): Lane? = tl.get()

    actual fun set(lane: Lane?) {
        tl.set(lane)
    }
}

internal actual fun platformNowMs(): Long = System.currentTimeMillis()

/**
 * No platform connectivity API on the JVM, so this cannot answer honestly on its own: it
 * reports the documented failure default ([NetMonitorPolicy]) unless it is told otherwise.
 * Tests construct [JvmNetMonitor] with an explicit value (or use `FakeNetMonitor`) — the
 * network is never baked in.
 */
class JvmNetMonitor(
    private val netClass: NetClass = NetClass.METERED,
    private val online: Boolean = false,
) : NetMonitor {
    private val state = MutableStateFlow(netClass)

    override fun current(): NetClass = state.value

    override fun isOnline(): Boolean = online

    override fun changes(): Flow<NetClass> = state

    fun pushMetered(isMetered: Boolean) {
        state.value = if (isMetered) NetClass.METERED else NetClass.UNMETERED
    }
}

/** Free bytes on the volume holding [path], or [DISK_UNKNOWN] if it cannot be determined. */
actual fun freeDiskBytes(path: String): Long =
    runCatching {
        val f = File(path)
        if (!f.exists()) return@runCatching DISK_UNKNOWN
        f.usableSpace
    }.getOrDefault(DISK_UNKNOWN)
