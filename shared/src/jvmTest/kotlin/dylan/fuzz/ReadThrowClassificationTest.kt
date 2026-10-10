package dylan.fuzz

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.JobState
import dylan.download.Priority
import dylan.model.ErrorCode
import dylan.model.MiniEntity
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.support.MutableClock
import dylan.support.TestLanes
import dylan.util.NetClass
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedReadChannelException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.write
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read that **throws**, driven through the real `DownloadEngine`, with no race in it.
 *
 * `Transfer.readChunk` used to be `awaitContent()` then `readAvailable(...)`, and ktor's
 * `readAvailable` (3.5.2, `ByteReadChannelOperations.kt`) is
 *
 * ```
 * if (isClosedForRead) return -1
 * if (readBuffer.exhausted()) awaitContent()
 * if (isClosedForRead) return -1
 * return readBuffer.readAvailable(buffer, offset, length)
 * ```
 *
 * `readBuffer`'s getter (`ByteChannel.kt:67`) opens with
 * `_closedCause.value?.throwOrNil(::ClosedReadChannelException)`. So a `cancel(cause)` landing
 * between the closed check and the buffer getter makes a read that had just been promised bytes
 * **throw** — and `pump` caught only `StallSignal`, so it escaped `pump` → `drain` → `copyBody`
 * and landed in `run`'s generic `catch (expected: Exception)`, which files it as a failed
 * *request* and gives up the byte offset the next `Range:` resumes from.
 *
 * That window is a race, so neither `ReadAfterAwaitContentContractTest` nor a real socket can force
 * it. [ReadThatThrows] can: it is the window modelled, not approximated. Everything asserted here
 * is therefore decided by construction, and none of it can pass on a scheduling coincidence.
 */
class ReadThrowClassificationTest {
    private lateinit var root: Path
    private lateinit var db: Dylan
    private lateinit var paths: Paths
    private lateinit var scope: CoroutineScope
    private lateinit var engine: DownloadEngine
    private lateinit var bulk: HttpClient
    private lateinit var log: LogBuffer
    private lateinit var reader: ReadThatThrows

    private val clock = MutableClock()
    private val requests = CopyOnWriteArrayList<String>()

    private val provider =
        object : MusicProvider {
            override suspend fun search(
                query: String,
                page: Int,
            ) = error("unused")

            override suspend fun album(id: String) = null

            override suspend fun artist(id: String) = null

            override suspend fun home() = dylan.model.HomeFeed(emptyList())

            override suspend fun topSearches() = emptyList<MiniEntity>()

            override suspend fun resolveStream(
                resolveRef: String,
                q: Quality,
            ) = SignedStream("http://mock/audio", "mp4")
        }

    @BeforeTest
    fun setup() {
        root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-read-throw-${System.nanoTime()}").toPath()
        FileSystem.SYSTEM.createDirectories(root)
        log = LogBuffer(minLevel = LogLevel.DEBUG)
        db = Dylan(DriverFactory("$root/dylan.db", log).createDriver())
        paths = Paths(root / "audio", FileSystem.SYSTEM)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        reader = ReadThatThrows(LANDED_BYTES)
        requests.clear()
        buildEngine(AppConfig(clock = clock))
    }

    @AfterTest
    fun teardown() {
        runCatching { engine.stop() }
        runCatching { scope.cancel() }
        runCatching { bulk.close() }
        runCatching { FileSystem.SYSTEM.deleteRecursively(root) }
    }

    private fun buildEngine(over: AppConfig) {
        val lanes = TestLanes.production()
        val cm =
            CacheManager(db, FileSystem.SYSTEM, paths, MutableStateFlow(emptySet()), over, lanes.disp, log)
        bulk =
            HttpClient(
                MockEngine { request ->
                    requests += request.headers[HttpHeaders.Range] ?: "none"
                    // The first answer is the race, and every answer after it is a healthy body:
                    // the point under test is what the copy loop *does* with the throw, so the
                    // follow-up has to be something a resume can actually complete.
                    val throwing = requests.size == 1
                    respond(
                        content = if (throwing) reader.reset() else ByteReadChannel(mp4Body(BODY_BYTES)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, "$BODY_BYTES"),
                    )
                },
            )
        engine =
            DownloadEngine(
                db = db,
                fs = FileSystem.SYSTEM,
                paths = paths,
                cfg = over,
                disp = lanes.disp,
                provider = provider,
                bulk = bulk,
                breakers = Breakers(),
                cacheManager = cm,
                netClass = { NetClass.UNMETERED },
                qualityPref = { Quality.BITRATE_128 },
                log = log,
            )
    }

    private fun admit(id: String) {
        db.dylanQueries.insertSong("saavn", id, id, "", null, null, "", "", 100L, 1L, "ref-$id", null, clock.nowMs())
    }

    private suspend fun runJob(id: String): JobState {
        val key = SongKey("saavn", id)
        engine.start()
        engine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, clock.nowMs()))
        withTimeout(JOB_CEILING_MS) {
            engine.states.first { it[key] is JobState.Done || it[key] is JobState.Failed }
        }
        return engine.states.value[key]!!
    }

    /**
     * With no retry budget the read failure is terminal — and **classified**, not crashed.
     *
     * Before the read site classified it, the throw left the copy loop before its verdict, so
     * `drain` never returned a `CopyOutcome` and the one place that could name a byte offset never
     * ran. The offsets and the reason are now produced inside the loop.
     */
    @Test
    fun aReadThatThrowsFailsTheJobRatherThanCrashingIt() =
        runBlocking {
            admit("s1")
            buildEngine(AppConfig(clock = clock, dlRetries = 0))

            val st = runJob("s1")

            assertTrue(
                st is JobState.Failed && st.err.code == ErrorCode.NETWORK,
                "a read that throws is a transport failure classified at the read site, got $st",
            )
            assertEquals(1, requests.size, "a zero budget must not re-issue the request")
            // The classification's own fingerprint. An uncaught throw reaches `run`'s generic
            // `catch (expected: Exception)`, which logs "request failed <message>" with no class and
            // no offset; a read the copy loop classified logs "read failed <song> at <bytes>: <type>:
            // <message>". Only the second can say where the transport gave up, so the offset in the
            // line is the thing that fails if this ever regresses to escaping.
            val lines = log.dump().map { "${it.tag}: ${it.msg}" }
            assertTrue(
                lines.any {
                    it.startsWith("dl: read failed ") &&
                        it.contains("$LANDED_BYTES") &&
                        it.contains("ClosedReadChannelException") &&
                        it.contains("scripted connection reset")
                },
                "the read failure must be reported by the read site with its offset and cause: $lines",
            )
            assertTrue(
                audioFiles().any { it.endsWith(".part") },
                "the part survives the bytes that landed: ${audioFiles()}",
            )
            assertEquals(
                null,
                db.dylanQueries.selectCached("saavn", "s1").executeAsOneOrNull(),
                "an unfinished body commits nothing",
            )
        }

    /**
     * With a budget the retry is the *resumable* one, which is only possible if the copy loop kept
     * its own verdict. `live` is folded after every accepted chunk, so the bytes that landed are
     * already in it — and the retry's `Range:` is that number read back through the loop's own
     * accounting rather than through `run`'s recovery path.
     */
    @Test
    fun aReadThatThrowsRetriesFromTheBytesThatLanded() =
        runBlocking {
            admit("s1")
            buildEngine(AppConfig(clock = clock, dlRetries = 1, dlBackoffBaseMs = FAST_BACKOFF_MS))

            val st = runJob("s1")

            assertTrue(st is JobState.Done, "the resume completes the object, got $st")
            assertEquals(2, requests.size, "the read failure produced exactly one retry")
            assertEquals("bytes=$LANDED_BYTES-", requests[1], "resume from the bytes that landed, not from zero")
            // Same fingerprint as above, and here it is the more interesting half: a throw that
            // escapes the copy loop is retried by `run`'s recovery path, while a throw the copy
            // loop classified is retried by the attempt's own verdict. Both resume — only the
            // second still says which read failed and at which byte.
            val lines = log.dump().map { "${it.tag}: ${it.msg}" }
            assertTrue(
                lines.any { it.startsWith("dl: read failed ") && it.contains("$LANDED_BYTES") },
                "the classified read failure is reported with its offset: $lines",
            )
        }

    private fun audioFiles() = FileSystem.SYSTEM.list(paths.audioDir).map { it.name }
}

/**
 * The instant of the race, modelled rather than waited for.
 *
 * `awaitContent` keeps telling the truth (there are bytes), `isClosedForRead` keeps answering false
 * (from this side the cause has not landed yet), `closedCause` already carries the reset, and
 * `readBuffer`'s getter already raises. A real `ByteChannel` holds exactly those four properties
 * for exactly as long as the writer's `cancel(cause)` takes to land between `readAvailable`'s two
 * statements — which is the whole of the flake this test exists to make impossible.
 */
private class ReadThatThrows(
    landed: Int,
) : ByteReadChannel {
    private val buffer: Source = Buffer().also { it.write(ByteArray(landed) { i -> (i % 251).toByte() }) }
    private val cause = IOException("scripted connection reset")

    override val closedCause: Throwable? get() = cause

    override val isClosedForRead: Boolean get() = false

    @kotlin.OptIn(io.ktor.utils.io.InternalAPI::class)
    override val readBuffer: Source
        get() {
            if (buffer.exhausted()) throw ClosedReadChannelException(cause)
            return buffer
        }

    override suspend fun awaitContent(min: Int): Boolean = true

    /** A fresh channel per response: each attempt gets its own landed bytes and its own reset. */
    fun reset(): ReadThatThrows = ReadThatThrows(LANDED_BYTES)

    override fun cancel(cause: Throwable?) = Unit
}

private const val LANDED_BYTES = 4096
private const val BODY_BYTES = 8192
private const val FAST_BACKOFF_MS = 1L
private const val JOB_CEILING_MS = 30_000L
