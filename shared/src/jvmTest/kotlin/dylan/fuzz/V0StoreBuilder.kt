package dylan.fuzz

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import okio.FileSystem
import okio.Path
import java.io.File

/**
 * **M2 — a v0 store, built from the real pre-split DDL.**
 *
 * The only way to reach the states a current build cannot produce is to write them by hand, and the
 * only *honest* way to write them is from the DDL that actually shipped. So this is a transcription
 * of the pre-split schema, and the transcription is the artefact under test: if it drifts from the
 * real v0 shape the scenarios stop testing the migration and start testing a fiction.
 *
 * **Why it is built by downgrading rather than from scratch.** Tables `1.sqm` does not touch
 * (`songs`, `favorites`, `play_history`, `search_history`, `settings`, `home_cache`) are created by
 * `Dylan.Schema.create` and are byte-identical to their v0 definitions, so the store is created at
 * the current schema and then *downgraded*: the split objects dropped and the pre-split ones put back.
 * That is the same construction `CacheMigrationTest` uses, for the same reason, and it means a change
 * to an untouched table cannot silently make a migration scenario pass.
 *
 * **Duplication, stated rather than hidden.** `CacheMigrationTest.seedV0()` holds the same DDL.
 * This file does not call it because that class is private to its own test and owned by a change in
 * flight; once it is idle, `seedV0()` should become a call into here so there is one transcription
 * (The implementation notes that recorded the shape discovery are retired.)
 *
 * **The seeding connection has no `foreign_keys` property**, exactly as the pre-split JVM factory did
 * not. That is the only way a `cached_files` row can outlive its song, and it is how real v0 stores
 * came to hold one — which is what S-MIG-03 exists for.
 */
object V0StoreBuilder {
    /** One `cached_files` row, with every column the v0 table had. */
    data class Row(
        val songId: String,
        val bitrate: Int = 128,
        val ext: String = "m4a",
        val bytes: Long = 1_000,
        val cachedAtMs: Long = 10,
        val lastUsedMs: Long? = null,
        val playCount: Long = 0,
        val pinned: Int = 0,
        val pinnedAtMs: Long? = null,
    )

    /**
     * What shape of v0 store to build. Every field is a *hostility* knob, and the default is the
     * cleanest store the current code could plausibly have left behind.
     */
    data class Spec(
        val rows: List<Row> =
            listOf(
                Row(
                    "1",
                    bitrate = 128,
                    bytes = 1_000,
                    cachedAtMs = 10,
                    lastUsedMs = 100,
                    playCount = 2,
                    pinned = 1,
                    pinnedAtMs = 50,
                ),
                Row(
                    "2",
                    bitrate = 320,
                    bytes = 2_000,
                    cachedAtMs = 20,
                    lastUsedMs = 200,
                    playCount = 0,
                    pinned = 0,
                    pinnedAtMs = 777,
                ),
                // The phantom pin of §3.9: pinned with no timestamp. The one change the migration is
                // allowed to make to the data.
                Row("3", bitrate = 128, ext = "mp3", bytes = 3_000, cachedAtMs = 30, pinned = 1, pinnedAtMs = null),
            ),
        val songIds: List<String> = listOf("1", "2", "3"),
        val historyCount: Int = 4,
        /** Delete song `2` after seeding: the orphan row S-MIG-03 is about. */
        val orphanSongId: String? = null,
        /**
         * Write one row per CHECK violation. `1.sqm`'s header claims every COPY is total; these are
         * the values that decide whether the claim is true.
         */
        val hostile: Boolean = false,
        /** An intent whose `reason` is not one of the four legal wires. */
        val unknownReason: Boolean = false,
        /** What to stamp. 0 is the real pre-versioning value; 1 is what Android/iOS wrote. */
        val userVersion: Long = 0L,
    )

    /** A v0 store on disk, plus the root its audio directory lives in. */
    class Handle(
        val root: Path,
        val spec: Spec,
    ) : AutoCloseable {
        val path = (root / "dylan.db").toString()
        val log = LogBuffer()

        /** A raw connection with **no** pragmas: what the v0 factory had. */
        fun raw(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$path")

        /** The production open. This is the thing under test, not a helper around it. */
        fun open(allowWipeOnCorruption: Boolean = false): SqlDriver {
            val factory = DriverFactory(path, log, allowWipeOnCorruption)
            return factory.createDriver()
        }

        override fun close() {
            runCatching { FileSystem.SYSTEM.deleteRecursively(root) }
        }
    }

    /**
     * Build the store and leave it **closed**, so the only way to open it is through [Handle.open] —
     * i.e. through production's own open path, pragmas and all.
     */
    fun build(
        tag: String,
        spec: Spec = Spec(),
    ): Handle {
        val root = fuzzRoot(tag)
        FileSystem.SYSTEM.createDirectories(root)
        val handle = Handle(root, spec)
        val driver = handle.raw()
        try {
            Dylan.Schema.create(driver)
            downgrade(driver)
            seed(driver, spec)
            driver.execute(null, "PRAGMA user_version = ${spec.userVersion}", 0)
        } finally {
            driver.close()
        }
        return handle
    }

    /** Put the pre-split shape back: one `cached_files` table, no split, no totals, no protection. */
    private fun downgrade(driver: SqlDriver) {
        val sql = Sql(driver)
        for (t in V0_DROP_TRIGGERS) sql.exec("DROP TRIGGER IF EXISTS $t")
        sql.exec("DROP VIEW IF EXISTS cached_files")
        for (t in V0_DROP_TABLES) sql.exec("DROP TABLE IF EXISTS $t")
        // The pre-split table, with **no** CHECKs — which is the whole reason it is rebuilt rather
        // than ALTERed, and the reason `Spec.hostile` can write values the current schema refuses.
        sql.exec(
            """
            CREATE TABLE cached_files (
              provider TEXT NOT NULL, song_id TEXT NOT NULL, bitrate INTEGER NOT NULL,
              ext TEXT NOT NULL DEFAULT 'm4a', bytes INTEGER NOT NULL, cached_at_ms INTEGER NOT NULL,
              last_used_ms INTEGER, play_count INTEGER NOT NULL DEFAULT 0,
              pinned INTEGER NOT NULL DEFAULT 0, pinned_at_ms INTEGER,
              PRIMARY KEY (provider, song_id),
              FOREIGN KEY (provider, song_id) REFERENCES songs(provider, song_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sql.exec("CREATE INDEX idx_cached_lru_unpinned ON cached_files(last_used_ms, bytes DESC) WHERE pinned = 0")
        sql.exec("CREATE INDEX idx_cached_pinned ON cached_files(pinned_at_ms) WHERE pinned = 1")

        // `1.sqm` renames, rebuilds and re-indexes `download_intents`; the v0 table is 5 columns and
        // carries no `priority` and no CHECK on `reason`.
        for (i in V0_DROP_INDEXES) sql.exec("DROP INDEX IF EXISTS $i")
        for (t in V0_INTENT_TRIGGERS) sql.exec("DROP TRIGGER IF EXISTS $t")
        sql.exec("ALTER TABLE download_intents RENAME TO download_intents_v1")
        sql.exec(
            "CREATE TABLE download_intents (provider TEXT NOT NULL, song_id TEXT NOT NULL, " +
                "reason TEXT NOT NULL, bitrate INTEGER NOT NULL, enqueued_at_ms INTEGER NOT NULL, " +
                "PRIMARY KEY (provider, song_id))",
        )
        sql.exec(
            "INSERT INTO download_intents(provider, song_id, reason, bitrate, enqueued_at_ms) " +
                "SELECT provider, song_id, reason, bitrate, enqueued_at_ms FROM download_intents_v1",
        )
        sql.exec("DROP TABLE download_intents_v1")
    }

    private fun seed(
        driver: SqlDriver,
        spec: Spec,
    ) {
        val sql = Sql(driver)
        for (id in spec.songIds) {
            sql.exec(
                "INSERT INTO songs(provider, song_id, title, subtitle, art_url_150, art_url_500, duration_s, " +
                    "has_320, updated_at_ms) VALUES ('saavn', '$id', 't$id', 's', '', '', 100, 1, 1)",
            )
            sql.exec("INSERT OR IGNORE INTO favorites VALUES ('saavn','$id',${spec.cachedAtFor(id)})")
        }
        repeat(spec.historyCount) { i ->
            val id = spec.songIds[i % spec.songIds.size]
            sql.exec("INSERT INTO play_history(provider, song_id, played_at_ms) VALUES ('saavn','$id',${10 + i})")
        }
        for (r in spec.rows) {
            sql.exec(
                "INSERT INTO cached_files(provider, song_id, bitrate, ext, bytes, cached_at_ms, last_used_ms, " +
                    "play_count, pinned, pinned_at_ms) VALUES ('saavn','${r.songId}',${r.bitrate},'${r.ext}'," +
                    "${r.bytes},${r.cachedAtMs},${r.lastUsedMs?.toString() ?: "NULL"},${r.playCount},${r.pinned}," +
                    "${r.pinnedAtMs?.toString() ?: "NULL"})",
            )
        }
        // The orphan: the song goes, its cache row stays. Only reachable because this connection has
        // foreign keys OFF, which is what a pre-split store effectively was.
        spec.orphanSongId?.let { sql.exec("DELETE FROM songs WHERE provider = 'saavn' AND song_id = '$it'") }

        val reason = if (spec.unknownReason) "WEIRD" else "USER_NOW"
        sql.exec("INSERT INTO download_intents VALUES ('saavn','1','$reason',128,1)")

        if (spec.hostile) {
            // One row per CHECK the new `library` has and the old `cached_files` did not. All four in
            // one store, because the point is that a *single* bad row is enough to wedge an install.
            sql.exec("UPDATE cached_files SET bitrate = 0 WHERE song_id = '1'")
            sql.exec("UPDATE cached_files SET ext = '' WHERE song_id = '2'")
            sql.exec("UPDATE cached_files SET bytes = -1, play_count = -5 WHERE song_id = '3'")
        }
    }

    private fun Spec.cachedAtFor(id: String): Long = rows.firstOrNull { it.songId == id }?.cachedAtMs ?: 1

    /**
     * The `Reconciler` over a migrated store, with the same stopped-engine discipline as
     * [HealthyStore.reconciler]. The engine is never started, so `resumeIntents` enqueues and nothing
     * downloads.
     */
    fun reconcilerOver(
        driver: SqlDriver,
        fs: FileSystem,
        root: Path,
        cfg: AppConfig = AppConfig(),
        log: LogBuffer = LogBuffer(),
    ): dylan.cache.Reconciler {
        val paths = Paths(root / "audio", fs)
        val db = Dylan(driver)
        val lanes = dylan.support.TestLanes.production()
        val engine =
            dylan.download.DownloadEngine(
                db = db,
                fs = fs,
                paths = paths,
                cfg = cfg,
                disp = lanes.disp,
                provider = NO_NETWORK_PROVIDER,
                bulk =
                    io.ktor.client.HttpClient(
                        io.ktor.client.engine.mock
                            .MockEngine { error("M2 never reaches the network") },
                    ),
                breakers = dylan.download.Breakers(),
                cacheManager =
                    dylan.cache.CacheManager(
                        db,
                        fs,
                        paths,
                        kotlinx.coroutines.flow.MutableStateFlow(emptySet()),
                        cfg,
                        lanes.disp,
                        log,
                    ),
                netClass = { dylan.util.NetClass.UNMETERED },
                qualityPref = { dylan.model.Quality.BITRATE_128 },
                log = log,
            )
        return newReconciler(db, fs, paths, cfg, log, engine)
    }

    private val NO_NETWORK_PROVIDER =
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
                q: dylan.model.Quality,
            ): dylan.provider.SignedStream? = null
        }

    /** The file the audio directory lives in for [handle]; `Handle` owns the root, not this. */
    fun audioDir(handle: Handle): Path = handle.root / "audio"

    /** True when [file] is on disk at all — the only honest check for "the user did not lose it". */
    fun exists(file: File): Boolean = file.exists()
}

private val V0_DROP_TRIGGERS =
    listOf(
        "trg_library_total_ins",
        "trg_library_total_del",
        "trg_library_total_upd",
        "trg_library_asset_ins",
        "trg_library_asset_upd",
        "trg_library_drop_assets",
        "trg_media_drop_library",
    )

private val V0_DROP_TABLES = listOf("library", "media_objects", "cache_totals", "protected_keys")

/** Indexes `1.sqm` drops or recreates; all of them are created by `Dylan.Schema.create` first. */
private val V0_DROP_INDEXES =
    listOf(
        "idx_history_song",
        "idx_songs_gc",
        "idx_songs_album",
        "idx_home_cache",
        "idx_intent_priority",
        "idx_cached_lru",
        "idx_cached_pinned",
        "idx_media_state",
    )

private val V0_INTENT_TRIGGERS = listOf("trg_intent_priority_ins", "trg_intent_priority_upd")
