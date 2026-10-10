package dylan.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class NetClass { METERED, UNMETERED }

/** `freeDiskBytes` failure sentinel. Never a real byte count: 0 would read as "no space". */
const val DISK_UNKNOWN: Long = -1L

/**
 * Both halves of connectivity, as one emission.
 *
 * They are independent and must stay that way, which is why they are one type with two fields
 * rather than one enum:
 *
 *  - [online] is whether a usable network path exists at all.
 *  - [netClass] is whether that path is cheap.
 *
 * Collapsing them is the defect that produced F-23. The UI's offline gate was derived from
 * [NetMonitor.changes], which reports only [netClass] — a **two-valued** ladder. An outage takes
 * `METERED` (unknown fails closed as [NetClass.METERED]) and a returning mobile-data path is also
 * `METERED`, so the flow's `distinctUntilChanged` swallowed the transition, the composition's
 * `remember(netClass)` never re-ran, and the UI's answer latched `false` for the rest of the session.
 * Meanwhile every request kept succeeding, because the request path polls [NetMonitor.isOnline] at
 * request time. Two implementations of the same predicate, answering differently at the same moment:
 * results on screen, every song row dimmed as unplayable, and album/artist rows beside them at full
 * alpha because they carry no offline gate.
 */
data class Connectivity(
    val online: Boolean,
    val netClass: NetClass,
)

/** Documents the shared failure default so a test fake cannot quietly disagree with production. */
object NetMonitorPolicy {
    val UNKNOWN_NET_CLASS: NetClass = NetClass.METERED

    const val UNKNOWN_ONLINE: Boolean = false

    /** Unknown connectivity, as one value: fail closed on both halves. */
    val UNKNOWN_CONNECTIVITY: Connectivity = Connectivity(UNKNOWN_ONLINE, UNKNOWN_NET_CLASS)
}

/**
 * Connectivity seam. Implementations MUST agree on all three points, which is why they are
 * an interface and not three unrelated `expect` classes:
 *
 *  - **Unmetered** means the platform's own "cheap" verdict: Android
 *    `NOT_METERED && NOT_RESTRICTED`, iOS `!isExpensive && !isConstrained`. The badge the UI
 *    shows and the bitrate the download policy picks read this one answer.
 *  - **Unknown connectivity fails closed** ([NetMonitorPolicy.UNKNOWN_CONNECTIVITY]): never
 *    claim a connection or an unmetered path we cannot verify. Cost of being wrong is a 128 kbps
 *    download instead of a 320 kbps one.
 *  - `changes()` and `online()` are both derived from [connectivity], so a flow emission can never
 *    contradict a poll, and neither half can be reconstructed from the other.
 */
interface NetMonitor {
    /**
     * Both halves, as a flow, emitted on every network event.
     *
     * The one member a platform implements. Everything below is derived from it in `commonMain`, so
     * no platform can ship a half that contradicts the other — which is the whole point of putting
     * it here rather than as two platform-specific flows.
     */
    fun connectivity(): Flow<Connectivity>

    fun current(): NetClass

    /** True when any usable network path exists (false = airplane/offline, or unknown). */
    fun isOnline(): Boolean

    /**
     * The metered-ness half only: what the badge and the download policy consume.
     *
     * Distinct by construction, because [Connectivity.netClass] is a two-valued ladder — that is a
     * property of the half, not a loss of information.
     */
    fun changes(): Flow<NetClass> = connectivity().map { it.netClass }.distinctUntilChanged()

    /**
     * The connectivity half only: what the UI's offline gate consumes.
     *
     * Derived from [connectivity] and **not** from [changes], because [changes] cannot express it:
     * a transition that leaves the metered-ness of the path unchanged is invisible to
     * `distinctUntilChanged` on a two-valued enum, and an offline→online transition on a metered
     * path is exactly that. Asserted in `NetMonitorSignalTest`.
     */
    fun online(): Flow<Boolean> = connectivity().map { it.online }.distinctUntilChanged()
}

expect fun freeDiskBytes(path: String): Long
