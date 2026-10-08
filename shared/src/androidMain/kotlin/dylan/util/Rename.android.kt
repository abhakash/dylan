package dylan.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The `.part` → final rename. `ATOMIC_MOVE` first, so the common case is a single `rename(2)` that
 * cannot leave a half-written file at the final name and keeps the source intact if it fails.
 *
 * **The fallback is not dead code, and it does cross filesystems.** A previous audit (#15) claimed
 * the `REPLACE_EXISTING` retry could not work across volumes because "java.nio does no implicit
 * copy+delete". That is wrong, and it was measured before being acted on: on a genuine cross-device
 * pair (a `hdiutil` ram disk against the boot volume, two distinct device IDs) `ATOMIC_MOVE` throws
 * `AtomicMoveNotSupportedException` and this fallback then **succeeds**, leaving the complete file
 * at the destination. `sun.nio.fs.UnixCopyFile.move` falls back to `copyFile(...)` on `EXDEV` for
 * exactly this reason; `WindowsFileCopy.move` uses `MoveFileEx(..., MOVEFILE_COPY_ALLOWED)`.
 *
 * It was deliberately *not* replaced with a hand-rolled copy+delete. That version was also measured
 * and it is worse: `Files.move`'s copy path rolls the target back if it fails, whereas writing
 * straight to the final name truncates the previous complete file and leaves a partial one there.
 * `RenameTest` pins both properties.
 */
actual fun fsRename(
    from: String,
    to: String,
) {
    val src = Path.of(from)
    val dst = Path.of(to)
    try {
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
    } catch (expected: java.nio.file.AtomicMoveNotSupportedException) {
        // Expected on any filesystem that cannot move atomically (SD card, some FUSE mounts) and on
        // a source and destination in different volumes. It says nothing about the file's state —
        // nothing has been moved or truncated — so the only useful action is the same move without
        // ATOMIC_MOVE, which is why the exception is dropped. The provider then falls back to
        // copy+delete on its own, and rolls the destination back if that copy fails.
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
    }
}
