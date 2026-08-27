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
import dylan.download.stallTripped
import dylan.download.stallWallCapMs
import dylan.model.ErrorCode
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.write
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
    var streamType: String = "mp4"

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
        return if (code == 200) SignedStream("http://mock/audio", streamType) else null
    }
}

class DownloadEngineTest {
    private val testLog =
        dylan.diag.LogBuffer(minLevel = dylan.diag.LogLevel.DEBUG).also { buf ->
            buf.bindSink { e -> println("[${e.level}] Dylan:${e.tag} ${e.msg}") }
        }

    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var engine: DownloadEngine
    private lateinit var provider: FakeProvider
    private lateinit var audioDir: okio.Path
    private val disp = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)
    private var cfg = AppConfig()
    private lateinit var bulk: HttpClient
    private var slowChunkBytes = 0
    private var slowChunkDelayMs = 0L
    private var bodyFeeder: Job? = null
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * RawSource fed chunk-by-chunk from a coroutine channel; EOF on channel close. Polls in
     * short windows so job cancellation (stall watchdog) unwinds within ~200 ms instead of
     * parking a lane uninterruptibly.
     */
    private class ScriptedSource(
        private val chunks: Channel<ByteArray>,
    ) : RawSource {
        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                val chunk =
                    runBlocking {
                        withTimeoutOrNull(200L) {
                            try {
                                chunks.receive()
                            } catch (_: ClosedReceiveChannelException) {
                                null
                            }
                        }
                    }
                when {
                    chunk != null -> {
                        sink.write(chunk)
                        return chunk.size.toLong()
                    }
                    chunks.isClosedForReceive -> return -1L
                }
            }
            return -1L // safety net: 60 s of silence reads as EOF
        }

        override fun close() {}
    }

    /** Body delivered slowChunkBytes every slowChunkDelayMs, then clean EOF. */
    private fun slowBodyChannel(): ByteReadChannel {
        val chunks = Channel<ByteArray>()
        bodyFeeder =
            testScope.launch {
                var off = 0
                while (off < mockBody.size) {
                    val n = minOf(slowChunkBytes, mockBody.size - off)
                    chunks.send(mockBody.copyOfRange(off, off + n))
                    off += n
                    delay(slowChunkDelayMs)
                }
                chunks.close()
            }
        return ByteReadChannel(ScriptedSource(chunks).buffered())
    }

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
        db.dylanQueries.insertSong(key.provider, key.songId, "s1", "", null, null, "", "", 100L, 1L, "enc-ref", null, 0L)
        provider = FakeProvider(ArrayDeque(listOf(200)))
        val mock =
            MockEngine { request ->
                val body: ByteReadChannel =
                    when {
                        slowChunkBytes > 0 -> slowBodyChannel()
                        else -> ByteReadChannel(mockBody)
                    }
                respond(
                    content = body,
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
        bulk = HttpClient(mock)
        audioDir = tmp.toPath() / "audio"
        buildEngine()
    }

    private fun buildEngine() {
        val fs = FileSystem.SYSTEM
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
                log = testLog,
            )
    }

    private fun rebuildEngine(newCfg: AppConfig) {
        runCatching { engine.stop() }
        cfg = newCfg
        buildEngine()
    }

    @AfterTest
    fun teardown() {
        bodyFeeder?.cancel()
        runCatching { engine.stop() }
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private suspend fun runJob(id: String = "s1"): JobState {
        val key = SongKey("saavn", id)
        engine.start()
        engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
        kotlinx.coroutines.withTimeout(15_000) {
            engine.states.first { s -> s[key] is JobState.Done || s[key] is JobState.Failed }
        }
        return engine.states.value[key]!!
    }

    private fun seedPart(id: String): okio.Path {
        val p = (tmp + "/audio").toPath() / "saavn_${id}_128.part"
        FileSystem.SYSTEM.write(p) { write(ByteArray(64)) }
        return p
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
            db.dylanQueries.insertSong(k2.provider, k2.songId, "s2", "", null, null, "", "", 100L, 1L, "enc-s2", null, 0L)
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

    @Test
    fun wallCapIsRateBasedNotFixed() {
        // 1 s track ⇒ expectedB = 20 400 B ⇒ cap = 20 400/8 = 2 550 ms (floor 300 ms).
        // The old `expectedB/20` formula gave 1 020 ms — killed flowing 1.5 s transfers.
        assertEquals(2_550L, stallWallCapMs(300, 20_400))
        assertEquals(120_000L, stallWallCapMs(120_000, 20_400))
        assertEquals(300L, stallWallCapMs(300, 400))
    }

    @Test
    fun stallWatchdogTripMatrix() {
        // Silent gap (no fresh bytes) trips regardless of wall budget.
        assertTrue(stallTripped(sinceChunkMs = 500, totalElapsedMs = 500, wallCapMs = 2_550, stallTimeoutMs = 400))
        // Flowing-but-slow: fresh bytes keep arriving, elapsed under cap ⇒ no trip.
        assertFalse(stallTripped(sinceChunkMs = 150, totalElapsedMs = 1_800, wallCapMs = 2_550, stallTimeoutMs = 1_000))
        // Trickle below the rate floor: bytes flow but the transfer outlives the cap ⇒ trip.
        assertTrue(stallTripped(sinceChunkMs = 100, totalElapsedMs = 2_600, wallCapMs = 2_550, stallTimeoutMs = 1_000))
        // Exactly-at boundaries are healthy (strict inequality guards against tick jitter).
        assertFalse(stallTripped(sinceChunkMs = 400, totalElapsedMs = 2_550, wallCapMs = 2_550, stallTimeoutMs = 400))
    }

    @Test
    fun slowFlowingStreamSurvivesWallCap() =
        runBlocking {
            // 1 s song ⇒ wall cap 2 550 ms; chunks flow every 150 ms (< stall timeout);
            // transfer takes ~1.8 s — survives the new cap, died under the old /20 formula.
            rebuildEngine(AppConfig(stallTimeoutMs = 1_000, stallWatchdogTickMs = 100, stallWallFloorMs = 300, dlRetries = 0))
            db.dylanQueries.insertSong("saavn", "slow", "slow", "", null, null, "", "", 1L, 1L, "enc-slow", null, 0L)
            provider.statuses = ArrayDeque(listOf(200, 200, 200, 200))
            slowChunkBytes = 100
            slowChunkDelayMs = 150
            mockBody = ftypBody(1200)
            mockHeaders = mapOf("Content-Length" to "1200", "Content-Type" to "audio/mp4")
            val st = runJob("slow")
            assertTrue(st is JobState.Done, "flowing transfer must not be wall-killed, got $st")
        }

    @Test
    fun missingContentTypeSniffsM4aFromMagic() =
        runBlocking {
            provider.streamType = "weird"
            mockBody = ftypBody(1000)
            mockHeaders = mapOf("Content-Length" to "1000")
            val st = runJob()
            assertTrue(st is JobState.Done, "ftyp body must survive unusable content-type, got $st")
            assertEquals(
                "m4a",
                db.dylanQueries
                    .selectCached("saavn", "s1")
                    .executeAsOne()
                    .ext,
            )
        }

    @Test
    fun missingContentTypeSniffsMp3FromId3() =
        runBlocking {
            provider.streamType = "weird"
            mockBody = ByteArray(800) { 'M'.code.toByte() }
            "ID3".encodeToByteArray().copyInto(mockBody)
            mockHeaders = mapOf("Content-Length" to "800")
            val st = runJob()
            assertTrue(st is JobState.Done, "ID3 body must survive unusable content-type, got $st")
            assertEquals(
                "mp3",
                db.dylanQueries
                    .selectCached("saavn", "s1")
                    .executeAsOne()
                    .ext,
            )
        }

    @Test
    fun nonResumableFailureDeletesItsPart() =
        runBlocking {
            val p = seedPart("s1")
            // use NO_SOURCE as non-resumable sentinel (resolve_ref null). insertSong is
            // INSERT OR IGNORE so it cannot clobber the setup row — null the source
            // via updateSongSource instead.
            db.dylanQueries.updateSongSource(null, null, 0L, "saavn", "s1")
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.NO_SOURCE, "got $st")
            assertFalse(FileSystem.SYSTEM.exists(p), "NO_SOURCE must not leak its .part")
        }

    @Test
    fun resumableFailureKeepsItsPartForReconciler() =
        runBlocking {
            val p = seedPart("s1")
            mockStatus = HttpStatusCode.InternalServerError
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.NETWORK, "got $st")
            assertTrue(FileSystem.SYSTEM.exists(p), "NETWORK failure keeps the .part for resume")
        }
}
