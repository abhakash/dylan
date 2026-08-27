package dylan

import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.repo.History
import dylan.util.AppDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Personalization queries over SQLite — the Home "Recently played albums" carousel source. */
class HistoryRepoTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var history: History

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-hist-${System.nanoTime()}"
        FileSystem.SYSTEM.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db").createDriver())
        history = History(db, AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default))
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private fun song(
        id: String,
        albumId: String?,
        albumName: String?,
    ) {
        db.dylanQueries.insertSong("saavn", id, id, "", albumId, albumName, "", "", 100L, 1L, "enc-$id", null, 0L)
    }

    private fun played(
        id: String,
        atMs: Long,
    ) {
        db.dylanQueries.insertHistory("saavn", id, atMs)
    }

    @Test
    fun ordersByMostRecentPlayAndGroupsByAlbum() =
        runBlocking {
            song("a1", "albX", "Album X")
            song("b1", "albY", "Album Y")
            song("b2", "albY", "Album Y")
            played("a1", 1_000L)
            played("b1", 2_000L)
            played("b2", 3_000L)
            val albums = history.recentAlbums(10)
            assertEquals(listOf("albY", "albX"), albums.map { it.albumId }, "album of the latest play first")
            assertEquals(2, albums.size, "two songs of one album collapse to a single carousel tile")
        }

    @Test
    fun excludesSongsWithoutAlbumAndRespectsLimit() =
        runBlocking {
            song("n1", null, null)
            for (i in 1..5) {
                song("s$i", "alb$i", "A$i")
                played("s$i", i * 1_000L)
                song("t$i", "alb$i", "A$i")
                played("t$i", i * 1_000L)
            }
            val albums = history.recentAlbums(3)
            assertTrue(albums.all { it.albumId != null && it.albumId.isNotEmpty() })
            assertEquals(3, albums.size, "limit caps the carousel")
            assertEquals(
                listOf("alb5", "alb4", "alb3"),
                albums.map { it.albumId },
                "newest plays first",
            )
        }

    @Test
    fun orphanSweepKeepsLiveHistory() =
        runBlocking {
            song("s1", "alb1", "A1")
            played("s1", 1_000L)
            db.dylanQueries.deleteOrphanHistory()
            val albums = history.recentAlbums(10)
            assertEquals(listOf("alb1"), albums.map { it.albumId }, "sweep must only drop dangling rows")
        }
}
