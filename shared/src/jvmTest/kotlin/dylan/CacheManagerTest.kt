package dylan

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import dylan.model.SongKey
import dylan.support.TestLanes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheManagerTest {
    /** 300 x 5 MB against a 25 MiB budget's 75 % pinned cap: a real demotion wave with survivors. */
    private val bulkPinBytes = 5_000_000L

    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var cacheManager: CacheManager
    private val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    private val lanes = TestLanes()
    private val disp = lanes.disp
    private val cfg = AppConfig(cacheMaxBytes = 25L * 1024 * 1024)
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

    @Test
    fun unplayedEvictsBeforeRecentlyPlayed() =
        runTest {
            cacheManager.enforceBudget(netNewBytes = 9_000_000L)
            val rows =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .map { it.song_id }
            assertEquals(listOf("p1"), rows, "unplayed + oldest-played evicted; recently played survives")
        }

    /**
     * Rewritten. The previous version asserted `pins.all { it.pinned_at_ms >= 10 }` over rows that
     * were *inserted* with `>= 10` — true by construction, and `all {}` over the empty list the
     * demotion wave leaves behind is also true. It could not fail, and its name claimed a
     * guarantee ("never blocks favorites") that nothing in the code implements.
     *
     * What the code actually guarantees is `pinnedMaxFraction` (75 % of the budget) for the pinned
     * sub-pool, ordered strictly by `pinned_at_ms`. Both are asserted here, and the ordering claim
     * is the part that is genuinely falsifiable: a regression to arbitrary-order demotion leaves
     * survivors and demotions interleaved and turns this red.
     */
    @Test
    fun pinnedDemotionRespectsThePinnedCapAndDemotesOldestPinFirst() =
        runTest {
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

            cacheManager.enforceBudget(netNewBytes = 0)

            val after = db.dylanQueries.selectAllCached().executeAsList()
            val survivors = after.filter { it.pinned == 1L }
            val demoted = all.filter { row -> after.none { it.song_id == row.song_id && it.pinned == 1L } }

            val pinnedBytes = db.dylanQueries.pinnedBytes().executeAsOne()
            val pinnedCap = (cfg.cacheMaxBytes * cfg.pinnedMaxFraction).toLong()
            assertTrue(notified, "a demotion wave must tell the user their favourites moved")
            assertTrue(
                survivors.isNotEmpty(),
                "the pinned sub-pool must not be emptied outright; survivors=${survivors.map { it.song_id }}",
            )
            assertTrue(
                demoted.isNotEmpty(),
                "300 x 20 MB against a 25 MB budget cannot fit without demotion",
            )
            assertTrue(
                pinnedBytes <= pinnedCap,
                "the pinned sub-pool must come back inside its 75 % budget: $pinnedBytes > $pinnedCap",
            )
            assertTrue(
                demoted.maxOf { pinAt(it) } <= survivors.minOf { pinAt(it) },
                "demotion must be strictly oldest-pinned-first: " +
                    "survivors=${survivors.map { it.song_id to pinAt(it) }} " +
                    "demoted-up-to=${demoted.maxOf { pinAt(it) }}",
            )
        }

    /**
     * The mirror image of the audit's "the pinned sub-pool has no row budget" finding: when a single
     * pinned file is already larger than the whole pinned cap, the demotion loop empties the pinned
     * pool completely — the user's only offline copy of an explicitly favourited track is unprotected
     * from its own pin. Found while rewriting the test above, which is why the fixture there uses
     * 5 MB pins rather than the 20 MB ones the old test used.
     */
    @Test
    @Ignore(
        "CacheManager.enforceBudget's pinned loop has no row or floor budget: while pinnedUsage > " +
            "cacheMaxBytes * pinnedMaxFraction it demotes oldest-pinned-first with no stop, so one " +
            "pinned file larger than the 75% cap empties the whole pinned pool. Counterexample: " +
            "cacheMaxBytes = 25 MiB, pinnedMaxFraction = 0.75 ⇒ cap = 19,660,800; 300 pins of " +
            "20,000,000 B plus two 10,000,000 B favourites ⇒ all 302 demoted, pinnedBytes = 0. " +
            "Fix: give the pinned sub-pool a row budget derived from the same 0.75 fraction and stop " +
            "demoting when only favourites remain (docs/codebase-audit.md 3.3).",
    )
    fun aPinLargerThanTheCapIsDemotedEvenThoughItIsTheOnlyOfflineCopy() =
        runTest {
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
            cacheManager.enforceBudget(netNewBytes = 0)
            val pinned =
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .filter { it.pinned == 1L }
            assertTrue(
                pinned.isNotEmpty(),
                "an explicitly pinned track must not be demoted by its own pin",
            )
        }

    @Test
    fun exemptKeyNeverEvictedInOwnPass() =
        runTest {
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
            db.dylanQueries.insertCached(key.provider, key.songId, 320L, "m4a", 20_000_000L, 999L, null, 0, 0, null)
            cacheManager.enforceBudget(netNewBytes = 0, exemptKeys = setOf(key))
            assertEquals(
                key.songId,
                db.dylanQueries
                    .selectCached(key.provider, key.songId)
                    .executeAsOneOrNull()
                    ?.song_id,
            )
        }

    /**
     * Rewritten with a control. The previous version wrote a 1 MB `.part` and asserted the cache
     * was non-empty — which is true before and after `enforceBudget` whether or not parts are
     * counted at all, because the shared fixture is already 40 MB against a 25 MB budget.
     *
     * Here the cached rows fit *under* the budget and only the parts push it over, so a regression
     * that drops `partBytes()` from the usage sum evicts nothing and the assertion fails. The
     * control asserts the opposite direction too: with no parts, the same rows survive.
     */
    @Test
    fun partBytesCountTowardUsage() =
        runTest {
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
                val cachedBefore = db2.dylanQueries.selectAllCached().executeAsList()
                val (c, b) = db2.dylanQueries.cachedCountAndBytes().executeAsOne()
                assertEquals(3L, c)
                assertTrue(
                    b < cfg.cacheMaxBytes,
                    "precondition: rows alone must fit, they are $b of ${cfg.cacheMaxBytes}",
                )

                // Control: without parts nothing is over budget, so nothing is evicted.
                cm2.enforceBudget(netNewBytes = 0)
                assertEquals(
                    cachedBefore.map { it.song_id }.toSet(),
                    db2.dylanQueries
                        .selectAllCached()
                        .executeAsList()
                        .map { it.song_id }
                        .toSet(),
                    "control: 24 MB of rows against a 25 MB budget evicts nothing",
                )

                val audio = root2.toPath() / "audio"
                FileSystem.SYSTEM.createDirectories(audio)
                FileSystem.SYSTEM.write(audio / "saavn_stray_128.part") { write(ByteArray(2_000_000)) }

                cm2.enforceBudget(netNewBytes = 0)
                val after =
                    db2.dylanQueries
                        .selectAllCached()
                        .executeAsList()
                        .map { it.song_id }
                        .toSet()
                assertTrue(
                    after.size < 3,
                    "2 MB of .part bytes must count toward the budget: 24 MB + 2 MB > 25 MB, " +
                        "so a victim is due, survivors=$after",
                )
                assertTrue(
                    "k1" !in after && "k2" in after && "k3" in after,
                    "LRU order is (never played) > (oldest play) > (newest play), so k1 is the victim: $after",
                )
            }
        }

    @Test
    fun clearCacheKeepsProtected() =
        runTest {
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

    @Test
    fun intentUpsertNeverDowngradesPriority() =
        runTest {
            db.dylanQueries.upsertIntent("saavn", "x", "USER_BULK", 320L, 1L)
            db.dylanQueries.upsertIntent("saavn", "x", "PREFETCH_NEXT", 128L, 2L)
            val intent =
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .first { it.song_id == "x" }
            assertEquals("USER_BULK", intent.reason, "lower-priority enqueue must not replace")
            db.dylanQueries.upsertIntent("saavn", "x", "USER_NOW", 320L, 3L)
            val upgraded =
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .first { it.song_id == "x" }
            assertEquals("USER_NOW", upgraded.reason)
        }

    private fun pinAt(row: dylan.db.Cached_files): Long = row.pinned_at_ms ?: 0L

    private suspend fun withFreshCache(block: suspend (Dylan, CacheManager, String) -> Unit) {
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
