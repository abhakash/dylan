package dylan

import dylan.cache.Paths
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.model.SongKey
import dylan.playback.WindowPreparer
import dylan.support.TestLanes
import dylan.util.Lane
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-03 — the sniff's `stat` must not run on the single-permit state lane.
 *
 * `WindowPreparer.sniffOk`'s own KDoc says the blocking `stat`/`open`/`read`/`close` "runs on the io
 * lane, never on the single state lane", because it is called 2-4 times per track change from the
 * state lane's `TrackChanged` handler. The `open`/`read` half was moved behind an io hop; the
 * `stat` in `stampOf` was not, so every re-prepare of an unchanged file parked the state lane on a
 * syscall it had already promised not to run.
 *
 * This test asserts the promise rather than the implementation: it calls `sniffOk` from the state
 * lane — the lane the Orchestrator actually calls it from — and reads which lane the filesystem
 * `stat` landed on, through `AppDispatchers`' own thread-scoped lane publication. A `stat` that
 * runs inline reports `STATE`; the hop reports `IO`.
 */
class WindowPreparerLaneTest {
    private val scheduler = TestCoroutineScheduler()
    private val lanes = TestLanes.virtual(scheduler)
    private val disp = lanes.disp

    /** Lane the most recent `stat` actually ran on. Read through `AppDispatchers`, not a guess. */
    private val statLane = AtomicReference<Lane?>(null)

    private inner class LaneProbe(
        delegate: FileSystem,
    ) : ForwardingFileSystem(delegate) {
        override fun metadataOrNull(path: Path): FileMetadata? {
            statLane.set(disp.current())
            return super.metadataOrNull(path)
        }
    }

    private var tmp = ""

    @BeforeTest
    fun setup() {
        tmp = "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/dylan-wp-lane-${System.nanoTime()}"
        FileSystem.SYSTEM.createDirectories(tmp.toPath())
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    @Test
    fun theSniffStatRunsOnTheIoLaneEvenWhenSniffOkIsCalledFromTheStateLane() =
        runTest(scheduler) {
            val db = Dylan(DriverFactory("$tmp/dylan.db", LogBuffer()).createDriver())
            val fs = LaneProbe(FileSystem.SYSTEM)
            val paths = Paths(tmp.toPath() / "audio", fs)
            val key = SongKey("saavn", "s1")
            db.dylanQueries
                .insertSong(key.provider, key.songId, key.songId, "", null, null, "", "", 100L, 1L, "ref", null, 0L)
            val bytes = 64L
            FileSystem.SYSTEM.write(paths.final(key, 128, "m4a")) { write(ByteArray(bytes.toInt())) }
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", bytes, 1L, null, 0, 0, null)
            val row = db.dylanQueries.selectCached(key.provider, key.songId).executeAsOne()
            val preparer = WindowPreparer(db, fs, paths, disp)

            // The lane the Orchestrator's TrackChanged handler runs on when it calls sniffOk.
            withContext(disp.on(Lane.STATE)) { preparer.sniffOk(row) }

            assertEquals(
                Lane.IO,
                statLane.get(),
                "the sniff's stat must hop to the io lane like the open/read beside it does; it ran " +
                    "on ${statLane.get() ?: "no lane"}, which is the caller's own lane",
            )
        }
}
