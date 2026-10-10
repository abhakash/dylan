package dylan.util

import platform.Foundation.NSLog

internal actual fun logErr(msg: String) {
    NSLog("dylan: %@", msg)
}
