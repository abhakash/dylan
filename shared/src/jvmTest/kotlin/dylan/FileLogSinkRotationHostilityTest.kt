package dylan

import dylan.diag.FileLogSink
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A failed rename inside [FileLogSink.rotate] destroys a generation, silently.
 *
 * `rotate()` wraps every step in `runCatching` and then continues the loop. The failure this
 * pins is not "the move didn't happen" — it is that the archive it failed to move is left at its
 * old path, where the *next* move in the same loop lands. So the generation is destroyed by
 * healthy traffic seconds later, and the `runCatching` guarantees the trail — the one file that
 * exists so a week-later triage can be done — is the last place a reader will find out why.
 *
 * The schedule is deterministic rather than hoped for, because it is a count, not a race:
 * `maxBytesPerFile` is set so that *every* write rotates, so each line advances the roll by one
 * move and the Nth `atomicMove` is a known statement in a known rotation. Under that schedule
 * the archive at `.1` before the failing roll is identifiable, and the assertion is that its
 * content survives the roll that could not promote it.
 *
 * No fix is applied in this wave: repairing `rotate()` means either logging the failure through
 * the ring (which is itself the sinking sink) or abandoning the roll, and both change behaviour
 * deliberately — see the report.
 */
class FileLogSinkRotationHostilityTest {
    private lateinit var dir: String
    private val fs = FileSystem.SYSTEM
    private val sinkScope = CoroutineScope(Dispatchers.Default)

    @BeforeTest
    fun setup() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-flog-hostile-${System.nanoTime()}"
        fs.createDirectories(dir.toPath())
    }

    @AfterTest
    fun teardown() {
        runCatching { fs.deleteRecursively(dir.toPath()) }
    }

    /**
     * A line longer than [PER_FILE_BYTES], so every accepted entry rolls the file exactly once.
     * Without that, the roll count per write is data-dependent and "the Nth move" is not a
     * schedule, it is a coincidence.
     */
    private fun entry(tag: Int) =
        LogBuffer.Entry(
            1_756_000_000_000L,
            LogLevel.INFO,
            "dl",
            "seed $tag ${"z".repeat(PADDING)}",
        )

    private fun read(name: String): String? =
        (dir.toPath() / name).let {
            runCatching {
                if (fs.exists(it)) fs.source(it).buffer().readUtf8() else null
            }.getOrNull()
        }

    @Test
    fun aFailedRenameDestroysTheArchiveItCouldNotPromote() =
        kotlinx.coroutines.runBlocking {
            val hostile = FailOneMoveFileSystem(fs, failMoveNumber = TARGET_MOVE)
            val sink =
                FileLogSink(
                    hostile,
                    dir.toPath(),
                    sinkScope,
                    maxBytesPerFile = PER_FILE_BYTES,
                    filesToKeep = KEEP,
                )

            // Three lines: .1 = seed0, .2 = seed1, .0 = seed2 at the end of the third roll.
            repeat(3) { sink.accept(entry(it)) }

            val settled =
                withTimeoutOrNull(10_000) {
                    while (hostile.moves.get() < MOVES_AFTER_THREE_ROLLS) delay(25)
                    true
                }
            assertTrue(settled == true, "rotation never reached the failing move")

            // Assertion 1 — the finding. The generation that was at `.1` before the failed
            // promotion is nowhere on disk: the roll that could not move it had already moved
            // `.2 → .3`, and the successful `.0 → .1` that followed landed on the empty slot.
            val onDisk =
                (0..KEEP).mapNotNull { read("dylan.log.$it") }
            assertTrue(
                "seed 1 " !in onDisk.joinToString("\n"),
                "the archive survived the failed rename — the loss this pins did not happen",
            )

            // Assertion 2 — the silence. Every step of rotate() is inside runCatching, so the
            // trail never records the volume's error.
            assertTrue(
                onDisk.joinToString("\n").contains("simulated").not(),
                "the volume's error message reached the trail: ${onDisk.joinToString("\n").take(200)}",
            )

            // Assertion 3 — retention still holds, so the defect is loss and not a leak.
            val files = (0..10).count { fs.exists(dir.toPath() / "dylan.log.$it") }
            assertTrue(files <= KEEP + 1, "leaked $files log files, keeping $KEEP")
        }

    /** The archive's own content must survive when no move fails — the control. */
    @Test
    fun everyGenerationSurvivesWhenNoMoveFails() =
        kotlinx.coroutines.runBlocking {
            val sink = FileLogSink(fs, dir.toPath(), sinkScope, maxBytesPerFile = PER_FILE_BYTES, filesToKeep = KEEP)
            repeat(3) { sink.accept(entry(it)) }

            val settled =
                withTimeoutOrNull(10_000) {
                    while (!fs.exists(dir.toPath() / "dylan.log.1")) delay(25)
                    true
                }
            assertTrue(settled == true, "rotation never produced an archive")
            val all = (0..KEEP).mapNotNull { read("dylan.log.$it") }.joinToString("\n")
            assertTrue("seed 0 " in all, "with no failure the first generation must be retained: ${all.take(200)}")
            assertTrue("seed 1 " in all, "with no failure the second generation must be retained: ${all.take(200)}")
        }

    /** A file that cannot be opened loses one line; the next write must still land. */
    @Test
    fun anUnopenableFileLosesOneLineNotTheTrail() =
        kotlinx.coroutines.runBlocking {
            val hostile = UnopenableFileSystem(fs)
            val sink =
                FileLogSink(
                    hostile,
                    dir.toPath(),
                    sinkScope,
                    maxBytesPerFile = Long.MAX_VALUE,
                    filesToKeep = 2,
                )

            hostile.openFails.set(true)
            sink.accept(entry(0))

            val healed =
                withTimeoutOrNull(5_000) {
                    hostile.openFails.set(false)
                    sink.accept(entry(1))
                    var text = read("dylan.log.0")
                    while (text == null || "seed 1 " !in text) {
                        delay(25)
                        text = read("dylan.log.0")
                    }
                    true
                }
            assertTrue(healed == true, "the trail never recovered after an unopenable file")
        }

    /**
     * Fails exactly one numbered `atomicMove` and succeeds before and after. The number is the
     * schedule: with one roll per write, the 5th move is `.1 → .2` in the third roll — the
     * promotion of the generation that the successful `.0 → .1` then overwrites.
     */
    private class FailOneMoveFileSystem(
        private val backing: FileSystem,
        private val failMoveNumber: Int,
    ) : ForwardingFileSystem(backing) {
        val moves = AtomicInteger()

        override fun atomicMove(
            source: Path,
            target: Path,
        ) {
            if (moves.incrementAndGet() == failMoveNumber) {
                throw okio.IOException("simulated: volume refuses the rename")
            }
            backing
                .atomicMove(source, target)
        }
    }

    private class UnopenableFileSystem(
        private val backing: FileSystem,
    ) : ForwardingFileSystem(backing) {
        val openFails = AtomicBoolean(false)

        override fun appendingSink(
            path: Path,
            mustExist: Boolean,
        ): okio.Sink {
            if (openFails.get()) throw okio.IOException("simulated: cannot open the log file")
            return backing
                .appendingSink(path, mustExist)
        }
    }

    private companion object {
        /** Files the sink keeps, so on disk at most `.0 .1 .2 .3`. */
        const val KEEP = 3

        /** Every line exceeds this, so every accepted entry rolls the file exactly once. */
        const val PER_FILE_BYTES = 64L

        /** Padding that makes each line longer than [PER_FILE_BYTES]. */
        const val PADDING = 96

        /** The 5th move is `.1 → .2` in the third roll. */
        const val TARGET_MOVE = 5

        /** Three rolls at two moves each: `.0 → .1` twice, `.2 → .3` once, `.1 → .2` once. */
        const val MOVES_AFTER_THREE_ROLLS = 6
    }
}
