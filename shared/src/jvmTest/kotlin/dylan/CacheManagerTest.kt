package dylan

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.model.SongKey
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

class CacheManagerTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var cacheManager: CacheManager
    private val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    private val disp = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)
    private val cfg = AppConfig(cacheMaxBytes = 25L * 1024 * 1024)

    @BeforeTest
    fun setup() {
        tmp = okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-test-${System.nanoTime()}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db").createDriver())
        cacheManager = CacheManager(db, fs, Paths(tmp.toPath() / "audio", fs), protectedKeys, cfg, disp)
        var i = 0

        fun song(id: String) =
            "saavn".let { p ->
                db.dylanQueries.insertSong(p, id, id, "", null, null, "", "", 200L, 1L, "ref", null, 0L)
                SongKey(p, id)
            }

        fun cached(
            key: SongKey,
            bytes: Long = 10_000_000L,
            lastUsed: Long? = null,
            playCount: Long = 0,
            pinned: Long = 0,
            pinnedAt: Long? = null,
        ) {
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", bytes, i++.toLong(), lastUsed, playCount, pinned, pinnedAt)
        }
        // unplayed-newest | played-recently | played-old | never-played-old
        cached(song("n1"))
        cached(song("p1"), lastUsed = 5_000L, playCount = 3)
        cached(song("p2"), lastUsed = 1_000L, playCount = 1)
        cached(song("n2"), lastUsed = null)
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    @Test
    fun unplayedEvictsBeforeRecentlyPlayed() =
        runTest {
            cacheManager.enforceBudget(netNewBytes = 9_000_000L)
            val rows =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .map { it.song_id }
            assertEquals(listOf("p1"), rows, "unplayed + oldest-played evicted; recently played survives")
        }

    @Test
    fun pinnedDemotesOldestPinFirstAndNeverBlocksFavorites() =
        runTest {
            db.dylanQueries.setPin(1L, 100L, "saavn", "n1")
            db.dylanQueries.setPin(1L, 200L, "saavn", "p1")
            var notified = false
            cacheManager.notifyOnce = { notified = true }
            val hugePinned = 3_000_000_000L
            repeat(300) { idx ->
                val k = SongKey("saavn", "bulk$idx")
                db.dylanQueries.insertSong(k.provider, k.songId, k.songId, "", null, null, "", "", 200L, 1L, "ref", null, 0L)
                db.dylanQueries.insertCached(
                    k.provider,
                    k.songId,
                    128L,
                    "m4a",
                    hugePinned / 150,
                    idx.toLong(),
                    null,
                    0,
                    1,
                    (idx + 10).toLong(),
                )
            }
            cacheManager.enforceBudget(netNewBytes = 0)
            val pins =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .filter { it.pinned == 1L }
                    .map { it.pinned_at_ms }
            assertTrue(notified)
            assertTrue(pins.all { (it ?: 0L) >= 10L }, "oldest pins demoted first")
        }

    @Test
    fun exemptKeyNeverEvictedInOwnPass() =
        runTest {
            val key = SongKey("saavn", "fresh")
            db.dylanQueries.insertSong(key.provider, key.songId, key.songId, "", null, null, "", "", 200L, 1L, "ref", null, 0L)
            db.dylanQueries.insertCached(key.provider, key.songId, 320L, "m4a", 20_000_000L, 999L, null, 0, 0, null)
            cacheManager.enforceBudget(netNewBytes = 0, exemptKeys = setOf(key))
            assertEquals(
                key.songId,
                db.dylanQueries
                    .selectCached(key.provider, key.songId)
                    .executeAsOneOrNull()
                    ?.song_id,
            )
        }

    @Test
    fun partBytesCountTowardUsage() =
        runTest {
            val fs = FileSystem.SYSTEM
            val audio = tmp.toPath() / "audio"
            fs.write(audio / "saavn_stray_128.part") { write(ByteArray(1_000_000)) }
            cacheManager.enforceBudget(netNewBytes = 0)
            assertTrue(
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .isNotEmpty(),
            )
        }

    @Test
    fun clearCacheKeepsProtected() =
        runTest {
            protectedKeys.value = setOf(SongKey("saavn", "p1"))
            cacheManager.clearCacheExcludingProtected()
            val rows =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .map { it.song_id }
            assertTrue("p1" in rows)
            assertFalse("n1" in rows)
        }

    @Test
    fun intentUpsertNeverDowngradesPriority() =
        runTest {
            db.dylanQueries.upsertIntent("saavn", "x", "USER_BULK", 320L, 1L)
            db.dylanQueries.upsertIntent("saavn", "x", "PREFETCH_NEXT", 128L, 2L)
            val row = db.dylanQueries.selectCached("saavn", "x").executeAsOneOrNull()
            val intent =
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .first { it.song_id == "x" }
            assertEquals("USER_BULK", intent.reason, "lower-priority enqueue must not replace")
            db.dylanQueries.upsertIntent("saavn", "x", "USER_NOW", 320L, 3L)
            val upgraded =
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .first { it.song_id == "x" }
            assertEquals("USER_NOW", upgraded.reason)
        }
}
