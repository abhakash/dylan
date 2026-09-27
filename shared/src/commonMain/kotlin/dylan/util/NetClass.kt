package dylan.util

import kotlinx.coroutines.flow.Flow

enum class NetClass { METERED, UNMETERED }

expect class NetMonitor {
    fun current(): NetClass

    /** True when any usable network path exists (false = airplane/offline). */
    fun isOnline(): Boolean

    fun changes(): Flow<NetClass>
}

expect fun freeDiskBytes(path: String): Long
