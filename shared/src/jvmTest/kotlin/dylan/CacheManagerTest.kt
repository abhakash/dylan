package dylan

import dylan.cache.CacheManager
import dylan.cache.CachePath
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.model.SongKey
import dylan.support.TestLanes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fixture every cache test in this file shares: a fresh temp root, a real on-disk database, a
 * real [CacheManager] over it, and the four seeded rows (`n1`, `p1`, `p2`, `n2`) the eviction-order
 * assertions count on. Split out so each subject below is a class of its own; the setup here is
 * byte-for-byte the one the single class used to run, so no test changed state by moving.
 */
abstract class CacheManagerFixture {
    protected lateinit var tmp: String
    protected lateinit var db: Dylan
    protected lateinit var cacheManager: CacheManager
    protected val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())

    // Virtual lanes: `enforceBudget` reaches the database through a `withContext` per batch, and
    // on a real `limitedParallelism(1)` lane every one of those is a thread hop with its own
    // dispatch latency — several hundred of them for the 300-pin wave. Sharing the runTest
    // scheduler makes them scheduler steps instead, with the single-permit contract intact.
    protected val scheduler = TestCoroutineScheduler()
    private val disp = TestLanes.virtual(scheduler).disp

    /**
     * The class fixture's budget is DERIVED (`cacheMaxFiles x AppConfig.CACHE_MEAN_TRACK_BYTES`),
     * so a byte budget is now requested by naming a file count: 26 files is a 26 MB byte budget,
     * the smallest count that still leaves the three rows of
     * [partBytesCountTowardUsageOnlyAfterTheyAreReported] (25 MB) inside it *and* leaves the
     * 2 MB of `.part` bytes reported by that test outside it. Both are asserted there, so the
     * number is load-bearing rather than arbitrary.
     */
    protected val cfg = AppConfig(cacheTargetBytes = SMALL_TARGET_BYTES)
    private val log = LogBuffer(minLevel = LogLevel.DEBUG)

    @BeforeTest
    fun setup() {
        tmp = freshDir("dylan-test")
        val fs = FileSystem.SYSTEM
        db = Dylan(DriverFactory("$tmp/dylan.db", log).createDriver())
        cacheManager = CacheManager(db, fs, Paths(tmp.toPath() / "audio", fs), protectedKeys, cfg, disp, log)
        var i = 0

        fun song(id: String) =
            "saavn".let { p ->
                db.dylanQueries.insertSong(p, id, id, "", null, null, "", "", 200L, 1L, "ref", null, 0L)
                SongKey(p, id)
            }

        fun cached(
            key: SongKey,
            bytes: Long = 10_000_000L,
            lastUsed: Long? = null,
            playCount: Long = 0,
            pinned: Long = 0,
            pinnedAt: Long? = null,
        ) {
            db.dylanQueries.insertCached(
                key.provider,
                key.songId,
                128L,
                "m4a",
                bytes,
                i++.toLong(),
                lastUsed,
                playCount,
                pinned,
                pinnedAt,
            )
        }
        // unplayed-newest | played-recently | played-old | never-played-old
        cached(song("n1"))
        cached(song("p1"), lastUsed = 5_000L, playCount = 3)
        cached(song("p2"), lastUsed = 1_000L, playCount = 1)
        cached(song("n2"), lastUsed = null)
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    protected fun dylan.cache.DownloadEntry.songId(): String = key.songId

    protected fun Dylan.songCount(): Long =
        dylanQueries
            .cachedCountAndBytes()
            .executeAsOne()
            .song_count

    protected fun Dylan.partBytes(): Long =
        dylanQueries
            .partTotals()
            .executeAsOne()
            .part_bytes

    protected fun pinAt(row: dylan.db.Cached_files): Long = row.pinned_at_ms ?: 0L

    protected suspend fun withFreshCache(
        cfg: AppConfig = this.cfg,
        block: suspend (Dylan, CacheManager, String) -> Unit,
    ) {
        val root = freshDir("dylan-parts")
        val fs = FileSystem.SYSTEM
        val db2 = Dylan(DriverFactory("$root/dylan.db", log).createDriver())
        val paths = Paths(root.toPath() / "audio", fs)
        fs.createDirectories(paths.audioDir)
        val cm2 = CacheManager(db2, fs, paths, MutableStateFlow(emptySet()), cfg, disp, log)
        try {
            block(db2, cm2, root)
        } finally {
            runCatching { fs.deleteRecursively(root.toPath()) }
        }
    }

    private fun freshDir(prefix: String): String =
        (FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/$prefix-${System.nanoTime()}").also {
            FileSystem.SYSTEM.createDirectories(it.toPath())
        }
}

/**
 * The byte budget the LRU pass enforces: victim order, the exempt key, the file cap's pending-row
 * reservation, the derivation of both caps from one knob, and when `.part` bytes count as usage.
 */
class CacheManagerBudgetTest : CacheManagerFixture() {
    @Test
    fun unplayedEvictsBeforeRecentlyPlayed() =
        runTest(scheduler) {
            cacheManager.enforceBudget(netNewBytes = 9_000_000L)
            val rows =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .map { it.song_id }
            assertEquals(listOf("p1"), rows, "unplayed + oldest-played evicted; recently played survives")
        }

    @Test
    fun exemptKeyNeverEvictedInOwnPass() =
        runTest(scheduler) {
            val key = SongKey("saavn", "fresh")
            db.dylanQueries.insertSong(
                key.provider,
                key.songId,
                key.songId,
                "",
                null,
                null,
                "",
                "",
                200L,
                1L,
                "ref",
                null,
                0L,
            )
            db.dylanQueries.insertCached(key.provider, key.songId, 320L, "m4a", 20_000_000L, 999L, null, 0L, 0L, null)
            cacheManager.enforceBudget(exemptKeys = setOf(key))
            assertEquals(
                key.songId,
                db.dylanQueries
                    .selectCached(key.provider, key.songId)
                    .executeAsOneOrNull()
                    ?.song_id,
            )
        }

    /**
     * CA-1. `while (usage > cap || count + 1 > maxFiles)` reserved a phantom slot on every call,
     * including the four whose row was already committed. At the cap, every completed download and
     * every favourite destroyed one extra unrelated file. The reservation is now an explicit
     * parameter, and this is the boundary: exactly at the cap with nothing pending, zero victims.
     */
    @Test
    fun atTheFileCapWithNoPendingRowNothingIsEvicted() =
        runTest(scheduler) {
            val capCfg = AppConfig(cacheMaxFiles = 5)
            withFreshCache(cfg = capCfg) { db2, cm2, _ ->
                repeat(5) { idx ->
                    val id = "k$idx"
                    db2.dylanQueries.insertSong("saavn", id, id, "", null, null, "", "", 200L, 1L, "ref", null, 0L)
                    db2.dylanQueries.insertCached("saavn", id, 128L, "m4a", 1_000L, idx.toLong(), null, 0L, 0L, null)
                }
                assertEquals(5L, db2.songCount())

                cm2.enforceBudget()
                assertEquals(
                    5L,
                    db2.songCount(),
                    "count == cacheMaxFiles with nothing pending must evict nothing",
                )

                // Control: the same state with one row genuinely pending DOES evict exactly one.
                cm2.enforceBudget(pendingRows = 1)
                assertEquals(4L, db2.songCount())
            }
        }

    /**
     * The byte budget is DERIVED from the file cap, and this pins that. It used to be an independent
     * 2 GB which `min(2 GB, 300 x 1 MB)` could never reach, so the figure both platform screens
     * print and divide by was not the figure the LRU pass enforced — a 2 GB progress bar that can
     * never leave 15 %. One knob, one number: raising the file cap raises the bytes those files may
     * occupy, and the budget `CacheManager` enforces is that same number.
     */
    @Test
    fun theByteBudgetIsDerivedFromTheFileCapSoTheEnforcedAndDisplayedNumbersCannotDisagree() =
        runTest(scheduler) {
            val defaults = AppConfig()
            // Bytes are the primary knob now; the row cap is derived from them. This is the
            // direction that makes the advertised figure real — it used to be inverted, so the
            // enforced budget was 300 MB while the UI showed 2 GB.
            assertEquals(
                defaults.cacheTargetBytes + defaults.cacheTargetBytes / 8,
                defaults.cacheMaxBytes,
                "the enforced ceiling must be the target plus its headroom, so the wall is not " +
                    "the advertised number",
            )
            assertEquals(
                (defaults.cacheMaxBytes / MEAN_TRACK_BYTES).toInt(),
                defaults.cacheMaxFiles,
                "the row cap must be derived from the byte budget, not the other way round",
            )
            val bigger = AppConfig(cacheTargetBytes = defaults.cacheTargetBytes * 2)
            assertEquals(
                defaults.cacheMaxBytes * 2,
                bigger.cacheMaxBytes,
                "doubling the byte target must double the enforced ceiling",
            )
            // Truncating division, so doubling the byte budget moves the row cap by at most one
            // row rather than exactly two-fold. Asserting exact doubling would be asserting an
            // arithmetic property the derivation does not have.
            assertTrue(
                bigger.cacheMaxFiles in (defaults.cacheMaxFiles * 2 - 1)..(defaults.cacheMaxFiles * 2 + 1),
                "the derived row cap must scale with the byte budget, " +
                    "was ${defaults.cacheMaxFiles} now ${bigger.cacheMaxFiles}",
            )
            assertTrue(
                defaults.cacheMaxBytes >= 2L * 1024 * 1024 * 1024,
                "the advertised budget must actually be at least 2 GB, was ${defaults.cacheMaxBytes}",
            )
            withFreshCache { _, cm2, _ ->
                assertEquals(
                    cfg.cacheMaxBytes,
                    cm2.byteBudget,
                    "the budget CacheManager enforces must be the one config derives and both UIs show",
                )
            }
        }

    @Test
    fun partBytesCountTowardUsageOnlyAfterTheyAreReported() =
        runTest(scheduler) {
            withFreshCache { db2, cm2, root2 ->
                fun seed(
                    id: String,
                    bytes: Long,
                    playCount: Long,
                    lastUsed: Long?,
                ) {
                    db2.dylanQueries.insertSong("saavn", id, id, "", null, null, "", "", 200L, 1L, "ref", null, 0L)
                    db2.dylanQueries.insertCached("saavn", id, 128L, "m4a", bytes, 0L, lastUsed, playCount, 0L, null)
                }
                seed("k1", 10_000_000L, playCount = 0, lastUsed = null)
                seed("k2", 10_000_000L, playCount = 1, lastUsed = 1_000L)
                seed("k3", 5_000_000L, playCount = 5, lastUsed = 9_000L)
                val (c, b) = db2.dylanQueries.cachedCountAndBytes().executeAsOne()
                assertEquals(3L, c)
                assertTrue(
                    b < cfg.cacheMaxBytes,
                    "precondition: rows alone must fit, they are $b of ${cfg.cacheMaxBytes}",
                )

                val audio = root2.toPath() / "audio"
                FileSystem.SYSTEM.createDirectories(audio)
                FileSystem.SYSTEM.write(audio / "saavn_stray_128.part") { write(ByteArray(2_000_000)) }

                // A caller that has not reported its parts has no parts on the books, and the
                // budget check is O(1) instead of a directory walk.
                cm2.enforceBudget()
                assertEquals(
                    3L,
                    db2.songCount(),
                    "an unreported .part must not silently move the goalposts",
                )

                cm2.refreshPartTotals()
                assertEquals(2_000_000L, db2.partBytes())

                cm2.enforceBudget()
                val after =
                    db2.dylanQueries
                        .selectAllCached()
                        .executeAsList()
                        .map { it.song_id }
                        .toSet()
                assertTrue(
                    after.size < 3,
                    "2 MB of .part bytes must count toward the budget: 25 MB + 2 MB > 26 MB, " +
                        "so a victim is due, survivors=$after",
                )
                assertTrue(
                    "k1" !in after && "k2" in after && "k3" in after,
                    "LRU order is (never played) > (oldest play) > (newest play), so k1 is the victim: $after",
                )
            }
        }
}

/**
 * The pinned sub-pool, which has its own budgets: the oversized-pin demotion, the demotion wave
 * that must notify the user and demote oldest-pinned-first, and the row budget that keeps the file
 * cap enforceable at all.
 */
class CachePinBudgetTest : CacheManagerFixture() {
    /** 300 x 5 MB against this budget's 75 % pinned cap: a real demotion wave with survivors. */
    private val bulkPinBytes = 5_000_000L

    /**
     * @Ignore removed. The counterexample was real: v0's pinned loop was bounded by bytes only, so
     * one pinned file larger than the whole cap walked the pool to empty — demoting the user's only
     * offline copy of an explicitly favourited track. The pinned sub-pool now has a row budget from
     * the same 0.75 fraction and a hard floor of one survivor, so this is enforced rather than
     * documented.
     */
    @Test
    fun aPinLargerThanTheCapIsDemotedEvenThoughItIsTheOnlyOfflineCopy() =
        runTest(scheduler) {
            db.dylanQueries.setPin(1L, 100L, "saavn", "n1")
            db.dylanQueries.setPin(1L, 200L, "saavn", "p1")
            repeat(3) { idx ->
                val k = SongKey("saavn", "big$idx")
                db.dylanQueries.insertSong(
                    k.provider,
                    k.songId,
                    k.songId,
                    "",
                    null,
                    null,
                    "",
                    "",
                    200L,
                    1L,
                    "ref",
                    null,
                    0L,
                )
                db.dylanQueries.insertCached(
                    k.provider,
                    k.songId,
                    128L,
                    "m4a",
                    20_000_000L,
                    idx.toLong(),
                    null,
                    0,
                    1,
                    (idx + 10).toLong(),
                )
            }
            cacheManager.enforceBudget()
            val pinned =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .filter { it.pinned == 1L }
            assertTrue(
                pinned.isNotEmpty(),
                "an explicitly pinned track must not be demoted by its own pin",
            )
            assertTrue(
                db.dylanQueries.pinnedBytes().executeAsOne() <= cacheManager.pinnedByteBudget,
                "and the survivors must fit the pinned byte budget",
            )
        }

    @Test
    fun pinnedDemotionRespectsThePinnedCapAndDemotesOldestPinFirst() =
        runTest(scheduler) {
            db.dylanQueries.setPin(1L, 100L, "saavn", "n1")
            db.dylanQueries.setPin(1L, 200L, "saavn", "p1")
            var notified = false
            cacheManager.notifyOnce = { notified = true }
            repeat(300) { idx ->
                val k = SongKey("saavn", "bulk$idx")
                db.dylanQueries.insertSong(
                    k.provider,
                    k.songId,
                    k.songId,
                    "",
                    null,
                    null,
                    "",
                    "",
                    200L,
                    1L,
                    "ref",
                    null,
                    0L,
                )
                db.dylanQueries.insertCached(
                    k.provider,
                    k.songId,
                    128L,
                    "m4a",
                    bulkPinBytes,
                    idx.toLong(),
                    null,
                    0,
                    1,
                    (idx + 10).toLong(),
                )
            }
            val all = db.dylanQueries.selectAllCached().executeAsList()
            assertEquals(302, all.count { it.pinned == 1L }, "precondition: 300 bulk pins plus n1 and p1")

            cacheManager.enforceBudget()

            val after = db.dylanQueries.selectAllCached().executeAsList()
            val survivors = after.filter { it.pinned == 1L }
            val demoted = all.filter { row -> after.none { it.song_id == row.song_id && it.pinned == 1L } }

            val pinnedBytes = db.dylanQueries.pinnedBytes().executeAsOne()
            assertTrue(notified, "a demotion wave must tell the user their favourites moved")
            assertTrue(
                survivors.isNotEmpty(),
                "the pinned sub-pool must not be emptied outright; survivors=${survivors.map { it.song_id }}",
            )
            assertTrue(
                demoted.isNotEmpty(),
                "300 x 5 MB against a 25 MB budget cannot fit without demotion",
            )
            assertTrue(
                pinnedBytes <= cacheManager.pinnedByteBudget,
                "pinned pool must fit its 75% budget: $pinnedBytes > ${cacheManager.pinnedByteBudget}",
            )
            assertTrue(
                demoted.maxOf { pinAt(it) } <= survivors.minOf { pinAt(it) },
                "demotion must be strictly oldest-pinned-first: " +
                    "survivors=${survivors.map { it.song_id to pinAt(it) }} " +
                    "demoted-up-to=${demoted.maxOf { pinAt(it) }}",
            )
        }

    /**
     * The pinned sub-pool's row budget, which is what makes the file cap enforceable at all.
     * 300 favourites x 4 MB is 1.2 GB, comfortably under a 2 GB byte cap, so the v0 byte-only loop
     * ran zero iterations, `lruVictim` (WHERE pinned = 0) matched nothing, and `?: break` exited
     * with the cap permanently unenforceable and no user-visible signal.
     *
     * The rows are sized to isolate the *row* budget. `CacheManager.byteBudget` is derived from the
     * file cap (`cacheMaxFiles x 1 MB`), so 10 files is a 10 MB byte budget and 12 x 4 MB would put
     * the pool 6x over the 7.5 MB pinned byte budget: a true result, but of the byte cap rather than
     * of the row cap under test. 12 x 500 KB is 6 MB against that same 7.5 MB pinned byte budget, so
     * only the row budget can bind.
     */
    @Test
    fun thePinnedPoolHasARowBudgetSoTheFileCapStaysEnforceable() =
        runTest(scheduler) {
            withFreshCache(cfg = AppConfig(cacheTargetBytes = 8_000_000L)) { db2, cm2, _ ->
                repeat(12) { idx ->
                    val k = SongKey("saavn", "fav$idx")
                    db2.dylanQueries
                        .insertSong("saavn", k.songId, k.songId, "", null, null, "", "", 200L, 1L, "r", null, 0L)
                    db2.dylanQueries.insertCached(
                        "saavn",
                        k.songId,
                        128L,
                        "m4a",
                        PINNED_FIXTURE_BYTES,
                        idx.toLong(),
                        null,
                        0L,
                        1L,
                        (idx + 1).toLong(),
                    )
                }
                assertEquals(12L, db2.dylanQueries.pinnedCount().executeAsOne())
                assertTrue(
                    12 * PINNED_FIXTURE_BYTES <= cm2.pinnedByteBudget,
                    "precondition: the pool must sit inside its pinned byte budget, or the row budget is " +
                        "not what is under test (${12 * PINNED_FIXTURE_BYTES} > ${cm2.pinnedByteBudget})",
                )

                cm2.enforceBudget()

                val pinned = db2.dylanQueries.pinnedCount().executeAsOne()
                assertEquals(
                    cm2.pinnedRowBudget.toLong(),
                    pinned,
                    "12 pins x 500 KB is under the pinned byte budget, so only a ROW budget can hold the pool",
                )
                assertTrue(pinned >= 1L, "the pool must keep at least one favourite")
            }
        }
}

/**
 * Protection and the claim/reap state machine: what `clearCacheExcludingProtected` and
 * `reapEvicting` may destroy, what the claim statement itself must honour, and the single-key
 * `evictOne` path.
 */
class CacheProtectionTest : CacheManagerFixture() {
    @Test
    fun clearCacheKeepsProtected() =
        runTest(scheduler) {
            protectedKeys.value = setOf(SongKey("saavn", "p1"))
            cacheManager.clearCacheExcludingProtected()
            val rows =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .map { it.song_id }
            assertTrue("p1" in rows)
            assertFalse("n1" in rows)
        }

    /**
     * The class rule is that the protection table is republished inside the same transaction as
     * the claim. `clearCacheExcludingProtected` did not, so it claimed against whatever the *last*
     * mutating transaction happened to leave in `protected_keys` — a track the user had started
     * playing since then was claimable, and `reapEvicting`, which finishes EVICTING rows on the
     * next boot with no protection check at all, unlinked the file the user was listening to.
     */
    @Test
    fun clearCacheCannotLeaveAProtectedSongClaimedForTheBootReap() =
        runTest(scheduler) {
            val fs = FileSystem.SYSTEM
            val paths = Paths(tmp.toPath() / "audio", fs)
            fs.createDirectories(paths.audioDir)
            val key = SongKey("saavn", "p1")
            val file = paths.final(key, 128, "m4a")
            fs.write(file) { write(ByteArray(10_000_000)) }
            // The user starts playing p1 *after* the table was last written: the flow knows, the
            // table does not.
            db.transaction { db.dylanQueries.clearProtectedKeys() }
            protectedKeys.value = setOf(key)

            cacheManager.clearCacheExcludingProtected()

            assertTrue(fs.exists(file), "clear cache must not unlink the playing track")
            assertEquals(
                0,
                db.dylanQueries
                    .evictingObjects()
                    .executeAsList()
                    .count { it.song_id == "p1" },
                "and it must not be left claimed: nothing here is going to destroy that row",
            )
            cacheManager.reapEvicting()
            assertTrue(fs.exists(file), "the boot reap must not be the thing that finally deletes it")
            assertEquals(
                1L,
                db.dylanQueries
                    .cachedCountAndBytes()
                    .executeAsOne()
                    .song_count,
                "precondition, and the real assertion: the protected row is still the library's",
            )
        }

    /**
     * The other half of the same rule, and the reachable one: `reapEvicting` is the destroyer that
     * runs on the *next* boot, with no claim in front of it to have honoured the protection set.
     * An EVICTING row is a promise made under an earlier process's protection set, and protection
     * is not static — a key that was evictable then can be the track the user is listening to now.
     */
    @Test
    fun theBootReapDoesNotUnlinkAnInterruptedEvictionTheTrackHasSinceBecomeProtected() =
        runTest(scheduler) {
            val fs = FileSystem.SYSTEM
            val paths = Paths(tmp.toPath() / "audio", fs)
            fs.createDirectories(paths.audioDir)
            val key = SongKey("saavn", "n1")
            // Only n1 is claimed, so the assertion is about n1 and nothing else.
            listOf("p1", "p2", "n2").forEach { db.dylanQueries.deleteCached("saavn", it) }
            val file = paths.final(key, 128, "m4a")
            fs.write(file) { write(ByteArray(10_000_000)) }
            db.transaction { db.dylanQueries.clearProtectedKeys() }
            assertEquals(
                1,
                db.dylanQueries
                    .claimAllUnprotected()
                    .executeAsList()
                    .size,
                "precondition: claimed",
            )
            assertTrue(fs.exists(file), "precondition: claimed, not yet unlinked")

            // The user starts playing the track the interrupted eviction was going to destroy.
            protectedKeys.value = setOf(key)
            assertEquals(0, cacheManager.reapEvicting(), "a now-protected claim is not reaped")
            assertTrue(fs.exists(file), "the boot reap must not be the thing that deletes it")
            assertEquals(
                0,
                db.dylanQueries
                    .evictingObjects()
                    .executeAsList()
                    .count { it.song_id == "n1" },
                "and it must go back to READY, so the next sweep re-derives it",
            )

            // Not an amnesty: the restored row is an ordinary READY one, so the next budget pass
            // re-derives whether it is still the right victim, and reclaims it once it is.
            assertEquals(
                1L,
                db.dylanQueries
                    .cachedCountAndBytes()
                    .executeAsOne()
                    .song_count,
                "the row is still there",
            )
            protectedKeys.value = emptySet()
            cacheManager.enforceBudget(netNewBytes = 90_000_000L)
            assertFalse(fs.exists(file), "and it is evictable again the moment nothing protects it")
        }

    /**
     * CA-2/CA-3's class fix: the claim statement itself consults the `protected_keys` table, so
     * there is no window between "decide who is protected" and "delete them" for a caller to slip
     * into. Asserted at the SQL boundary, which is where the guarantee now lives.
     */
    @Test
    fun theClaimStatementItselfHonoursTheProtectionTable() {
        protectedKeys.value = setOf(SongKey("saavn", "p1"))
        db.transaction {
            db.dylanQueries.clearProtectedKeys()
            db.dylanQueries.putProtectedKey("saavn", "p1", "PLAYING")
        }
        val claimed = db.dylanQueries.claimAllUnprotected().executeAsList()
        assertFalse(claimed.any { it.song_id == "p1" }, "a protected key must never be claimable")
        assertTrue(claimed.any { it.song_id == "n1" })
        assertEquals(
            1L,
            db.dylanQueries.protectedKeyCount().executeAsOne(),
            "the protection set is a table now, not three .value reads in a composable",
        )
    }

    @Test
    fun evictOneRefusesAProtectedKeyAndKeepsItsFile() {
        val fs = FileSystem.SYSTEM
        val paths = Paths(tmp.toPath() / "audio", fs)
        fs.createDirectories(paths.audioDir)
        fs.write(paths.final(SongKey("saavn", "n1"), 128, "m4a")) { write(ByteArray(10_000_000)) }
        protectedKeys.value = setOf(SongKey("saavn", "n1"))
        runTest(scheduler) {
            assertFalse(cacheManager.evictOne(SongKey("saavn", "n1")), "a protected key must be refused")
            assertTrue(fs.exists(paths.final(SongKey("saavn", "n1"), 128, "m4a")), "and its file must survive")
        }
    }

    @Test
    fun evictOneRemovesAnUnprotectedKeyAndItsFile() {
        val fs = FileSystem.SYSTEM
        val paths = Paths(tmp.toPath() / "audio", fs)
        fs.createDirectories(paths.audioDir)
        val file = paths.final(SongKey("saavn", "n1"), 128, "m4a")
        fs.write(file) { write(ByteArray(10_000_000)) }
        runTest(scheduler) {
            assertTrue(cacheManager.evictOne(SongKey("saavn", "n1")))
            assertFalse(fs.exists(file))
            assertEquals(null, db.dylanQueries.selectCached("saavn", "n1").executeAsOneOrNull())
        }
    }

    /**
     * CA-4's class rule: a file we could not unlink keeps its row, so the next sweep retries it.
     * A non-empty directory at the final path is the one unlink failure that is reproducible.
     */
    @Test
    fun aFailedUnlinkKeepsTheRowForTheNextSweep() {
        val fs = FileSystem.SYSTEM
        val paths = Paths(tmp.toPath() / "audio", fs)
        fs.createDirectories(paths.audioDir)
        val key = SongKey("saavn", "n1")
        val blocked = paths.final(key, 128, "m4a")
        fs.createDirectories(blocked)
        fs.write(blocked / "occupied") { write(ByteArray(8)) }
        runTest(scheduler) {
            cacheManager.enforceBudget(netNewBytes = 90_000_000L)
            assertTrue(
                db.dylanQueries.selectCached("saavn", "n1").executeAsOneOrNull() != null,
                "the row must survive an unlink that failed, or the track is gone for good",
            )
            assertEquals(
                0L,
                db.dylanQueries
                    .evictingObjects()
                    .executeAsList()
                    .count { it.song_id == "n1" }
                    .toLong(),
                "and it must go back to READY rather than sit in EVICTING",
            )
        }
    }

    /**
     * The other half of the state machine: a process death between the claim and the unlink leaves
     * an EVICTING row, and [CacheManager.reapEvicting] is what finishes it. Idempotent by
     * construction — a second call finds nothing to do.
     */
    @Test
    fun anInterruptedEvictionIsReapedOnTheNextBoot() {
        val fs = FileSystem.SYSTEM
        val paths = Paths(tmp.toPath() / "audio", fs)
        fs.createDirectories(paths.audioDir)
        val key = SongKey("saavn", "n1")
        // Narrowed to one cached row: `setup` leaves four, and `claimAllUnprotected` claims every
        // one of them, so a test that says "one interrupted eviction" has to say so in its fixture.
        listOf("p1", "p2", "n2").forEach { db.dylanQueries.deleteCached("saavn", it) }
        fs.write(paths.final(key, 128, "m4a")) { write(ByteArray(10_000_000)) }
        db.transaction { db.dylanQueries.clearProtectedKeys() }
        val claimed = db.dylanQueries.claimAllUnprotected().executeAsList()
        assertEquals(1, claimed.size, "precondition: exactly one row is claimed")
        assertTrue(fs.exists(paths.final(key, 128, "m4a")), "precondition: claimed, not yet unlinked")

        runTest(scheduler) {
            assertEquals(1, cacheManager.reapEvicting(), "the interrupted eviction must be finished, not re-claimed")
            assertFalse(fs.exists(paths.final(key, 128, "m4a")))
            assertEquals(0, cacheManager.reapEvicting(), "a second reap must be a no-op")
        }
    }
}

/**
 * What is left of the original single class and is not about eviction budgets: the download-intent
 * upsert priority, the removable-key flow, and the on-disk file-name grammar.
 */
class CacheManagerTest : CacheManagerFixture() {
    @Test
    fun intentUpsertNeverDowngradesPriority() =
        runTest(scheduler) {
            db.dylanQueries.upsertIntent("saavn", "x", "USER_BULK", 320L, 1L)
            db.dylanQueries.upsertIntent("saavn", "x", "PREFETCH_NEXT", 128L, 2L)
            val intent =
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .first { it.song_id == "x" }
            assertEquals("USER_BULK", intent.reason, "lower-priority enqueue must not replace")
            assertEquals(1L, intent.priority, "priority is stored, not recomputed per query")
            db.dylanQueries.upsertIntent("saavn", "x", "USER_NOW", 320L, 3L)
            val upgraded =
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .first { it.song_id == "x" }
            assertEquals("USER_NOW", upgraded.reason)
            assertEquals(0L, upgraded.priority)
        }

    @Test
    fun removableKeysFollowTheProtectionFlows() =
        runTest(scheduler) {
            val before =
                cacheManager.downloads
                    .first()
            assertTrue(before.all { it.removable })
            assertEquals(4, before.size, "one JOIN, one row per cached song")

            protectedKeys.value = setOf(SongKey("saavn", "n1"))
            val after =
                cacheManager.downloads
                    .first()
            assertFalse(after.first { it.songId() == "n1" }.removable)
            assertTrue(after.first { it.songId() == "p1" }.removable)
            assertFalse(cacheManager.isRemovable(SongKey("saavn", "n1")))
            assertTrue(cacheManager.isRemovable(SongKey("saavn", "p1")))
        }

    @Test
    fun theFileNameGrammarRoundTripsAndRefusesForeignFiles() {
        val parsed = CachePath.parse(CachePath.fileName("saavn", "abc-123", 128, "m4a"))
        assertEquals(CacheFileNameAssert("saavn", "abc-123", 128, "m4a"), CacheFileNameAssert.of(parsed))
        assertEquals(null, CachePath.parse("cover.jpg"), "a cover is not ours to delete")
        assertEquals(null, CachePath.parse("cover_500.jpg"), "two segments is not our shape")
        val debris = CachePath.parse("saavn_x_128.m4a.part.tmp")
        assertEquals(null, debris, "an interrupted unlink's debris is not ours either")
        assertEquals("m4a", CachePath.parse("saavn_x_128.m4a")?.ext)
        // A provider id is sanitised too, which §8.2's adapter-boundary rule never applied to.
        val escaped = CachePath.fileName("../../etc", "x", 128, "m4a")
        assertFalse(escaped.contains('/'), "provider must be sanitised: $escaped")
        assertEquals(
            "saavn_x_128.m4a",
            CachePath.fileName("saavn", "x", 128, "m4a"),
            "the on-disk layout must not move",
        )
    }
}

/**
 * Small enough that 12 pins sit inside the pinned byte budget, so the row budget is the binding
 * constraint. See `thePinnedPoolHasARowBudgetSoTheFileCapStaysEnforceable`.
 */
private const val PINNED_FIXTURE_BYTES = 500_000L

/**
 * The assumed mean rendition size `AppConfig` multiplies the file cap by. Mirrored rather than
 * imported (it is private there), so that changing the production constant has to update this
 * value deliberately — the derivation is what this test class is asserting, not the arithmetic.
 */
private const val MEAN_TRACK_BYTES = 1_000_000L

/**
 * A byte target small enough that a handful of rows fills the cache, so eviction is reachable in a
 * unit test. 23 MB target + 12.5% headroom => ~26 MB enforced, matching what these tests assert.
 */
private const val SMALL_TARGET_BYTES = 23_000_000L

private data class CacheFileNameAssert(
    val provider: String,
    val songId: String,
    val bitrate: Int,
    val ext: String,
) {
    companion object {
        fun of(parsed: dylan.cache.CacheFileName?): CacheFileNameAssert? = parsed?.let(::from)

        private fun from(n: dylan.cache.CacheFileName) = CacheFileNameAssert(n.provider, n.songId, n.bitrate, n.ext)
    }
}
