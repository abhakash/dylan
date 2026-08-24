package dylan

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.JobState
import dylan.download.Priority
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.util.AppDispatchers
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeProvider(
    var statuses: ArrayDeque<Int>,
) : MusicProvider {
    var resolveCalls = 0
    val resolvedKeys = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

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
    ): SignedStream? {
        resolveCalls++
        resolvedKeys += resolveRef
        gate?.await()
        val code = statuses.removeFirstOrNull() ?: 200
        return if (code == 200) SignedStream("http://mock/audio", "mp4") else null
    }
}

class DownloadEngineTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var engine: DownloadEngine
    private lateinit var provider: FakeProvider
    private lateinit var audioDir: okio.Path
    private val disp = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)
    private val cfg = AppConfig()
    private val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    private var mockStatus: HttpStatusCode = HttpStatusCode.OK
    private var mockBody: ByteArray = ByteArray(0)
    private var mockHeaders: Map<String, String> = emptyMap()
    private var netNow = dylan.util.NetClass.UNMETERED
    private var prefNow = Quality.BITRATE_128

    private fun ftypBody(size: Int): ByteArray {
        val b = ByteArray(size)
        "ftyp".encodeToByteArray().copyInto(b, 4)
        return b
    }

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-eng-${System.nanoTime()}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db").createDriver())
        val key = SongKey("saavn", "s1")
        db.dylanQueries.insertSong(key.provider, key.songId, "s1", "", null, null, "", "", 100L, 1L, 1L, "enc-ref", null, 0L)
        provider = FakeProvider(ArrayDeque(listOf(200)))
        val mock =
            MockEngine { request ->
                respond(
                    content = mockBody,
                    status = mockStatus,
                    headers =
                        headersOf(
                            *mockHeaders
                                .map { (k, v) ->
                                    k to listOf(v)
                                }.toTypedArray(),
                        ),
                )
            }
        val bulk = HttpClient(mock)
        audioDir = tmp.toPath() / "audio"
        val cacheManager = CacheManager(db, fs, Paths(audioDir, fs), protectedKeys, cfg, disp)
        engine =
            DownloadEngine(
                db = db,
                fs = fs,
                paths = Paths(audioDir, fs),
                cfg = cfg,
                disp = disp,
                provider = provider,
                bulk = bulk,
                breakers = Breakers(),
                cacheManager = cacheManager,
                netClass = { netNow },
                qualityPref = { prefNow },
            )
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private suspend fun runJob(): JobState {
        val key = SongKey("saavn", "s1")
        engine.start()
        engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
        kotlinx.coroutines.withTimeout(10_000) {
            engine.states.first { s -> s[key] is JobState.Done || s[key] is JobState.Failed }
        }
        return engine.states.value[key]!!
    }

    @Test
    fun exactContentLengthPassesAndCommits() =
        runBlocking {
            mockBody = ftypBody(1000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Done, "expected Done got $st")
            assertEquals(
                "m4a",
                db.dylanQueries
                    .selectCached("saavn", "s1")
                    .executeAsOne()
                    .ext,
            )
        }

    @Test
    fun sizeMismatchFailsCorruptAndDeletesArtifact() =
        runBlocking {
            mockBody = ftypBody(500)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == dylan.model.ErrorCode.CORRUPT_SIZE, "got $st")
            val parts = FileSystem.SYSTEM.list((tmp + "/audio").toPath()).filter { it.name.endsWith(".part") }
            assertTrue(parts.isEmpty(), "artifact must be deleted")
        }

    @Test
    fun mp3SkipsFtypCheck() =
        runBlocking {
            mockBody = ByteArray(800) { 'M'.code.toByte() }
            mockHeaders = mapOf("Content-Length" to "800", "Content-Type" to "audio/mpeg")
            val st = runJob()
            assertTrue(st is JobState.Done, "mp3 without ftyp must pass, got $st")
            assertEquals(
                "mp3",
                db.dylanQueries
                    .selectCached("saavn", "s1")
                    .executeAsOne()
                    .ext,
            )
        }

    @Test
    fun badContainerFailsCorruptContainer() =
        runBlocking {
            mockBody = ByteArray(1000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == dylan.model.ErrorCode.CORRUPT_CONTAINER, "got $st")
        }

    @Test
    fun forbiddenRetriesResolveThenFailsForbiddenRegion() =
        runBlocking {
            mockStatus = HttpStatusCode.Forbidden
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == dylan.model.ErrorCode.FORBIDDEN_REGION, "got $st")
            assertEquals(cfg.resolveCapPerJob, provider.resolveCalls)
        }

    @Test
    fun notFoundFailsImmediately() =
        runBlocking {
            mockStatus = HttpStatusCode.NotFound
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == dylan.model.ErrorCode.NOT_FOUND)
        }

    @Test
    fun successConsumesIntentRow() =
        runBlocking {
            db.dylanQueries.upsertIntent("saavn", "s1", "USER_NOW", 128L, 1L)
            mockBody = ftypBody(1000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            runJob()
            assertFalse(
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .any { it.song_id == "s1" },
            )
        }

    @Test
    fun commitPreservesEngagementStatsOnUpgrade() =
        runBlocking {
            val key = SongKey("saavn", "s1")
            FileSystem.SYSTEM.write(audioDir / "saavn_s1_128.m4a") { write(ByteArray(999)) }
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", 999L, 5L, 777L, 4L, 1L, 42L)
            prefNow = Quality.BITRATE_320
            mockBody = ftypBody(1000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Done)
            val row = db.dylanQueries.selectCached(key.provider, key.songId).executeAsOne()
            assertEquals(320L, row.bitrate)
            assertEquals(777L, row.last_used_ms)
            assertEquals(4L, row.play_count)
            assertEquals(42L, row.pinned_at_ms)
            assertEquals(1L, row.pinned)
        }

    @Test
    fun cachedSufficientBitrateSkipsNetworkEntirely() =
        runBlocking {
            val key = SongKey("saavn", "s1")
            FileSystem.SYSTEM.write(audioDir / "saavn_s1_128.m4a") { write(ByteArray(1000)) }
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", 1000L, 1L, null, 0, 0, null)
            engine.start()
            engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
            kotlinx.coroutines.withTimeout(10_000) {
                engine.states.first { s -> s[key] is JobState.Done }
            }
            assertEquals(0, provider.resolveCalls, "sufficiency dedupe must serve from cache without resolving")
        }

    @Test
    fun meteredNeverSpendsCellularOnUpgrade() =
        runBlocking {
            val key = SongKey("saavn", "s1")
            netNow = dylan.util.NetClass.METERED
            prefNow = Quality.BITRATE_320
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", 1000L, 1L, null, 0, 0, null)
            engine.start()
            engine.enqueue(DownloadJob(key, Priority.QUALITY_UPGRADE, 320, 0L))
            kotlinx.coroutines.withTimeout(10_000) {
                engine.states.first { s -> s[key] is JobState.Done }
            }
            assertEquals(0, provider.resolveCalls, "metered upgrade must not touch the network")
            assertEquals(
                128L,
                db.dylanQueries
                    .selectCached(key.provider, key.songId)
                    .executeAsOne()
                    .bitrate,
            )
        }

    @Test
    fun duplicateEnqueueWhileExecutingRunsOnce() =
        runBlocking {
            val k1 = SongKey("saavn", "s1")
            val k2 = SongKey("saavn", "s2")
            db.dylanQueries.insertSong(k2.provider, k2.songId, "s2", "", null, null, "", "", 100L, 1L, 1L, "enc-s2", null, 0L)
            mockBody = ftypBody(1000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            provider.gate = CompletableDeferred()
            engine.start()
            engine.enqueue(DownloadJob(k1, Priority.USER_NOW, 128, 1L))
            kotlinx.coroutines.withTimeout(10_000) {
                engine.states.first { it[k1] is JobState.Resolving || it[k1] is JobState.Downloading }
            }
            engine.enqueue(DownloadJob(k1, Priority.USER_NOW, 128, 2L))
            provider.gate?.complete(Unit) ?: error("gate missing")
            engine.enqueue(DownloadJob(k2, Priority.USER_NOW, 128, 3L))
            kotlinx.coroutines.withTimeout(20_000) {
                engine.states.first { it[k2] is JobState.Done || it[k2] is JobState.Failed }
            }
            assertEquals(1, provider.resolvedKeys.count { it == "enc-ref" }, "duplicate same-key job must be dropped, not re-run")
            assertTrue(engine.states.value[k1] is JobState.Done)
        }

    @Test
    fun partCapSacrificesPrefetchPartsBeforeNewestUserPart() =
        runBlocking {
            val fs = FileSystem.SYSTEM

            fun part(id: String) = audioDir / "saavn_${id}_128.part"

            fun seed(
                id: String,
                reason: String,
                ageMs: Long,
            ) {
                fs.write(part(id)) { write(ByteArray(10)) }
                java.io.File(part(id).toString()).setLastModified(System.currentTimeMillis() - ageMs)
                db.dylanQueries.upsertIntent("saavn", id, reason, 128L, 0L)
            }
            seed("p1", "PREFETCH_NEXT", 40_000)
            seed("p2", "PREFETCH_NEXT", 30_000)
            seed("p3", "PREFETCH_NEXT", 20_000)
            seed("u1", "USER_NOW", 10_000)
            engine.enforcePartCap()
            assertFalse(fs.exists(part("p1")), "oldest PREFETCH part is the first victim")
            assertTrue(fs.exists(part("p2")))
            assertTrue(fs.exists(part("p3")))
            assertTrue(fs.exists(part("u1")), "just-preempted USER_NOW part must survive the cap pass")
        }
}
