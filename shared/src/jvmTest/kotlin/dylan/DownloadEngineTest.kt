package dylan

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.download.Breakpoint
import dylan.download.Breaker
import dylan.download.BreakerState
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.EnqueueResult
import dylan.download.JobQueue
import dylan.download.JobState
import dylan.download.PartMeta
import dylan.download.Priority
import dylan.download.ProgressThrottle
import dylan.download.Reply
import dylan.download.Resume
import dylan.download.SizeVerdict
import dylan.download.parseContentRange
import dylan.download.parseRetryAfterMs
import dylan.download.resumeDecision
import dylan.download.sizeVerdict
import dylan.download.stallTripped
import dylan.download.stallWallCapMs
import dylan.model.ErrorCode
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.support.MutableClock
import dylan.support.TestLanes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.write
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.system.measureNanoTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeProvider(
    var statuses: ArrayDeque<Int>,
) : MusicProvider {
    var resolveCalls = 0
    val resolvedKeys = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var streamType: String = "mp4"

    /** URL host per resolve_ref, so a test can put two jobs on two different breakers. */
    var hostFor: (String) -> String = { "mock" }
    var urlOverride: String? = null

    /** Throw on this 1-based resolve call — the "provider blew up" path DL-4 is about. */
    var throwOnResolve: Int = 0

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
        if (throwOnResolve == resolveCalls) throw IllegalStateException("provider exploded")
        gate?.await()
        val code = statuses.removeFirstOrNull() ?: 200
        val url = urlOverride ?: "http://${hostFor(resolveRef)}/audio"
        return if (code == 200) SignedStream(url, streamType) else null
    }
}

/**
 * One scripted reply for one request.
 *
 * The old harness could only produce one status for the whole run, which is why 206, 416, 429, 503
 * and 401 were produced by *no* test — and therefore why `If-Range` was never sent anywhere and
 * the resume path was untested.
 */
private class Scripted(
    val status: HttpStatusCode = HttpStatusCode.OK,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val chunkBytes: Int = 0,
    val chunkDelayMs: Long = 0,
    /** Deliver only this many bytes, then close cleanly — a server that hangs up early. */
    val stopAfterBytes: Int = -1,
    /** Throw an IOException after this many bytes — a TCP reset mid-transfer. */
    val throwAfterBytes: Int = -1,
)

/** The plan is mutable and read per request, so a test can script after the graph is built. */
private class Plan(
    var replies: List<Scripted> = emptyList(),
    var repeatLast: Boolean = true,
    var fallback: () -> Scripted = { Scripted() },
) {
    fun at(i: Int): Scripted = replies.getOrNull(i) ?: replies.lastOrNull()?.takeIf { repeatLast } ?: fallback()
}

private class RequestLog(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Per-request scripted [MockEngine], with the recorded requests the assertions read. */
private class ScriptedOrigin(
    private val plan: Plan,
    private val scope: CoroutineScope,
    private val feeders: MutableList<Job>,
) {
    val requests = CopyOnWriteArrayList<RequestLog>()
    private val liveBodies = AtomicInteger(0)
    val peakBodies = AtomicInteger(0)

    val mock: MockEngine =
        MockEngine { request: HttpRequestData ->
            val i = requests.size
            requests += RequestLog(request.method.value, request.url.toString(), request.headers.entries().associate { it.key to it.value.joinToString(",") })
            val scripted = plan.at(i)
            respond(
                content = bodyChannel(scripted),
                status = scripted.status,
                headers = headersOf(*scripted.headers.map { (k, v) -> k to listOf(v) }.toTypedArray()),
            )
        }

    fun count(): Int = requests.size

    fun rangeOf(i: Int): String? = requests[i].header(HttpHeaders.Range)

    fun ifRangeOf(i: Int): String? = requests[i].header(HttpHeaders.IfRange)

    fun closeAll() {
        feeders.forEach { it.cancel() }
        feeders.clear()
    }

    private fun bodyChannel(scripted: Scripted): ByteReadChannel {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val limit = if (scripted.stopAfterBytes >= 0) scripted.stopAfterBytes else scripted.body.size
        feeders +=
            scope.launch {
                var off = 0
                while (off < limit) {
                    val n = if (scripted.chunkBytes > 0) min(scripted.chunkBytes, limit - off) else limit - off
                    chunks.send(scripted.body.copyOfRange(off, off + n))
                    off += n
                    if (scripted.chunkDelayMs > 0) delay(scripted.chunkDelayMs)
                }
                chunks.close(if (scripted.throwAfterBytes >= 0) IOException("scripted connection reset") else null)
            }
        liveBodies.incrementAndGet()
        peakBodies.updateAndGet { prev -> maxOf(prev, liveBodies.get()) }
        return ByteReadChannel(CountingSource(chunks, liveBodies).buffered())
    }
}

/**
 * Counts down on EOF so a test can observe how many bodies were open at once, and treats silence
 * as a failure.
 *
 * The previous harness returned EOF after 60 s of silence, so a hung test reported `Done` — the
 * harness itself encoded the truncation-equals-success bug the engine is being fixed for.
 */
private class CountingSource(
    private val chunks: Channel<ByteArray>,
    private val live: AtomicInteger,
) : RawSource {
    private var counted = true

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val chunk =
            runBlocking {
                withTimeoutOrNull(READ_POLL_MS) {
                    try {
                        chunks.receive()
                    } catch (_: ClosedReceiveChannelException) {
                        null
                    }
                }
            }
        if (chunk == null) {
            if (chunks.isClosedForReceive) {
                release()
                return -1L
            }
            throw IOException("scripted body went silent for ${READ_POLL_MS}ms")
        }
        sink.write(chunk)
        return chunk.size.toLong()
    }

    override fun close() {
        release()
    }

    private fun release() {
        if (counted) {
            counted = false
            live.decrementAndGet()
        }
    }

    private companion object {
        const val READ_POLL_MS = 2_000L
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
    private lateinit var audioDir: Path
    private lateinit var origin: ScriptedOrigin
    private lateinit var cacheManager: CacheManager
    private lateinit var bulk: HttpClient
    private val plan = Plan()
    private val disp = TestLanes().disp
    private var cfg = AppConfig()
    private val feeders = mutableListOf<Job>()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    private var netNow = dylan.util.NetClass.UNMETERED
    private var prefNow = Quality.BITRATE_128
    private var mockStatus: HttpStatusCode = HttpStatusCode.OK
    private var mockBody: ByteArray = ByteArray(0)
    private var mockHeaders: Map<String, String> = emptyMap()

    // ---- fixtures -----------------------------------------------------------------------------

    /** A minimal but *real* mp4 head: `ftyp` at 4 plus a printable major brand at 8. */
    private fun mp4Body(size: Int): ByteArray {
        val b = ByteArray(size)
        "ftyp".encodeToByteArray().copyInto(b, 4)
        "M4A ".encodeToByteArray().copyInto(b, 8)
        return b
    }

    private fun mp3Body(
        size: Int,
        id3: Boolean = true,
    ): ByteArray {
        val b = ByteArray(size)
        var off = 0
        if (id3) {
            "ID3".encodeToByteArray().copyInto(b, 0)
            b[3] = 3
            off = 10
        }
        b[off] = 0xFF.toByte()
        b[off + 1] = 0xFB.toByte()
        return b
    }

    /** A bot-wall / CDN error page: plausible length, no container anywhere in it. */
    private fun htmlBody(size: Int): ByteArray {
        val page = "<!doctype html><html><head><title>403 Forbidden</title></head><body>Access denied</body></html>"
        val blob = page.encodeToByteArray()
        return ByteArray(size) { blob[it % blob.size] }
    }

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-eng-${System.nanoTime()}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db", testLog).createDriver())
        insertSong("saavn", "s1", "enc-ref")
        provider = FakeProvider(ArrayDeque(listOf(200)))
        audioDir = tmp.toPath() / "audio"
        plan.fallback = { Scripted(mockStatus, mockHeaders, mockBody) }
        origin = ScriptedOrigin(plan, testScope, feeders)
        bulk = HttpClient(origin.mock)
        buildEngine()
    }

    private fun insertSong(
        provider: String,
        id: String,
        ref: String,
        durationS: Long = 100L,
    ) {
        db.dylanQueries.insertSong(provider, id, id, "", null, null, "", "", durationS, 1L, ref, null, 0L)
    }

    private fun buildEngine(fs: FileSystem = FileSystem.SYSTEM) {
        val paths = Paths(audioDir, fs)
        cacheManager = CacheManager(db, fs, paths, protectedKeys, cfg, disp, testLog)
        engine =
            DownloadEngine(
                db = db,
                fs = fs,
                paths = paths,
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

    private fun rebuildEngine(
        newCfg: AppConfig = cfg,
        fs: FileSystem = FileSystem.SYSTEM,
    ) {
        runCatching { engine.stop() }
        cfg = newCfg
        buildEngine(fs)
    }

    @AfterTest
    fun teardown() {
        origin.closeAll()
        runCatching { engine.stop() }
        runCatching { testScope.cancel() }
        runCatching { bulk.close() }
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private suspend fun runJob(id: String = "s1"): JobState {
        val key = SongKey("saavn", id)
        engine.start()
        engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
        withTimeout(JOB_TIMEOUT_MS) {
            engine.states.first { s -> s[key] is JobState.Done || s[key] is JobState.Failed }
        }
        return engine.states.value[key]!!
    }

    private fun part(
        id: String,
        bits: Int = 128,
    ): Path = audioDir / "saavn_${id}_$bits.part"

    private fun seedPart(
        id: String,
        bytes: ByteArray,
        bits: Int = 128,
    ): Path {
        val p = part(id, bits)
        FileSystem.SYSTEM.write(p) { write(bytes) }
        return p
    }

    private fun seedResumable(
        id: String,
        head: ByteArray,
        total: Long,
        etag: String,
        bits: Int = 128,
    ) {
        seedPart(id, head, bits)
        val bp = Breakpoint(head.size.toLong(), total, etag, Quality.of(bits), 1_756_000_000_000L)
        assertTrue(PartMeta.write(FileSystem.SYSTEM, PartMeta.sidecarOf(part(id, bits)), bp), "the sidecar must be writable or the cold-resume test means nothing")
    }

    private fun cachedRow(id: String) = db.dylanQueries.selectCached("saavn", id).executeAsOneOrNull()

    private fun exists(name: String): Boolean = FileSystem.SYSTEM.exists(audioDir / name)

    private fun parts(): List<Path> = FileSystem.SYSTEM.list(audioDir).filter { it.name.endsWith(".part") }

    // ---- pure decisions ------------------------------------------------------------------------

    @Test
    fun resumeDecisionIsOneFunctionForEveryPath() {
        val bp = Breakpoint(2_000_000, 3_000_000, "v1", Quality.BITRATE_128, 0L)

        @Suppress("LongParameterList")
        fun reply(
            status: Int,
            etag: String? = null,
            length: Long? = null,
            rangeStart: Long? = null,
            total: Long? = null,
            retryAfterMs: Long? = null,
        ) = Reply(status, etag, null, length, rangeStart, null, total, retryAfterMs)

        assertEquals(Resume.Append(2_000_000, 3_000_000, "v1"), resumeDecision(bp, reply(206, "v1", 1_000_000, 2_000_000, 3_000_000)))
        assertEquals(Resume.Refetch, resumeDecision(bp, reply(206, "v1", 1_100_000, 0, 3_100_000)), "206-from-0 against a 2 MB part is DL-3's undetectable case")
        assertEquals(Resume.Refetch, resumeDecision(bp, reply(206, "v2", 1_000_000, 2_000_000, 3_000_000)), "a changed validator must never splice")
        assertEquals(Resume.Refetch, resumeDecision(bp, reply(206, "v1", 1_000_000, 2_000_000, 3_100_000)), "a changed total must never splice")
        assertEquals(Resume.Restart, resumeDecision(bp, reply(200, "v2", 1_100_000)), "a 200 to a Range restarts on the body in hand")
        assertEquals(Resume.Refetch, resumeDecision(bp, reply(416, total = 1_500_000)))
        assertEquals(Resume.Fail(ErrorCode.RATE_LIMITED, "http 429"), resumeDecision(bp, reply(429, retryAfterMs = 1_000)))
        assertEquals(Resume.Fail(ErrorCode.RATE_LIMITED, "http 503"), resumeDecision(bp, reply(503)))
        assertEquals(Resume.Fail(ErrorCode.EXPIRED, "http 401"), resumeDecision(bp, reply(401)))
        assertEquals(Resume.Fail(ErrorCode.EXPIRED, "http 403"), resumeDecision(bp, reply(403)))
        assertEquals(Resume.Fail(ErrorCode.NOT_FOUND, "http 404"), resumeDecision(bp, reply(404)))
        assertEquals(Resume.Fail(ErrorCode.NETWORK, "http 500"), resumeDecision(bp, reply(500)))
        assertEquals(Resume.Fail(ErrorCode.DRIFT, "206 without a Content-Range start"), resumeDecision(bp, reply(206, length = 10)))
        // Cold start: nothing held, so a 206 from zero is simply an append.
        assertEquals(Resume.Append(0, 1_000, null), resumeDecision(null, reply(206, length = 1_000, rangeStart = 0, total = 1_000)))
        assertEquals(Resume.Restart, resumeDecision(null, reply(200, length = 1_000)))
    }

    @Test
    fun contentRangeKeepsTheStartOffsetAndBothRetryAfterForms() {
        assertEquals(Triple(200L, 1023L, 1024L), parseContentRange("bytes 200-1023/1024"))
        assertEquals(Triple(0L, 9L, null), parseContentRange("bytes 0-9/*"))
        assertNull(parseContentRange("bytes */1024"))
        assertNull(parseContentRange("items 1-2/3"))
        val now = 1_756_000_000_000L
        assertEquals(120_000L, parseRetryAfterMs("120", now))
        assertEquals(0L, parseRetryAfterMs("0", now))
        assertNull(parseRetryAfterMs(null, now))
        assertNull(parseRetryAfterMs("soon", now))
        // HTTP-date form. A fixed instant, so the expectation is the instant, not "now".
        val parsed = assertNotNull(parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 0L), "RFC-1123 form must parse")
        assertEquals(1_445_412_480_000L, parsed, "2015-10-21T07:28:00Z")
        assertEquals(0L, parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", parsed), "a date already past yields 0, not a negative delay")
    }

    @Test
    fun sizeCheckUsesThePersistedTotalNotTheEstimate() {
        // DL-6 counterexample: a legitimate 128 kbps m4a for a 100 s track. bps is already 27%
        // padded, so 90% of duration*bps sits *above* the real file and rejected every one of them.
        val raw = 100L * 20_400L
        assertEquals(SizeVerdict.BandOk, sizeVerdict(1_580_000, null, raw * 4 / 10, raw * 13 / 10), "the band must accept a real 128 kbps file")
        assertEquals(SizeVerdict.Exact, sizeVerdict(1_000, 1_000, 0, 0))
        assertEquals(SizeVerdict.Short, sizeVerdict(999, 1_000, 0, 0))
        assertEquals(SizeVerdict.Oversize, sizeVerdict(1_001, 1_000, 0, 0))
        assertEquals(SizeVerdict.BandLow, sizeVerdict(10, null, 100, 200))
        assertEquals(SizeVerdict.BandHigh, sizeVerdict(300, null, 100, 200))
    }

    @Test
    fun breakpointSidecarRoundTripsAndRejectsATornWrite() {
        val bp = Breakpoint(2_000_000, 3_000_000, "W/\"abc\"", Quality.BITRATE_320, 1_756_000_000_123L)
        assertEquals(bp, PartMeta.decode(PartMeta.encode(bp)))
        assertNull(PartMeta.decode("v=0\nq=BITRATE_128\nbytes=1\n"), "another version is ignored, not misread")
        assertNull(PartMeta.decode("v=1\nbytes=1\n"), "quality is required")
        assertEquals(3_000_000L, bp.wrote(3_000_000).totalBytes, "wrote() keeps the origin's declaration")
        assertTrue(bp.contradicts("v2", null))
        assertFalse(bp.contradicts(null, null), "no answer to compare against is not a contradiction")
    }

    @Test
    fun priorityIsRankedNotOrdinalAndWireNamesAreStable() {
        assertEquals(listOf("USER_NOW", "USER_BULK", "PREFETCH_NEXT", "QUALITY_UPGRADE"), Priority.entries.map { it.wire })
        assertEquals(Priority.entries.sortedBy { it.rank }, Priority.entries, "rank order must equal declaration order")
        assertEquals(Priority.USER_NOW, Priority.fromWire("USER_NOW"))
        assertNull(Priority.fromWire("nope"))
        assertNull(Priority.fromWire(null))
    }

    @Test
    fun queueOrdersByRankThenAttemptAndNeverDoubleBooksAKey() {
        val q = JobQueue(3)
        val a = q.offer(job("a", Priority.QUALITY_UPGRADE)) as EnqueueResult.Queued
        val b = q.offer(job("b", Priority.USER_NOW)) as EnqueueResult.Queued
        val c = q.offer(job("c", Priority.PREFETCH_NEXT)) as EnqueueResult.Queued
        assertEquals(listOf("b", "c", "a"), listOfNotNull(q.claimNext()?.key?.songId, q.claimNext()?.key?.songId, q.claimNext()?.key?.songId))
        assertEquals(EnqueueResult.SupersededByHigherPriority, q.offer(job("a", Priority.QUALITY_UPGRADE)), "a same-key duplicate must not disturb the live attempt")
        val pre = q.offer(job("a", Priority.USER_NOW))
        assertTrue(pre is EnqueueResult.Preempted, "a strictly better priority must preempt, got $pre")
        assertEquals("a", (pre as EnqueueResult.Preempted).victim.key.songId)
        assertNull(q.claimNext(), "a key that is still owned is never handed to a second worker")
        q.release(SongKey("saavn", "b"))
        assertTrue(a.id.seq < b.id.seq && b.id.seq < c.id.seq, "attempt ids are monotonic, so a clock step cannot reorder the queue")
        assertEquals(b.id, q.activeAttempt(SongKey("saavn", "b")))
        // At capacity, a job nobody can displace is dropped rather than growing the queue.
        val full = JobQueue(1)
        full.offer(job("x", Priority.USER_NOW))
        assertEquals(EnqueueResult.Dropped, full.offer(job("y", Priority.PREFETCH_NEXT)))
    }

    private fun job(
        id: String,
        reason: Priority,
    ) = DownloadJob(SongKey("saavn", id), reason, 128, 0L)

    @Test
    fun breakerCountsThenOpensThenProbesOnce() {
        val b = Breaker(failureThreshold = 3, baseCooldownMs = 1_000, maxCooldownMs = 60_000, jitter = { it })
        val now = 0L
        assertTrue(b.tryAcquire(now))
        repeat(2) {
            b.onFailure(now)
            assertEquals(BreakerState.CLOSED, b.state, "two strikes are not a pattern")
        }
        b.onFailure(now)
        assertEquals(BreakerState.OPEN, b.state)
        assertEquals(1_000L, b.view().openUntilMs, "the first open is the base cooldown")
        assertFalse(b.tryAcquire(now + 999), "still open inside the window")
        assertTrue(b.tryAcquire(now + 1_000), "the deadline releases exactly one probe")
        assertFalse(b.tryAcquire(now + 1_000), "half-open admits one caller, not a herd")
        b.onFailure(now + 1_000)
        assertEquals(3_000L, b.view().openUntilMs, "a failed probe doubles the cooldown")
        assertTrue(b.tryAcquire(now + 3_000))
        b.onSuccess()
        assertEquals(BreakerState.CLOSED, b.state)
        assertEquals(0, b.view().failures)
    }

    @Test
    fun breakerDeadlinesAreMonotonicAndTheHostMapIsBounded() {
        val registry = Breakers(maxHosts = 8)
        val host = registry.forHost("h")
        repeat(3) { host.onFailure(registry.nowMs()) }
        assertEquals(BreakerState.OPEN, host.state, "5xx, connection failures and stalls are host-health signals too")
        assertEquals(BreakerState.CLOSED, registry.stateOf("other"), "granularity is the host")
        assertTrue(registry.nowMs() > 0)
        repeat(200) { registry.forHost("host-$it") }
        assertTrue(registry.size() <= 8, "host map must be bounded, was ${registry.size()}")
    }

    @Test
    fun rateLimitIsHonouredForThisWindowOnly() {
        val b = Breaker(jitter = { it })
        b.onRateLimited(1_000L, 3_600_000L)
        assertEquals(1_000L + 3_600_000L, b.view().openUntilMs, "a server's Retry-After is honoured")
        b.onRateLimited(1_000L + 3_600_000L, 1L)
        assertEquals(1_000L + 3_600_000L + 1L, b.view().openUntilMs, "each window starts from now, so yesterday's hour cannot stick")
    }

    // ---- engine: the resume matrix -------------------------------------------------------------

    @Test
    fun exactContentLengthPassesAndCommits() =
        runBlocking {
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Done, "expected Done got $st")
            assertEquals("m4a", assertNotNull(cachedRow("s1")).ext)
            assertEquals(1, origin.count(), "a clean 200 is one request")
        }

    @Test
    fun aShortBodyIsResumableAndKeepsItsPart() =
        runBlocking {
            // DL-2. The old engine called this CORRUPT_SIZE *and deleted the .part*, destroying the
            // only resumable bytes; with no Content-Length it accepted the short file outright.
            rebuildEngine(cfg.copy(dlRetries = 0))
            plan.replies = listOf(Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "12000", "Content-Type" to "audio/mp4"), mp4Body(12_000), stopAfterBytes = 6_000))
            plan.repeatLast = false
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.CORRUPT_SIZE, "got $st")
            assertEquals(1, origin.count())
            assertTrue(parts().isNotEmpty(), "a size mismatch is a symptom, not a reason to delete the bytes")
            assertNull(cachedRow("s1"), "nothing may be committed from a short body")
        }

    @Test
    fun truncatedBodyResumesFromTheBytesThatActuallyLanded() =
        runBlocking {
            // DL-1, end to end: the retry must ask from the writer's byte count, not from a stale
            // local. Before the Breakpoint, `partB` was never written back and every retry began
            // again at byte 0.
            plan.replies =
                listOf(
                    Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "12000", "Content-Type" to "audio/mp4"), mp4Body(12_000), stopAfterBytes = 6_000),
                    Scripted(HttpStatusCode.PartialContent, mapOf("Content-Length" to "6000", "Content-Range" to "bytes 6000-11999/12000", "Content-Type" to "audio/mp4"), ByteArray(6_000)),
                )
            plan.repeatLast = false
            val st = runJob()
            assertTrue(st is JobState.Done, "resume must complete the file, got $st")
            assertEquals("bytes=6000-", origin.rangeOf(1))
            assertEquals(12_000L, assertNotNull(cachedRow("s1")).bytes)
            assertTrue(parts().isEmpty(), "a committed part is renamed, not left behind")
        }

    @Test
    fun truncatedBodyWithNoDeclaredLengthIsNotCommitted() =
        runBlocking {
            // The other half of DL-2: with no declared length there is no way to *know* it is
            // short, so the only honest outcome is that the two-sided band rejects it.
            insertSong("saavn", "s2", "enc-s2", durationS = 1L)
            rebuildEngine(cfg.copy(dlRetries = 0))
            plan.replies = listOf(Scripted(HttpStatusCode.OK, mapOf("Content-Type" to "audio/mp4"), mp4Body(6_000)))
            plan.repeatLast = false
            val st = runJob("s2")
            assertTrue(st is JobState.Failed, "a chunked body far under the estimate must not be committed, got $st")
            assertNull(cachedRow("s2"))
        }

    @Test
    fun coldResumeSendsRangeAndIfRangeFromThePersistedSidecar() =
        runBlocking {
            // DL-3: a part left by a previous process is the *normal* resume path, and before the
            // sidecar there was nothing to send but a byte offset.
            seedResumable("s1", mp4Body(64), total = 12_000, etag = "\"v1\"")
            plan.replies =
                listOf(
                    Scripted(
                        HttpStatusCode.PartialContent,
                        mapOf("Content-Length" to "11936", "Content-Range" to "bytes 64-11999/12000", "ETag" to "\"v1\"", "Content-Type" to "audio/mp4"),
                        ByteArray(11_936),
                    ),
                )
            plan.repeatLast = false
            val st = runJob()
            assertTrue(st is JobState.Done, "a cold resume must complete, got $st")
            assertEquals("bytes=64-", origin.rangeOf(0))
            assertEquals("\"v1\"", origin.ifRangeOf(0), "the persisted validator is the If-Range source")
            assertEquals(12_000L, assertNotNull(cachedRow("s1")).bytes)
        }

    @Test
    fun originAnsweringRangeWithTwoHundredRestartsWithoutAWastedRequest() =
        runBlocking {
            // Extremely common from CDNs. The body in hand is the whole object, so the stale 64
            // bytes are dropped and this body is used: the old path threw it away and re-requested.
            seedResumable("s1", mp4Body(64), total = 12_000, etag = "\"v1\"")
            plan.replies = listOf(Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "1000", "ETag" to "\"v2\"", "Content-Type" to "audio/mp4"), mp4Body(1_000)))
            plan.repeatLast = false
            val st = runJob()
            assertTrue(st is JobState.Done, "a 200 to a Range is a usable whole object, got $st")
            assertEquals(1, origin.count(), "no wasted round trip")
            assertEquals(1_000L, assertNotNull(cachedRow("s1")).bytes, "the stale 64 bytes must be gone, not prepended")
        }

    @Test
    fun changedEtagMidResumeRefetchesInsteadOfSplicing() =
        runBlocking {
            // The audit's worked example: old file 3.0 MB, part 2.0 MB, the server now serves a
            // different rendition of 3.1 MB. The old parser discarded the Content-Range start, so
            // the 206 looked fine, the size check passed, the ftyp sniff read the intact old head
            // and a corrupt file was committed and auto-pinned.
            seedResumable("s1", mp4Body(2_000_000), total = 3_000_000, etag = "\"v1\"")
            plan.replies =
                listOf(
                    Scripted(
                        HttpStatusCode.PartialContent,
                        mapOf("Content-Length" to "1100000", "Content-Range" to "bytes 0-1099999/3100000", "ETag" to "\"v2\"", "Content-Type" to "audio/mp4"),
                        mp4Body(1_100_000),
                    ),
                    Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "3100000", "ETag" to "\"v2\"", "Content-Type" to "audio/mp4"), mp4Body(3_100_000)),
                )
            plan.repeatLast = false
            val st = runJob()
            assertTrue(st is JobState.Done, "got $st")
            assertEquals(2, origin.count(), "the spliced range must be discarded and the object re-fetched")
            assertNull(origin.rangeOf(1), "the re-fetch must not ask for a range we no longer trust")
            assertEquals(3_100_000L, assertNotNull(cachedRow("s1")).bytes, "the committed file is the rendition, not a splice")
        }

    @Test
    fun notFoundFailsImmediately() =
        runBlocking {
            mockStatus = HttpStatusCode.NotFound
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.NOT_FOUND)
            assertEquals(1, origin.count())
        }

    @Test
    fun forbiddenRetriesResolveThenFailsForbiddenRegion() =
        runBlocking {
            mockStatus = HttpStatusCode.Forbidden
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.FORBIDDEN_REGION, "got $st")
            assertEquals(cfg.resolveCapPerJob, provider.resolveCalls)
        }

    // ---- engine: the failure taxonomy ----------------------------------------------------------

    @Test
    fun networkErrorMidTransferIsNotReportedAsStorage() =
        runBlocking {
            // A TCP reset at 90% used to land in the same catch-all as a disk failure and was
            // reported as "Not enough space. Free up storage or clear cache." with zero retries.
            plan.replies =
                listOf(
                    Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "12000", "Content-Type" to "audio/mp4"), mp4Body(12_000), stopAfterBytes = 10_800, throwAfterBytes = 10_800),
                    Scripted(HttpStatusCode.PartialContent, mapOf("Content-Length" to "1200", "Content-Range" to "bytes 10800-11999/12000", "Content-Type" to "audio/mp4"), ByteArray(1_200)),
                )
            plan.repeatLast = false
            val st = runJob()
            assertTrue(st is JobState.Done, "a dropped connection is resumable, got $st")
            assertEquals("bytes=10800-", origin.rangeOf(1), "resume from the bytes that landed, not from the declared length")
            assertEquals(12_000L, assertNotNull(cachedRow("s1")).bytes)
        }

    @Test
    fun exhaustedRetriesOnFiveHundredFailWithNetwork() =
        runBlocking {
            mockStatus = HttpStatusCode.InternalServerError
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.NETWORK, "got $st")
            assertEquals(cfg.dlRetries + 1, origin.count(), "one attempt plus dlRetries, and no more")
        }

    @Test
    fun diskFullIsReportedAsStorage() =
        runBlocking {
            // ForwardingFileSystem fails only the open-for-write, which is what ENOSPC looks like.
            val full =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun openReadWrite(
                        path: Path,
                        mustCreate: Boolean,
                        mustExist: Boolean,
                    ): FileHandle = throw IOException("ENOSPC: no space left on device")
                }
            rebuildEngine(cfg, full)
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.STORAGE, "got $st")
            assertEquals(1, origin.count(), "a full disk is not retried")
        }

    @Test
    fun anUnexpectedThrowStillYieldsATerminalState() =
        runBlocking {
            // DL-4: with no catch(Throwable) the coroutine died, `states[key]` kept a non-terminal
            // Downloading, and the Orchestrator's withTimeoutOrNull(120s) sat there before reporting
            // NETWORK_TIMEOUT. The reachable throws are a provider, a SQLDelight call, fsRename's
            // `check(rc == 0)` on iOS, `fs.list`, and `enforcePartCap` deleting the executing part.
            rebuildEngine(cfg.copy(dlRetries = 0))
            provider.throwOnResolve = 1
            val st = runJob()
            assertTrue(st is JobState.Failed, "a throw inside the job must not leave a non-terminal state, got $st")
        }

    @Test
    fun aMalformedStreamUrlIsTerminalNotACrash() =
        runBlocking {
            provider.urlOverride = "http://[::1"
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000")
            val st = runJob()
            assertTrue(st is JobState.Failed, "an unparseable signed URL must settle, got $st")
        }

    @Test
    fun rateLimitedUserNowFailsFast() =
        runBlocking {
            plan.replies = listOf(Scripted(HttpStatusCode.TooManyRequests, mapOf("Retry-After" to "1")))
            plan.repeatLast = false
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.RATE_LIMITED, "got $st")
            assertEquals(1, origin.count(), "a USER_NOW must not sit in a rate-limit backoff")
        }

    @Test
    fun rateLimitedBackgroundRequeuesAndCarriesItsAttemptBudget() =
        runBlocking {
            insertSong("saavn", "s3", "enc-s3")
            plan.replies =
                listOf(
                    Scripted(HttpStatusCode.TooManyRequests, mapOf("Retry-After" to "0")),
                    Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4"), mp4Body(1_000)),
                )
            plan.repeatLast = false
            val key = SongKey("saavn", "s3")
            engine.start()
            val res = engine.enqueue(DownloadJob(key, Priority.PREFETCH_NEXT, 128, 0L))
            assertTrue(res is EnqueueResult.Queued, "got $res")
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { s -> s[key] is JobState.Done || s[key] is JobState.Failed } }
            assertTrue(engine.states.value[key] is JobState.Done, "a deferred background job must still finish: ${engine.states.value[key]}")
            assertEquals(2, origin.count())
        }

    @Test
    fun rateLimitWithoutRetryAfterIsBoundedByDlRetries() =
        runBlocking {
            // `requeueLater` used to reset the budget to zero, so a 429 with no Retry-After was an
            // infinite five-second loop with no cap.
            insertSong("saavn", "s4", "enc-s4")
            rebuildEngine(cfg.copy(dlRetries = 1))
            plan.replies = listOf(Scripted(HttpStatusCode.TooManyRequests))
            plan.repeatLast = false
            val key = SongKey("saavn", "s4")
            engine.start()
            engine.enqueue(DownloadJob(key, Priority.PREFETCH_NEXT, 128, 0L))
            withTimeout(RETRY_TIMEOUT_MS) { engine.states.first { s -> s[key] is JobState.Failed } }
            assertEquals(2, origin.count(), "a 429 with no Retry-After must not loop forever")
        }

    @Test
    fun retryAfterInHttpDateFormIsHonoured() =
        runBlocking {
            insertSong("saavn", "s5", "enc-s5")
            plan.replies =
                listOf(
                    Scripted(HttpStatusCode.TooManyRequests, mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")),
                    Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4"), mp4Body(1_000)),
                )
            plan.repeatLast = false
            val key = SongKey("saavn", "s5")
            engine.start()
            engine.enqueue(DownloadJob(key, Priority.PREFETCH_NEXT, 128, 0L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { s -> s[key] is JobState.Done || s[key] is JobState.Failed } }
            // The date is in 2015, so the computed wait is 0 and the job goes straight back on the
            // queue. What this pins down is that the HTTP-date form parsed instead of being ignored.
            assertTrue(engine.states.value[key] is JobState.Done, "got ${engine.states.value[key]}")
            assertEquals(2, origin.count())
        }

    @Test
    fun aLongRetryAfterDoesNotPinTheEngine() =
        runBlocking {
            // The old breaker wait was `delay(min(remaining, 5_000)); continue` with no attempt
            // accounting and no deadline, while holding the engine's only slot: a Retry-After: 3600
            // pinned the process and every other track waited behind it.
            insertSong("saavn", "s6", "enc-s6")
            insertSong("saavn", "s7", "enc-s7")
            provider.hostFor = { if (it == "enc-s6") "limited" else "healthy" }
            plan.replies =
                listOf(
                    Scripted(HttpStatusCode.TooManyRequests, mapOf("Retry-After" to "3600")),
                    Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4"), mp4Body(1_000)),
                )
            plan.repeatLast = false
            val limited = SongKey("saavn", "s6")
            val healthy = SongKey("saavn", "s7")
            engine.start()
            engine.enqueue(DownloadJob(limited, Priority.PREFETCH_NEXT, 128, 0L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { origin.count() == 1 } }
            engine.enqueue(DownloadJob(healthy, Priority.USER_NOW, 128, 0L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { s -> s[healthy] is JobState.Done || s[healthy] is JobState.Failed } }
            assertTrue(engine.states.value[healthy] is JobState.Done, "a rate-limited host must not block a healthy one: ${engine.states.value[healthy]}")
        }

    // ---- engine: container verification --------------------------------------------------------

    @Test
    fun htmlErrorPageServedAsAudioIsNotCommitted() =
        runBlocking {
            // DL-7: the code's own comment said body magic is the truth and the code inverted it — a
            // 200 with `Content-Type: audio/mpeg` and an HTML body took the mp3 path, skipped the
            // ftyp gate and was committed as a playable file.
            mockBody = htmlBody(2_000)
            mockHeaders = mapOf("Content-Length" to "2000", "Content-Type" to "audio/mpeg")
            val st = runJob()
            assertTrue(st is JobState.Failed, "an HTML body must never be committed, got $st")
            assertNull(cachedRow("s1"))
            assertFalse(exists("saavn_s1_128.mp3"))
        }

    @Test
    fun mp3WithId3CommitsWithoutFtyp() =
        runBlocking {
            mockBody = mp3Body(800)
            mockHeaders = mapOf("Content-Length" to "800", "Content-Type" to "audio/mpeg")
            val st = runJob()
            assertTrue(st is JobState.Done, "mp3 without ftyp must pass, got $st")
            assertEquals("mp3", assertNotNull(cachedRow("s1")).ext)
        }

    @Test
    fun bareMpegFrameSyncIsRecognisedAsMp3() =
        runBlocking {
            mockBody = mp3Body(800, id3 = false)
            mockHeaders = mapOf("Content-Length" to "800", "Content-Type" to "audio/mpeg")
            val st = runJob()
            assertTrue(st is JobState.Done, "a frame-sync-only mp3 must pass, got $st")
            assertEquals("mp3", assertNotNull(cachedRow("s1")).ext)
        }

    @Test
    fun aFileShorterThanTheMagicIsNotPlayable() =
        runBlocking {
            // The engine used to check 4 bytes and playback 12, so a 4-11 byte file passed
            // verification and was then rejected at playback.
            mockBody = mp4Body(8)
            mockHeaders = mapOf("Content-Length" to "8", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Failed, "got $st")
            assertNull(cachedRow("s1"))
        }

    @Test
    fun badContainerFailsCorruptContainer() =
        runBlocking {
            mockBody = ByteArray(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.CORRUPT_CONTAINER, "got $st")
        }

    @Test
    fun missingContentTypeSniffsM4aFromMagic() =
        runBlocking {
            provider.streamType = "weird"
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000")
            val st = runJob()
            assertTrue(st is JobState.Done, "ftyp body must survive unusable content-type, got $st")
            assertEquals("m4a", assertNotNull(cachedRow("s1")).ext)
        }

    @Test
    fun missingContentTypeSniffsMp3FromId3() =
        runBlocking {
            provider.streamType = "weird"
            mockBody = mp3Body(800)
            mockHeaders = mapOf("Content-Length" to "800")
            val st = runJob()
            assertTrue(st is JobState.Done, "ID3 body must survive unusable content-type, got $st")
            assertEquals("mp3", assertNotNull(cachedRow("s1")).ext)
        }

    // ---- engine: the rest of the pipeline ------------------------------------------------------

    @Test
    fun successConsumesIntentRow() =
        runBlocking {
            db.dylanQueries.upsertIntent("saavn", "s1", "USER_NOW", 128L, 1L)
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            runJob()
            assertFalse(db.dylanQueries.allIntents().executeAsList().any { it.song_id == "s1" })
        }

    @Test
    fun commitPreservesEngagementStatsOnUpgrade() =
        runBlocking {
            val key = SongKey("saavn", "s1")
            FileSystem.SYSTEM.write(audioDir / "saavn_s1_128.m4a") { write(ByteArray(999)) }
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", 999L, 5L, 777L, 4L, 1L, 42L)
            prefNow = Quality.BITRATE_320
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val st = runJob()
            assertTrue(st is JobState.Done)
            val row = assertNotNull(cachedRow("s1"))
            assertEquals(320L, row.bitrate)
            assertEquals(777L, row.last_used_ms)
            assertEquals(4L, row.play_count)
            assertEquals(42L, row.pinned_at_ms)
            assertEquals(1L, row.pinned)
        }

    @Test
    fun qualityUpgradeHonoursTheRequestedBitrate() =
        runBlocking {
            // M-5: the job's bitrate used to be read once for an intent row and then recomputed
            // from the user's preference, so the idle upgrade scanner asked for 320, got 128, and
            // Step.DEDUPE short-circuited to Done — a no-op for every user who had not chosen 320.
            val key = SongKey("saavn", "s1")
            FileSystem.SYSTEM.write(audioDir / "saavn_s1_128.m4a") { write(ByteArray(1_000)) }
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", 1_000L, 1L, null, 0, 0, null)
            prefNow = Quality.BITRATE_128
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            engine.start()
            engine.enqueue(DownloadJob(key, Priority.QUALITY_UPGRADE, 320, 0L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { s -> s[key] is JobState.Done || s[key] is JobState.Failed } }
            assertEquals(1, provider.resolveCalls, "an explicit upgrade must reach the network")
            assertEquals(320L, assertNotNull(cachedRow("s1")).bitrate, "the requested rendition is the one fetched")
        }

    @Test
    fun cachedSufficientBitrateSkipsNetworkEntirely() =
        runBlocking {
            val key = SongKey("saavn", "s1")
            FileSystem.SYSTEM.write(audioDir / "saavn_s1_128.m4a") { write(ByteArray(1_000)) }
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", 1_000L, 1L, null, 0, 0, null)
            engine.start()
            engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { s -> s[key] is JobState.Done } }
            assertEquals(0, provider.resolveCalls, "sufficiency dedupe must serve from cache without resolving")
        }

    @Test
    fun meteredNeverSpendsCellularOnUpgrade() =
        runBlocking {
            val key = SongKey("saavn", "s1")
            netNow = dylan.util.NetClass.METERED
            prefNow = Quality.BITRATE_320
            FileSystem.SYSTEM.write(audioDir / "saavn_s1_128.m4a") { write(ByteArray(1_000)) }
            db.dylanQueries.insertCached(key.provider, key.songId, 128L, "m4a", 1_000L, 1L, null, 0, 0, null)
            engine.start()
            engine.enqueue(DownloadJob(key, Priority.QUALITY_UPGRADE, 320, 0L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { s -> s[key] is JobState.Done } }
            assertEquals(0, provider.resolveCalls, "metered upgrade must not touch the network")
            assertEquals(128L, assertNotNull(cachedRow("s1")).bitrate)
        }

    @Test
    fun duplicateEnqueueWhileExecutingRunsOnce() =
        runBlocking {
            val k1 = SongKey("saavn", "s1")
            val k2 = SongKey("saavn", "s2")
            insertSong("saavn", "s2", "enc-s2")
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            provider.gate = CompletableDeferred()
            engine.start()
            engine.enqueue(DownloadJob(k1, Priority.USER_NOW, 128, 1L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { it[k1] is JobState.Resolving || it[k1] is JobState.Downloading } }
            engine.enqueue(DownloadJob(k1, Priority.USER_NOW, 128, 2L))
            assertTrue(assertNotNull(provider.gate).complete(Unit), "the gate must be open for the next resolve")
            engine.enqueue(DownloadJob(k2, Priority.USER_NOW, 128, 3L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { it[k2] is JobState.Done || it[k2] is JobState.Failed } }
            assertEquals(1, provider.resolvedKeys.count { it == "enc-ref" }, "a duplicate same-key job must be dropped, not re-run")
            assertTrue(engine.states.value[k1] is JobState.Done, "a superseded enqueue must not disturb the live state")
        }

    @Test
    fun awaitAttemptSettlesTheAttemptItWasHanded() =
        runBlocking {
            mockBody = mp4Body(1_000)
            mockHeaders = mapOf("Content-Length" to "1000", "Content-Type" to "audio/mp4")
            val key = SongKey("saavn", "s1")
            engine.start()
            val res = engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
            val id = assertNotNull((res as? EnqueueResult.Queued)?.id, "enqueue must hand back an attempt id: $res")
            assertEquals(id, engine.attemptOf(key))
            val settled = engine.awaitAttempt(id, JOB_TIMEOUT_MS)
            assertTrue(settled is JobState.Done, "got $settled")
            assertTrue(engine.states.value[key] is JobState.Done)
        }

    @Test
    fun cancelAttemptSettlesThatAttemptAndKeepsThePart() =
        runBlocking {
            // What W3-D needs on a generation change: abandon *this* attempt and keep the bytes.
            provider.gate = CompletableDeferred()
            engine.start()
            val key = SongKey("saavn", "s1")
            val res = engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
            val id = assertNotNull((res as? EnqueueResult.Queued)?.id)
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { it[key] is JobState.Resolving } }
            seedPart("s1", mp4Body(64))
            engine.cancelAttempt(id, keepPart = true)
            val settled = engine.awaitAttempt(id, JOB_TIMEOUT_MS)
            assertTrue(settled is JobState.Cancelled, "the abandoned attempt must settle, got $settled")
            assertTrue(parts().isNotEmpty(), "keepPart=true must not delete the bytes")
            assertTrue(assertNotNull(provider.gate).complete(Unit))
        }

    @Test
    fun upgradeSourceKeysProtectTheSourceOfAnInFlightUpgrade() =
        runBlocking {
            // `upgradeSourceKeys` had six reads and no writes. With it permanently empty the
            // *source* file of an in-flight 128→320 was LRU-evictable, so a failed upgrade left the
            // user with no copy of a track they already had offline.
            provider.gate = CompletableDeferred()
            engine.start()
            val key = SongKey("saavn", "s1")
            engine.enqueue(DownloadJob(key, Priority.QUALITY_UPGRADE, 320, 0L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { it[key] is JobState.Resolving } }
            assertTrue(key in cacheManager.upgradeSourceKeys.value, "the 128 file is the only copy while a 320 is in flight: ${cacheManager.upgradeSourceKeys.value}")
            assertTrue(assertNotNull(provider.gate).complete(Unit))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { it[key] is JobState.Done || it[key] is JobState.Failed } }
            assertFalse(key in cacheManager.upgradeSourceKeys.value, "the guard must be released on a terminal state")
        }

    @Test
    fun theTransferGateBoundsConcurrentCopiesNotConcurrentJobs() =
        runBlocking {
            // maxConcurrentParts=1 with two workers: both jobs may be alive, but only one may be
            // copying bytes. The limit belongs on the transfer step, not on the whole job.
            insertSong("saavn", "s2", "enc-s2")
            insertSong("saavn", "s3", "enc-s3")
            rebuildEngine(cfg.copy(maxConcurrentParts = 1))
            plan.replies = List(3) { Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "4000", "Content-Type" to "audio/mp4"), mp4Body(4_000), chunkBytes = 512, chunkDelayMs = 20) }
            plan.repeatLast = false
            engine.start()
            engine.enqueue(DownloadJob(SongKey("saavn", "s1"), Priority.USER_NOW, 128, 0L))
            engine.enqueue(DownloadJob(SongKey("saavn", "s2"), Priority.USER_NOW, 128, 1L))
            engine.enqueue(DownloadJob(SongKey("saavn", "s3"), Priority.USER_NOW, 128, 2L))
            withTimeout(JOB_TIMEOUT_MS) { engine.states.first { s -> listOf("s1", "s2", "s3").all { s[SongKey("saavn", it)] is JobState.Done } } }
            assertEquals(1, origin.peakBodies.get(), "only one body may be copied at a time when the budget is 1")
            assertEquals(3, origin.count(), "all three jobs still run")
        }

    // ---- watchdog ------------------------------------------------------------------------------

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
        assertTrue(stallTripped(sinceChunkMs = 500, totalElapsedMs = 500, wallCapMs = 2_550, stallTimeoutMs = 400))
        assertFalse(stallTripped(sinceChunkMs = 150, totalElapsedMs = 1_800, wallCapMs = 2_550, stallTimeoutMs = 1_000))
        assertTrue(stallTripped(sinceChunkMs = 100, totalElapsedMs = 2_600, wallCapMs = 2_550, stallTimeoutMs = 1_000))
        assertFalse(stallTripped(sinceChunkMs = 400, totalElapsedMs = 2_550, wallCapMs = 2_550, stallTimeoutMs = 400))
    }

    @Test
    fun slowFlowingStreamSurvivesWallCap() =
        runBlocking {
            // 1 s song ⇒ wall cap 2 550 ms; chunks flow every 150 ms (< stall timeout); the
            // transfer takes ~1.8 s — survives the new cap, died under the old /20 formula.
            insertSong("saavn", "slow", "enc-slow", durationS = 1L)
            provider.statuses = ArrayDeque(listOf(200, 200, 200, 200))
            plan.replies = listOf(Scripted(HttpStatusCode.OK, mapOf("Content-Length" to "1200", "Content-Type" to "audio/mp4"), mp4Body(1_200), chunkBytes = 100, chunkDelayMs = 150))
            rebuildEngine(AppConfig(stallTimeoutMs = 1_000, stallWatchdogTickMs = 100, stallWallFloorMs = 300, dlRetries = 0))
            val st = runJob("slow")
            assertTrue(st is JobState.Done, "flowing transfer must not be wall-killed, got $st")
        }

    // ---- parts ---------------------------------------------------------------------------------

    @Test
    fun partCapSacrificesPrefetchPartsBeforeNewestUserPart() =
        runBlocking {
            seed("p1", "PREFETCH_NEXT", 40_000)
            seed("p2", "PREFETCH_NEXT", 30_000)
            seed("p3", "PREFETCH_NEXT", 20_000)
            seed("u1", "USER_NOW", 10_000)
            engine.enforcePartCap()
            assertFalse(exists("saavn_p1_128.part"), "oldest PREFETCH part is the first victim")
            assertTrue(exists("saavn_p2_128.part"))
            assertTrue(exists("saavn_p3_128.part"))
            assertTrue(exists("saavn_u1_128.part"), "just-preempted USER_NOW part must survive the cap pass")
        }

    @Test
    fun partCapCountsEveryPartIncludingTheOneBeingWritten() =
        runBlocking {
            // DL-8: the old arithmetic excluded the executing part from the candidate pool and then
            // compared the *rest* against the cap, so the directory held maxConcurrentParts + 1
            // exactly when a download was running — the case nobody could notice.
            seed("q1", "PREFETCH_NEXT", 40_000)
            seed("q2", "PREFETCH_NEXT", 30_000)
            seed("q3", "PREFETCH_NEXT", 20_000)
            seed("q4", "PREFETCH_NEXT", 10_000)
            engine.enforcePartCap()
            assertEquals(cfg.maxConcurrentParts, parts().size, "the cap must be a hard ceiling")
        }

    @Test
    fun partCapIsANoOpWhileWithinBudget() =
        runBlocking {
            seed("r1", "PREFETCH_NEXT", 40_000)
            seed("r2", "PREFETCH_NEXT", 30_000)
            engine.enforcePartCap()
            engine.enforcePartCap()
            assertEquals(2, parts().size)
        }

    private fun seed(
        id: String,
        reason: String,
        ageMs: Long,
    ) {
        FileSystem.SYSTEM.write(part(id)) { write(ByteArray(10)) }
        java.io.File(part(id).toString()).setLastModified(System.currentTimeMillis() - ageMs)
        db.dylanQueries.upsertIntent("saavn", id, reason, 128L, 0L)
    }

    @Test
    fun nonResumableFailureDeletesItsPart() =
        runBlocking {
            val p = seedPart("s1", mp4Body(64))
            // updateSongSource rather than insertSong: insertSong is INSERT OR IGNORE.
            db.dylanQueries.updateSongSource(null, null, 0L, "saavn", "s1")
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.NO_SOURCE, "got $st")
            assertFalse(FileSystem.SYSTEM.exists(p), "NO_SOURCE must not leak its .part")
        }

    @Test
    fun resumableFailureKeepsItsPartForReconciler() =
        runBlocking {
            val p = seedPart("s1", mp4Body(64))
            mockStatus = HttpStatusCode.InternalServerError
            val st = runJob()
            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.NETWORK, "got $st")
            assertTrue(FileSystem.SYSTEM.exists(p), "NETWORK failure keeps the .part for resume")
        }

    // ---- performance ---------------------------------------------------------------------------

    /**
     * The progress throttle, measured the way `LogSinkPerfHarness` measures the log path:
     * `measureNanoTime` over a fixed workload, printed, and asserted against a loose bound (a
     * shared CI box is too noisy for a tight one).
     *
     * The old predicate was `if (n - last < 250 && loaded < denom) return`, and `denom` was an
     * *estimate* a resumed transfer can pass mid-stream — after which every 64 KB chunk emitted,
     * and each emission is a full Map copy that recomposes five Compose screens. The workload
     * below reproduces exactly that: 16 MB with a 1 MB denominator.
     */
    @Test
    fun progressThrottleIsBoundedByTimeNotByChunkCount() {
        val chunk = 64L * 1024L
        val total = 16L * 1024L * 1024L
        val denom = 1L * 1024L * 1024L
        val chunks = total / chunk
        var emissions = 0
        val ns =
            measureNanoTime {
                val clock = MutableClock()
                val throttle = ProgressThrottle(clock, 250L)
                var loaded = 0L
                while (loaded < total) {
                    loaded += chunk
                    clock.advanceMs(1)
                    throttle.take(loaded, denom)?.let { emissions++ }
                }
            }
        val oldPredicateEmissions = chunks - (denom / chunk) + 1
        println(
            "[bench] %-46s %6d emissions / %d chunks (%5.1f ns/chunk); old predicate would emit %d".format(
                "progress throttle, 16 MB with a 1 MB denominator",
                emissions,
                chunks,
                ns / chunks,
                oldPredicateEmissions,
            ),
        )
        assertTrue(emissions <= 8, "16 MB past its denominator must not emit per chunk: $emissions emissions")
    }

    /**
     * Per-enqueue cost. `enforcePartCap` used to run `fs.list` + a `metadataOrNull` per part + a
     * full `allIntents()` scan on *every* enqueue, on the io lane that also serves image caching and
     * log writes. The claim is that the cost no longer scales with the audio directory, so this
     * measures an enqueue with an empty directory and with 60 parts, and asserts the ratio.
     */
    @Test
    fun enqueueCostDoesNotScaleWithThePartCount() {
        var blackhole = 0L
        fun measureEnqueues(rounds: Int): Long =
            measureNanoTime {
                repeat(rounds) { pass ->
                    for (i in 1..ENQUEUE_ROUNDS) {
                        engine.enqueue(DownloadJob(SongKey("saavn", "e$pass$i"), Priority.PREFETCH_NEXT, 128, 0L))
                        blackhole += engine.states.value.size
                    }
                }
            }
        val quiet = measureEnqueues(2)
        repeat(60) { i -> FileSystem.SYSTEM.write(audioDir / "crowd_$i.part") { write(ByteArray(8)) } }
        val crowded = measureEnqueues(2)
        println(
            "[bench] %-46s %8.2f ms empty / %8.2f ms with 60 parts (%6.0f ns vs %6.0f ns per enqueue)".format(
                "DownloadEngine.enqueue, %d enqueues each".format(ENQUEUE_ROUNDS * 2),
                quiet / 1e6,
                crowded / 1e6,
                quiet / (ENQUEUE_ROUNDS * 2.0),
                crowded / (ENQUEUE_ROUNDS * 2.0),
            ),
        )
        assertTrue(blackhole > 0)
        assertTrue(crowded < quiet * 3 + 5_000_000L, "enqueue must not walk the directory: ${quiet}ns vs ${crowded}ns")
    }

    /**
     * The queue is bounded at construction and says so, instead of growing until
     * `enforcePartCap` deletes `.part` files out from under jobs that are merely *queued*.
     */
    @Test
    fun theQueueIsBoundedAndSaysSo() =
        runBlocking {
            var queued = 0
            var dropped = 0
            repeat(5_000) { i ->
                when (engine.enqueue(DownloadJob(SongKey("saavn", "q$i"), Priority.PREFETCH_NEXT, 128, 0L))) {
                    is EnqueueResult.Queued -> queued++
                    EnqueueResult.Dropped -> dropped++
                    is EnqueueResult.Preempted, EnqueueResult.SupersededByHigherPriority -> Unit
                }
            }
            assertTrue(queued <= 256, "the queue must be bounded, accepted $queued")
            assertTrue(dropped > 0, "overflow must be reported as Dropped, accepted $queued")
        }

    private companion object {
        const val JOB_TIMEOUT_MS = 20_000L
        const val RETRY_TIMEOUT_MS = 60_000L
        const val ENQUEUE_ROUNDS = 2_000
    }
}
