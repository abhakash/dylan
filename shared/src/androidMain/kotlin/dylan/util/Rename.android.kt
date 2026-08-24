package dylan.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

actual fun fsRename(
    from: String,
    to: String,
) {
    val src = Path.of(from)
    val dst = Path.of(to)
    try {
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
    }
}
