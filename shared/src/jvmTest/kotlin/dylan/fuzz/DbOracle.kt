package dylan.fuzz

import app.cash.sqldelight.db.SqlDriver
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.download.PartMeta
import dylan.util.Clock
import okio.FileSystem

/**
 * **M1 — the oracle.** Reads the store through its own connection and asserts the same
 * invariants the app does, from outside it.
 *
 * One object, one job: given a real SQLite file and the real audio directory, say what "correct"
 * means. It writes nothing except inside a transaction it rolls back (the CONSTRAINT group), so its
 * verdict does not depend on how many times it ran — which is what lets every scenario call
 * [check] after every step instead of once at the end.
 *
 * Three design decisions that are load-bearing rather than stylistic:
 *
 * 1. **Scope, not skipping.** A condition the ledger suspended is not evaluated. It is reported in
 *    [lastSkipped] with the reason, so "held", "not asked" and "held because the test broke the
 *    world" are three distinguishable answers. An oracle that quietly passed a suspended check is the
 *    red-herring generator the plan warns about.
 * 2. **Procedural conditions are conditions.** Fifteen of the catalogue's post-conditions are about
 *    rows *and* files *and* sidecars at once and cannot be a SQL statement. They are first-class
 *    members of the same catalogue, run at the same points, and skipped under exactly the same
 *    rules, so there is no way to accidentally assert only the SQL half of a two-part property.
 * 3. **Controls travel with the check.** Every condition that could pass vacuously — a constraint
 *    that might not exist, a plan that might not sort, a `COALESCE` that might hide a missing row —
 *    carries the query or write that proves the check is live. See [PlanCondition].
 *
 * **What the catalogue does *not* contain, and why.** The conditions that need a live
 * `DownloadEngine` (`INV-31`, `INV-33`, `INV-35`, `INV-36`, `INV-42`), a live eviction
 * (`INV-17`), a `Reconciler.run()` between two reads (`INV-16b`, `INV-23`, `INV-24`) or a resume
 * across the wire (`INV-29`, `INV-30`) are scenarios, not invariants: they are statements about a
 * *transition*, and an oracle invoked inside the transition could only ever see one side of it.
 * Putting them here would mean an oracle that had to be told when to lie.
 */
class DbOracle(
    private val driver: SqlDriver,
    private val fs: FileSystem,
    private val paths: Paths,
    private val cfg: AppConfig,
    private val clock: Clock,
    private val ledger: EnvironmentLedger = EnvironmentLedger(),
) {
    val sql = Sql(driver)

    /** Everything, in report order. Built once: the catalogue is a constant. */
    val catalogue: List<PostCondition> = buildCatalogue()

    fun byId(id: String): PostCondition? = catalogue.firstOrNull { it.id == id }

    /** Conditions that exist but were not run, with the reason each was not. */
    data class Skipped(
        val id: String,
        val why: String,
    )

    /** What the last [check]/[checkOnly] did *not* run. Never inferred; see the class KDoc. */
    var lastSkipped: List<Skipped> = emptyList()
        private set

    /**
     * Run every condition in scope and return what failed. An empty list is the only pass.
     *
     * [quiesced] is asserted by the caller, never inferred: the design deliberately permits an
     * `EVICTING` row to outlive its library row by one pass, so a mid-flight check that flagged it
     * would be reporting the design. Conditions scoped to a quiesced point are skipped instead.
     */
    fun check(
        step: String,
        quiesced: Boolean = true,
    ): List<Violation> = run(step, quiesced, catalogue)

    /** A subset, for a hot path that only cares about one family. Same scope rules. */
    fun checkOnly(
        ids: List<String>,
        step: String,
        quiesced: Boolean = true,
    ): List<Violation> {
        val wanted = ids.mapNotNull { id -> byId(id) }
        missingIds = (ids - wanted.map { it.id }).toSet()
        return run(step, quiesced, wanted)
    }

    private var missingIds: Set<String> = emptySet()

    private fun run(
        step: String,
        quiesced: Boolean,
        conditions: List<PostCondition>,
    ): List<Violation> {
        val ctx = context(step, quiesced)
        val out = ArrayList<Violation>()
        val skipped = ArrayList<Skipped>()
        val suspended = ledger.suspendedBy()
        for (c in conditions) {
            // A suspended condition and a quiesced-only condition are both "not applicable
            // right now", and each is recorded as a skip rather than a violation - that
            // distinction is the oracle's whole point.
            val skipWhy =
                suspended[c.id]?.let { "suspended by the ledger: $it" }
                    ?: if (c.quiescedOnly && !quiesced) "only meaningful at a quiesced point" else null
            if (skipWhy != null) {
                skipped += Skipped(c.id, skipWhy)
            } else {
                c.check(ctx)?.let { out += it }
            }
        }
        lastSkipped = skipped
        return out
    }

    /** Ids [checkOnly] was asked about and the catalogue does not have. Must be empty. */
    fun unknownIds(): Set<String> = missingIds.also { missingIds = emptySet() }

    private fun context(
        step: String,
        quiesced: Boolean,
    ) = CheckContext(
        step = step,
        quiesced = quiesced,
        sql = sql,
        fs = fs,
        paths = paths,
        cfg = cfg,
        clock = clock,
        fsSnapshot = FsSnapshot.read(fs, paths.audioDir),
        ledger = ledger,
    )

    // ---- the catalogue -------------------------------------------------------------------------

    private fun buildCatalogue(): List<PostCondition> =
        schemaConditions() + totalsConditions() + referentialConditions() +
            pinConditions() + diskConditions() + partConditions() + intentConditions() +
            historyConditions() + shapeConditions()

    // A. Open and schema identity.

    private fun schemaConditions(): List<PostCondition> =
        listOf(
            // `SCHEMA_VERSION` is a literal, not `Dylan.Schema.version`: the factory writes that
            // number, so comparing against it is "the code equals itself". Hard-coded deliberately,
            // so a version bump without a suite update is itself a red.
            ScalarCondition(
                id = "INV-01",
                group = Group.SCHEMA,
                title = "user_version equals the schema version after every open",
                sql = "SELECT (SELECT * FROM pragma_user_version) - $SCHEMA_VERSION",
            ),
            ScalarCondition(
                id = "INV-02",
                group = Group.SCHEMA,
                title = "foreign keys are ON on this connection",
                sql = "SELECT 1 - (SELECT * FROM pragma_foreign_keys)",
            ),
            // Restricted to tables and views. The plan's own statement for this is a bare
            // `SELECT name FROM sqlite_master WHERE name NOT IN (…)`, which is wrong: `sqlite_master`
            // also holds every index and trigger, so it returns rows on a perfectly healthy store and
            // the check could never be green — a check that can only fail is not a check.
            ScalarCondition(
                id = "INV-03",
                group = Group.SCHEMA,
                title = "every table and view the app reads exists",
                sql =
                    "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','view') " +
                        "AND name NOT LIKE 'sqlite_%' AND name NOT IN (${EXPECTED_OBJECTS.joinToString { "'$it'" }})",
            ),
            // The *exact* index set, as an equality rather than a containment. A containment check
            // is what lets a chain that stopped at statement 20 of 33 pass by having run most of
            // itself — `CacheMigrationTest` uses the same argument for the migration chain.
            ProceduralCondition(
                id = "INV-03i",
                group = Group.SCHEMA,
                title = "the index set is exactly the one dylan.sq declares",
            ) { ctx ->
                val actual = ctx.sql.indexes().filterNot { it.startsWith("sqlite_") }
                val missing = EXPECTED_INDEXES - actual.toSet()
                val extra = actual - EXPECTED_INDEXES
                when {
                    missing.isEmpty() && extra.isEmpty() -> null
                    missing.isNotEmpty() -> "missing: $missing"
                    else -> "unexpected: $extra"
                }
            },
            ProceduralCondition(
                id = "INV-03t",
                group = Group.SCHEMA,
                title = "every trigger dylan.sq declares exists",
            ) { ctx ->
                val missing = EXPECTED_TRIGGERS - ctx.sql.triggers().toSet()
                if (missing.isEmpty()) null else "missing: $missing"
            },
            ProceduralCondition(
                id = "INV-03v",
                group = Group.SCHEMA,
                title = "the cached_files reader surface still answers",
            ) { ctx ->
                val view = ctx.sql.text("SELECT COUNT(*) FROM cached_files WHERE provider IS NOT NULL")
                val table = ctx.sql.text("SELECT COUNT(*) FROM library")
                if (view == table) null else "the view reports $view rows, library holds $table"
            },
        )

    // B. cache_totals.

    private fun totalsConditions(): List<PostCondition> =
        listOf(
            // `COALESCE(…, -1)` and not `COALESCE(…, 0)`: a *missing* singleton and a real zero are
            // the same number to the app (`cachedCountAndBytes` coalesces to 0) and must not be the
            // same number to an invariant. With -1 the difference is non-zero when the row is absent.
            ScalarCondition(
                id = "INV-04",
                group = Group.TOTALS,
                title = "final_bytes equals SUM(library.bytes)",
                sql =
                    "SELECT COALESCE((SELECT final_bytes FROM cache_totals WHERE id = 0), -1) - " +
                        "COALESCE((SELECT SUM(bytes) FROM library), 0)",
            ),
            ScalarCondition(
                id = "INV-05",
                group = Group.TOTALS,
                title = "final_count equals COUNT(library)",
                sql =
                    "SELECT COALESCE((SELECT final_count FROM cache_totals " +
                        "WHERE id = 0), -1) - (SELECT COUNT(*) FROM library)",
            ),
            ScalarCondition(
                id = "INV-06",
                group = Group.TOTALS,
                title = "the totals singleton exists, is unique, and is id = 0",
                sql =
                    "SELECT (SELECT COUNT(*) FROM cache_totals) - 1 + " +
                        "(SELECT COUNT(*) FROM cache_totals WHERE id <> 0)",
            ),
            RejectionCondition(
                id = "INV-06n",
                group = Group.TOTALS,
                title = "a second totals row is refused by CHECK (id = 0)",
                write =
                    "INSERT INTO cache_totals(id, final_bytes, final_count, " +
                        "part_bytes, part_count) VALUES (1, 0, 0, 0, 0)",
                probe = "SELECT COUNT(*) FROM cache_totals",
            ),
            ScalarCondition(
                id = "INV-07",
                group = Group.TOTALS,
                title = "no total is ever negative",
                sql =
                    "SELECT COUNT(*) FROM cache_totals WHERE final_bytes < 0 " +
                        "OR final_count < 0 OR part_bytes < 0 OR part_count < 0",
            ),
            // INV-08a: *after* a sweep. INV-08b ("during a session") is a separate, @Ignore'd canary
            // in `M1InvariantsTest` — see the F-2 note there.
            ProceduralCondition(
                id = "INV-08",
                group = Group.TOTALS,
                title = "part_bytes/part_count match the .part files on disk",
                quiescedOnly = true,
            ) { ctx ->
                val expected = "${ctx.fsSnapshot.parts.sumOf { it.size }}|${ctx.fsSnapshot.parts.size}"
                val reported = ctx.sql.text(PART_TOTALS_SHAPE)
                if (reported == expected) null else "cache_totals says '$reported', the directory holds '$expected'"
            },
        )

    // C. Referential integrity.

    private fun referentialConditions(): List<PostCondition> =
        listOf(
            ScalarCondition(
                id = "INV-10",
                group = Group.REFERENTIAL,
                title = "no orphan library row",
                sql =
                    "SELECT COUNT(*) FROM library l WHERE NOT EXISTS " +
                        "(SELECT 1 FROM songs s WHERE s.provider = l.provider AND s.song_id = l.song_id)",
            ),
            ScalarCondition(
                id = "INV-11",
                group = Group.REFERENTIAL,
                title = "no orphan media_objects row",
                sql =
                    "SELECT COUNT(*) FROM media_objects m WHERE NOT EXISTS " +
                        "(SELECT 1 FROM songs s WHERE s.provider = m.provider AND s.song_id = m.song_id)",
            ),
            ScalarCondition(
                id = "INV-12",
                group = Group.REFERENTIAL,
                title = "no orphan favorites / play_history row",
                sql =
                    "SELECT (SELECT COUNT(*) FROM favorites f WHERE NOT EXISTS " +
                        "(SELECT 1 FROM songs s WHERE s.provider = f.provider AND s.song_id = f.song_id)) + " +
                        "(SELECT COUNT(*) FROM play_history h WHERE NOT EXISTS " +
                        "(SELECT 1 FROM songs s WHERE s.provider = h.provider AND s.song_id = h.song_id))",
            ),
            ScalarCondition(
                id = "INV-12i",
                group = Group.REFERENTIAL,
                title = "no orphan download_intents row",
                sql =
                    "SELECT COUNT(*) FROM download_intents d WHERE NOT EXISTS " +
                        "(SELECT 1 FROM songs s WHERE s.provider = d.provider AND s.song_id = d.song_id)",
            ),
            // INV-13, observed rather than asserted destructively: a child row that has no parent is
            // exactly what a *disabled* cascade leaves behind, so its absence proves the cascade is
            // live without deleting anything a later check depends on. The destructive half is in
            // `M1InvariantsTest.aDeletedSongTakesItsCacheRowsWithIt`, which runs the delete inside a
            // transaction it rolls back.
            ProceduralCondition(
                id = "INV-13",
                group = Group.REFERENTIAL,
                title = "ON DELETE CASCADE is live (no child row outlives its song)",
            ) { ctx ->
                val orphaned =
                    ctx.sql.scalarLong(
                        "SELECT (SELECT COUNT(*) FROM library l WHERE NOT EXISTS (SELECT 1 FROM songs s " +
                            "WHERE s.provider = l.provider AND s.song_id = l.song_id)) + " +
                            "(SELECT COUNT(*) FROM favorites f WHERE NOT EXISTS (SELECT 1 FROM songs s " +
                            "WHERE s.provider = f.provider AND s.song_id = f.song_id)) + " +
                            "(SELECT COUNT(*) FROM play_history h WHERE NOT EXISTS (SELECT 1 FROM songs s " +
                            "WHERE s.provider = h.provider AND s.song_id = h.song_id))",
                    )
                if (orphaned == 0L) null else "$orphaned child row(s) reference a song that does not exist"
            },
            ScalarCondition(
                id = "INV-14",
                group = Group.REFERENTIAL,
                title = "every READY media_objects row is a library row's active rendition",
                sql =
                    "SELECT COUNT(*) FROM media_objects m WHERE m.state = 'READY' AND NOT EXISTS " +
                        "(SELECT 1 FROM library l WHERE l.provider = m.provider AND l.song_id = m.song_id " +
                        "AND l.bitrate = m.bitrate AND l.ext = m.ext)",
            ),
            ScalarCondition(
                id = "INV-15",
                group = Group.REFERENTIAL,
                title = "a library row and its active rendition agree on bytes",
                sql =
                    "SELECT COUNT(*) FROM library l JOIN media_objects m ON m.provider = l.provider " +
                        "AND m.song_id = l.song_id AND m.bitrate = l.bitrate " +
                        "AND m.ext = l.ext WHERE m.bytes <> l.bytes",
            ),
            // INV-16(a): a tripwire on the *absence* of a feature. Nothing in the tree calls
            // `beginWrite`/`failObject`, so a store the current code wrote must never carry these
            // states. The other direction (a seeded WRITING row must be reaped) is a scenario,
            // because it needs a `Reconciler.run()` between two checks.
            ScalarCondition(
                id = "INV-16",
                group = Group.REFERENTIAL,
                title = "a store the current code wrote carries no WRITING / VERIFY_FAILED rendition",
                sql = "SELECT COUNT(*) FROM media_objects WHERE state IN ('WRITING','VERIFY_FAILED')",
            ),
        )

    // D. Pins.

    private fun pinConditions(): List<PostCondition> =
        listOf(
            ScalarCondition(
                id = "INV-18",
                group = Group.PINS,
                title = "pinned = 1 if and only if pinned_at_ms is not null",
                sql = "SELECT COUNT(*) FROM library WHERE (pinned = 0) <> (pinned_at_ms IS NULL)",
            ),
            ProceduralCondition(
                id = "INV-19a",
                group = Group.PINS,
                title = "a pin with no timestamp is refused",
            ) { ctx ->
                val target = ctx.sql.text("SELECT song_id FROM library ORDER BY song_id LIMIT 1")
                if (target.isEmpty()) {
                    return@ProceduralCondition "the fixture has no cache rows, " +
                        "so the check proved nothing"
                }
                val threw =
                    ctx.sql.expectRejected(
                        "UPDATE library SET pinned = 1, pinned_at_ms " +
                            "= NULL WHERE song_id = '$target'",
                    )
                if (threw != null) {
                    null
                } else {
                    "UPDATE library SET pinned = 1, pinned_at_ms " +
                        "= NULL was ACCEPTED for song_id='$target'"
                }
            },
            ProceduralCondition(
                id = "INV-19b",
                group = Group.PINS,
                title = "a pin timestamp with no pin is refused",
            ) { ctx ->
                val target = ctx.sql.text("SELECT song_id FROM library WHERE pinned = 1 ORDER BY song_id LIMIT 1")
                if (target.isEmpty()) {
                    return@ProceduralCondition "the fixture has no pinned row, " +
                        "so the check proved nothing"
                }
                val threw =
                    ctx.sql.expectRejected(
                        "UPDATE library SET pinned = 0, pinned_at_ms " +
                            "= 17 WHERE song_id = '$target'",
                    )
                if (threw != null) {
                    null
                } else {
                    "UPDATE library SET pinned = 0, pinned_at_ms " +
                        "= 17 was ACCEPTED for song_id='$target'"
                }
            },
            // Scoped to a quiesced point: `LibraryCommitter.commit` re-pins on
            // `favorited || prev.pinned` and `Repos.remove` demotes, so a divergence is legal *while*
            // a commit is in flight. "A favourite with no cache row yet" is also legal (`Repos.add`
            // pins a row that may not exist) and is deliberately not asserted.
            ScalarCondition(
                id = "INV-20",
                group = Group.PINS,
                title = "a favourite's cache row is pinned",
                sql = "SELECT COUNT(*) FROM favorites f JOIN library l USING(provider, song_id) WHERE l.pinned = 0",
                quiescedOnly = true,
            ),
        )

    // E. Disk <-> rows.

    private fun diskConditions(): List<PostCondition> =
        listOf(
            // Both halves, because `Reconciler.checkOne` treats "missing" and "wrong size"
            // identically and a check that only looked for *missing* would pass on a truncated file.
            ProceduralCondition(
                id = "INV-21",
                group = Group.DISK,
                title = "no cache row references a missing file",
                quiescedOnly = true,
            ) { ctx -> ctx.rowFileMismatches() },
            ProceduralCondition(
                id = "INV-22",
                group = Group.DISK,
                title = "no untracked file in the audio directory",
                quiescedOnly = true,
            ) { ctx -> ctx.untrackedFiles() },
        )

    // F. `.part` and resume records.

    private fun partConditions(): List<PostCondition> =
        listOf(
            ProceduralCondition(
                id = "INV-25",
                group = Group.PARTS,
                title = "the number of .part files equals cache_totals.part_count",
                quiescedOnly = true,
            ) { ctx ->
                val count = ctx.fsSnapshot.parts.size
                val reported = ctx.sql.scalarLong("SELECT part_count FROM cache_totals WHERE id = 0")
                if (reported.toInt() == count) null else "part_count=$reported, $count on disk"
            },
            ProceduralCondition(
                id = "INV-26",
                group = Group.PARTS,
                title = "every .part an intent names is resumable (decodable sidecar)",
            ) { ctx ->
                // Not "every .part has a sidecar": a `.part` with none is *unreachable bytes*, which is
                // what INV-27 grades against the grace window, and calling it broken here would make
                // the two conditions contradict each other. The pair that IS an invariant is the one
                // the reconciler depends on — a part a `download_intents` row says a transfer wants
                // is re-enqueued on the next boot, and `loadFromDisk` reads sidecars and nothing
                // else, so without one it resumes from zero and the offset is gone.
                val named = ctx.intentKeys().map { "$it.part" }.toSet()
                val resumable = ctx.resumableParts()
                val unresumable = named.filter { it in ctx.fsSnapshot.byName && it !in resumable }
                when {
                    unresumable.isEmpty() -> null
                    else -> "an intent names it and there is no decodable sidecar: $unresumable"
                }
            },
            ProceduralCondition(
                id = "INV-27",
                group = Group.PARTS,
                title = "no .part bytes are unreachable",
            ) { ctx ->
                val resumable = ctx.resumableParts()
                val intentKeys = ctx.intentKeys()
                val graceMs = ctx.cfg.partGraceHours * 3_600_000L
                val unreachable =
                    ctx.fsSnapshot.parts.filter { part ->
                        val named = part.name.removeSuffix(".part") in intentKeys
                        val fresh = ctx.clock.nowMs() - part.mtimeMs in 0..graceMs
                        part.name !in resumable && !named && !fresh
                    }
                when {
                    unreachable.isEmpty() -> null
                    else ->
                        "no sidecar, no intent row, and past the ${ctx.cfg.partGraceHours}h grace: " +
                            unreachable.joinToString { it.name }
                }
            },
            // The regression test for the guard at `PartStore.persist` (`if (!fs.exists(part)) return`).
            // A sidecar with no `.part` seeds a *phantom* resumable part on the next boot, because
            // `loadFromDisk` reads sidecars and nothing else.
            ProceduralCondition(
                id = "INV-28",
                group = Group.PARTS,
                title = "no resume record without a .part",
            ) { ctx ->
                val orphans =
                    ctx.fsSnapshot.sidecars
                        .map { it.name.removeSuffix(".meta") }
                        .filter { base -> base !in ctx.fsSnapshot.byName }
                when {
                    orphans.isEmpty() -> null
                    else -> "sidecar without its part: $orphans"
                }
            },
        )

    // G. Intents and the protection table.

    private fun intentConditions(): List<PostCondition> =
        listOf(
            ScalarCondition(
                id = "INV-32",
                group = Group.INTENTS,
                title = "download_intents.priority equals the canonical CASE over reason",
                sql =
                    "SELECT COUNT(*) FROM download_intents WHERE priority <> (CASE reason " +
                        "WHEN 'USER_NOW' THEN 0 WHEN 'USER_BULK' THEN 1 WHEN 'PREFETCH_NEXT' THEN 2 ELSE 3 END)",
            ),
            RejectionCondition(
                id = "INV-34",
                group = Group.INTENTS,
                title = "protected_keys.reason is constrained to the four legal values",
                write = "INSERT INTO protected_keys(provider, song_id, reason) VALUES ('saavn', 'probe', 'NOPE')",
                probe = "SELECT COUNT(*) FROM protected_keys WHERE reason = 'NOPE'",
            ),
        )

    // H. Engagement and history.

    private fun historyConditions(): List<PostCondition> =
        listOf(
            ScalarCondition(
                id = "INV-37",
                group = Group.HISTORY,
                title = "no negative play_count",
                sql = "SELECT COUNT(*) FROM library WHERE play_count < 0",
            ),
            // The bound is `historyLimit + (ties at the cut millisecond)`: `trimHistory` cuts on
            // `< (SELECT MIN(t) … LIMIT ?)` and deliberately keeps every row *at* the cut. So the
            // assertion is the shape of the trim — "every row older than the cut is gone, and every
            // row inside the newest-N window is kept" — not a bare count, because a bare count is
            // satisfied by a table that was never trimmed at all.
            ProceduralCondition(
                id = "INV-38",
                group = Group.HISTORY,
                title = "play_history is trimmed to historyLimit with no orphan",
            ) { ctx ->
                val limit = ctx.cfg.historyLimit.toLong()
                val cut =
                    ctx.sql.scalarLong(
                        "SELECT MIN(t) FROM (SELECT played_at_ms AS t FROM " +
                            "play_history ORDER BY played_at_ms DESC LIMIT $limit)",
                    )
                if (cut == Sql.NO_ROW) return@ProceduralCondition null
                val older = ctx.sql.scalarLong("SELECT COUNT(*) FROM play_history WHERE played_at_ms < $cut")
                val inside = ctx.sql.scalarLong("SELECT COUNT(*) FROM play_history WHERE played_at_ms >= $cut")
                val newest =
                    ctx.sql.scalarLong(
                        "SELECT COUNT(*) FROM (SELECT 1 FROM play_history " +
                            "ORDER BY played_at_ms DESC LIMIT $limit)",
                    )
                when {
                    older > 0 -> "$older row(s) older than the cut survived"
                    inside != newest -> "the window holds $inside rows, the newest-$limit window holds $newest"
                    else -> null
                }
            },
        )

    // I. Query-plan and constraint shape.

    private fun shapeConditions(): List<PostCondition> =
        listOf(
            PlanCondition(
                id = "INV-39",
                group = Group.SHAPE,
                title = "the intent scheduler rides idx_intent_priority",
                query = "SELECT * FROM download_intents ORDER BY priority, enqueued_at_ms",
                controlQuery = "SELECT * FROM download_intents ORDER BY enqueued_at_ms, priority",
            ),
            PlanCondition(
                id = "INV-39l",
                group = Group.SHAPE,
                title = "the LRU victim order is the index, not a sort",
                query = LRU_ORDER_QUERY,
                controlQuery = "SELECT provider FROM library ORDER BY bytes DESC",
            ),
            RejectionCondition(
                id = "INV-40a",
                group = Group.SHAPE,
                title = "library refuses negative bytes",
                write =
                    "UPDATE library SET bytes = -1 WHERE song_id = (SELECT " +
                        "song_id FROM library ORDER BY song_id LIMIT 1)",
                probe = "SELECT COUNT(*) FROM library WHERE bytes < 0",
            ),
            RejectionCondition(
                id = "INV-40b",
                group = Group.SHAPE,
                title = "library refuses a non-positive bitrate",
                write =
                    "UPDATE library SET bitrate = 0 WHERE song_id = " +
                        "(SELECT song_id FROM library ORDER BY song_id LIMIT 1)",
                probe = "SELECT COUNT(*) FROM library WHERE bitrate <= 0",
            ),
            RejectionCondition(
                id = "INV-40c",
                group = Group.SHAPE,
                title = "library refuses an empty ext",
                write =
                    "UPDATE library SET ext = '' WHERE song_id = (SELECT " +
                        "song_id FROM library ORDER BY song_id LIMIT 1)",
                probe = "SELECT COUNT(*) FROM library WHERE ext = ''",
            ),
            RejectionCondition(
                id = "INV-40d",
                group = Group.SHAPE,
                title = "library refuses a negative play_count",
                write =
                    "UPDATE library SET play_count = -1 WHERE song_id = " +
                        "(SELECT song_id FROM library ORDER BY song_id LIMIT 1)",
                probe = "SELECT COUNT(*) FROM library WHERE play_count < 0",
            ),
            RejectionCondition(
                id = "INV-41",
                group = Group.SHAPE,
                title = "media_objects refuses an unknown lifecycle state",
                write = "UPDATE media_objects SET state = 'NONSENSE'",
                probe = "SELECT COUNT(*) FROM media_objects WHERE state = 'NONSENSE'",
            ),
        )

    // ---- shared helpers on the context ---------------------------------------------------------

    /**
     * `provider_songId_bitrate` for every intent — exactly the stem of the `.part` file the
     * reconciler's `pendingParts` set is built from (`paths.part(key, bitrate)`), so "the intent
     * names this part" compares against the same string production builds rather than a guess.
     */
    private fun CheckContext.intentKeys(): Set<String> =
        sql
            .rows(
                "SELECT provider || '_' || song_id || " +
                    "'_' || bitrate FROM download_intents",
            ).toSet()

    /**
     * `.part` file names whose sidecar decodes into a `Breakpoint`, read through the *production*
     * decoder rather than a re-implementation of it: `PartStore.loadFromDisk` reads sidecars and
     * nothing else, so a check that parsed the format its own way would prove nothing about the
     * resume path.
     */
    private fun CheckContext.resumableParts(): Set<String> =
        fsSnapshot.parts
            .mapNotNull { part -> part.name.takeIf { PartMeta.read(fs, PartMeta.sidecarOf(part.path)) != null } }
            .toSet()

    private fun CheckContext.rowFileMismatches(): String? {
        val bad = ArrayList<String>()
        for (row in sql.rows(LIBRARY_ROW_SHAPE)) {
            val (name, declared) = row.split('|')
            val entry = fsSnapshot.byName[name]
            when {
                entry == null -> bad += "$name: no file, row says $declared bytes"
                entry.size != declared.toLong() -> bad += "$name: file is ${entry.size} B, row says $declared"
            }
        }
        return bad.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * A file in the audio directory that no `library` row describes and no `download_intents` row
     * explains. Only names that *parse* are considered — `CachePath.parse` is the allowlist the
     * orphan sweep itself uses, so `cover.jpg` and `notes.txt` are invisible here by construction,
     * which is what makes this the *complement* of "a file the app did not write is never deleted"
     * rather than a duplicate of it.
     *
     * A `.part` inside `partGraceHours` is exempt, for exactly the reason the reconciler exempts it:
     * a transfer that is in flight, or that a process died on, has no `library` row yet by design.
     * Grading it here as untracked would contradict INV-27's grace window inside one report.
     */
    private fun CheckContext.untrackedFiles(): String? {
        val known = sql.rows(LIBRARY_KEY_SHAPE).toSet()
        val named = intentKeys().map { "$it.part" }.toSet()
        val graceMs = cfg.partGraceHours * 3_600_000L
        val bad =
            fsSnapshot.entries
                .filter { paths.parseFileName(it.name) != null }
                .filter { it.name !in known }
                .filter { it.name !in named }
                .filterNot { it.name.endsWith(".part") && clock.nowMs() - it.mtimeMs in 0..graceMs }
        return bad.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "no row describes: $it" }
    }
}

/**
 * The version the current code must stamp. **Not** `Dylan.Schema.version`: comparing the code with
 * itself is a tautology, and the point of INV-01 is that a version bump without a suite update is a
 * red. Bump it here, in [SCHEMA_VERSION_MIGRATED_FROM] and in the `1.sqm` header together.
 */
const val SCHEMA_VERSION = 2L

/** A v0 store the current code cannot produce; the version `1.sqm` is applied from. */
const val SCHEMA_VERSION_MIGRATED_FROM = 1L

/** `part_bytes|part_count` in one row, so a mismatch report carries both halves. */
private const val PART_TOTALS_SHAPE =
    "SELECT (SELECT part_bytes FROM cache_totals WHERE id = 0) || " +
        "'|' || (SELECT part_count FROM cache_totals WHERE id = 0)"

/** `<fileName>|<bytes>` for every cache row — the shape INV-21 compares against the filesystem. */
private const val LIBRARY_ROW_SHAPE =
    "SELECT provider || '_' || song_id || '_' || bitrate || '.' || ext || '|' || bytes FROM library"

/** `<provider>_<songId>_<bitrate>.<ext>` — the file name a library row must be written at. */
private const val LIBRARY_KEY_SHAPE =
    "SELECT provider || '_' || song_id || '_' || bitrate || '.' || ext FROM library"

/** The `ORDER BY` of `lruVictims`'s inner query, verbatim: it is what `idx_cached_lru` IS. */
private const val LRU_ORDER_QUERY =
    "SELECT provider, song_id FROM library WHERE pinned = 0 " +
        "ORDER BY (play_count = 0) DESC, (last_used_ms IS NULL) DESC, last_used_ms, cached_at_ms, provider, song_id"

private val EXPECTED_OBJECTS =
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
        // and the reader surface that survived the split
        "cached_files",
    )

private val EXPECTED_INDEXES =
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

private val EXPECTED_TRIGGERS =
    setOf(
        "trg_intent_priority_ins",
        "trg_intent_priority_upd",
        "trg_library_asset_ins",
        "trg_library_asset_upd",
        "trg_library_drop_assets",
        "trg_library_total_del",
        "trg_library_total_ins",
        "trg_library_total_upd",
        "trg_media_drop_library",
    )
