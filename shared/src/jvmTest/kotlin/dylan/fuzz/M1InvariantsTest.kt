package dylan.fuzz

import dylan.download.PartMeta
import dylan.model.SongKey
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **M1 — the invariant suite over a healthy store.** `docs/testing/fuzzy-e2e-plan.md` §6.
 *
 * The suite's whole value is that it can go red. Three things make that true here and all three are
 * visible in the code rather than asserted in prose:
 *
 *  * **Every scenario names its own falsifier.** Each `@Test` carries a `MUTATION:` block naming the
 *    single-line change that turns it red. Those strings are the specification of what each check
 *    observes, and they are what a reviewer reads before trusting a green. They were *executed* —
 *    see `docs/testing/fuzzy-e2e-implementation-notes.md` for the before/after table.
 *  * **The controls are assertions.** A plan guard carries a query that *must* sort; a constraint
 *    guard carries the write that must be refused. Neither can be green for the wrong reason.
 *  * **The fixture is stated, not implied.** [HealthyStore.describe] appears in every failure
 *    message, so a violation is always reported against the store it was seen in.
 *
 * **Determinism.** Real `TestLanes.production()` lanes wherever an engine exists, and no engine at
 * all where one does not need to exist (see `HealthyStore.reconciler`). The wall clock is
 * [dylan.support.MutableClock]; every grace window is exercised by ageing an mtime against it, which
 * is the technique `ReconcilerClockTest` already uses. Nothing here waits on a timer.
 */
class M1InvariantsTest {
    private lateinit var store: HealthyStore

    @BeforeTest
    fun setup() {
        store = HealthyStore.build()
        // INV-08/INV-25 are two-tier on purpose: "after a reconcile they match" is the real
        // contract and "during a session they match" is finding F-2. A store that has never been
        // reconciled has published no `part_bytes` at all — nothing in a fresh fixture does, because
        // the only writer is `Reconciler.run()` — so the baseline establishes the reconciled
        // baseline rather than pretending one exists.
        runBlocking { store.reconciler().run() }
    }

    @AfterTest
    fun teardown() {
        runCatching { store.close() }
    }

    /**
     * The baseline. Every condition in the catalogue runs against the reference store and must hold.
     *
     * This is the one test that notices a *newly added* invariant that is wrong, and the one a schema
     * change breaks first — which is why it runs the whole catalogue rather than a hand-picked subset.
     *
     * MUTATION: `dylan.sq:146` `final_count + 1` → `final_count + 0`. INV-04/INV-05 go red on the
     * first `cached(...)` call: the totals row the trigger seeds still *appears*, so without
     * INV-04/INV-05 this test would stay green through a store whose every total is wrong.
     */
    @Test
    fun theWholeCatalogueHoldsOnAHealthyStore() {
        val violations = store.oracle.check(step = "healthy store")
        assertTrue(
            violations.isEmpty(),
            "${violations.size} invariant(s) failed on ${store.describe()}:\n" +
                violations.joinToString("\n") { it.toString() } +
                skippedNote(),
        )
        assertEquals(
            emptySet(),
            store.oracle.unknownIds(),
            "the scenario and the catalogue " +
                "disagree about which invariants exist",
        )
    }

    /**
     * The catalogue may not shrink silently, and may not contain a condition that never runs. Both are
     * asserted here rather than left to review, because a suite that quietly loses a check is
     * indistinguishable from one that was never written.
     *
     * MUTATION: delete `id = "INV-14"` from `DbOracle.referentialConditions`. Before this test the
     * loss was invisible; after it, red.
     */
    @Test
    fun everyConditionRanAndTheCatalogueIsTheOneThePlanNames() {
        store.oracle.check(step = "coverage")
        val skipped = store.oracle.lastSkipped.associate { it.id to it.why }
        assertEquals(emptySet(), skipped.keys, "these conditions did not run on a healthy store: $skipped")
        val present =
            store.oracle.catalogue
                .map { it.id }
                .toSet()
        assertEquals(PLANNED_CATALOGUE, present, "the catalogue drifted from the plan's §6 list")
        assertEquals(present.size, store.oracle.catalogue.size, "two conditions share an id")
    }

    /**
     * INV-04/INV-05 with a *transition* rather than a baseline: the point is not that the totals are
     * right at rest, it is that they follow every write — including the two shapes a trigger-based
     * design is most likely to miss, a `bytes` change and a delete.
     *
     * MUTATION: `dylan.sq:154` `MAX(0, final_bytes - old.bytes)` → `final_bytes - old.bytes`.
     * The `UPDATE` trigger's own MAX masks it, so the delete is the only witness — which is why the
     * delete of a 900-byte row is in the middle of this sequence and INV-07 is asserted alongside.
     */
    @Test
    fun totalsFollowEveryLibraryWrite() {
        val totals = {
            store.sql.text(
                "SELECT (SELECT final_bytes FROM cache_totals WHERE id=0) || '|' || " +
                    "(SELECT final_count FROM cache_totals WHERE id=0)",
            )
        }
        assertEquals("6700|5", totals(), "the seeded rows: 2000 + 1500 + 1100 + 1200 + 900")

        store.sql.exec("UPDATE library SET bytes = bytes + 500 WHERE song_id = 'cold'")
        assertEquals("7200|5", totals(), "trg_library_total_upd must follow it")

        store.sql.exec("DELETE FROM library WHERE song_id = 'mp3track'")
        assertEquals("6300|4", totals(), "trg_library_total_del must follow it, MAX(0) or not")

        store.sql.exec(
            "INSERT INTO library(provider, song_id, bitrate, ext, bytes, " +
                "cached_at_ms, last_used_ms, play_count, pinned, pinned_at_ms) " +
                "VALUES ('saavn', 'uncached', 128, 'm4a', 3300, 1, NULL, 0, 0, NULL)",
        )
        assertEquals("9600|5", totals(), "trg_library_total_ins must follow it")

        assertEquals(
            emptyList(),
            store.oracle.checkOnly(listOf("INV-04", "INV-05", "INV-07"), step = "after writes"),
            "the totals must agree with the rows after every write",
        )
    }

    /**
     * INV-07 — no total is ever negative — over a store whose totals were *already* short.
     *
     * This is the scenario mutation **G-02** needs and cannot get any other way: the
     * `MAX(0, final_bytes - old.bytes)` in `trg_library_total_del` is *defence*, and its triggering
     * condition (`final_bytes < old.bytes`) is unreachable through SQL, because a store the current
     * code wrote always has the bytes. So the honest way to observe the `MAX` is to hand the store
     * totals that do not describe it — which is exactly what a build that predates the triggers, or a
     * hand-edited file, looks like — and then delete a row bigger than what is accounted for.
     *
     * Without the `MAX` the total goes to `-1 600`; with it, it clamps at zero. **INV-04 must NOT be
     * consulted here**: this store is deliberately inconsistent, so `checkOnly` names INV-07 alone.
     * That distinction is the test: a scenario that ran the whole catalogue here would be red on the
     * fixture it just built, and would learn to ignore the catalogue.
     *
     * MUTATION: `dylan.sq:154` `MAX(0, final_bytes - old.bytes)` → `final_bytes - old.bytes`.
     */
    @Test
    fun totalsNeverGoNegativeEvenWhenTheStoreWasAlreadyShort() {
        store.sql.exec("UPDATE cache_totals SET final_bytes = 100 WHERE id = 0")
        assertEquals(
            100L,
            store.sql.scalarLong("SELECT final_bytes FROM cache_totals WHERE id = 0"),
            "precondition: the totals no longer describe the rows",
        )

        store.sql.exec("DELETE FROM library WHERE song_id = 'cold'")

        assertEquals(
            0L,
            store.sql.scalarLong("SELECT final_bytes FROM cache_totals WHERE id = 0"),
            "a negative total is unrepresentable through SQL — the CHECK and the MAX both say so",
        )
        assertEquals(
            emptyList(),
            store.oracle.checkOnly(listOf("INV-07"), step = "after the clamp"),
            "INV-07 must observe the clamp, and only INV-07: this store is deliberately inconsistent",
        )
        // ...and the honest admission that the *other* conditions are red for a reason we created.
        assertTrue(
            store.oracle.checkOnly(listOf("INV-04"), step = "after the clamp").isNotEmpty(),
            "INV-04 is expected to be red here — the fixture broke it on purpose",
        )
    }

    /**
     * The singleton is seeded by a trigger, not by a caller, so the *second* row is the interesting
     * one. `INSERT OR IGNORE` is deliberately not usable in the triggers (`dylan.sq:135-141` explains
     * why), so a scenario that only inserted once would pass against the broken form.
     *
     * MUTATION: `dylan.sq:107` delete `CHECK (id = 0)`. INV-06's *count* cannot see a second row that
     * nothing has inserted yet — only INV-06n's write-and-expect-throw can.
     */
    @Test
    fun theTotalsSingletonIsUniqueAndCannotBeWidened() {
        assertEquals(1L, store.sql.scalarLong("SELECT COUNT(*) FROM cache_totals"), "exactly one totals row")
        val threw =
            store.sql.expectRejected(
                "INSERT INTO cache_totals(id, final_bytes, final_count, " +
                    "part_bytes, part_count) VALUES (1, 7, 7, 7, 7)",
            )
        assertNotNull(threw, "a second totals row must be refused by CHECK (id = 0)")
        assertEquals(1L, store.sql.scalarLong("SELECT COUNT(*) FROM cache_totals"), "and nothing may have landed")
    }

    /**
     * INV-13, destructively — the only place in the suite that deletes a `songs` row, and it happens
     * inside a transaction that is rolled back, so the check is about the cascade rather than about
     * whether the fixture can be rebuilt. `CASCADE` is a no-op on a connection whose `foreign_keys`
     * pragma is missing, and `DriverFactory` supplies it as a *driver property* precisely because
     * statements do not survive the pool; a check that merely counted orphans would be green on such
     * a connection right up until the day it was not.
     *
     * MUTATION: `DriverFactory.jvm.kt:54` drop the `foreign_keys` property. Then this delete leaves
     * four child rows. The counts are read *inside* the transaction, so the rollback cannot hide it.
     */
    @Test
    fun aDeletedSongTakesItsCacheRowsWithIt() {
        store.sql.rolledBack {
            store.sql.exec("DELETE FROM songs WHERE provider = 'saavn' AND song_id = 'pinned320'")
            assertEquals(
                0L,
                store.sql.scalarLong(
                    "SELECT (SELECT COUNT(*) FROM library WHERE song_id = 'pinned320') + " +
                        "(SELECT COUNT(*) FROM media_objects WHERE song_id = 'pinned320') + " +
                        "(SELECT COUNT(*) FROM favorites WHERE song_id = 'pinned320') + " +
                        "(SELECT COUNT(*) FROM play_history WHERE song_id = 'pinned320')",
                ),
                "ON DELETE CASCADE must reach every child table",
            )
            assertEquals(
                0L,
                store.sql.scalarLong("SELECT final_count FROM cache_totals WHERE id = 0") -
                    store.sql.scalarLong("SELECT COUNT(*) FROM library"),
                "the totals triggers must have seen the cascade too",
            )
        }
        assertEquals(5L, store.sql.scalarLong("SELECT COUNT(*) FROM library"), "the rollback restored the fixture")
        assertEquals(
            6_700L,
            store.sql.scalarLong("SELECT final_bytes FROM cache_totals WHERE id = 0"),
            "and so did the totals, which the cascade also touched",
        )
    }

    /**
     * INV-16's second direction: a row in a state **no current writer can produce**. `beginWrite` and
     * `failObject` have zero callers in the tree, so nothing short of a store from another build (or
     * a hand edit) can produce a `WRITING` row — which is exactly why INV-16 is a canary in *both*
     * directions: "a store the current code wrote has none" and "when one is seeded, the reconciler
     * reaps it and the rest of the store is untouched".
     *
     * MUTATION: `Reconciler.reapInterruptedWrites` move `dropObject` out of the `gone` branch (the
     * historical CA-4 defect). Then INV-16 still passes — the row's state is `WRITING`, not counted
     * by it — and only this scenario sees the row survive.
     */
    @Test
    fun aSeededNonPlayableRenditionIsReapedAndNothingElseIs() =
        runBlocking {
            val before = store.sql.text(SHAPE_ALL_ROWS)
            store.sql.exec(
                "INSERT INTO media_objects(provider, song_id, bitrate, ext, bytes, state, verified_at_ms) " +
                    "VALUES ('saavn', 'recent', 320, 'm4a', 4400, 'WRITING', NULL)",
            )
            assertEquals(
                1L,
                store.sql.scalarLong("SELECT COUNT(*) FROM media_objects WHERE state = 'WRITING'"),
                "precondition: the seeded rendition is there",
            )
            assertEquals(
                emptyList(),
                store.oracle.checkOnly(listOf("INV-14", "INV-15"), step = "seeded"),
                "a non-READY rendition that is not the library row's active object is outside INV-14/15 by design",
            )

            store.reconciler().run()

            assertEquals(
                0L,
                store.sql.scalarLong("SELECT COUNT(*) FROM media_objects WHERE song_id = 'recent' AND bitrate = 320"),
                "the reaped rendition's row must be gone",
            )
            assertTrue(
                !store.fs.exists(store.paths.final(SongKey("saavn", "recent"), 320, "m4a")),
                "and so must its file",
            )
            assertEquals(before, store.sql.text(SHAPE_ALL_ROWS), "nothing else may change: ${store.describe()}")
            assertEquals(
                emptyList(),
                store.oracle.check(step = "after the reap"),
                "a sweep that repaired the store must leave the catalogue green",
            )
        }

    /**
     * INV-21 and INV-22, and the ledger doing its job. The break is made on purpose and recorded, so
     * the "before" state is assessed with the condition suspended and the "after" state with it live.
     * Without the ledger a deliberate break is indistinguishable from a defect — which is the whole
     * reason it exists.
     *
     * MUTATION: `Reconciler.fullSweep` delete the `paths.parseFileName(file.name) == null` guard.
     * Then `cover.jpg` and `notes.txt` are swept and this test is red: user data deleted by a
     * reconciliation pass, from a name the app itself refuses to parse.
     */
    @Test
    fun outOfBandDamageIsDetectedAndRepairedAndForeignFilesAreNeverTouched() =
        runBlocking {
            store.fs.delete(store.paths.final(SongKey("saavn", "cold"), 128, "m4a"), mustCheck = false)

            store.ledger.broke(what = "deleted the file for song 'cold' out of band", suspends = setOf("INV-21"))
            assertEquals(
                emptyList(),
                store.oracle.checkOnly(listOf("INV-21", "INV-04"), step = "with the break recorded"),
                "a suspended condition must not be evaluated, and nothing else may be silenced with it",
            )
            assertEquals(
                listOf("INV-21"),
                store.oracle.lastSkipped.map { it.id },
                "and it must be reported as skipped rather than passed",
            )

            store.ledger.clear()
            assertEquals(
                listOf("INV-21"),
                store.oracle.checkOnly(listOf("INV-21"), step = "break cleared").map { it.id },
                "a missing file is an INV-21 violation, and the ledger's clearing must restore the check",
            )

            // The repair cadence is the **weekly** full sweep, and asserting that is the point: the
            // setup run already stamped `reconcile_full_ms`, and the boot path only re-stats rows
            // that have never been verified (`unstampedObjects`). So "not due" must leave the damage
            // in place *and* keep reporting it — a boot that quietly forgot would be the defect.
            store.reconciler().run()
            assertEquals(
                1L,
                store.sql.scalarLong("SELECT COUNT(*) FROM library WHERE song_id = 'cold'"),
                "a sweep that is not due must not repair this",
            )
            assertEquals(
                listOf("INV-21"),
                store.oracle.checkOnly(listOf("INV-21"), step = "before the sweep is due").map { it.id },
                "and the damage must still be reported while it is unrepaired",
            )

            store.clock.advanceMs(FULL_SWEEP_INTERVAL_MS + 60_000)
            store.reconciler().run()

            assertEquals(
                0L,
                store.sql.scalarLong("SELECT COUNT(*) FROM library WHERE song_id = 'cold'"),
                "the sweep must drop the row describing a file that is gone",
            )
            assertTrue(store.fs.exists(store.foreignFile("cover.jpg")), "cover.jpg must survive a full sweep")
            assertTrue(store.fs.exists(store.foreignFile("notes.txt")), "notes.txt must survive a full sweep")
            assertEquals(
                emptyList(),
                store.oracle.check(step = "after the repair"),
                "the catalogue must be green again: ${store.describe()}",
            )
        }

    /**
     * INV-27 and INV-26 in the direction that fails, and the reason they are two conditions and not
     * one. A `.part` with no sidecar is *not* a broken pairing — it is unreachable bytes, and the
     * only thing that can condemn it is the grace window elapsing. So this scenario ages the clock
     * rather than editing the store, which is why those windows are testable at all.
     *
     * MUTATION: INV-27's `fresh` clause dropped (`… in 0..graceMs`). Then the fixture's `halfdone`
     * part is condemned on the **baseline**, and `theWholeCatalogueHoldsOnAHealthyStore` goes red —
     * which is the control that says the grace window is load-bearing in both directions.
     */
    @Test
    fun anUnreachablePartIsCondemnedOnlyWhenItsGraceWindowElapses() =
        runBlocking {
            assertEquals(
                emptyList(),
                store.oracle.checkOnly(listOf("INV-26", "INV-27", "INV-28"), step = "fresh part"),
                "a fresh, sidecar-less .part is inside the grace window: ${store.describe()}",
            )

            store.clock.advanceHours(2)
            val stale = store.oracle.checkOnly(listOf("INV-26", "INV-27"), step = "aged part")
            assertEquals(
                listOf("INV-27"),
                stale.map { it.id },
                "only reachability speaks: INV-26 is about intent-named parts and 'halfdone' has no intent",
            )
            assertTrue(
                stale.single().detail.contains("halfdone"),
                "the counterexample must name the file: ${stale.single().detail}",
            )

            // The reclaim is the weekly full sweep, and the setup run already stamped its key — so
            // the clock has to cross the interval, and the file is re-aged against the new `now` so
            // the one-hour boundary is still the thing under test. This is the technique
            // `ReconcilerClockTest.partFileIsHeldForTheConfiguredPartGraceHours` uses, for the same
            // reason: two `run()` calls in a row are not two sweeps.
            store.clock.advanceMs(FULL_SWEEP_INTERVAL_MS + 120_000)
            store.age(store.paths.part(SongKey("saavn", "halfdone"), 128), 61 * 60_000)
            store.reconciler().run()
            assertTrue(
                store.fs.list(store.paths.audioDir).none { it.name.startsWith("saavn_halfdone_") },
                "the sweep must reclaim the unreachable bytes",
            )
            assertTrue(
                store.fs.list(store.paths.audioDir).any { it.name.startsWith("saavn_wip_") },
                "and must NOT reclaim the part an intent still names",
            )
        }

    /**
     * INV-26's failing direction, seeded directly: an intent names a `.part` whose sidecar is gone, so
     * the reconciler's next boot re-enqueues a transfer that can only start from zero.
     * `PartStore.loadFromDisk` reads sidecars and nothing else, which is what makes the sidecar the
     * only thing that can make a part resumable.
     *
     * MUTATION: `PartMeta.decode` accept a missing `bytes=` field (`?: return null` → `?: 0L`).
     * Then this sidecar would still "decode" and INV-26 would stay green on an unresumable part.
     */
    @Test
    fun anIntentNamedPartWithNoSidecarIsNotResumable() {
        assertEquals(
            emptyList(),
            store.oracle.checkOnly(listOf("INV-26"), step = "baseline"),
            "precondition: the fixture's intent-named part has a decodable sidecar",
        )
        store.sql.exec("DELETE FROM download_intents WHERE song_id = 'wip'")
        store.ledger.broke(
            what = "the reconciler drops the sidecar before re-enqueueing",
            suspends = setOf("INV-27"),
        )
        store.fs.delete(PartMeta.sidecarOf(store.paths.part(SongKey("saavn", "wip"), 128)), mustCheck = false)

        // Put the intent back: the point is a part that an intent names and that nothing can resume.
        store.sql.exec(
            "INSERT INTO download_intents(provider, song_id, reason, bitrate, enqueued_at_ms) " +
                "VALUES ('saavn', 'wip', 'PREFETCH_NEXT', 128, ${store.clock.nowMs()})",
        )
        val violations = store.oracle.checkOnly(listOf("INV-26"), step = "sidecar gone")
        assertEquals(listOf("INV-26"), violations.map { it.id }, "an intent-named part with no sidecar is unresumable")
        assertTrue(
            violations.single().detail.contains("saavn_wip_128.part"),
            "and the counterexample must name it: ${violations.single().detail}",
        )
    }

    /**
     * INV-28's failing direction, seeded directly: a `.part.meta` with no `.part` is a *phantom*
     * resumable part. On the next boot `loadFromDisk` seeds an index entry describing bytes that are
     * not there, and `PartStore.persist`'s `if (!fs.exists(part)) return` guard is the only reason the
     * current code does not write one after every completed download.
     *
     * MUTATION: **G-05** — `PartStore.persist` delete the `if (!fs.exists(part)) return` guard. That
     * does not fail *this* test (it needs a completed download to have just run), which is stated
     * here rather than claimed: G-05's witness is `S-DL-08` in M3.
     */
    @Test
    fun aSidecarWithoutItsPartIsAPhantomResumeRecord() {
        store.phantomSidecar("ghost", bytes = 2_048)
        val violations = store.oracle.checkOnly(listOf("INV-28"), step = "phantom sidecar")
        assertEquals(listOf("INV-28"), violations.map { it.id }, "a sidecar with no .part must be reported")
        assertTrue(
            violations.single().detail.contains("ghost"),
            "and the counterexample must name it: ${violations.single().detail}",
        )
    }

    /**
     * The plan guards, and the reason [PlanCondition] insists on a control. `EXPLAIN QUERY PLAN` is a
     * four-column result whose *text* is the last column, so "the plan must not sort" read off column
     * zero is vacuously true forever. This asserts all four halves at once.
     *
     * MUTATION: `Sql.plan` read column 0 instead of the last. Then neither query reports a sort, the
     * control assertion fires, and this test is red — which is the entire reason the control exists.
     */
    @Test
    fun thePlanChecksHaveAControlThatProvesTheyCanSeeASort() {
        assertEquals(
            emptyList(),
            store.oracle.checkOnly(listOf("INV-39"), step = "intents"),
            "the intent scheduler " +
                "must ride its index",
        )
        assertEquals(
            emptyList(),
            store.oracle.checkOnly(listOf("INV-39l"), step = "lru"),
            "the LRU victim order must be the index",
        )

        val control = store.sql.plan("SELECT * FROM download_intents ORDER BY enqueued_at_ms, priority")
        assertTrue(
            control.any { it.contains("TEMP B-TREE") },
            "the control query must report a sort, " +
                "or \"no sort\" says nothing: $control",
        )
        assertTrue(
            store.sql.plan(LRU_CONTROL_QUERY).any { it.contains("TEMP B-TREE") },
            "and so must the LRU control",
        )
        assertEquals(
            emptyList(),
            store.oracle.checkOnly(listOf("INV-39", "INV-39l"), step = "both"),
        )
    }

    /**
     * INV-04's *missing-row* half, and finding **F-2**'s reachability stated rather than asserted away.
     * `cachedCountAndBytes` coalesces a missing singleton to 0, so the app cannot tell "no rows yet"
     * from "the totals row is gone" — and `setPartTotals` is an `UPDATE`, so a fresh install with
     * `.part` files and no `library` row can never record `part_bytes` at all.
     *
     * The fix is not ours to make (it needs a decision about write frequency on the io lane), so this
     * test pins the *primitive* that makes the finding visible: a scalar read that distinguishes "no
     * row" from a real zero.
     *
     * MUTATION: `Sql.scalarLong` return 0 instead of -1 for "no row" — the trap its own KDoc names.
     * Then INV-04 would be green on a store with no totals row at all, which is the exact vacuity the
     * `-1` exists to prevent.
     */
    @Test
    fun aMissingTotalsSingletonIsVisibleToTheOracleAndNotToTheApp() {
        assertEquals(
            0L,
            store.sql.scalarLong(
                "SELECT COALESCE((SELECT final_bytes FROM cache_totals WHERE id = 0), -1) - " +
                    "COALESCE((SELECT SUM(bytes) FROM library), 0)",
            ),
            "precondition: the difference is zero while the row exists",
        )
        assertEquals(
            Sql.NO_ROW,
            store.sql.scalarLong("SELECT final_bytes FROM cache_totals WHERE id = 99"),
            "the primitive must distinguish 'no row' from a real zero",
        )
        assertEquals(
            0L,
            store.sql.scalarLong("SELECT COALESCE((SELECT final_bytes FROM cache_totals WHERE id = 99), 0)"),
            "…which is exactly the coalesce the app's own query performs, and it hides the difference",
        )
        assertEquals(
            6_700L,
            store.db.dylanQueries
                .cachedCountAndBytes()
                .executeAsOne()
                .total_bytes,
            "the app reads a coalesced figure, and cannot tell the two states apart",
        )
    }

    /**
     * INV-08b — a **known-red canary**, shipped disabled with the finding inline. Between boots nothing
     * publishes `part_bytes`: the only writer is `Reconciler.run()` → `CacheManager.refreshPartTotals`,
     * and the O(1) form documented as "for a caller that already tracks its own parts"
     * (`CacheManager.setPartTotals`) has zero callers. So the byte budget `claimLruVictims` reads is
     * stale by the size of the in-flight parts.
     *
     * Un-ignoring this is the owner's call (plan §13 Q2) and it is the exit criterion for **F-2**.
     * While `@Ignore`d it stays *compilable and runnable*, so un-ignoring it is a one-word edit rather
     * than a repair job — and a canary that cannot run cannot inform anybody.
     *
     * MUTATION: publish from `PartStore.persist` (a `setPartTotals` call), and this test goes green
     * with no other change. That is what makes it a canary and not a comment.
     */
    @Ignore("FOLLOW-UP (owner): INV-08b is expected RED on the current tree — F-2, plan §13 Q2.")
    @Test
    fun partTotalsArePublishedDuringASessionAndNotOnlyAtBoot() {
        store.orphanPart("late", bytes = 1_234, bits = 128, ageMs = 0)
        val violations = store.oracle.checkOnly(listOf("INV-08"), step = "mid-session")
        assertEquals(
            emptyList(),
            violations,
            "cache_totals.part_bytes must track the .part files without a reconcile: ${violations.map { it.detail }}",
        )
    }

    private fun skippedNote(): String {
        val s = store.oracle.lastSkipped
        return if (s.isEmpty()) "" else "\nskipped: ${s.joinToString { "${it.id} (${it.why})" }}"
    }

    private companion object {
        /** Songs · library · media_objects · final_bytes · final_count · intents, in one string. */
        const val SHAPE_ALL_ROWS =
            "SELECT (SELECT COUNT(*) FROM songs) || '/' || (SELECT COUNT(*) FROM library) || '/' || " +
                "(SELECT COUNT(*) FROM media_objects) || '/' || (SELECT " +
                "final_bytes FROM cache_totals WHERE id = 0) || '/' || " +
                "(SELECT final_count FROM cache_totals WHERE id = 0) || '/' || (SELECT COUNT(*) FROM download_intents)"

        /** Deliberately not the index order: SQLite must sort it, which is what makes it a control. */
        const val LRU_CONTROL_QUERY = "SELECT provider FROM library ORDER BY bytes DESC"

        /**
         * `Reconciler.FULL_SWEEP_INTERVAL_MS`, which is private to that class and owned by another
         * change. Duplicated here rather than made internal, because a test reaching into production
         * for a constant is how a test starts asserting the implementation instead of the contract —
         * and the value is also stated in `Reconciler.fullSweepDue`'s own KDoc.
         */
        const val FULL_SWEEP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Every id the plan's §6 catalogue names for a healthy store. The two that are *not* here are
         * explained in `DbOracle`'s KDoc: `INV-08b` is the @Ignore'd canary and the engine- and
         * transition-dependent conditions are M3+ scenarios.
         */
        val PLANNED_CATALOGUE =
            setOf(
                "INV-01",
                "INV-02",
                "INV-03",
                "INV-03i",
                "INV-03t",
                "INV-03v",
                "INV-04",
                "INV-05",
                "INV-06",
                "INV-06n",
                "INV-07",
                "INV-08",
                "INV-10",
                "INV-11",
                "INV-12",
                "INV-12i",
                "INV-13",
                "INV-14",
                "INV-15",
                "INV-16",
                "INV-18",
                "INV-19a",
                "INV-19b",
                "INV-20",
                "INV-21",
                "INV-22",
                "INV-25",
                "INV-26",
                "INV-27",
                "INV-28",
                "INV-32",
                "INV-34",
                "INV-37",
                "INV-38",
                "INV-39",
                "INV-39l",
                "INV-40a",
                "INV-40b",
                "INV-40c",
                "INV-40d",
                "INV-41",
            )
    }
}
