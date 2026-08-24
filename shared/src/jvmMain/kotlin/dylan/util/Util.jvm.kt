package dylan.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

actual fun nowMs(): Long = System.currentTimeMillis()

actual class NetMonitor {
    actual fun current(): NetClass = NetClass.UNMETERED

    actual fun changes(): Flow<NetClass> = emptyFlow()
}

actual fun freeDiskBytes(path: String): Long = File(path).usableSpace
