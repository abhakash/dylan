package dylan.fuzz

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dylan.db.Dylan
import dylan.support.MutableClock
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **M2 — the v0 → current migration, and finding F-1.** `docs/testing/fuzzy-e2e-plan.md` §7.9.
 *
 * Everything here goes through the *production* open path (`DriverFactory`), never around it, because
 * the property under test is a property of the open: the pragmas, the `user_version` policy and the
 * `applyMigrations` branch are all part of what is being verified.
 *
 * Two of these scenarios are not "does it work" tests. `aKillDuringTheMigrationLeavesAStoreThatCannotBeReopened`
 * is a **characterisation test**: it asserts that F-1 is real, so the day somebody wraps
 * `applyMigrations` in a transaction the test goes red and says "this finding is now stale" instead of
 * quietly ceasing to describe reality. `aRolledBackMigrationLeavesAStoreThatReopensCleanly` is the
 * other half: it is the fix, executed, and it is green today.
 */
class M2MigrationTest {
    // ---- S-MIG-01 ----------------------------------------------------------------------------------

    /**
     * S-MIG-01 — a fresh store lands on the schema version with foreign keys on, and every object the
     * app reads exists. The first half is INV-01/INV-02 over the real factory rather than a driver a
     * test opened, which is the difference that matters: the JVM factory supplies `foreign_keys` as a
     * *connection property* because statements do not survive its connection manager.
     *
     * MUTATION: `DriverFactory.jvm.kt:54` drop the `foreign_keys` property. `verify()` still passes on
     * the connection that opened it, so this test is the one that notices.
     */
    @Test
    fun aFreshStoreLandsOnTheSchemaVersionWithForeignKeysOnAndEveryObjectPresent() {
        V0StoreBuilder.build("mig01").use { handle ->
            val driver = handle.open()
            try {
                val sql = Sql(driver)
                assertEquals(SCHEMA_VERSION, sql.userVersion(), "a fresh store must land on the schema version")
                assertEquals(1L, sql.pragmaInt("PRAGMA foreign_keys"), "ON DELETE CASCADE is a no-op otherwise")
                for (name in SCHEMA_OBJECTS) {
                    assertEquals(
                        1L,
                        sql.scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE name = '$name'"),
                        "missing $name",
                    )
                }
                assertEquals(
                    emptySet(),
                    MIGRATION_INDEXES - sql.indexes().toSet(),
                    "every index 1.sqm / the schema declares must be present",
                )
            } finally {
                driver.close()
            }
        }
    }

    // ---- S-MIG-02 ----------------------------------------------------------------------------------

    /**
     * S-MIG-02 — a clean v0 store migrates field for field. The projection is named column by column,
     * so a `SELECT *` that shifted a value cannot be mistaken for a passing migration, and the pin
     * repair is the *only* difference the migration is allowed to make to the data.
     *
     * MUTATION: `1.sqm:73` `MAX(1, bitrate)` → `bitrate`. Nothing in the clean spec trips it, which
     * is why S-MIG-04 exists; here it would show up as `saavn|1|0|…`.
     */
    @Test
    fun aCleanV0StoreMigratesFieldForField() {
        V0StoreBuilder.build("mig02").use { handle ->
            val driver = handle.open()
            try {
                val sql = Sql(driver)
                assertEquals(SCHEMA_VERSION, sql.userVersion())
                assertEquals(
                    listOf(
                        "saavn|1|128|m4a|1000|10|100|2|1|50",
                        "saavn|2|320|m4a|2000|20|200|0|0|",
                        // v0 allowed pinned=1 with a NULL pinned_at_ms; the repair gives it cached_at_ms.
                        "saavn|3|128|mp3|3000|30||0|1|30",
                    ),
                    sql.rows(LIBRARY_SHAPE + " ORDER BY song_id"),
                    "v0 field for field, in library's column order",
                )
                // The pre-split reader surface still answers, with the same shape.
                assertEquals(3L, sql.scalarLong("SELECT COUNT(*) FROM cached_files"))
                assertEquals(3L, sql.scalarLong("SELECT COUNT(*) FROM media_objects WHERE state = 'READY'"))
                assertEquals(
                    6_000L,
                    sql.scalarLong(
                        "SELECT final_bytes FROM " +
                            "cache_totals WHERE id = 0",
                    ),
                    "totals seeded from the copy",
                )
                assertEquals(3L, sql.scalarLong("SELECT final_count FROM cache_totals WHERE id = 0"))
                assertEquals(
                    0L,
                    sql.scalarLong(
                        "SELECT COUNT(*) FROM media_objects " +
                            "WHERE state <> 'READY'",
                    ),
                    "the pre-split state machine starts at READY",
                )
            } finally {
                driver.close()
            }
        }
    }

    // ---- S-MIG-03 ----------------------------------------------------------------------------------

    /**
     * S-MIG-03 — a v0 store holding a row whose song is gone still migrates, and the *whole* chain
     * ran. The orphan is possible only because v0's connection had the cascade off part of the time;
     * copied verbatim it would violate the new `library → songs` foreign key and abort the chain
     * before `DROP TABLE cached_files`, i.e. before anything is wiped and before the version is set.
     *
     * The index list is the assertion that the chain finished rather than stopping at the first
     * failure: `idx_intent_priority` is the *last* statement in `1.sqm`.
     *
     * MUTATION: **G-11** — `1.sqm:53-55` delete the orphan `DELETE`. Then the migration aborts on the
     * COPY and this test fails at `userVersion()`, not at the row list, which is the point of asserting
     * both.
     */
    @Test
    fun aV0StoreWithAnOrphanRowStillMigrates() {
        val spec = V0StoreBuilder.Spec(orphanSongId = "2")
        V0StoreBuilder.build("mig03", spec).use { handle ->
            val driver = handle.open()
            try {
                val sql = Sql(driver)
                assertEquals(SCHEMA_VERSION, sql.userVersion())
                assertEquals(
                    listOf("1", "3"),
                    sql.rows("SELECT song_id FROM library ORDER BY song_id"),
                    "the two songs survive; the orphan is dropped, not the migration",
                )
                assertEquals(4_000L, sql.scalarLong("SELECT final_bytes FROM cache_totals WHERE id = 0"))
                assertEquals(2L, sql.scalarLong("SELECT final_count FROM cache_totals WHERE id = 0"))
                assertEquals(
                    emptySet(),
                    MIGRATION_INDEXES - sql.indexes().toSet(),
                    "the whole chain ran",
                )
                assertEquals(
                    emptySet(),
                    sql.indexes().filter { it.startsWith("idx_cached_lru_unpinned") }.toSet(),
                    "and no v0 index survived it",
                )
                assertEquals(
                    spec.songIds.size.toLong(),
                    sql.scalarLong("SELECT COUNT(*) FROM favorites"),
                    "favourites are untouched by the copy — including the orphaned song's",
                )
            } finally {
                driver.close()
            }
        }
    }

    // ---- S-MIG-04 ----------------------------------------------------------------------------------

    /**
     * S-MIG-04 — hostile v0 rows must not abort the migration.
     *
     * **The plan predicted this scenario would be RED, and it is not.** §7.9 says `1.sqm:49-53` copies
     * `bytes`, `bitrate`, `ext` and `play_count` "verbatim" into a table with four new CHECKs, and
     * recommends extending the orphan guard at `1.sqm:45-47` to cover them. The file in the tree
     * already clamps all four (`MAX(1, bitrate)`, `CASE WHEN ext = '' THEN 'm4a'`, `MAX(0, bytes)`,
     * `MAX(0, play_count)`, `1.sqm:71-79`), and
     * `CacheMigrationTest.aV0StoreWithCheckViolatingRowsStillMigratesAndRepairsTheValues`
     * already covers it. The plan is stale on this point; the finding (H4) has been fixed and this
     * scenario is the regression guard, not a canary. Recorded rather than quietly dropped, because a
     * plan that says "expected red" and a suite that says "green" is a discrepancy somebody has to
     * resolve.
     *
     * MUTATION: `1.sqm:74` `MAX(0, bytes)` → `bytes`. Then a `-1` byte row raises a CHECK violation on
     * the COPY, the chain aborts before `DROP TABLE cached_files`, `user_version` is never advanced,
     * and this test fails at the *first* assertion — which is the F-1 failure mode reached by a
     * different door.
     */
    @Test
    fun v0HostileRowsAreClampedRatherThanAbortingTheMigration() {
        val spec = V0StoreBuilder.Spec(hostile = true)
        V0StoreBuilder.build("mig04", spec).use { handle ->
            val driver = handle.open()
            try {
                val sql = Sql(driver)
                assertEquals(SCHEMA_VERSION, sql.userVersion(), "the chain must have completed")
                assertEquals(
                    listOf("1", "2", "3"),
                    sql.rows("SELECT song_id FROM library ORDER BY song_id"),
                    "a clamp repairs the value; it must not drop the track",
                )
                assertEquals(
                    listOf(
                        "saavn|1|1|m4a|1000|2",
                        "saavn|2|320|m4a|2000|0",
                        "saavn|3|128|mp3|0|0",
                    ),
                    sql.rows(REPAIRED_SHAPE + " ORDER BY song_id"),
                    "each CHECK-violating value is clamped to the nearest legal one, in place",
                )
                // `media_objects` is seeded from the repaired rows, so it inherits the clamps.
                assertEquals(
                    listOf("saavn|1|1|m4a|1000", "saavn|2|320|m4a|2000", "saavn|3|128|mp3|0"),
                    sql.rows(REPAIRED_ASSET_SHAPE + " ORDER BY song_id"),
                )
                assertEquals(
                    3_000L,
                    sql.scalarLong(
                        "SELECT final_bytes FROM " +
                            "cache_totals WHERE id = 0",
                    ),
                    "1000 + 2000 + the clamped -1 -> 0",
                )
                assertEquals(
                    emptySet(),
                    MIGRATION_INDEXES - sql.indexes().toSet(),
                    "the chain must have reached its last statement",
                )
            } finally {
                driver.close()
            }
        }
    }

    // ---- S-MIG-05 ----------------------------------------------------------------------------------

    /**
     * S-MIG-05 — a v0 store whose audio directory holds a `.part` and its sidecar but whose table has
     * no row for it: the shape the reconciler re-enqueues on the next boot. A fresh part survives; one
     * past `partGraceHours` is swept. This is journey 5's "the reconciler re-enqueues `.part` files from
     * previous processes" stated as a migration property, because a migration that dropped the sidecar
     * would make every one of those transfers restart from zero.
     *
     * MUTATION: `1.sqm` untouched by this — so the honest mutation is in the reconciler:
     * `Reconciler.fullSweep` delete the `file !in pendingParts` guard. Then the fresh `.part` is
     * reclaimed immediately and this test is red on its first assertion.
     */
    @Test
    fun aV0PartWithASidecarAndNoRowSurvivesTheMigrationAndIsSweptOnlyWhenStale() =
        runBlocking {
            val clock = MutableClock()
            val cfg = dylan.config.AppConfig(clock = clock)
            V0StoreBuilder.build("mig05").use { handle ->
                val fs = FileSystem.SYSTEM
                val audioDir = V0StoreBuilder.audioDir(handle)
                fs.createDirectories(audioDir)
                val part = audioDir / "saavn_resume_128.part"
                fs.write(part) { write(ByteArray(700) { 1 }) }
                java.io.File(part.toString()).setLastModified(clock.nowMs())

                val driver = handle.open()
                try {
                    // Migration first: the sidecar is not in the database at all, so nothing in the
                    // chain can see it, and that is the point — it is bytes on disk that must survive.
                    val sql = Sql(driver)
                    assertEquals(SCHEMA_VERSION, sql.userVersion())
                    assertEquals(
                        0L,
                        sql.scalarLong(
                            "SELECT COUNT(*) FROM download_intents " +
                                "WHERE song_id = 'resume'",
                        ),
                        "no intent row for it",
                    )

                    val reconciler = V0StoreBuilder.reconcilerOver(driver, fs, handle.root, cfg)
                    reconciler.run()
                    assertTrue(fs.exists(part), "a fresh .part is inside the grace window and must survive")

                    clock.advanceMs(FULL_SWEEP_INTERVAL_MS + 120_000)
                    java.io.File(part.toString()).setLastModified(clock.nowMs() - 61 * 60_000)
                    V0StoreBuilder.reconcilerOver(driver, fs, handle.root, cfg).run()
                    assertFalse(fs.exists(part), "a 61-minute-old .part with no intent row is past the window")
                } finally {
                    driver.close()
                }
            }
        }

    // ---- S-MIG-06 — finding F-1 --------------------------------------------------------------------

    /**
     * **S-MIG-06 / F-1 — a process death between two migration statements leaves a store that can never
     * be opened again.** A characterisation test: it asserts the *defect*, so that fixing it turns
     * this red instead of leaving a suite that has quietly stopped describing reality.
     *
     * How the death is produced, and why it is faithful rather than a mock:
     * `Dylan.Schema.migrate` is the *real* generated function, run against the *real* JDBC driver over
     * the *real* file. The only change is that a [DyingDriver] throws a plain `RuntimeException` on
     * execute number *k+1* — which is exactly what a `SIGKILL` between statement *k* and *k+1* does to
     * the code that was running: the statements already issued are committed (each is its own
     * autocommit transaction), and nothing after the kill point ever runs. Crucially `user_version`
     * is **not** set, because `DriverFactory` sets it *after* `migrate` returns and `migrate` never
     * returned.
     *
     * The consequences asserted below are the ones a user would meet:
     *  * the next open **throws**, and throws the same way every time;
     *  * `user_version` is still the pre-migration value, so the next open takes the
     *    `from < target` branch and re-runs the chain from statement 1 — `CREATE TABLE library` —
     *    which now exists;
     *  * the file is **not** wiped, even with `allowWipeOnCorruption = true`, because
     *    `PRAGMA integrity_check` answers "ok": this is a healthy file at a half-applied schema.
     *
     * MUTATION (the fix): wrap `DriverFactory.applyMigrations` in `Dylan(driver).transaction { }`. Then
     * every one of these assertions inverts and this test is red — and
     * `aRolledBackMigrationLeavesAStoreThatReopensCleanly` is the green half of the same change.
     */
    @Test
    fun aKillDuringTheMigrationLeavesAStoreThatCannotBeReopened() {
        val statements = countMigrationStatements()
        assertTrue(statements > 1, "the chain must be more than one statement to be interruptible, was $statements")

        val recovered = ArrayList<Int>()
        val wedged = ArrayList<Int>()
        for (k in 0..statements) {
            V0StoreBuilder.build("mig06").use { handle ->
                val raw = handle.raw()

                // The caught exception IS the signal under test — a simulated process death is the
                // input, not an error — so it is not swallowed, it is recorded as `died`.
                @Suppress("SwallowedException")
                val died =
                    try {
                        Dylan.Schema.migrate(DyingDriver(raw, dieAt = k + 1), 0L, SCHEMA_VERSION)
                        false
                    } catch (e: SimulatedProcessDeath) {
                        true
                    } finally {
                        raw.close()
                    }
                // The control: the death landed exactly where it was aimed, so "k statements applied"
                // is a fact and not a hope. k == statements aims past the end of the chain, so there
                // is nothing to die before — and that case is the one that *did* finish, yet is still
                // wedged below, because `user_version` is only written after `migrate` returns.
                assertEquals(k < statements, died, "k=$k: the death must land exactly where it was aimed")

                val bytesBefore = java.io.File(handle.path).length()
                val first = runCatching { handle.open().also { it.close() } }
                if (first.isSuccess) {
                    recovered += k
                    assertEquals(0, k, "recovering after a death is only possible at k=0 — that is the whole finding")
                } else {
                    wedged += k
                    // …and it is permanent: a second attempt fails identically.
                    val second = runCatching { handle.open() }
                    assertTrue(second.isFailure, "k=$k: a wedged store must fail on every open, not just the first")
                    assertTrue(
                        java.io.File(handle.path).exists(),
                        "k=$k: the user's file must still be there — the failure is not a wipe",
                    )
                    assertTrue(
                        java.io.File(handle.path).length() > 0,
                        "k=$k: and it must not have been truncated (was $bytesBefore before the second open)",
                    )
                    // Not even the opt-in wipes it: the bytes are a *healthy* file at a half-applied schema.
                    val wiped = runCatching { handle.open(allowWipeOnCorruption = true) }
                    assertTrue(wiped.isFailure, "k=$k: integrity_check is 'ok', so even the opt-in must refuse to wipe")
                    assertTrue(java.io.File(handle.path).exists(), "k=$k: the file survived even the opt-in")
                }
            }
        }
        assertEquals(listOf(0), recovered, "only an uninterrupted migration recovers")
        assertEquals((1..statements).toList(), wedged, "every other kill point is terminal")
    }

    /**
     * The other half of F-1, and the fix **executed**: the same real chain, run inside the driver's own
     * transaction and then rolled back. The store is left byte-for-byte as a v0 store, so the next
     * open migrates it from scratch and succeeds.
     *
     * This is what makes the previous test actionable rather than merely a complaint: the one-line
     * change the plan recommends (`Dylan(driver).transaction { … }` around `applyMigrations`) is
     * demonstrated here against the real chain, and the `Sql.inTransaction` helper it reuses is the
     * helper `Sql.rolledBack` is built on.
     */
    @Test
    fun aRolledBackMigrationLeavesAStoreThatReopensCleanly() {
        val statements = countMigrationStatements()
        for (k in 1..statements) {
            V0StoreBuilder.build("mig06fix").use { handle ->
                val raw = handle.raw()
                try {
                    // `dieAt = k + 1` makes the same driver the death simulation, and the transaction is
                    // what turns that death from permanent into nothing at all.
                    Sql(raw).rolledBack {
                        Dylan.Schema.migrate(DyingDriver(raw, dieAt = k + 1), 0L, SCHEMA_VERSION)
                    }
                } catch (_: SimulatedProcessDeath) {
                    // Nothing to do: the transaction's rollback is what removed it, and that is the
                    // property under test.
                } finally {
                    raw.close()
                }
                val driver = handle.open()
                try {
                    assertEquals(
                        SCHEMA_VERSION,
                        Sql(driver).userVersion(),
                        "k=$k: with the migration atomic, every kill point is recoverable",
                    )
                } finally {
                    driver.close()
                }
            }
        }
    }

    /** How many statements `1.sqm` actually issues, measured rather than quoted from the plan. */
    private fun countMigrationStatements(): Int {
        V0StoreBuilder.build("migcount").use { handle ->
            val raw = handle.raw()
            return try {
                val counter = CountingDriver(raw)
                Dylan.Schema.migrate(counter, 0L, SCHEMA_VERSION)
                counter.count
            } finally {
                raw.close()
            }
        }
    }

    // ---- S-MIG-07/08/09 ----------------------------------------------------------------------------

    /**
     * S-MIG-07 — a store stamped at a version the current build does not know is **refused, not
     * wiped**, and `v1` (the version Android/iOS write, with v0 content) still migrates. The `from <
     * target` branch is what makes the second half true, and it is the branch F-1 lands in.
     *
     * MUTATION: `DriverFactory.isCorrupt()` return `true` unconditionally. Then the opt-in path
     * deletes the file, and this test fails on "the user's file must still be there".
     */
    @Suppress("NestedBlockDepth")
    @Test
    fun aStoreStampedAtTheWrongVersionIsRefusedNotWipedAndV1StillMigrates() {
        V0StoreBuilder.build("mig07", V0StoreBuilder.Spec(userVersion = SCHEMA_VERSION_MIGRATED_FROM)).use { handle ->
            val driver = handle.open()
            try {
                assertEquals(SCHEMA_VERSION, Sql(driver).userVersion(), "v1 content must still run the chain")
            } finally {
                driver.close()
            }
            for (ahead in listOf(SCHEMA_VERSION + 1, SCHEMA_VERSION + 5)) {
                V0StoreBuilder.build("mig07b").use { newer ->
                    val setup = newer.raw()
                    try {
                        Dylan.Schema.migrate(setup, 0L, SCHEMA_VERSION)
                        setup.execute(null, "PRAGMA user_version = $ahead", 0)
                    } finally {
                        setup.close()
                    }
                    val file = java.io.File(newer.path)
                    val length = file.length()
                    assertFailsWith<IllegalStateException> { newer.open(allowWipeOnCorruption = true) }
                    assertTrue(file.exists(), "a downgrade is not corruption")
                    assertEquals(length, file.length(), "and the file must be byte-for-byte untouched")
                }
            }
        }
    }

    /**
     * S-MIG-08 — an unknown v0 intent `reason` is normalised, not rejected. v0's CASE mapped every
     * unknown reason to rank 3, so `QUALITY_UPGRADE` preserves both the ordering the old scheduler
     * computed and the new CHECK.
     *
     * MUTATION: `1.sqm:233-234` copy `reason` through unchanged. Then the INSERT raises a CHECK
     * violation, the chain aborts before its last statement, and this test fails at `userVersion()`.
     */
    @Test
    fun anUnknownIntentReasonIsNormalisedNotRejected() {
        V0StoreBuilder.build("mig08", V0StoreBuilder.Spec(unknownReason = true)).use { handle ->
            val driver = handle.open()
            try {
                val sql = Sql(driver)
                assertEquals(SCHEMA_VERSION, sql.userVersion())
                assertEquals(
                    "QUALITY_UPGRADE|3",
                    sql.text("SELECT reason || '|' || priority FROM download_intents WHERE song_id = '1'"),
                    "normalised to the only wire value that both satisfies the CHECK and keeps v0's rank",
                )
                assertEquals(
                    0L,
                    sql.scalarLong(
                        "SELECT COUNT(*) FROM download_intents WHERE priority <> (CASE reason " +
                            "WHEN 'USER_NOW' THEN 0 WHEN 'USER_BULK' THEN 1 WHEN 'PREFETCH_NEXT' THEN 2 ELSE 3 END)",
                    ),
                    "INV-32 holds after a migration, not only on a fresh store",
                )
            } finally {
                driver.close()
            }
        }
    }

    /**
     * S-MIG-09 — migrating is idempotent. Five re-opens must change nothing, and the *evidence* is that
     * no index was rebuilt rather than only that the rows still read the same: a chain that stopped
     * halfway also leaves the right rows for everything it copied before it died.
     *
     * MUTATION: `DriverFactory.applyMigrations` drop the `from == target` guard so `Schema.migrate` is
     * called unconditionally. Then re-opening re-runs `CREATE TABLE library` and the second open
     * throws — which is the F-1 failure mode reached from a healthy store.
     */
    @Test
    fun migratingTwiceIsANoOp() {
        V0StoreBuilder.build("mig09").use { handle ->
            var shape = ""
            repeat(5) { i ->
                val driver = handle.open()
                try {
                    val sql = Sql(driver)
                    assertEquals(SCHEMA_VERSION, sql.userVersion(), "open #$i")
                    val now = sql.text(ENTIRE_STORE_SHAPE)
                    if (i == 0) shape = now else assertEquals(shape, now, "open #$i changed the store")
                    assertEquals(
                        emptySet(),
                        MIGRATION_INDEXES - sql.indexes().toSet(),
                        "open #$i: every index is still there, so nothing was dropped and rebuilt",
                    )
                    assertEquals(
                        emptyList(),
                        sql.triggers().filter { !it.startsWith("trg_") },
                        "open #$i: no stray trigger",
                    )
                } finally {
                    driver.close()
                }
            }
        }
    }

    private companion object {
        const val LIBRARY_SHAPE =
            "SELECT provider || '|' || song_id || '|' || bitrate || '|' || ext || '|' || bytes || '|' || " +
                "cached_at_ms || '|' || COALESCE(last_used_ms, '') || '|' || play_count || '|' || pinned || '|' || " +
                "COALESCE(pinned_at_ms, '') FROM library"

        const val REPAIRED_SHAPE =
            "SELECT provider || '|' || song_id || '|' || bitrate || '|' " +
                "|| ext || '|' || bytes || '|' || play_count FROM library"

        const val REPAIRED_ASSET_SHAPE =
            "SELECT provider || '|' || song_id || '|' || bitrate || '|' || ext || '|' || bytes FROM media_objects"

        /** Every table's row count plus the two totals, so "nothing changed" is a real statement. */
        const val ENTIRE_STORE_SHAPE =
            "SELECT (SELECT COUNT(*) FROM songs) || '/' || (SELECT COUNT(*) FROM library) || '/' || " +
                "(SELECT COUNT(*) FROM media_objects) || '/' || (SELECT COUNT(*) FROM favorites) || '/' || " +
                "(SELECT COUNT(*) FROM play_history) || '/' || (SELECT COUNT(*) FROM download_intents) || '/' || " +
                "(SELECT final_bytes FROM cache_totals WHERE id = 0) || " +
                "'/' || (SELECT final_count FROM cache_totals WHERE id = 0)"

        val SCHEMA_OBJECTS =
            listOf(
                "songs",
                "library",
                "media_objects",
                "cache_totals",
                "protected_keys",
                "favorites",
                "play_history",
                "search_history",
                "settings",
                "home_cache",
                "download_intents",
                "cached_files",
            )

        val MIGRATION_INDEXES =
            setOf(
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
            )

        /** `Reconciler.FULL_SWEEP_INTERVAL_MS`, private there and owned by a change in flight. */
        const val FULL_SWEEP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * Counts `execute` calls, so "the chain is N statements" is measured rather than quoted from the plan
 * (the plan says 33; nothing in the code says so, and the number is a property of the generated file).
 */
private class CountingDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var count = 0
        private set

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        count++
        return delegate.execute(identifier, sql, parameters, binders)
    }
}

/**
 * A delegating driver that stops the world on execute number [dieAt].
 *
 * [SimulatedProcessDeath] is a plain `RuntimeException`, not a `SQLException`, deliberately: a real
 * process death is not an error the driver can report or the factory can classify, it is the code
 * simply stopping. Anything the driver or `DriverFactory` would translate it into would make the
 * simulation softer than the thing it stands for.
 */
private class DyingDriver(
    private val delegate: SqlDriver,
    private val dieAt: Int,
) : SqlDriver by delegate {
    private var seen = 0

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (++seen == dieAt) throw SimulatedProcessDeath()
        return delegate.execute(identifier, sql, parameters, binders)
    }
}

/** "The process stopped here." */
private class SimulatedProcessDeath : RuntimeException("simulated process death")
