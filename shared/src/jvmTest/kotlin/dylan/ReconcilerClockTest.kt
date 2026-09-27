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
import dylan.provider.MusicProvider
import dylan.support.MutableClock
import dylan.support.TestLanes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two reconciler grace windows (60 s orphan-final, `partGraceHours` for `.part`) were
 * untestable because the only way to age a file was `setLastModified` against the real wall
 * clock. With `Clock` injected they are exact: set mtime, advance the fake clock, assert.
 */
class ReconcilerClockTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var paths: Paths
    private lateinit var reconciler: Reconciler
    private val clock = MutableClock()
    private val fs = FileSystem.SYSTEM
    private val disp = TestLanes().disp
    private val cfg = AppConfig(clock = clock)
    private val log = LogBuffer(minLevel = LogLevel.DEBUG)

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-recclk-${System.nanoTime()}"
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db", log).createDriver())
        paths = Paths(tmp.toPath() / "audio", fs)
        fs.createDirectories(paths.audioDir)
        val cm = CacheManager(db, fs, paths, MutableStateFlow(emptySet()), cfg, disp, log)
        reconciler =
            Reconciler(
                db,
                fs,
                paths,
                cfg,
                disp,
                DownloadEngine(
                    db = db,
                    fs = fs,
                    paths = paths,
                    cfg = cfg,
                    disp = disp,
                    provider = unusedProvider,
                    bulk = HttpClient(MockEngine { error("no net") }),
                    breakers = Breakers(),
                    cacheManager = cm,
                    netClass = { dylan.util.NetClass.UNMETERED },
                    qualityPref = { Quality.BITRATE_128 },
                    log = log,
                ),
                cm,
                log,
            )
    }

    @AfterTest
    fun teardown() {
        runCatching { fs.deleteRecursively(tmp.toPath()) }
    }

    private val unusedProvider =
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

    private fun write(
        name: String,
        agedMs: Long,
    ): Path {
        val p = paths.audioDir / name
        fs.write(p) { writeUtf8("x") }
        assertTrue(File(p.toString()).setLastModified(clock.nowMs() - agedMs), "could not age $name")
        return p
    }

    @Test
    fun orphanFinalSurvivesInsideTheSixtySecondWindow() =
        runTest {
            val orphan = write("saavn_ghost_128.m4a", agedMs = 30_000)
            reconciler.run()
            assertTrue(fs.exists(orphan), "a 30 s old orphan is inside the grace window and must survive")
        }

    @Test
    fun orphanFinalIsSweptOnceTheWindowElapses() =
        runTest {
            val orphan = write("saavn_ghost_128.m4a", agedMs = 30_000)
            clock.advanceSeconds(31)
            reconciler.run()
            assertFalse(fs.exists(orphan), "61 s old orphan is past the window and must be swept")
        }

    @Test
    fun partFileIsHeldForTheConfiguredPartGraceHours() =
        runTest {
            val part = write("saavn_half_128.m4a.part", agedMs = 59 * 60_000)
            reconciler.run()
            assertTrue(fs.exists(part), "a 59 min old .part is inside the 1 h grace window")

            clock.advanceSeconds(120)
            reconciler.run()
            assertFalse(fs.exists(part), "a 61 min old .part is past the grace window")
        }

    @Test
    fun theSameFileSweepsOrSurvivesDependingOnlyOnTheInjectedClock() =
        runTest {
            val name = "saavn_ghost_128.m4a"
            write(name, agedMs = 90_000)
            reconciler.run()
            assertFalse(fs.exists(paths.audioDir / name), "90 s old ⇒ swept")

            // Same path, same bytes, same sweep: only the injected clock moved.
            write(name, agedMs = 10_000)
            reconciler.run()
            assertTrue(fs.exists(paths.audioDir / name), "10 s old ⇒ inside the window, must survive")
        }
}
