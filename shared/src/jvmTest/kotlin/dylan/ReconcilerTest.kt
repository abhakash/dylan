package dylan

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.support.TestLanes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReconcilerTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var engine: DownloadEngine
    private lateinit var reconciler: Reconciler

    // Virtual lanes: see `TestLanes.virtual`.
    private val scheduler = TestCoroutineScheduler()
    private val disp = TestLanes.virtual(scheduler).disp
    private val cfg = AppConfig()
    private val testLog = LogBuffer(minLevel = LogLevel.DEBUG)

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-rec-${System.nanoTime()}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db", testLog).createDriver())
        val provider =
            object : MusicProvider {
                override suspend fun search(
                    query: String,
                    page: Int,
                ) = error("unused")

                override suspend fun album(id: String) = null

                override suspend fun artist(id: String) = null

                override suspend fun home() = dylan.model.HomeFeed(emptyList())

                override suspend fun topSearches() = emptyList<dylan.model.MiniEntity>()

                override suspend fun resolveStream(
                    resolveRef: String,
                    q: Quality,
                ) = null
            }
        testLog.bindSink { e -> println("RECON-LOG ${e.level} ${e.tag}: ${e.msg}") }
        val paths = Paths(tmp.toPath() / "audio", fs)
        val cm = CacheManager(db, fs, paths, MutableStateFlow(emptySet()), cfg, disp, testLog)
        engine =
            DownloadEngine(
                db = db,
                fs = fs,
                paths = paths,
                cfg = cfg,
                disp = disp,
                provider = provider,
                bulk =
                    io.ktor.client.HttpClient(
                        io.ktor.client.engine.mock
                            .MockEngine { error("no net") },
                    ),
                breakers = Breakers(),
                cacheManager = cm,
                netClass = { dylan.util.NetClass.UNMETERED },
                qualityPref = { Quality.BITRATE_128 },
                log = testLog,
            )
        reconciler = Reconciler(db, fs, paths, cfg, disp, engine, cm, testLog)
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private fun admit(key: SongKey) {
        db.dylanQueries.insertSong(
            key.provider,
            key.songId,
            key.songId,
            "",
            null,
            null,
            "",
            "",
            100L,
            1L,
            "ref",
            null,
            0L,
        )
    }

    @Test
    fun orphanFileDeletedOrphanRowDeleted() =
        runTest(scheduler) {
            val audio = (tmp + "/audio").toPath()
            val ghost = audio / "saavn_ghost_128.m4a"
            FileSystem.SYSTEM.write(ghost) { write(ByteArray(10)) }
            java.io.File(ghost.toString()).setLastModified(System.currentTimeMillis() - 120_000)
            val k = SongKey("saavn", "rowless")
            admit(k)
            db.dylanQueries.insertCached(k.provider, k.songId, 128L, "m4a", 10L, 1L, null, 0, 0, null)
            reconciler.run()
            assertFalse(FileSystem.SYSTEM.exists(audio / "saavn_ghost_128.m4a"))
            assertEquals(null, db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull())
        }

    @Test
    fun sizeMismatchDeletesBoth() =
        runTest(scheduler) {
            val k = SongKey("saavn", "mismatch")
            admit(k)
            val audio = (tmp + "/audio").toPath()
            FileSystem.SYSTEM.write(audio / "saavn_mismatch_128.m4a") { write(ByteArray(50)) }
            db.dylanQueries.insertCached(k.provider, k.songId, 128L, "m4a", 100L, 1L, null, 0, 0, null)
            reconciler.run()
            assertFalse(FileSystem.SYSTEM.exists(audio / "saavn_mismatch_128.m4a"))
            assertEquals(null, db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull())
        }

    /**
     * CA-4, in the reconciler rather than in `CacheManager`. A failed unlink must keep the row: a
     * dropped row plus a surviving file is exactly the state `weeklyGc`'s `deleteOrphanLibrary`
     * and the next launch's orphan sweep then finish — so one transient EACCES/EBUSY/EMFILE (or a
     * file still open by the player) costs the user the track permanently. A non-empty directory at
     * the final path is the one unlink failure that is reproducible off-device.
     */
    @Test
    fun aSizeMismatchWhoseUnlinkFailsKeepsItsRowForTheNextSweep() =
        runTest(scheduler) {
            val k = SongKey("saavn", "short")
            admit(k)
            val audio = (tmp + "/audio").toPath()
            val path = audio / "saavn_short_128.m4a"
            FileSystem.SYSTEM.createDirectories(path)
            FileSystem.SYSTEM.write(path / "occupied") { write(ByteArray(8)) }
            db.dylanQueries.insertCached(k.provider, k.songId, 128L, "m4a", 100L, 1L, null, 0, 0, null)

            reconciler.run()

            assertNotNull(
                db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull(),
                "an unlink that failed must leave the row, or the next orphan sweep deletes the file " +
                    "and the track is gone for good",
            )
        }

    /**
     * M1 as the audit states it: a cached row whose file is **already gone** — deleted externally, or
     * by `DownloadEngine.commit`'s `deleteQuietly` on a failed commit — must not be kept "for
     * retry" with a retryable error logged on every weekly sweep.
     *
     * This test exists to *pin the measured behaviour* rather than to catch a regression, because
     * the audit's mechanism is wrong: it claims okio's `FileSystem.delete` defaults to
     * `mustExist = true` and therefore throws `FileNotFoundException` for an absent path. Measured
     * on the resolved okio 3.18.1, the default is `false` — `delete(path)` on an absent path
     * returns normally, and only the explicit `delete(path, mustExist = true)` throws (see
     * `deleteMustExistIsWhatThrowsOnAnAbsentPath` below). So the phantom row the finding describes
     * does not occur, and the sibling test above is the only real unlink failure there is: a path
     * that exists but cannot be removed, which throws `IOException` under either setting.
     *
     * If okio ever flips that default, this test is what notices.
     */
    @Test
    fun aCachedRowWhoseFileIsAlreadyGoneIsDroppedRatherThanKeptForRetry() =
        runTest(scheduler) {
            val k = SongKey("saavn", "vanished")
            admit(k)
            db.dylanQueries.insertCached(k.provider, k.songId, 128L, "m4a", 4096L, 1L, null, 0, 0, null)
            // No file was ever created at the final path — precondition, stated not assumed.
            assertFalse(FileSystem.SYSTEM.exists(audioDir() / "saavn_vanished_128.m4a"))

            reconciler.run()

            assertEquals(
                null,
                db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull(),
                "a row with no file has nothing to retry",
            )
            val totals = db.dylanQueries.cachedCountAndBytes().executeAsOne()
            assertEquals(0L, totals.song_count, "the totals must not count a row with no file")
            assertEquals(0L, totals.total_bytes, "…nor its bytes, which do not exist")
        }

    /**
     * The fact `M1` gets wrong, asserted directly so the claim in [unlink]'s KDoc cannot rot: with
     * the *default* `mustExist`, an absent path is a success; only an explicit `true` throws
     * `FileNotFoundException`, and a present-but-undeletable path throws `IOException` either way.
     */
    @Test
    fun deleteMustExistIsWhatThrowsOnAnAbsentPath() =
        runTest(scheduler) {
            val audio = audioDir()
            val absent = audio / "saavn_absent_128.m4a"
            assertFalse(FileSystem.SYSTEM.exists(absent), "precondition: the path is not there")
            assertTrue(
                runCatching { FileSystem.SYSTEM.delete(absent) }.isSuccess,
                "okio's default mustExist is false, so an absent path is a successful unlink",
            )
            assertTrue(
                runCatching { FileSystem.SYSTEM.delete(absent, mustExist = true) }.isFailure,
                "…and the audit's 'mustExist = true by default' claim would show up here as a pass",
            )
            val busy = audio / "saavn_busy_128.m4a"
            FileSystem.SYSTEM.createDirectories(busy)
            FileSystem.SYSTEM.write(busy / "occupied") { write(ByteArray(4)) }
            assertTrue(
                runCatching { FileSystem.SYSTEM.delete(busy) }.isFailure,
                "a present-but-undeletable path fails under either setting — the CA-4 case",
            )
        }

    /**
     * M1's other half in the direction that *is* real: a file that is present and short is a
     * mismatch, its unlink succeeds, and the row goes.
     */
    @Test
    fun aShortFileIsStillUnlinkedAndItsRowDropped() =
        runTest(scheduler) {
            val k = SongKey("saavn", "reallyshort")
            admit(k)
            FileSystem.SYSTEM.write(audioDir() / "saavn_reallyshort_128.m4a") { write(ByteArray(10)) }
            db.dylanQueries.insertCached(k.provider, k.songId, 128L, "m4a", 4096L, 1L, null, 0, 0, null)

            reconciler.run()

            assertFalse(FileSystem.SYSTEM.exists(audioDir() / "saavn_reallyshort_128.m4a"))
            assertEquals(null, db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull())
        }

    private fun audioDir() = (tmp + "/audio").toPath()

    @Test
    fun intentWithoutFinalReenqueuedWithFinalDropped() =
        runTest(scheduler) {
            val pending = SongKey("saavn", "pending")
            val done = SongKey("saavn", "done")
            admit(pending)
            admit(done)
            db.dylanQueries.upsertIntent(pending.provider, pending.songId, "USER_NOW", 128L, 1L)
            db.dylanQueries.upsertIntent(done.provider, done.songId, "PREFETCH_NEXT", 128L, 2L)
            val audio = (tmp + "/audio").toPath()
            FileSystem.SYSTEM.write(audio / "saavn_done_128.m4a") { write(ByteArray(64)) }
            db.dylanQueries.insertCached(done.provider, done.songId, 128L, "m4a", 64L, 1L, null, 0, 0, null)
            reconciler.run()
            val intents =
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .map { it.song_id }
            assertTrue("pending" in intents, "interrupted download must re-enqueue")
            assertFalse("done" in intents, "completed download's intent must be consumed")
        }
}
