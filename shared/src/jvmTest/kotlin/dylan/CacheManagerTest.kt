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
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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
     * @Ignore removed. The counterexample was real: v0's pinned loop was bounded by bytes only, so
     * one pinned file larger than the whole cap walked the pool to empty — demoting the user's only
     * offline copy of an explicitly favourited track. The pinned sub-pool now has a row budget from
     * the same 0.75 fraction and a hard floor of one survivor, so this is enforced rather than
     * documented.
     */
    @Test
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
     * The pinned sub-pool's row budget, which is what makes the 300-file cap enforceable at all.
     * 300 favourites x 4 MB is 1.2 GB, comfortably under a byte cap, so the v0 byte-only loop ran
     * zero iterations, `lruVictim` (WHERE pinned = 0) matched nothing, and `?: break` exited with
     * the cap permanently unenforceable and no user-visible signal.
     */
    @Test
    fun thePinnedPoolHasARowBudgetSoTheFileCapStaysEnforceable() =
        runTest {
            withFreshCache(cfg = AppConfig(cacheMaxBytes = 2_000_000_000L, cacheMaxFiles = 10)) { db2, cm2, _ ->
                repeat(12) { idx ->
                    val k = SongKey("saavn", "fav$idx")
                    db2.dylanQueries
                        .insertSong("saavn", k.songId, k.songId, "", null, null, "", "", 200L, 1L, "r", null, 0L)
                    db2.dylanQueries.insertCached(
                        "saavn",
                        k.songId,
                        128L,
                        "m4a",
                        4_000_000L,
                        idx.toLong(),
                        null,
                        0L,
                        1L,
                        (idx + 1).toLong(),
                    )
                }
                assertEquals(12L, db2.dylanQueries.pinnedCount().executeAsOne())

                cm2.enforceBudget()

                val pinned = db2.dylanQueries.pinnedCount().executeAsOne()
                assertEquals(
                    cm2.pinnedRowBudget.toLong(),
                    pinned,
                    "12 pins x 4 MB is under a 2 GB byte cap, so only a ROW budget can hold the pool",
                )
                assertTrue(pinned >= 1L, "the pool must keep at least one favourite")
            }
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
        runTest {
            val capCfg = AppConfig(cacheMaxBytes = 1_000_000_000L, cacheMaxFiles = 5)
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

    @Test
    fun partBytesCountTowardUsageOnlyAfterTheyAreReported() =
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
                    "2 MB of .part bytes must count toward the budget: 25 MB + 2 MB > 25 MB, " +
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
        runTest {
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
        runTest {
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
        runTest {
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
        fs.write(paths.final(key, 128, "m4a")) { write(ByteArray(10_000_000)) }
        db.transaction { db.dylanQueries.clearProtectedKeys() }
        val claimed = db.dylanQueries.claimAllUnprotected().executeAsList()
        assertTrue(claimed.any { it.song_id == "n1" }, "precondition: the row is claimed and its file still there")
        assertTrue(fs.exists(paths.final(key, 128, "m4a")), "precondition: claimed, not yet unlinked")

        runTest {
            assertEquals(1, cacheManager.reapEvicting())
            assertFalse(fs.exists(paths.final(key, 128, "m4a")))
            assertEquals(0, cacheManager.reapEvicting(), "a second reap must be a no-op")
        }
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
        runTest {
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

    private fun dylan.cache.DownloadEntry.songId(): String = key.songId

    private fun Dylan.songCount(): Long =
        dylanQueries
            .cachedCountAndBytes()
            .executeAsOne()
            .song_count

    private fun Dylan.partBytes(): Long =
        dylanQueries
            .partTotals()
            .executeAsOne()
            .part_bytes

    private fun pinAt(row: dylan.db.Cached_files): Long = row.pinned_at_ms ?: 0L

    private suspend fun withFreshCache(
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

private data class CacheFileNameAssert(
    val provider: String,
    val songId: String,
    val bitrate: Int,
    val ext: String,
) {
    companion object {
        fun of(parsed: dylan.cache.CacheFileName?): CacheFileNameAssert? =
            parsed?.let { CacheFileNameAssert(it.provider, it.songId, it.bitrate, it.ext) }
    }
}
