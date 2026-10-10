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
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A failed rename inside [FileLogSink.rotate] must not destroy a generation, and must not be silent.
 *
 * The promotions form a chain — each one's *target* is the next promotion's *source* — so a step
 * wrapped in a bare `runCatching` that the loop then continues past lets a healthy move land on
 * exactly the path of the archive the failed move was supposed to promote. The generation is then
 * destroyed seconds later by ordinary traffic, and the trail is guaranteed never to say so: it is
 * the one artefact a week-later triage can read, which is why this is the worst place in the
 * codebase for a swallowed error.
 *
 * The schedule is deterministic rather than hoped for, because it is a count, not a race:
 * `maxBytesPerFile` is set so that *every* write rotates, so each line advances the roll by a
 * known number of moves and the failing move is a known statement in a known rotation. The fault
 * is injected by a real `ForwardingFileSystem` that throws from `atomicMove`/`delete` — a
 * file-backed filesystem, not a mock, so the surrounding moves really do land on disk.
 *
 * On recording: an aborted rotation leaves the live file at or past `maxBytesPerFile`, so the next
 * line attempts the roll again. A volume that stays broken must not therefore write one
 * "rotation failed" line per log line and bury the real content under noise, so the reason is
 * recorded once per distinct reason.
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
     * Without that, the roll count per write is data-dependent and "the failing move" is not a
     * schedule, it is a coincidence.
     */
    private fun entry(tag: Int) =
        LogBuffer.Entry(
            1_756_000_000_000L,
            LogLevel.INFO,
            "dl",
            "seed $tag ${"z".repeat(PADDING)}",
        )

    private fun path(name: String): Path = dir.toPath() / name

    private fun read(name: String): String? =
        path(name).let {
            runCatching { if (fs.exists(it)) fs.source(it).buffer().readUtf8() else null }.getOrNull()
        }

    /**
     * Deterministic wait for the writer coroutine to have processed a line, polled on the file
     * rather than on a move counter. A blocked move does not advance the counter, so the counter
     * is not a waitable signal for a rotation that is failing — and the sink writes on a real
     * background scope, so a test that asserts on disk states has to know the line landed.
     */
    private suspend fun awaitText(
        name: String,
        predicate: (String) -> Boolean,
    ): String {
        var text = ""
        val ok =
            withTimeoutOrNull(AWAIT_CEILING_MS) {
                while (true) {
                    text = read(name) ?: ""
                    if (predicate(text)) return@withTimeoutOrNull true
                    delay(POLL_MS)
                }
            }
        assertTrue(ok == true, "timed out waiting for [$name] to match; last read: ${text.take(200)}")
        return text
    }

    /**
     * Deterministic wait for a promotion to have been *attempted*. The counter increments before
     * the fault, so it reaches its bound whether the move succeeds or is blocked — which is what
     * makes it a waitable signal for a rotation that is failing, unlike any state on disk.
     *
     * It is also a commit point for the write: `writeEntryLocked` writes the line and only then
     * calls `rotate()`, both inside the same `withLock`, so once the counter has moved the line
     * that caused it is already on disk and flushed.
     */
    private suspend fun awaitMoves(
        hostile: FaultyFileSystem,
        atLeast: Int,
    ): Boolean =
        withTimeoutOrNull(AWAIT_CEILING_MS) {
            while (hostile.moves.get() < atLeast) delay(POLL_MS)
            true
        } == true

    /** Snapshot of every generation on disk, so a test can prove a roll did not half-apply. */
    private fun snapshot(): List<String> = (0..KEEP).mapNotNull { read("dylan.log.$it") }

    // ---- the defect ---------------------------------------------------------------------

    /**
     * A blocked promotion must not destroy the archive it could not move.
     *
     * With the promotion of `.1 → .2` blocked, the *old* code ran the loop's next statement:
     * `atomicMove(".0", ".1")`, which lands on exactly the path of the archive that just failed
     * to move. `.1` held the only copy of that generation, so it was overwritten and lost — and
     * every step was inside `runCatching`, so nothing in the trail said why.
     *
     * Three assertions, because three separate things must now hold at once:
     *  1. the archive at `.1` survives, byte for byte — it is the only copy;
     *  2. the trail says the rotation failed and carries the volume's own reason, which is what
     *     a triage needs to know that the short archive set is a fault and not a policy;
     *  3. logging carries on into the live file, because a rotation failure that stops the trail
     *     turns a broken volume into a blind one.
     */
    @Test
    fun aBlockedPromotionKeepsTheArchiveAndSaysWhyInTheTrail() =
        kotlinx.coroutines.runBlocking {
            val hostile = FaultyFileSystem(fs, failMoveFrom = "dylan.log.1", failMoveTo = "dylan.log.2")
            val sink =
                FileLogSink(
                    hostile,
                    dir.toPath(),
                    sinkScope,
                    maxBytesPerFile = PER_FILE_BYTES,
                    filesToKeep = KEEP,
                )

            // Line 0 rolls `.0 → .1`, so `.1` holds generation 0. Line 1 must promote
            // `.1 → .2` *before* it can roll `.0 → .1`, and that promotion is blocked.
            sink.accept(entry(0))
            assertTrue(awaitMoves(hostile, 1), "the first roll never happened")
            sink.accept(entry(1))
            // Settle on the move counter, not on the live file: under the mutation this prefixed
            // the file no longer exists, so a poll on its contents would time out and hide which
            // assertion actually fired.
            assertTrue(awaitMoves(hostile, 2), "the promotion never ran")

            // 1. The archive is intact. Asserted first, because it is the finding: under the old
            //    code this file no longer existed, because the roll that could not promote it ran
            //    `atomicMove(".0", ".1")` anyway and overwrote the only copy of the generation.
            val archived = read("dylan.log.1")
            assertTrue(
                archived != null && "seed 0 " in archived,
                "the generation at .1 was destroyed by the roll that could not promote it: " +
                    snapshot().joinToString("\n").take(300),
            )
            // And the live generation is not lost either: the abort happens before `.0 → .1`.
            val live = read("dylan.log.0") ?: ""
            assertTrue(
                "seed 1 " in live,
                "the live generation was lost during the failed roll: ${live.take(120)}",
            )

            // 2. The trail records the failure and why. This is the part that was structurally
            //    impossible before: every step was inside runCatching, so the error had no path
            //    to any reader.
            assertTrue(
                SIMULATED_VOLUME_ERROR in live,
                "the trail records no reason for the failure, so a triage cannot act on it: " +
                    live.take(200),
            )
            assertTrue(
                "dylan.log.1" in live,
                "the trail does not say which archive was left behind: ${live.take(200)}",
            )
            // One record, not one per line: the roll is retried on the next line and a permanently
            // broken volume must not bury the real log lines under its own diagnostics.
            assertEquals(
                1,
                live.lineSequence().count { "rotation failed" in it },
                "the failed rotation was recorded once per line rather than once per reason",
            )

            // 3. Logging keeps going. The failure must not close the trail.
            sink.accept(entry(2))
            // Both branches attempt one promotion for this roll, so the counter reaches 3 either
            // way: the sink has genuinely tried to roll, which is the settle signal.
            assertTrue(awaitMoves(hostile, 3), "the third roll never ran")
            val after = read("dylan.log.0") ?: ""
            assertTrue(
                "seed 2 " in after,
                "the live file stopped accepting lines after the rotation failed: ${after.take(120)}",
            )
            // Still exactly one failure record, now that two more lines have been through the sink.
            assertEquals(
                1,
                after.lineSequence().count { "rotation failed" in it },
                "the failed rotation was recorded once per line rather than once per reason",
            )

            // Retention still holds, so the fix costs no leak.
            val files = (0..10).count { fs.exists(dir.toPath() / "dylan.log.$it") }
            assertTrue(files <= KEEP + 1, "leaked $files log files, keeping $KEEP")
        }

    /**
     * The same defect one step later: when the *live* roll itself fails, the live generation is
     * not lost and the archive chain is not clobbered.
     *
     * This pins the other half of the abort. Blocking `.0 → .1` leaves `.1` holding the archive
     * that was already promoted and the live file holding the newest generation. The old code
     * would have run `atomicMove(".0", ".1")` anyway, overwriting `.1`.
     */
    @Test
    fun aBlockedLiveRollKeepsTheLiveGenerationAndTheArchiveUnderIt() =
        kotlinx.coroutines.runBlocking {
            val hostile = FaultyFileSystem(fs, failMoveFrom = "dylan.log.0", failMoveTo = "dylan.log.1")
            val sink =
                FileLogSink(
                    hostile,
                    dir.toPath(),
                    sinkScope,
                    maxBytesPerFile = PER_FILE_BYTES,
                    filesToKeep = KEEP,
                )

            sink.accept(entry(0))
            val live = awaitText("dylan.log.0") { "rotation failed" in it }

            // `.0` now holds the newest generation and could not become `.1`.
            assertTrue(
                "seed 0 " in live,
                "the live generation was lost when the live roll failed: ${live.take(120)}",
            )
            // `.1` was never created, so there is nothing that could have been clobbered by a
            // successful `.0 → .1` — the archive set is exactly as it was before the attempt.
            assertTrue(
                read("dylan.log.1") == null,
                "an archive appeared at .1 although the roll that would have created it failed",
            )
            assertTrue(
                SIMULATED_VOLUME_ERROR in live,
                "the trail records no reason for the failed live roll",
            )
        }

    /**
     * The eviction of the oldest archive is part of the roll, and its failure must abort too.
     *
     * A swallowed `delete` here is the reason the retention window can silently narrow: the
     * promotions below it then run against an archive set that was never freed, so the oldest
     * file is overwritten by a move that was supposed to land in a slot the eviction had made
     * room for. The observable contract is that the set on disk is byte-for-byte what it was
     * before the attempt.
     *
     * Settled with [FileLogSink.flush] rather than a poll: the sink writes on a background
     * coroutine, so "the state before the attempt" has to be taken when the writer is provably
     * idle. `flush` drains the queue and is already the documented stop/background path, so it is
     * the honest settle point — and without it, this test would race its own precondition.
     */
    @Test
    fun aFailedEvictionOfTheOldestArchiveIsRecordedAndLeavesTheSetUnchanged() =
        kotlinx.coroutines.runBlocking {
            val hostile = FaultyFileSystem(fs, failDeleteOf = "dylan.log.$KEEP")
            val sink =
                FileLogSink(
                    hostile,
                    dir.toPath(),
                    sinkScope,
                    maxBytesPerFile = PER_FILE_BYTES,
                    filesToKeep = KEEP,
                )

            // Four lines is the first point at which `.3` is occupied *and* the archive set is
            // complete. The settle signal is the failure note, which only lands once the writer
            // has committed the last fill line and its aborted roll.
            repeat(KEEP + 1) { i -> sink.accept(entry(i)) }
            val settled = awaitText("dylan.log.0") { "rotation failed" in it }
            val before = (0..KEEP).mapNotNull { read("dylan.log.$it") }
            assertTrue(
                "seed 0 " in (read("dylan.log.$KEEP") ?: ""),
                "the oldest archive never appeared, so this test proves nothing: " +
                    before.joinToString("\n").take(300),
            )

            // The 5th line's roll has to evict `.3` first, and that is what is blocked.
            sink.accept(entry(KEEP + 1))
            // The note is deduped by reason, so settle on the line that caused the second roll.
            val live = awaitText("dylan.log.0") { "seed ${KEEP + 1} " in it }

            assertTrue(
                "rotation failed" in live,
                "the failed eviction was never recorded: ${live.take(200)}",
            )
            assertTrue(
                "could not evict" in live,
                "the failed eviction is not attributed in the trail: ${live.take(200)}",
            )
            assertTrue(
                "dylan.log.$KEEP" in live,
                "the trail does not name the archive it could not evict: ${live.take(200)}",
            )
            // The archive set is byte-for-byte what it was: every promotion after a failed
            // eviction would have applied that eviction's work to a set that was never freed.
            val after = (0..KEEP).mapNotNull { read("dylan.log.$it") }
            assertEquals(
                before.drop(1),
                after.drop(1),
                "the roll continued past a failed eviction, so the archive set was half-applied",
            )
        }

    // ---- the controls -------------------------------------------------------------------

    /**
     * The archive's own content must survive when no move fails — the control. Without it the
     * test above could pass for the wrong reason (a ring that never promotes anything also never
     * destroys anything).
     */
    @Test
    fun everyGenerationSurvivesWhenNoMoveFails() =
        kotlinx.coroutines.runBlocking {
            val sink = FileLogSink(fs, dir.toPath(), sinkScope, maxBytesPerFile = PER_FILE_BYTES, filesToKeep = KEEP)
            repeat(3) { sink.accept(entry(it)) }

            val settled =
                withTimeoutOrNull(AWAIT_CEILING_MS) {
                    while (!fs.exists(path("dylan.log.1"))) delay(POLL_MS)
                    true
                }
            assertTrue(settled == true, "rotation never produced an archive")
            val all = snapshot().joinToString("\n")
            assertTrue("seed 0 " in all, "with no failure the first generation must be retained: ${all.take(200)}")
            assertTrue("seed 1 " in all, "with no failure the second generation must be retained: ${all.take(200)}")
            assertTrue(
                all.lineSequence().none { "rotation failed" in it },
                "a roll that succeeded recorded a failure: ${all.take(200)}",
            )
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
                withTimeoutOrNull(AWAIT_CEILING_MS) {
                    hostile.openFails.set(false)
                    sink.accept(entry(1))
                    var text = read("dylan.log.0")
                    while (text == null || "seed 1 " !in text) {
                        delay(POLL_MS)
                        text = read("dylan.log.0")
                    }
                    true
                }
            assertTrue(healed == true, "the trail never recovered after an unopenable file")
        }

    /**
     * Fails one filesystem statement by *name*, so the schedule is readable: the test states which
     * promotion or eviction is broken and the tie to the subsequent clobber is visible without
     * arithmetic on a move counter.
     */
    private class FaultyFileSystem(
        private val backing: FileSystem,
        private val failMoveFrom: String? = null,
        private val failMoveTo: String? = null,
        private val failDeleteOf: String? = null,
    ) : ForwardingFileSystem(backing) {
        /** Number of promotions the sink really attempted — observable proof the sink tried. */
        val moves = AtomicInteger()

        /**
         * A move is faulty only when a *named* fault was asked for. With both filters null there
         * is no move fault at all — otherwise the delete-only case would throw from every
         * `atomicMove`, which blocks the whole trail and proves nothing about eviction.
         */
        private val moveFaultArmed = failMoveFrom != null || failMoveTo != null

        override fun atomicMove(
            source: Path,
            target: Path,
        ) {
            moves.incrementAndGet()
            val blocked =
                moveFaultArmed &&
                    (failMoveFrom == null || source.name == failMoveFrom) &&
                    (failMoveTo == null || target.name == failMoveTo)
            if (blocked) throw IOException("$SIMULATED_VOLUME_ERROR: ${source.name} -> ${target.name}")
            backing.atomicMove(source, target)
        }

        override fun delete(
            path: Path,
            mustExist: Boolean,
        ) {
            if (failDeleteOf != null && path.name == failDeleteOf) {
                throw IOException("$SIMULATED_VOLUME_ERROR: delete of ${path.name}")
            }
            backing.delete(path, mustExist)
        }
    }

    private class UnopenableFileSystem(
        private val backing: FileSystem,
    ) : ForwardingFileSystem(backing) {
        val openFails = AtomicBoolean(false)

        override fun appendingSink(
            file: Path,
            mustExist: Boolean,
        ): Sink {
            if (openFails.get()) throw IOException("simulated: cannot open the log file")
            return backing.appendingSink(file, mustExist)
        }
    }

    private companion object {
        const val AWAIT_CEILING_MS = 10_000L
        const val POLL_MS = 25L

        /** Generous settle window for [FileLogSink.flush], which is how a test takes a snapshot. */
        const val FLUSH_SETTLE_MS = 10_000L

        /** Files the sink keeps, so on disk at most `.0 .1 .2 .3`. */
        const val KEEP = 3

        /** Every line exceeds this, so every accepted entry rolls the file exactly once. */
        const val PER_FILE_BYTES = 64L

        /** Padding that makes each line longer than [PER_FILE_BYTES]. */
        const val PADDING = 96

        /** The volume's own words, which the trail must carry through to the reader. */
        const val SIMULATED_VOLUME_ERROR = "simulated: volume refuses the rename"
    }
}
