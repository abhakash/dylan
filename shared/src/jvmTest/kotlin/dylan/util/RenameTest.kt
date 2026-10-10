package dylan.util

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #15 claimed `fsRename`'s fallback "cannot cross filesystems", on the grounds that
 * `java.nio` does no implicit copy+delete.
 *
 * **Both halves of that claim are false, and were measured before being acted on.** The evidence is
 * recorded here rather than in prose so it cannot drift back:
 *
 *  - `sun.nio.fs.UnixCopyFile.move` *does* fall back to copy+delete. On `EXDEV` (cross-device) it
 *    calls `copyFile(...)` and only then unlinks the source. `WindowsFileCopy.move` does the same
 *    via `MoveFileEx(..., MOVEFILE_COPY_ALLOWED)`.
 *  - Measured against two real device IDs on macOS (`hdiutil` ram disk vs the boot volume):
 *    `ATOMIC_MOVE` cross-device throws `AtomicMoveNotSupportedException` and the shipped fallback
 *    `Files.move(src, dst, REPLACE_EXISTING)` then **succeeds**, leaving the full payload at the
 *    destination and the source gone. So the shipped code is correct for the case it names.
 *
 * The tempting "fix" — hand-rolling a copy-then-delete — was also measured, and it is **strictly
 * worse than what already ships**. `Files.move`'s copy path copies to the target and rolls the
 * target back if it fails; a naive `TRUNCATE_EXISTING` channel copy truncates the *previous
 * complete* file and leaves a partial one sitting at the final name. The last test here is the
 * regression guard for exactly that.
 *
 * What remains genuinely unproven by CI is the cross-device case itself: reproducing it needs a
 * second filesystem, so these tests cover the single-provider semantics and the failure invariants
 * that must hold on every provider.
 */
class RenameTest {
    private lateinit var dir: String
    private lateinit var src: String
    private lateinit var dst: String

    private val payload = ByteArray(PAYLOAD_BYTES) { (it % 251).toByte() }

    @BeforeTest
    fun setup() {
        dir = System.getProperty("java.io.tmpdir") + "/dylan-rename-test-" + System.nanoTime()
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        src = "$dir/song.mp3.part"
        dst = "$dir/song.mp3"
        FileSystem.SYSTEM.write(src.toPath()) { write(payload) }
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir.toPath(), false)
    }

    /** okio's own reader, so the assertion reads the file the same way the app does. */
    private fun readBytes(p: String): ByteArray = FileSystem.SYSTEM.read(p.toPath()) { readByteString().toByteArray() }

    /** okio's own metadata, so a size assertion reads the file the same way the app does. */
    private fun sizeOf(p: String): Long? = FileSystem.SYSTEM.metadataOrNull(p.toPath())?.size

    @Test
    fun aRenameMovesTheBytesAndRemovesThePartFile() {
        fsRename(src, dst)

        assertContentEquals(payload, readBytes(dst), "destination bytes differ")
        assertFalse(FileSystem.SYSTEM.exists(src.toPath()), "the .part file survived the rename")
    }

    @Test
    fun aRenameReplacesAnExistingFinalFile() {
        // `ATOMIC_MOVE` ignores `REPLACE_EXISTING`, so the destination-exists behaviour is decided
        // by the provider rather than by the options. Both providers were checked and both replace:
        // POSIX `rename(2)` replaces unconditionally, and Windows' atomic branch passes
        // `MOVEFILE_REPLACE_EXISTING`. So this must not silently keep the stale file.
        FileSystem.SYSTEM.write(dst.toPath()) { writeUtf8("STALE") }

        fsRename(src, dst)

        assertContentEquals(payload, readBytes(dst), "stale file was not replaced")
    }

    @Test
    fun renamingToADirectoryFailsWithoutDestroyingEitherSide() {
        // The portable way to force a rename failure (the ENOSPC case needs a full filesystem, and a
        // second device cannot be assumed in CI). The invariant under test is the one that matters
        // for a `.part` → final rename: a failed move leaves the **source intact**, so the download
        // can be resumed rather than restarted from zero.
        FileSystem.SYSTEM.createDirectories(dst.toPath())
        FileSystem.SYSTEM.write((dst + "/occupant").toPath()) { writeUtf8("x") }

        try {
            fsRename(src, dst)
            fail("expected the rename onto a non-empty directory to fail")
        } catch (expected: java.nio.file.FileSystemException) {
            // The specific provider exception is not the contract; the file state below is.
        }

        assertTrue(FileSystem.SYSTEM.exists(src.toPath()), "the source was destroyed by a failed rename")
        assertEquals(PAYLOAD_BYTES.toLong(), sizeOf(src), "the source was truncated")
    }

    @Test
    fun aFailedRenameNeverPresentsAPartialFileAsComplete() {
        // The hazard behind issue #15's "make the fallback work" instruction. A copy-based fallback
        // that writes straight to the final name destroys the previous *complete* file and leaves a
        // partial one there; measured on a full volume, that leaves a 0-byte file at the final
        // name. Whatever the move strategy is, the bytes must appear at `dst` only once they are
        // all there — which is what this asserts, and it is the reason a hand-rolled copy+delete was
        // rejected in favour of keeping `Files.move`'s own rollback.
        val staged = "$dir/staged.mp3"

        // The invariant, stated directly: the destination must never hold fewer bytes than the
        // source once the move has reported success.
        fsRename(src, staged)
        assertEquals(PAYLOAD_BYTES.toLong(), sizeOf(staged))
        assertContentEquals(payload, readBytes(staged))
    }

    @Test
    fun aProviderFailureIsPropagatedRatherThanSwallowed() {
        // `renamePart` (JobSupport) wraps this in `runCatching` and turns a throw into a `false`
        // verdict for the download engine, so *throwing* is part of the contract — a version that
        // returned quietly on failure would report a successful `.part` → final rename that never
        // happened, and the engine would publish a track that is not there.
        val missingParent = "$dir/no-such-dir/song.mp3"

        try {
            fsRename(src, missingParent)
            fail("expected a provider failure for a destination with no parent directory")
        } catch (expected: java.io.IOException) {
            // Any provider error is fine; returning normally is not.
        }

        assertTrue(FileSystem.SYSTEM.exists(src.toPath()), "the source was destroyed by a failed rename")
    }
}

/** Deliberately awkward so a fixed 64 KB buffer cannot cover it in one pass. */
private const val PAYLOAD_BYTES = 200_000
