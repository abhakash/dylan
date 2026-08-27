package dylan.util

import kotlinx.coroutines.flow.Flow

enum class NetClass { METERED, UNMETERED }

expect class NetMonitor {
    fun current(): NetClass

    fun changes(): Flow<NetClass>
}

expect fun freeDiskBytes(path: String): Long
