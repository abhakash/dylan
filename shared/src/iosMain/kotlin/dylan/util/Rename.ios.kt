package dylan.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.rename

@OptIn(ExperimentalForeignApi::class)
actual fun fsRename(
    from: String,
    to: String,
) {
    val rc = rename(from, to)
    check(rc == 0) { "rename($from -> $to) failed" }
}
