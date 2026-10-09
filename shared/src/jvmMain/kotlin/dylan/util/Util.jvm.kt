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
 *
 * Both halves live in one [Connectivity] state so the two derived flows (`changes()` and
 * `online()`) can never disagree about what the monitor knows.
 */
class JvmNetMonitor(
    netClass: NetClass = NetClass.METERED,
    online: Boolean = false,
) : NetMonitor {
    private val state = MutableStateFlow(Connectivity(online, netClass))

    override fun connectivity(): Flow<Connectivity> = state

    override fun current(): NetClass = state.value.netClass

    override fun isOnline(): Boolean = state.value.online

    fun push(
        online: Boolean,
        netClass: NetClass,
    ) {
        state.value = Connectivity(online, netClass)
    }

    /** Mirrors the platform push API so one signature drives every test that needs a metered toggle. */
    fun pushMetered(isMetered: Boolean) {
        push(state.value.online, if (isMetered) NetClass.METERED else NetClass.UNMETERED)
    }

    fun pushOnline(isOnline: Boolean) {
        push(isOnline, state.value.netClass)
    }
}

/** Free bytes on the volume holding [path], or [DISK_UNKNOWN] if it cannot be determined. */
actual fun freeDiskBytes(path: String): Long =
    runCatching {
        val f = File(path)
        if (!f.exists()) return@runCatching DISK_UNKNOWN
        f.usableSpace
    }.getOrDefault(DISK_UNKNOWN)
