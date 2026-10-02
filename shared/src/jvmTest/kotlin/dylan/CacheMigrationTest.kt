package dylan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The migration chain and the open-time policy, against a real SQLite file.
 *
 * Before this there were no `.sqm` files, no schema version, and all three DriverFactory actuals
 * answered a failed open with `wipe(); open()` — so a schema mismatch silently deleted the user's
 * favourites, history and cache, on Android from inside Application.onCreate before the first
 * frame. Both halves of that are asserted here: the v0 store survives the upgrade, and a store
 * that fails to open is *not* deleted.
 */
class CacheMigrationTest {
    @Test
    fun freshFileIsCreatedAtTheExpectedVersionWithForeignKeysOn() {
        withStore { store ->
            assertEquals(Dylan.Schema.version, store.userVersion(), "a fresh store must land on the schema version")
            assertEquals(1L, store.pragmaInt("PRAGMA foreign_keys"), "ON DELETE CASCADE is a no-op otherwise")
            listOf("songs", "library", "media_objects", "cache_totals", "protected_keys", "cached_files").forEach {
                assertEquals(1L, store.num("SELECT COUNT(*) FROM sqlite_master WHERE name = '$it'"), "missing $it")
            }
        }
    }

    @Test
    fun v0StoreMigratesAndKeepsEveryFavouriteHistoryRowAndCacheRow() {
        withStore { store ->
            store.seedV0()
            store.reopen()

            assertEquals(Dylan.Schema.version, store.userVersion())
            assertEquals(3L, store.num("SELECT COUNT(*) FROM favorites"))
            assertEquals(4L, store.num("SELECT COUNT(*) FROM play_history"))
            // The projection is named column by column (rows() reads the FIRST column of each row),
            // so a SELECT * that shifted a column cannot be mistaken for a passing migration.
            val rows = store.rows(LIBRARY_SHAPE + " ORDER BY song_id")
            assertEquals(3, rows.size)
            assertEquals(
                listOf(
                    "saavn|1|128|m4a|1000|10|100|2|1|50",
                    "saavn|2|320|m4a|2000|20|200|0|0|",
                    // v0 row 3 was pinned=1 with a NULL pinned_at_ms; the one change the migration is
                    // allowed to make to the data is that pin repair, so this row carries cached_at_ms.
                    "saavn|3|128|mp3|3000|30||0|1|30",
                ),
                rows,
                "v0 field for field, in library's column order",
            )
            // ...and the pre-split reader surface still answers, with the same shape.
            assertEquals(3L, store.num("SELECT COUNT(*) FROM cached_files"))
            assertEquals(1L, store.num("SELECT COUNT(*) FROM cached_files WHERE song_id = '1' AND bytes = 1000"))
        }
    }

    @Test
    fun migrationRepairsThePhantomPinAndMaterialisesTheAssetAndTotalsTables() {
        withStore { store ->
            // pinned=1 with a NULL pinned_at_ms, and pinned=0 with one set: the two shapes v0
            // allowed and that nothing ever cleared.
            store.seedV0()
            store.reopen()

            assertEquals(
                "saavn|3|1|30",
                store.text(PIN_SHAPE + " WHERE song_id = '3'"),
                "a pin with no timestamp must get one, so demotion order is defined",
            )
            assertEquals(
                "saavn|2|0|",
                store.text(PIN_SHAPE + " WHERE song_id = '2'"),
                "an unpinned row must not keep a pin timestamp",
            )
            assertEquals(3L, store.num("SELECT COUNT(*) FROM media_objects WHERE state = 'READY'"))
            assertEquals(0L, store.num("SELECT COUNT(*) FROM media_objects WHERE state <> 'READY'"))
            assertEquals(
                6000L,
                store.num("SELECT final_bytes FROM cache_totals WHERE id = 0"),
                "totals must be seeded from the copied rows",
            )
            assertEquals(3L, store.num("SELECT final_count FROM cache_totals WHERE id = 0"))
        }
    }

    @Test
    fun cacheTotalsFollowInsertsUpdatesAndDeletes() {
        withStore { store ->
            val db = Dylan(store.driver)
            store.seedSong("a")
            db.dylanQueries.insertCached("saavn", "a", 128L, "m4a", 500L, 1L, null, 0L, 0L, null)
            assertTotals(store, bytes = 500L, count = 1L)
            db.dylanQueries.touchUsed(9L, "saavn", "a")
            db.dylanQueries.bumpPlayCount("saavn", "a")
            assertTotals(store, bytes = 500L, count = 1L)
            db.dylanQueries.upsertCached("saavn", "a", 320L, "m4a", 900L, 2L, 9L, 1L, 1L, 5L)
            assertTotals(store, bytes = 900L, count = 1L)
            assertEquals(
                1L,
                store.num("SELECT COUNT(*) FROM media_objects WHERE song_id = 'a'"),
                "the superseded rendition is dropped with the row",
            )
            db.dylanQueries.deleteCached("saavn", "a")
            assertTotals(store, bytes = 0L, count = 0L)
            assertEquals(
                0L,
                store.num("SELECT COUNT(*) FROM media_objects"),
                "no asset may outlive its per-song record",
            )
        }
    }

    @Test
    fun deletingASongCascadesAndUpdatesTotals() {
        withStore { store ->
            val db = Dylan(store.driver)
            store.seedSong("a")
            db.dylanQueries.insertCached("saavn", "a", 128L, "m4a", 500L, 1L, null, 0L, 0L, null)
            store.exec("DELETE FROM songs WHERE provider = 'saavn' AND song_id = 'a'")
            assertTotals(store, bytes = 0L, count = 0L)
            assertEquals(0L, store.num("SELECT COUNT(*) FROM media_objects"))
        }
    }

    @Test
    fun priorityIsStoredAndIndexedRatherThanRecomputedPerQuery() {
        withStore { store ->
            val db = Dylan(store.driver)
            store.seedSong("a")
            db.dylanQueries.upsertIntent("saavn", "a", "PREFETCH_NEXT", 128L, 1L)
            assertEquals(2L, store.num("SELECT priority FROM download_intents WHERE song_id = 'a'"))
            db.dylanQueries.upsertIntent("saavn", "a", "USER_NOW", 320L, 2L)
            assertEquals(0L, store.num("SELECT priority FROM download_intents WHERE song_id = 'a'"))
            db.dylanQueries.upsertIntent("saavn", "a", "PREFETCH_NEXT", 128L, 3L)
            assertEquals(
                0L,
                store.num("SELECT priority FROM download_intents WHERE song_id = 'a'"),
                "a lower-priority enqueue must not win",
            )
            val plan = store.plan("SELECT * FROM download_intents ORDER BY priority, enqueued_at_ms")
            assertTrue(plan.none { it.contains("TEMP B-TREE") }, "intentsOrdered must ride its index: $plan")
        }
    }

    @Test
    fun aFailedOpenThrowsAndLeavesTheUsersFileAlone() {
        withStore { store ->
            store.seedV0()
            store.close()
            // Not a SQLite file at all: the old code answered this by deleting it.
            java.io.File(store.path).writeBytes(ByteArray(4096) { (it % 251).toByte() })
            val before = java.io.File(store.path).length()

            assertFailsWith<Exception> { DriverFactory(store.path, LogBuffer()).createDriver() }
            assertTrue(java.io.File(store.path).exists(), "a failed open must never delete the database")
            assertEquals(before, java.io.File(store.path).length(), "the file must be byte-for-byte untouched")

            // ...unless corruption is positively established AND the caller opted in.
            val factory = DriverFactory(store.path, LogBuffer(), allowWipeOnCorruption = true)
            val recovered = Store(store.path, factory.createDriver())
            assertEquals(Dylan.Schema.version, recovered.scalarLong("PRAGMA user_version"))
            assertEquals(
                0L,
                recovered.scalarLong("SELECT COUNT(*) FROM library"),
                "a wiped store is rebuilt empty",
            )
            recovered.close()
        }
    }

    @Test
    fun aV0StoreWithAnOrphanCachedRowStillMigrates() {
        // Totality against the hazard the migration header does not name: this chain runs with
        // foreign keys ON (the JVM factory supplies them as driver *properties*, which is the only
        // form that survives SQLDelight's transaction and the JDBC pool), so `INSERT INTO library
        // ... SELECT ... FROM cached_files` enforces the new library -> songs foreign key.
        //
        // A v0 store can hold a cached_files row whose song is gone — that is precisely what
        // `deleteOrphanLibrary` exists to repair, because v0 enforced the cascade only on
        // connections that happened to have the pragma on. Left in place it aborts the migration
        // *before* `DROP TABLE cached_files`, so nothing is wiped and every later open re-runs the
        // same chain and fails identically: the install is permanently unopenable.
        withV0Store { store ->
            store.seedV0()
            // FKs are OFF on this connection (no driver property), which is how the orphan arose.
            store.exec("DELETE FROM songs WHERE provider = 'saavn' AND song_id = '2'")
            assertEquals(
                1L,
                store.num("SELECT COUNT(*) FROM cached_files WHERE song_id = '2'"),
                "precondition: an orphaned cache row survived the song it pointed at",
            )
            store.close()
            store.reopen()

            assertEquals(Dylan.Schema.version, store.userVersion())
            assertEquals(
                listOf("1", "3"),
                store.rows("SELECT song_id FROM library ORDER BY song_id"),
                "the two songs survive; the orphan is dropped, not the migration",
            )
            assertEquals(0L, store.num("SELECT COUNT(*) FROM cached_files WHERE song_id = '2'"))
            assertTotals(store, bytes = 4_000L, count = 2L)
            // The rest of the chain still ran, i.e. this did not stop at the first failure: the
            // index below is the LAST statement in 1.sqm.
            // Every index 1.sqm creates, and none left over from v0: the chain is asserted by its
            // artefacts, so a chain that stopped halfway cannot pass by having run most of itself.
            assertEquals(
                listOf(
                    "idx_cached_lru",
                    "idx_cached_pinned",
                    "idx_favorites",
                    "idx_history",
                    "idx_history_song",
                    "idx_home_cache",
                    "idx_intent_priority",
                    "idx_media_state",
                    "idx_search_history",
                    "idx_songs_album",
                    "idx_songs_gc",
                ),
                store.rows("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%' ORDER BY name"),
                "the whole chain must have run",
            )
            assertEquals(3L, store.num("SELECT COUNT(*) FROM favorites"), "favourites are untouched by the copy")
            assertEquals(0L, store.num("SELECT priority FROM download_intents WHERE song_id = '1'"))
        }
    }

    /**
     * H4: the COPY is total against the new CHECKs, not only against the new foreign key.
     *
     * v0's `cached_files` enforced none of library's four CHECKs — that is the whole reason the
     * table is rebuilt rather than ALTERed — so a real v0 store can hold `bitrate = 0`, `ext = ''`,
     * `bytes < 0` or `play_count < 0`. Copied verbatim each of those raises a CHECK violation on
     * the INSERT, which aborts the chain *before* `DROP TABLE cached_files`: nothing is wiped,
     * `setUserVersion` never runs, and every later open re-runs the identical chain and fails
     * identically. With `DriverFactory.isCorrupt` now correctly refusing to wipe anything that is
     * not content corruption there is no longer any path that recovers it — a permanently
     * unopenable install.
     *
     * The pin repair shipped with the FK fix; these four did not. The assertion is the whole chain
     * (the index list below is the LAST statement in 1.sqm) plus the repaired values, so a clamp
     * that merely deleted the row instead would fail on the count.
     */
    @Test
    fun aV0StoreWithCheckViolatingRowsStillMigratesAndRepairsTheValues() {
        withV0Store { store ->
            store.seedV0()
            // One row per CHECK, all four in one v0 store: the point is that a *single* bad row is
            // enough to wedge the install, so they must all be repairable together.
            store.exec("UPDATE cached_files SET bitrate = 0 WHERE song_id = '1'")
            store.exec("UPDATE cached_files SET ext = '' WHERE song_id = '2'")
            store.exec("UPDATE cached_files SET bytes = -1, play_count = -5 WHERE song_id = '3'")
            store.close()
            store.reopen()

            assertEquals(Dylan.Schema.version, store.userVersion(), "the chain must have completed")
            assertEquals(
                listOf("1", "2", "3"),
                store.rows("SELECT song_id FROM library ORDER BY song_id"),
                "a clamp repairs the value; it must not drop the track",
            )
            assertEquals(
                listOf(
                    "saavn|1|1|m4a|1000|2",
                    "saavn|2|320|m4a|2000|0",
                    "saavn|3|128|mp3|0|0",
                ),
                store.rows(REPAIRED_SHAPE + " ORDER BY song_id"),
                "each CHECK-violating value is clamped to the nearest legal one, in place",
            )
            // media_objects is seeded from the repaired library rows, so it inherits the clamps
            // rather than re-reading the v0 columns.
            assertEquals(
                listOf("saavn|1|1|m4a|1000", "saavn|2|320|m4a|2000", "saavn|3|128|mp3|0"),
                store.rows(REPAIRED_ASSET_SHAPE + " ORDER BY song_id"),
            )
            // 1000 + 2000 + the clamped -1 -> 0.
            assertEquals(3_000L, store.num("SELECT final_bytes FROM cache_totals WHERE id = 0"))
            assertEquals(3L, store.num("SELECT final_count FROM cache_totals WHERE id = 0"))
            // The whole chain ran, not most of it: the last statement in 1.sqm is this index.
            assertTrue(
                store.rows("SELECT name FROM sqlite_master WHERE type = 'index'").contains("idx_intent_priority"),
                "the chain must have reached its last statement",
            )
            assertEquals(3L, store.num("SELECT COUNT(*) FROM favorites"), "favourites are untouched by the copy")
        }
    }

    @Test
    fun aStoreFromANewerBuildIsRefusedNotWiped() {
        withStore { store ->
            store.seedV0()
            store.reopen()
            store.exec("PRAGMA user_version = ${Dylan.Schema.version + 5}")
            store.close()
            val factory = DriverFactory(store.path, LogBuffer(), allowWipeOnCorruption = true)
            assertFailsWith<IllegalStateException> { factory.createDriver() }
            assertTrue(java.io.File(store.path).exists(), "a downgrade is not corruption")
        }
    }

    // ---- the v0 store, byte for byte -----------------------------------------------------------

    private fun Store.seedV0() {
        exec("DROP TRIGGER IF EXISTS trg_library_total_ins")
        exec("DROP TRIGGER IF EXISTS trg_library_total_del")
        exec("DROP TRIGGER IF EXISTS trg_library_total_upd")
        exec("DROP TRIGGER IF EXISTS trg_library_asset_ins")
        exec("DROP TRIGGER IF EXISTS trg_library_asset_upd")
        exec("DROP TRIGGER IF EXISTS trg_library_drop_assets")
        exec("DROP TRIGGER IF EXISTS trg_media_drop_library")
        exec("DROP TRIGGER IF EXISTS trg_media_ready_ins")
        exec("DROP TRIGGER IF EXISTS trg_media_ready_del")
        exec("DROP TRIGGER IF EXISTS trg_media_ready_upd")
        exec("DROP VIEW IF EXISTS cached_files")
        exec("DROP TABLE IF EXISTS library")
        exec("DROP TABLE IF EXISTS media_objects")
        exec("DROP TABLE IF EXISTS cache_totals")
        exec("DROP TABLE IF EXISTS protected_keys")
        // The pre-split shape, including its two indexes and its ON DELETE CASCADE.
        exec(
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
        exec("CREATE INDEX idx_cached_lru_unpinned ON cached_files(last_used_ms, bytes DESC) WHERE pinned = 0")
        exec("CREATE INDEX idx_cached_pinned ON cached_files(pinned_at_ms) WHERE pinned = 1")
        exec("DROP INDEX IF EXISTS idx_history_song")
        exec("DROP INDEX IF EXISTS idx_songs_gc")
        exec("DROP INDEX IF EXISTS idx_songs_album")
        exec("DROP INDEX IF EXISTS idx_home_cache")
        exec("DROP TRIGGER IF EXISTS trg_intent_priority_ins")
        exec("DROP TRIGGER IF EXISTS trg_intent_priority_upd")
        exec("ALTER TABLE download_intents RENAME TO download_intents_v1")
        exec(
            "CREATE TABLE download_intents (provider TEXT NOT NULL, song_id TEXT NOT NULL, " +
                "reason TEXT NOT NULL, bitrate INTEGER NOT NULL, enqueued_at_ms INTEGER NOT NULL, " +
                "PRIMARY KEY (provider, song_id))",
        )
        // The v0 table is 5 columns and the v1 one it is copied out of is 6, so the copy names its
        // columns: `SELECT *` is six values into a five-column table, which is a hard SQL error.
        exec(
            "INSERT INTO download_intents(provider, song_id, reason, bitrate, enqueued_at_ms) " +
                "SELECT provider, song_id, reason, bitrate, enqueued_at_ms FROM download_intents_v1",
        )
        exec("DROP TABLE download_intents_v1")
        exec("PRAGMA user_version = 0")
        for (id in listOf("1", "2", "3")) {
            exec(
                "INSERT INTO songs(provider, song_id, title, subtitle, art_url_150, art_url_500, duration_s, " +
                    "has_320, updated_at_ms) VALUES ('saavn', '$id', 't$id', 's', '', '', 100, 1, 1)",
            )
        }
        exec("INSERT INTO favorites VALUES ('saavn','1',5), ('saavn','2',6), ('saavn','3',7)")
        exec(
            "INSERT INTO play_history(provider, song_id, played_at_ms) VALUES " +
                "('saavn','1',11), ('saavn','2',12), ('saavn','1',13), ('saavn','3',14)",
        )
        // The two pin shapes v0 permitted: pinned with no timestamp, unpinned with one.
        exec("INSERT INTO cached_files VALUES ('saavn','1',128,'m4a',1000,10,100,2,1,50)")
        exec("INSERT INTO cached_files VALUES ('saavn','2',320,'m4a',2000,20,200,0,0,777)")
        exec("INSERT INTO cached_files VALUES ('saavn','3',128,'mp3',3000,30,NULL,0,1,NULL)")
        exec("INSERT INTO download_intents VALUES ('saavn','1','USER_NOW',128,1)")
    }

    // ---- harness --------------------------------------------------------------------------------

    private class Store(
        val path: String,
        var driver: SqlDriver,
    ) {
        fun close() = driver.close()

        fun reopen() {
            driver.close()
            driver = DriverFactory(path, LogBuffer()).createDriver()
        }

        fun exec(sql: String) {
            driver.execute(null, sql, 0)
        }

        fun text(sql: String): String = rows(sql).singleOrNull() ?: ""

        /** Every row's first column, in order — a one-column projection of a multi-row query. */
        fun rows(sql: String): List<String> = driver.queryStrings(sql)

        fun num(sql: String): Long = scalarLong(sql)

        fun scalarLong(sql: String): Long = driver.scalarLong(sql)

        fun userVersion(): Long = scalarLong("PRAGMA user_version")

        fun pragmaInt(sql: String): Long = scalarLong(sql)

        fun plan(sql: String): List<String> = driver.queryPlan("EXPLAIN QUERY PLAN $sql")

        fun seedSong(id: String) {
            exec(
                "INSERT OR IGNORE INTO songs(provider, song_id, title, subtitle, art_url_150, art_url_500, " +
                    "duration_s, has_320, updated_at_ms) VALUES ('saavn','$id','t','s','','',100,1,1)",
            )
        }
    }

    private fun withStore(block: (Store) -> Unit) {
        val dir = "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/dylan-mig-${System.nanoTime()}"
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        val path = "$dir/dylan.db"
        try {
            block(Store(path, DriverFactory(path, LogBuffer()).createDriver()))
        } finally {
            runCatching { FileSystem.SYSTEM.deleteRecursively(dir.toPath()) }
        }
    }

    /**
     * A store built WITHOUT the `foreign_keys` connection property — which is what the JVM factory
     * used to do, and what a v0 Android store effectively was, since the pragma was issued after
     * the driver had already run `create`/`migrate`. Foreign-key enforcement is therefore off for
     * the whole seed, which is the only way a `cached_files` row can outlive its song. Reopened
     * through [DriverFactory], i.e. with foreign keys ON.
     */
    private fun withV0Store(block: (Store) -> Unit) {
        val dir = "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/dylan-v0-${System.nanoTime()}"
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        val path = "$dir/dylan.db"
        try {
            val raw = JdbcSqliteDriver("jdbc:sqlite:$path")
            Dylan.Schema.create(raw)
            block(Store(path, raw))
        } finally {
            runCatching { FileSystem.SYSTEM.deleteRecursively(dir.toPath()) }
        }
    }

    private companion object {
        const val PIN_SHAPE =
            "SELECT provider || '|' || song_id || '|' || pinned || '|' || COALESCE(pinned_at_ms, '') FROM library"
        const val LIBRARY_SHAPE =
            "SELECT provider || '|' || song_id || '|' || bitrate || '|' || ext || '|' || bytes || '|' || " +
                "cached_at_ms || '|' || COALESCE(last_used_ms, '') || '|' || play_count || '|' || pinned || '|' || " +
                "COALESCE(pinned_at_ms, '') FROM library"

        /** The four columns 1.sqm clamps, so one row string carries all four repairs. */
        const val REPAIRED_SHAPE =
            "SELECT provider || '|' || song_id || '|' || bitrate || '|' || ext || '|' || bytes || " +
                "'|' || play_count FROM library"

        /** The same four columns on the per-rendition table seeded from the repaired rows. */
        const val REPAIRED_ASSET_SHAPE =
            "SELECT provider || '|' || song_id || '|' || bitrate || '|' || ext || '|' || bytes FROM media_objects"
    }

    private fun assertTotals(
        store: Store,
        bytes: Long,
        count: Long,
    ) {
        assertEquals(count, store.scalarLong("SELECT final_count FROM cache_totals WHERE id = 0"), "final_count")
        assertEquals(bytes, store.scalarLong("SELECT final_bytes FROM cache_totals WHERE id = 0"), "final_bytes")
        val figures = Dylan(store.driver).dylanQueries.cachedCountAndBytes().executeAsOne()
        assertEquals(count, figures.song_count, "cachedCountAndBytes count")
        assertEquals(bytes, figures.total_bytes, "cachedCountAndBytes bytes")
    }
}

/**
 * The mapper runs once per row, so the whole result set has to be drained inside it; reading one
 * row and stopping is what made `rows()` return a single String.
 */
private fun SqlDriver.queryStrings(sql: String): List<String> =
    executeQuery(
        null,
        sql,
        { c ->
            val out = ArrayList<String>()
            while (c.next().value) {
                out += c.getString(0) ?: ""
            }
            QueryResult.Value(out)
        },
        0,
        null,
    ).value

/** -1L is "no row", 0L is a real zero: the totals assertions depend on telling those apart. */
private fun SqlDriver.scalarLong(sql: String): Long =
    executeQuery(
        null,
        sql,
        { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else -1L) },
        0,
        null,
    ).value

/**
 * One `id|parent|notused|detail` line per plan row. The plan *text* is the LAST column: reading
 * column 0 (the integer id) would make "the query must not use a TEMP B-TREE" vacuously true.
 */
private fun SqlDriver.queryPlan(sql: String): List<String> =
    executeQuery(
        null,
        sql,
        { c ->
            val out = ArrayList<String>()
            while (c.next().value) {
                out += (0..PLAN_COLUMNS).mapNotNull { c.getString(it) }.joinToString("|")
            }
            QueryResult.Value(out)
        },
        0,
        null,
    ).value

private const val PLAN_COLUMNS = 3
