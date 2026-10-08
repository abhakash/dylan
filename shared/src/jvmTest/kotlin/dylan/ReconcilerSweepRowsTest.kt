package dylan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.config.AppConfig
import dylan.db.Cached_files
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
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-10 — the weekly sweep judges a file against the byte total it already holds.
 *
 * `fullSweep` reads every cached row (`selectAllCached`), and `rows` carries `bytes`. It then threw
 * that away and re-derived each row's total with its own `dbLane` hop: 1 + N round trips plus N
 * stats at the 2 415-row cap. Worse, the re-read was answered by *one* row per song — the library
 * row, i.e. the active rendition — and then filtered with
 * `takeIf { it.bitrate == bitrate && it.ext == ext }`. A song that holds two renditions therefore
 * has a row that can answer for the sibling: whenever the row that comes back is not the rendition
 * being checked, the filter drops it, `recorded` is `null`, `size == recorded` is false, and the
 * sweep unlinks a file whose size matched its own row exactly.
 *
 * That interleaving is real, not hypothetical: the sweep runs on the io lane while the download
 * engine commits quality upgrades on the dbLane. Both tests below drive it through the one seam
 * that exists between the sweep's row read and its per-row re-read — the `stat` itself — and both
 * are red without the fix, because the fixed sweep compares the file against the total it read at
 * :123 and never asks the database a second time.
 */
class ReconcilerSweepRowsTest {
    private lateinit var tmp: String
    private lateinit var driver: SqlDriver
    private lateinit var db: Dylan
    private lateinit var paths: Paths
    private lateinit var cacheManager: CacheManager
    private lateinit var engine: DownloadEngine
    private lateinit var reconciler: Reconciler

    private val scheduler = TestCoroutineScheduler()
    private val disp = TestLanes.virtual(scheduler).disp
    private val cfg = AppConfig()
    private val testLog = LogBuffer(minLevel = LogLevel.DEBUG)

    /** One rendition, named the way `CachePath.fileName` names it. */
    private data class Cache(
        val id: String,
        val bits: Int,
        val ext: String = "m4a",
    ) {
        fun key(): SongKey = SongKey(PROVIDER, id)
    }

    /**
     * A delegating `okio.FileSystem` that lands a concurrent `upsertCached` — the download engine
     * committing a quality upgrade — inside the sweep's own `stat`, which is the one suspension
     * point between `fullSweep`'s row read and its per-row re-read. The metadata is read first, so
     * the sweep sees the old file and (before the fix) the new row.
     */
    private inner class UpgradeOnStatFileSystem(
        delegate: FileSystem,
        private val watch: String,
        private val to: Cache,
        private val toBytes: Long,
    ) : ForwardingFileSystem(delegate) {
        private val armed = AtomicBoolean(true)

        override fun metadataOrNull(path: Path): FileMetadata? {
            val meta = super.metadataOrNull(path)
            if (path.name == watch && armed.compareAndSet(true, false)) commitUpgrade(to, toBytes)
            return meta
        }
    }

    @BeforeTest
    fun setup() {
        tmp = "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/dylan-rec-rows-${System.nanoTime()}"
        FileSystem.SYSTEM.createDirectories(tmp.toPath())
        driver = DriverFactory("$tmp/dylan.db", testLog).createDriver()
        db = Dylan(driver)
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
        testLog.bindSink { e -> println("RECON-ROWS-LOG ${e.level} ${e.tag}: ${e.msg}") }
        val fs = FileSystem.SYSTEM
        paths = Paths(tmp.toPath() / "audio", fs)
        cacheManager = CacheManager(db, fs, paths, MutableStateFlow(emptySet()), cfg, disp, testLog)
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
                cacheManager = cacheManager,
                netClass = { dylan.util.NetClass.UNMETERED },
                qualityPref = { Quality.BITRATE_128 },
                log = testLog,
            )
        reconciler = Reconciler(db, fs, paths, cfg, disp, engine, cacheManager, testLog)
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private fun seedRow(
        c: Cache,
        bytes: Long,
    ) {
        db.dylanQueries.insertSong(c.key().provider, c.id, c.id, "", null, null, "", "", 100L, 1L, "ref", null, 0L)
        db.dylanQueries.insertCached(PROVIDER, c.id, c.bits.toLong(), c.ext, bytes, 1L, null, 0, 0, null)
    }

    private fun writeFile(
        c: Cache,
        size: Int,
    ): Path {
        val path = paths.final(c.key(), c.bits, c.ext)
        FileSystem.SYSTEM.write(path) { write(ByteArray(size)) }
        return path
    }

    private fun rowFor(id: String): Cached_files? = db.dylanQueries.selectCached(PROVIDER, id).executeAsOneOrNull()

    private fun scalar(sql: String): Long =
        driver
            .executeQuery(
                null,
                sql,
                { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else -1L) },
                0,
                null,
            ).value

    /**
     * `DownloadEngine.commit`'s shape for an upgrade: the new asset row is promoted, the new file
     * lands, and the library row moves to the new rendition — which drops the old asset row out
     * from under the file the sweep is currently holding a total for.
     */
    private fun commitUpgrade(
        to: Cache,
        bytes: Long,
    ) {
        db.dylanQueries.beginWrite(PROVIDER, to.id, to.bits.toLong(), to.ext, bytes, null)
        promoteAsset(to, bytes)
        writeFile(to, bytes.toInt())
        db.dylanQueries.upsertCached(PROVIDER, to.id, to.bits.toLong(), to.ext, bytes, 1L, null, 0, 0, null)
    }

    /** The second rendition of a song: on disk and in `media_objects`, READY and healthy. */
    private fun seedHealthySibling(
        c: Cache,
        bytes: Long,
    ): Path {
        db.dylanQueries.beginWrite(PROVIDER, c.id, c.bits.toLong(), c.ext, bytes, null)
        promoteAsset(c, bytes)
        return writeFile(c, bytes.toInt())
    }

    private fun promoteAsset(
        c: Cache,
        bytes: Long,
    ) {
        val stamped = System.currentTimeMillis()
        db.dylanQueries
            .promoteObject(bytes, null, stamped, PROVIDER, c.id, c.bits.toLong(), c.ext)
    }

    /**
     * The critical invariant: a total the sweep holds must describe exactly the file it keeps.
     * Checked for every surviving row, so a wrong total — a sibling's bytes, a default, a null —
     * shows up as a row the sweep kept over a file it did not, or a total the bookkeeping no
     * longer agrees with.
     */
    private fun assertTotalsAgreeWithTheRowsAndTheFiles() {
        val rows = db.dylanQueries.selectAllCached().executeAsList()
        rows.forEach { row ->
            val path = paths.final(SongKey(row.provider, row.song_id), row.bitrate.toInt(), row.ext)
            assertTrue(FileSystem.SYSTEM.exists(path), "row ${row.song_id}@${row.bitrate} has no file at $path")
            assertEquals(
                row.bytes,
                FileSystem.SYSTEM.metadata(path).size,
                "row ${row.song_id}@${row.bitrate} records ${row.bytes} B over a file of another size",
            )
        }
        val bytes = rows.sumOf { it.bytes }
        assertEquals(rows.size.toLong(), scalar("SELECT COUNT(*) FROM library"), "library row count")
        assertEquals(bytes, scalar("SELECT COALESCE(SUM(bytes), 0) FROM library"), "library byte sum")
        val totals = db.dylanQueries.cachedCountAndBytes().executeAsOne()
        assertEquals(rows.size.toLong(), totals.song_count, "final_count must be the row count")
        assertEquals(bytes, totals.total_bytes, "final_bytes must be the sum of the rows' bytes")
    }

    /**
     * (a) The byte-total invariant over a whole corpus: healthy rows keep their files, mismatched
     * rows lose theirs, and the totals after the sweep are exactly the rows the sweep kept. The
     * last song is the F-10 case — its file matches the total the sweep already read, but an
     * upgrade commits mid-sweep, so the row a second read would have answered with is the
     * *sibling's*.
     */
    @Test
    fun theWeeklySweepKeepsTheByteTotalsItAlreadyHoldsForEveryRowInTheSet() =
        runTest(scheduler) {
            val healthy = Cache("aaa-healthy", 128)
            val short = Cache("bbb-short", 128)
            val missing = Cache("ccc-missing", 128)
            val upgraded = Cache("zzz-two", 128)
            val higher = Cache("zzz-two", 320)

            val healthyFile = writeFile(healthy, 100)
            seedRow(healthy, 100L)
            val shortFile = writeFile(short, 50)
            seedRow(short, 100L)
            seedRow(missing, 100L)
            // Precondition for the upgraded song: its file matches its row, and the song holds a
            // second rendition whose file is healthy.
            val oldFile = writeFile(upgraded, 64)
            seedRow(upgraded, 64L)
            val siblingFile = seedHealthySibling(higher, 200L)

            // The seam has to sit inside the sweep's own stat, so the reconciler under test runs
            // over the probing filesystem.
            val probe = UpgradeOnStatFileSystem(FileSystem.SYSTEM, oldFile.name, higher, 200L)
            reconciler = Reconciler(db, probe, paths, cfg, disp, engine, cacheManager, testLog)

            reconciler.run()

            assertTrue(
                FileSystem.SYSTEM.exists(healthyFile),
                "a file whose size matches its row must survive the sweep",
            )
            assertTrue(
                FileSystem.SYSTEM.exists(oldFile),
                "the sweep held a total of 64 B for this file and the file is 64 B; unlinking it is " +
                    "the F-10 hazard, and it means the totals after the sweep are wrong too",
            )
            assertTrue(
                FileSystem.SYSTEM.exists(siblingFile),
                "the sibling rendition's healthy file is not the row under check and must be left alone",
            )
            assertFalse(FileSystem.SYSTEM.exists(shortFile), "a short file is a mismatch and is unlinked")

            assertEquals(100L, rowFor(healthy.id)?.bytes, "the healthy row must survive unchanged")
            assertEquals(null, rowFor(short.id), "the mismatched row must be dropped")
            assertEquals(null, rowFor(missing.id), "a row with no file must be dropped")
            val upgradedRow = rowFor(upgraded.id)
            assertNotNull(upgradedRow, "the upgraded song must keep a library row")
            assertEquals(higher.bits.toLong(), upgradedRow.bitrate, "the library row moved to the new rendition")
            val left =
                FileSystem.SYSTEM
                    .list(paths.audioDir)
                    .map { it.name }
                    .sorted()
            assertEquals(
                listOf("saavn_aaa-healthy_128.m4a", "saavn_zzz-two_128.m4a", "saavn_zzz-two_320.m4a"),
                left,
                "the healthy file, the healthy file the sweep was holding a total for, and the upgrade's",
            )
            assertEquals(2L, scalar("SELECT COUNT(*) FROM library"), "two rows must survive the sweep")
            assertEquals(300L, scalar("SELECT COALESCE(SUM(bytes), 0) FROM library"), "…totalling 300 B")

            assertTotalsAgreeWithTheRowsAndTheFiles()
        }

    /**
     * (b) The same hazard at its narrowest: one song, two renditions, one healthy file. The sweep
     * is checking the 128 rendition it read at `:123` when the upgrade commits; a second read
     * answers with the 320 row, the filter rejects it, and the 128 file — 64 bytes, exactly the
     * total the sweep is holding — is unlinked.
     */
    @Test
    fun aSongWithTwoRenditionsDoesNotLoseTheFileItsOwnRowDescribes() =
        runTest(scheduler) {
            val active = Cache("two-renditions", 128)
            val sibling = Cache("two-renditions", 320)
            val activeFile = writeFile(active, 64)
            seedRow(active, 64L)
            val siblingFile = seedHealthySibling(sibling, 200L)

            val probe = UpgradeOnStatFileSystem(FileSystem.SYSTEM, activeFile.name, sibling, 200L)
            reconciler = Reconciler(db, probe, paths, cfg, disp, engine, cacheManager, testLog)

            reconciler.run()

            assertTrue(
                FileSystem.SYSTEM.exists(activeFile),
                "the rendition the sweep is checking is healthy: its row says 64 B and the file is " +
                    "64 B, so only a sibling's row could have made the sweep unlink it",
            )
            assertTrue(FileSystem.SYSTEM.exists(siblingFile), "the other rendition's file is untouched")
            assertTotalsAgreeWithTheRowsAndTheFiles()
        }

    private companion object {
        const val PROVIDER = "saavn"
    }
}
