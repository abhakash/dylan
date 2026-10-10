package dylan.fuzz

import app.cash.sqldelight.db.SqlDriver
import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.download.Breakers
import dylan.download.Breakpoint
import dylan.download.DownloadEngine
import dylan.download.PartMeta
import dylan.model.Quality
import dylan.model.SongKey
import dylan.support.MutableClock
import dylan.support.TestLanes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/**
 * A store written by "the current code", built out of **hostile-but-legal** rows: a pinned row, an
 * unpinned row, a row whose file is a `.mp3` rather than a `.m4a`, a superseded rendition that no
 * longer has a library row, two `.part` files — one resumable, one fresh and sidecar-less — an
 * intent for the resumable one, favourites, history, and two files the app did not write.
 *
 * Everything goes through the same [FaultyFileSystem] the app uses, so a scenario can be seeded
 * *through* a fault-injecting disk.
 *
 * **Every byte is named.** Nothing here is a magic constant: a reader who wants to know why the
 * fixture is `7 songs · 5 cached (2 pinned) · 2 parts · 1 intent · 2 foreign files` reads
 * [describe] rather than counting, and every count in that sentence is a consequence of the rows
 * below.
 */
class HealthyStore(
    private val root: Path,
    val cfg: AppConfig,
    val clock: MutableClock,
) : AutoCloseable {
    val fs = FaultyFileSystem(FileSystem.SYSTEM)

    init {
        // Before the driver: `JdbcSqliteDriver` refuses to create a file whose directory is missing,
        // and the failure it throws says nothing about which directory it wanted.
        fs.createDirectories(root)
    }

    val dbPath = (root / "dylan.db").toString()
    val driver: SqlDriver = DriverFactory(dbPath, LogBuffer()).createDriver()
    val db = Dylan(driver)
    val paths = Paths(root / "audio", fs)
    val sql = Sql(driver)
    val ledger = EnvironmentLedger()
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val log = LogBuffer()
    val oracle = DbOracle(driver, fs, paths, cfg, clock, ledger)

    /**
     * The engine, built on demand and **not started**.
     *
     * §3.2: a P1 check "must not depend on the download engine". A started engine would make
     * `Reconciler.resumeIntents()` enqueue a real transfer for the fixture's intent row, and that
     * transfer would race every assertion in the scenario against a `.part` file it is rewriting. A
     * stopped engine accepts the enqueue (the queue, the intent write and the log all still happen,
     * so the reconciler's behaviour is real) and no worker ever claims the job. [startWorkers] is
     * the explicit opt-in for the M3 scenarios that do want one.
     */
    var engine: DownloadEngine? = null
        private set

    /** The provider the engine would resolve through; it never reaches the network in M1/M2. */
    private val provider =
        object : dylan.provider.MusicProvider {
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
            ): dylan.provider.SignedStream = dylan.provider.SignedStream("http://mock/audio", "mp4")
        }

    /**
     * The `Reconciler` under test, over a **stopped** engine. See [engine]: the reconciler must be
     * exercised for real, and nothing may be downloading while its result is inspected.
     */
    fun reconciler(): Reconciler = newReconciler(db, fs, paths, cfg, log, newEngine())

    fun newEngine(
        bulk: HttpClient = HttpClient(MockEngine { error("M1/M2 never reach the network") }),
    ): DownloadEngine {
        engine?.stop()
        val lanes = TestLanes.production()
        val cm = CacheManager(db, fs, paths, MutableStateFlow(emptySet()), cfg, lanes.disp, log)
        val e =
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
        engine = e
        return e
    }

    /** The explicit opt-in that [newEngine] deliberately withholds. */
    fun startWorkers(): DownloadEngine = requireNotNull(engine) { "call newEngine() first" }.also { it.start() }

    /** One `songs` row. `durationS` short enough that the size band is not a factor. */
    fun song(
        id: String,
        durationS: Long = 100L,
        has320: Boolean = true,
    ) {
        db.dylanQueries.insertSong(
            "saavn",
            id,
            "title-$id",
            "sub",
            null,
            null,
            "",
            "",
            durationS,
            if (has320) 1L else 0L,
            "ref-$id",
            null,
            clock.nowMs(),
        )
    }

    /**
     * A cache row **and** its file, in one call, so the two can never disagree by accident: every
     * INV-21/INV-22 violation in this suite is a *deliberate* one.
     */
    fun cached(
        id: String,
        bytes: Long,
        bits: Int = 128,
        ext: String = "m4a",
        pinned: Boolean = false,
        playCount: Long = 0L,
        lastUsedMs: Long? = null,
        ageMs: Long = 0L,
    ): Path {
        song(id)
        db.dylanQueries.insertCached(
            "saavn",
            id,
            bits.toLong(),
            ext,
            bytes,
            clock.nowMs() - ageMs,
            lastUsedMs,
            playCount,
            if (pinned) 1L else 0L,
            if (pinned) clock.nowMs() - ageMs else null,
        )
        val path = paths.final(SongKey("saavn", id), bits, ext)
        fs.write(path) { write(ByteArray(bytes.toInt()) { 'x'.code.toByte() }) }
        return path
    }

    /** A `.part` with a valid sidecar, i.e. a genuinely resumable transfer. */
    fun resumablePart(
        id: String,
        bytes: Long,
        total: Long,
        bits: Int = 128,
        etag: String = "\"v1\"",
        ageMs: Long = 0L,
    ): Path {
        song(id)
        val path = paths.part(SongKey("saavn", id), bits)
        fs.write(path) { write(ByteArray(bytes.toInt()) { 'y'.code.toByte() }) }
        age(path, ageMs)
        val bp = Breakpoint(bytes, total, etag, Quality.of(bits), clock.nowMs() - ageMs)
        check(PartMeta.write(fs, PartMeta.sidecarOf(path), bp)) { "the fixture could not write a sidecar for $id" }
        return path
    }

    /** A `.part` with **no** sidecar: bytes nothing can resume from, held only inside the grace window. */
    fun orphanPart(
        id: String,
        bytes: Long,
        bits: Int = 128,
        ageMs: Long = 0L,
    ): Path {
        song(id)
        val path = paths.part(SongKey("saavn", id), bits)
        fs.write(path) { write(ByteArray(bytes.toInt()) { 'z'.code.toByte() }) }
        age(path, ageMs)
        return path
    }

    /** A `.part.meta` with no `.part` — the phantom resumable record `PartStore.persist` used to write. */
    fun phantomSidecar(
        id: String,
        bytes: Long,
        bits: Int = 128,
    ): Path {
        song(id)
        val part = paths.part(SongKey("saavn", id), bits)
        val sidecar = PartMeta.sidecarOf(part)
        check(PartMeta.write(fs, sidecar, Breakpoint(bytes, bytes * 2, null, Quality.of(bits), clock.nowMs())))
        return sidecar
    }

    /** A file this app did not write — the `parseFileName == null → continue` contract. */
    fun foreignFile(name: String): Path = paths.audioDir / name

    fun favourite(
        id: String,
        atMs: Long = clock.nowMs(),
    ) {
        db.dylanQueries.addFavorite("saavn", id, atMs)
    }

    fun history(
        id: String,
        atMs: Long,
    ) {
        db.dylanQueries.insertHistory("saavn", id, atMs)
    }

    fun intent(
        id: String,
        reason: String = "PREFETCH_NEXT",
        bits: Int = 128,
        atMs: Long = clock.nowMs(),
    ) {
        sql.exec(
            "INSERT INTO download_intents(provider, song_id, reason, bitrate, enqueued_at_ms) " +
                "VALUES ('saavn', '$id', '$reason', $bits, $atMs)",
        )
    }

    /** Set an mtime in the past, against the injected clock — the `ReconcilerClockTest` technique. */
    fun age(
        path: Path,
        ageMs: Long,
    ) {
        val at = clock.nowMs() - ageMs
        check(File(path.toString()).setLastModified(at)) { "could not age ${path.name} to $at" }
    }

    /**
     * The one-line human summary used in every failure message. Every figure is derived, so the
     * fixture and its description cannot drift.
     */
    fun describe(): String {
        val songs = sql.scalarLong("SELECT COUNT(*) FROM songs")
        val cached = sql.scalarLong("SELECT COUNT(*) FROM library")
        val pinned = sql.scalarLong("SELECT COUNT(*) FROM library WHERE pinned = 1")
        val parts = fs.list(paths.audioDir).count { it.name.endsWith(".part") }
        val intents = sql.scalarLong("SELECT COUNT(*) FROM download_intents")
        val foreign = fs.list(paths.audioDir).count { paths.parseFileName(it.name) == null }
        return "$songs songs · $cached cached ($pinned pinned) · $parts " +
            "parts · $intents intent(s) · $foreign foreign file(s)"
    }

    override fun close() {
        runCatching { engine?.stop() }
        runCatching { scope.cancel() }
        runCatching { driver.close() }
        // The root *is* the scratch directory (`<tag>-<nanoTime>/dylan.db`), so there is no parent to
        // delete and nothing to leak. Removing the parent instead would reach outside this fixture.
        runCatching { FileSystem.SYSTEM.deleteRecursively(root) }
    }

    companion object {
        /**
         * The reference healthy store. Exposed as one function so a scenario says
         * `HealthyStore.build()` and the shape is defined in exactly one place.
         *
         * Why each row is here is stated inline; a fixture whose rows are unexplained is a fixture
         * nobody can extend safely.
         */
        fun build(
            root: Path = defaultRoot(),
            cfg: AppConfig = AppConfig(clock = MutableClock()),
        ): HealthyStore {
            val clock = cfg.clock as? MutableClock ?: MutableClock()
            val store = HealthyStore(root, cfg.copy(clock = clock), clock)
            store.seedHealthy()
            return store
        }

        fun defaultRoot(): Path {
            val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()
            return "$dir/dylan-fuzz-${System.nanoTime()}".toPath()
        }
    }

    private fun seedHealthy() {
        val now = clock.nowMs()
        // Every `.part` is written through `age(path, 0)`, i.e. its mtime is the *injected* clock's
        // now and not the real wall clock. Without that, INV-27's grace window is decided by how far
        // the fixture's clock has drifted from the machine's, which is exactly the kind of accidental
        // dependency this suite exists to remove.
        //
        // 1. Pinned, played, most recently used — the row LRU must never pick.
        cached(
            "pinned320",
            bytes = 2_000,
            bits = 320,
            pinned = true,
            playCount = 9,
            lastUsedMs = now - 1_000,
            ageMs = 10_000,
        )
        // 2. Pinned only — proves the pin is a separate fact from play_count (idx_cached_pinned's shape).
        cached("pinned128", bytes = 1_500, bits = 128, pinned = true, playCount = 0, lastUsedMs = null, ageMs = 20_000)
        // 3. Unpinned, played, recently used — the `(last_used_ms IS NULL) = 0` arm of idx_cached_lru.
        cached("recent", bytes = 1_100, bits = 128, playCount = 4, lastUsedMs = now - 5_000, ageMs = 5_000)
        // 4. Unpinned, unplayed, no last_used — the coldest row, and the first eviction candidate.
        cached("cold", bytes = 1_200, bits = 128, playCount = 0, lastUsedMs = null, ageMs = 90_000)
        // 5. An `.mp3` rendition, so "the ext is part of the file name" is exercised by the fixture
        //    rather than assumed.
        cached(
            "mp3track",
            bytes = 900,
            bits = 128,
            ext = "mp3",
            playCount = 1,
            lastUsedMs = now - 2_000,
            ageMs = 30_000,
        )

        // 6. Resumable: a `.part` with a sidecar AND the intent row that says a transfer wants it.
        resumablePart("wip", bytes = 700, total = 9_000, bits = 128)
        intent("wip", reason = "PREFETCH_NEXT", bits = 128)

        // 7. Unreachable bytes: a `.part` with no sidecar and no intent, held only by the grace
        //    window. INV-27 passes on the window and is proven load-bearing by ageing it past it.
        orphanPart("halfdone", bytes = 400, bits = 128, ageMs = 0)

        // 8. A song with nothing on disk at all — the uncached shape, which INV-21/INV-22 must ignore.
        song("uncached")

        favourite("pinned320", atMs = now - 100)
        favourite("pinned128", atMs = now - 200)
        favourite("uncached", atMs = now - 300)

        // History: distinct milliseconds, so `trimHistory`'s tie rule has nothing to hide behind.
        history("recent", now - 30_000)
        history("cold", now - 20_000)
        history("pinned320", now - 10_000)
        history("uncached", now - 1_000)

        // Two files the app did not write. Neither parses, so the orphan sweep must never see them.
        fs.write(foreignFile("cover.jpg")) { writeUtf8("not audio") }
        fs.write(foreignFile("notes.txt")) { writeUtf8("shopping list") }
    }
}

/**
 * The `Reconciler` over a **stopped** engine, shared with the M2 scenarios.
 *
 * Those reconcile a store they *migrated* rather than one they built, so they get the same "stopped
 * engine" discipline instead of each inventing its own: a running engine would let
 * `Reconciler.resumeIntents` start a real transfer for the fixture's intent row while the scenario is
 * inspecting the result.
 */
internal fun newReconciler(
    db: Dylan,
    fs: FileSystem,
    paths: Paths,
    cfg: AppConfig,
    log: LogBuffer,
    engine: DownloadEngine,
): Reconciler {
    val lanes = TestLanes.production()
    val cm = CacheManager(db, fs, paths, MutableStateFlow(emptySet()), cfg, lanes.disp, log)
    return Reconciler(db, fs, paths, cfg, lanes.disp, engine, cm, log)
}

/** A scratch root under the system temp dir with a `nanoTime` suffix: unique per run, never shared. */
internal fun fuzzRoot(tag: String): Path {
    val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()
    return "$dir/dylan-fuzz-$tag-${System.nanoTime()}".toPath()
}
