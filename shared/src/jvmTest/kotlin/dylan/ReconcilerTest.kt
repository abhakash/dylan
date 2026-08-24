package dylan

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.util.AppDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconcilerTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var engine: DownloadEngine
    private lateinit var reconciler: Reconciler
    private val disp = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)
    private val cfg = AppConfig()

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-rec-${System.nanoTime()}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db").createDriver())
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
        val paths = Paths(tmp.toPath() / "audio", fs)
        val cm = CacheManager(db, fs, paths, MutableStateFlow(emptySet()), cfg, disp)
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
            )
        reconciler = Reconciler(db, fs, paths, cfg, disp, engine, cm)
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private fun admit(key: SongKey) {
        db.dylanQueries.insertSong(key.provider, key.songId, key.songId, "", null, null, "", "", 100L, 1L, 1L, "ref", null, 0L)
    }

    @Test
    fun orphanFileDeletedOrphanRowDeleted() =
        runTest {
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
        runTest {
            val k = SongKey("saavn", "mismatch")
            admit(k)
            val audio = (tmp + "/audio").toPath()
            FileSystem.SYSTEM.write(audio / "saavn_mismatch_128.m4a") { write(ByteArray(50)) }
            db.dylanQueries.insertCached(k.provider, k.songId, 128L, "m4a", 100L, 1L, null, 0, 0, null)
            reconciler.run()
            assertFalse(FileSystem.SYSTEM.exists(audio / "saavn_mismatch_128.m4a"))
            assertEquals(null, db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull())
        }

    @Test
    fun intentWithoutFinalReenqueuedWithFinalDropped() =
        runTest {
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
