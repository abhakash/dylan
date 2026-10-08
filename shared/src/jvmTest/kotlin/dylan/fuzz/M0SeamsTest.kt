package dylan.fuzz

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
import dylan.download.DownloadJob
import dylan.download.JobState
import dylan.download.Priority
import dylan.model.ErrorCode
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.support.MutableClock
import dylan.support.TestLanes
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **M0 — the de-risk gate.** Everything in `docs/testing/fuzzy-e2e-plan.md` after this milestone
 * rests on two claims that were `UNVERIFIED` when it was written:
 *
 * 1. ktor's `MockEngine` accepts a `ByteReadChannel` body, so a hostile origin can fail *mid-stream*
 *    (FI-06/07/08/15 all need it).
 * 2. an okio `FileSystem` decorator can intercept every write path the app uses — including
 *    `PartMeta.write`, which goes through the **final** `FileSystem.write`.
 *
 * If either were false the plan needed a different engine and a different fault mechanism, and that
 * had to be discovered here rather than in M3. Both are proven below **against the real
 * `DownloadEngine`**, not against a mock of it, and each test carries its own control so it cannot
 * pass vacuously.
 *
 * Verified, for the record:
 * * `respond(scope, ByteReadChannel, HttpStatusCode, Headers)` exists in
 *   `ktor-client-mock-jvm-3.5.2.jar` (`javap io.ktor.client.engine.mock.MockUtilsKt`). No
 *   `HttpClientEngine` wrapper was needed.
 * * `FileSystem.write` is `final` and `sink` is `abstract` (`javap okio.FileSystem` on
 *   `okio-jvm-3.18.1.jar`), so overriding `sink` is sufficient — and okio ships
 *   `ForwardingFileSystem`, so the decorator is delegation, not reimplementation.
 * * **`FileHandle.write` is also `final`**, and the audio body is written through it
 *   (`Transfer.kt:608`). So the *only* ENOSPC a decorator can put on the audio path is at the open,
 *   which is what [aFaultAtThePartOpenIsReportedAsStorage] injects. A byte quota on the body is not
 *   reachable, and pretending otherwise would have produced a test that asserted `STORAGE` for a
 *   reason that had nothing to do with the disk.
 * * `dylan.util.fsRename` is `java.nio`-backed on the JVM and is *not* interceptable this way; the
 *   defaulted `rename` seam on `DownloadEngine` is what makes the rename-failure path reachable.
 *
 * Two production facts these fixtures have to respect, both discovered here:
 *
 * * **`cfg.dlRetries` is the resume budget.** `Transfer.retryOrGiveUp` gives up when
 *   `attempts + 1 > dlRetries`, so a store configured with `dlRetries = 0` can *never* resume —
 *   a reset or a truncated body is terminal by construction, not by defect. The two resume
 *   scenarios below therefore configure one retry, and
 *   [aTruncatedBodyWithNoRetryBudgetIsTerminalAndKeepsItsPart] pins the zero-retry half so the
 *   difference is a deliberate variable rather than an accident.
 * * **`PartMeta.write` is called from `PartStore.persist`, which `DownloadEngine.runJob` invokes
 *   from its `finally`** — after COMMIT has renamed the part away. So a sidecar write can only ever
 *   happen on a job that *kept* its part, and it can never affect whether a transfer commits.
 *   FI-23's shape ("the sidecar write fails, the transfer still commits") is therefore
 *   unreachable in the current production code; see
 *   [theSidecarWriteGoesThroughTheDecoratorSoItCanBeFaulted] for what is asserted instead.
 */
class M0SeamsTest {
    private lateinit var root: Path
    private lateinit var driver: app.cash.sqldelight.db.SqlDriver
    private lateinit var db: Dylan
    private lateinit var fs: FaultyFileSystem
    private lateinit var paths: Paths
    private lateinit var scope: CoroutineScope
    private lateinit var origin: OriginScript
    private lateinit var bulk: HttpClient
    private lateinit var engine: DownloadEngine
    private lateinit var reconciler: Reconciler

    private val log = LogBuffer(minLevel = LogLevel.DEBUG)
    private val clock = MutableClock()

    /**
     * Per-test, because it *is* the subject of two of these tests: the resume ladder is
     * `cfg.dlRetries` and the backoff is `cfg.dlBackoffBaseMs`, and both are policy a scenario has
     * to state rather than inherit.
     */
    private var cfg: AppConfig = AppConfig(clock = clock)

    @BeforeTest
    fun setup() {
        root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-m0-${System.nanoTime()}").toPath()
        fs = FaultyFileSystem(FileSystem.SYSTEM)
        fs.createDirectories(root)
        driver = DriverFactory("$root/dylan.db", log).createDriver()
        db = Dylan(driver)
        paths = Paths(root / "audio", fs)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        origin = OriginScript(scope, mp4Body(BODY_BYTES))
        bulk = HttpClient(origin.mock)
        buildEngine(AppConfig(clock = clock))
    }

    private fun buildEngine(over: AppConfig) {
        cfg = over
        val lanes = TestLanes.production()
        val cm = CacheManager(db, fs, paths, MutableStateFlow(emptySet()), cfg, lanes.disp, log)
        engine =
            DownloadEngine(
                db = db,
                fs = fs,
                paths = paths,
                cfg = cfg,
                disp = lanes.disp,
                provider = provider,
                bulk = bulk,
                breakers = Breakers(),
                cacheManager = cm,
                netClass = { dylan.util.NetClass.UNMETERED },
                qualityPref = { Quality.BITRATE_128 },
                log = log,
            )
        reconciler = Reconciler(db, fs, paths, cfg, lanes.disp, engine, cm, log)
    }

    @AfterTest
    fun teardown() {
        runCatching { origin.close() }
        runCatching { engine.stop() }
        runCatching { scope.cancel() }
        runCatching { bulk.close() }
        runCatching { FileSystem.SYSTEM.deleteRecursively(root) }
    }

    private val provider =
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
            ): SignedStream = SignedStream("http://mock/audio", "mp4")
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

    private fun audioFiles(): List<String> = fs.list(paths.audioDir).map { it.name }

    // ---- seam 1: a body that fails mid-stream ---------------------------------------------------

    /**
     * **Seam 1a — FI-07, a reset after k bytes.** The bytes that landed must survive, and the next
     * request must ask for exactly those bytes. The control is the assertion on [OriginScript.requests]:
     * if the channel had never failed, the second request would be `Range: bytes=0-` and the part
     * would be empty, so a harness that silently delivered the whole body fails here.
     *
     * One retry, no backoff — see the class KDoc for why `dlRetries` is the variable under test.
     */
    @Test
    fun aMidStreamResetKeepsTheBytesThatLandedAndTheResumeAsksForThem() =
        runBlocking {
            admit("s1")
            buildEngine(cfg.copy(dlRetries = 1, dlBackoffBaseMs = FAST_BACKOFF_MS))
            origin.plan =
                listOf(
                    OriginStep.Reset(stopAfterBytes = RESET_AFTER, declared = BODY_BYTES),
                    OriginStep.Range(from = RESET_AFTER.toLong(), to = BODY_BYTES - 1L, total = BODY_BYTES.toLong()),
                )

            val st = runJob("s1")

            assertTrue(st is JobState.Done, "a reset is resumable, got $st")
            assertEquals(2, origin.requests.size, "the reset must have produced exactly one resume")
            assertEquals(
                "bytes=$RESET_AFTER-",
                origin.requests[1].range,
                "resume from the bytes that " +
                    "landed, not from zero",
            )
            assertEquals("identity", origin.requests[1].acceptEncoding, "the resume contract includes Accept-Encoding")
            assertNull(origin.requests[1].ifRange, "no validator was ever offered, so none may be sent")
            assertEquals(
                BODY_BYTES.toLong(),
                db.dylanQueries
                    .selectCached("saavn", "s1")
                    .executeAsOne()
                    .bytes,
                "the committed file is the whole object, not the part that landed",
            )
        }

    /**
     * **Seam 1b — FI-08, a truncated body with no exception.** A clean EOF under a full-length
     * `Content-Length` must be caught by the size check and leave the part resumable. If the
     * decorator could not produce a *clean* early close this would surface as a `NETWORK` verdict
     * from the channel instead, which is the distinction the two scenarios exist to make.
     */
    @Test
    fun aTruncatedBodyIsResumableRatherThanCommitted() =
        runBlocking {
            admit("s1")
            buildEngine(cfg.copy(dlRetries = 1, dlBackoffBaseMs = FAST_BACKOFF_MS))
            origin.plan =
                listOf(
                    OriginStep.Truncate(stopAfterBytes = TRUNCATE_AFTER, declared = BODY_BYTES),
                    OriginStep.Range(from = TRUNCATE_AFTER.toLong(), to = BODY_BYTES - 1L, total = BODY_BYTES.toLong()),
                )

            val st = runJob("s1")

            assertTrue(st is JobState.Done, "a truncated body is resumable, got $st")
            assertEquals(2, origin.requests.size, "the truncation must have produced exactly one resume")
            assertEquals("bytes=$TRUNCATE_AFTER-", origin.requests[1].range, "resume from the truncated length")
            assertEquals(
                BODY_BYTES.toLong(),
                db.dylanQueries
                    .selectCached("saavn", "s1")
                    .executeAsOne()
                    .bytes,
            )
        }

    /**
     * The other half of the pair, and the reason the two above configure `dlRetries = 1`: with **no**
     * retry budget a truncated body is terminal *and keeps its part*. The `CORRUPT_SIZE` code is
     * the point — it is deliberately not in `NON_RESUMABLE_CODES`, so the only resumable bytes are
     * not deleted by the failure (DL-2).
     */
    @Test
    fun aTruncatedBodyWithNoRetryBudgetIsTerminalAndKeepsItsPart() =
        runBlocking {
            admit("s1")
            buildEngine(cfg.copy(dlRetries = 0))
            origin.plan = listOf(OriginStep.Truncate(stopAfterBytes = TRUNCATE_AFTER, declared = BODY_BYTES))

            val st = runJob("s1")

            assertTrue(
                st is JobState.Failed && st.err.code == ErrorCode.CORRUPT_SIZE,
                "with no retry budget there is nothing to resume into, got $st",
            )
            assertEquals(1, origin.requests.size, "a zero budget must not re-issue the request")
            assertNull(db.dylanQueries.selectCached("saavn", "s1").executeAsOneOrNull(), "nothing may be committed")
            assertTrue(
                audioFiles().any { it.endsWith(".part") },
                "a size mismatch is a symptom, not a reason to delete the bytes: ${audioFiles()}",
            )
        }

    // ---- seam 2: the filesystem decorator --------------------------------------------------------

    /**
     * **Seam 2a — ENOSPC at the open-for-write is real (FI-22).**
     *
     * This is the *only* ENOSPC the decorator can put on the audio path: `okio.FileHandle.write` is
     * final, so the body bytes cannot be intercepted and a byte quota *on them* would measure nothing
     * (see `FaultyFileSystem`'s KDoc). What a full volume looks like to `Transfer.drain` is an open
     * that fails, and it must come out as `STORAGE` — not as a network failure and not as a committed
     * short file.
     *
     * `quotaBytes = 0` is the honest way to say "the volume is full": the budget is consumed at the
     * open, before a byte has been written. The armed-write form ([failAllWrites]) is the *other*
     * mechanism for the same branch and is exercised by seam 2b, so the two are not redundant.
     *
     * The control that makes this non-vacuous is [FaultyFileSystem.countersNonZeroFor]: without it a
     * decorator that was silently bypassed would leave the scenario green while proving nothing.
     *
     * MUTATION: `FaultyFileSystem.overQuota()` → `false`, i.e. the quota never trips. Then the transfer
     * completes and the test is red — which is the control that says the *volume* is what made it
     * fail, not the body size.
     */
    @Test
    fun aQuotaOnTheAudioDirectoryIsReportedAsStorage() =
        runBlocking {
            admit("s1")
            buildEngine(cfg.copy(dlRetries = 0))
            origin.plan = listOf(OriginStep.Ok(mp4Body(BODY_BYTES)))
            fs.quotaBytes = 0L

            val st = runJob("s1")

            assertTrue(st is JobState.Failed && st.err.code == ErrorCode.STORAGE, "got $st")
            assertEquals(
                1,
                origin.requests.size,
                "a full disk is not a transport failure, so it must not be retried",
            )
            val seen = fs.countersNonZeroFor("openReadWrite")
            assertTrue(
                seen.getValue("openReadWrite") > 0,
                "the fault was never injected — the decorator was bypassed: $seen",
            )
            assertNull(
                db.dylanQueries.selectCached("saavn", "s1").executeAsOneOrNull(),
                "a transfer that wrote nothing must commit nothing",
            )
            assertTrue(
                audioFiles().none { it.endsWith(".m4a") },
                "no truncated file may appear in the audio directory: ${audioFiles()}",
            )
        }

    /**
     * **Seam 2b — `PartMeta.write` is interceptable.** `FileSystem.write` is *final* in okio and
     * delegates to `sink`, so overriding `sink` is the only way to fault the `.part.meta` sidecar.
     *
     * `persist` writes the sidecar only when the `.part` still exists, and `runJob` calls it from its
     * `finally`, so the reachable shape is a job that **kept** its part. The scenario arms the
     * sidecar unconditionally and asserts three things: the decorator was reached (`sink` counted),
     * the sidecar is absent, and the part is untouched — which is FI-23's "the fault is non-fatal"
     * half, asserted against the only call site that can reach it. The other half of FI-23 ("the
     * transfer still commits") is **unreachable in the current production code**, because a commit
     * renames the part away before `persist` runs; see the class KDoc.
     *
     * MUTATION: delete `count("sink")` from `FaultyFileSystem.sink` — i.e. simulate the app bypassing
     * the decorator entirely. Then the counter never moves, the sidecar *is* written, and the test is
     * red. This is §5.8's "the injector was actually reached" control expressed as a mutation.
     */
    @Test
    fun theSidecarWriteGoesThroughTheDecoratorSoItCanBeFaulted() =
        runBlocking {
            admit("s1")
            buildEngine(cfg.copy(dlRetries = 0))
            origin.plan = listOf(OriginStep.Reset(stopAfterBytes = RESET_AFTER, declared = BODY_BYTES))
            fs.failAllWrites(".part.meta")

            val before = fs.countersNonZeroFor("sink").getValue("sink")
            // The terminal state is published *before* the attempt's `finally` runs, and `persist` is
            // in that `finally` — so the interception, not the state, is the event to wait for. See
            // `FaultyFileSystem.awaitOp`.
            val sidecarWrite = fs.awaitOp("sink")
            val st = runJob("s1")

            assertTrue(
                st is JobState.Failed,
                // The code is CORRUPT_SIZE, not NETWORK: the script declares BODY_BYTES but resets
                // at RESET_AFTER, so the body provably ended early and the size check is the
                // honest verdict. This precondition only needs "it failed" — the subject of the
                // test is the sidecar interception, asserted below.
                "precondition: a reset with no retry budget must fail, got $st",
            )
            withTimeout(JOB_CEILING_MS) {
                sidecarWrite.await()
            }
            assertTrue(
                fs.countersNonZeroFor("sink").getValue("sink") > before,
                "the counter and the event disagree: ${fs.counters}",
            )
            assertTrue(
                audioFiles().any { it.endsWith(".part") },
                "the fault must not disturb the bytes: ${audioFiles()}",
            )
            assertNull(
                audioFiles().firstOrNull { it.endsWith(".part.meta") },
                "the armed sidecar write must have failed, and `persist` is non-fatal about it",
            )
        }

    /**
     * **Seam 2c — a `delete` that fails keeps the row, and the next sweep drops both.** FI-25. This
     * proves the decorator can make `fs.delete` throw *without* breaking the app, which is the
     * precondition for the reconciler's "unlink failed, row kept for retry" contract being testable
     * at all — and it asserts the retry half too, because a row kept forever is the other half of
     * the same defect (CA-4).
     *
     * The row is seeded with a `bytes` that disagrees with the file on purpose, and its
     * `verified_at_ms` is cleared, because otherwise this sweep has nothing to do: a matching file is
     * stamped and kept, and a non-matching one is only re-examined by the *weekly* full sweep —
     * `fullSweepDue` writes `reconcile_full_ms` on the way past, so the second `run()` below would
     * find nothing due and the retry would not happen for another seven days. An unstamped row is
     * what the boot sweep (`unstampedObjects`) exists to repair, so it is the state in which "kept
     * for retry, retried on the next boot" is actually observable.
     */
    @Test
    fun aDeleteThatFailsIsInjectedWithoutBreakingTheReconciler() =
        runBlocking {
            val key = SongKey("saavn", "doomed")
            admit(key.songId)
            val final = paths.final(key, 128, "m4a")
            fs.write(final) { write(mp4Body(ON_DISK_BYTES)) }
            // The row claims more bytes than the file holds: a truncated file, which is exactly the
            // shape `checkOne` is written to detect.
            db.dylanQueries.insertCached(
                key.provider,
                key.songId,
                128L,
                "m4a",
                ROW_CLAIMS_BYTES,
                clock.nowMs(),
                null,
                0L,
                0L,
                null,
            )
            Sql(driver).exec("UPDATE media_objects SET verified_at_ms = NULL WHERE song_id = 'doomed'")
            fs.undeletable += ".m4a"

            val before = fs.countersNonZeroFor("delete").getValue("delete")
            reconciler.run()

            val after = fs.countersNonZeroFor("delete")
            assertTrue(after.getValue("delete") > before, "the delete was never attempted: $after")
            assertTrue(fs.exists(final), "an unlink that threw must leave the file")
            assertEquals(
                1L,
                Sql(driver).scalarLong("SELECT COUNT(*) FROM library WHERE song_id = 'doomed'"),
                "a failed unlink must keep the row for the next sweep",
            )

            // The retry half: the same sweep, with the fault lifted, must finish the job.
            fs.undeletable.clear()
            reconciler.run()

            assertTrue(!fs.exists(final), "the second sweep must complete the unlink")
            assertEquals(
                0L,
                Sql(driver).scalarLong("SELECT COUNT(*) FROM library WHERE song_id = 'doomed'"),
                "a successful unlink must drop the row",
            )
            assertEquals(
                0L,
                Sql(driver).scalarLong("SELECT COUNT(*) FROM media_objects WHERE song_id = 'doomed'"),
                "and the per-rendition record with it",
            )
        }

    private companion object {
        const val BODY_BYTES = 12_000
        const val JOB_CEILING_MS = 30_000L

        /**
         * A resume offset that is not a multiple of the copy buffer, so a harness that delivered
         * whole chunks regardless of [OriginStep.stopAfterBytes] would produce a different `Range`.
         */
        const val RESET_AFTER = 1_080
        const val TRUNCATE_AFTER = 500

        /** Policy, not behaviour: the retry ladder is `dlBackoffBaseMs × attempt`. */
        const val FAST_BACKOFF_MS = 5L

        const val ON_DISK_BYTES = 1_000
        const val ROW_CLAIMS_BYTES = 1_500L
    }
}
