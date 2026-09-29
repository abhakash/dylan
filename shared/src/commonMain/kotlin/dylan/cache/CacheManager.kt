package dylan.cache

import dylan.config.AppConfig
import dylan.db.ClaimAllUnprotected
import dylan.db.Dylan
import dylan.model.Song
import dylan.model.SongKey
import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/** One row of the Downloads screen, from a single JOIN rather than 1 + N round trips. */
data class DownloadEntry(
    val key: SongKey,
    val song: Song,
    val bitrate: Int,
    val bytes: Long,
    val pinned: Boolean,
    val removable: Boolean,
)

/**
 * Budget enforcement, eviction and the protection set.
 *
 * **The protection set is a table, not a read.** Every mutation runs inside one transaction that
 * first republishes [protectedKeys] + [inFlightJobKeys] + [upgradeSourceKeys] + the caller's
 * `exemptKeys` into `protected_keys`, and every victim selection is a single
 * `UPDATE ... WHERE ... NOT IN (protected_keys) RETURNING ...`. Selection therefore cannot
 * observe a stale protection set — which is what turned CA-1, CA-2, CA-3 and the phantom-pin
 * race into four separate TOCTOU bugs.
 *
 * **Eviction is two-phase.** A victim is *claimed* (state READY -> EVICTING) by the statement
 * that selects it, the unlink happens on the io lane, and only a successful unlink drops the
 * row. A crash mid-pass leaves EVICTING rows, which [reapEvicting] finishes; a failed unlink
 * restores the row to READY and is retried on the next sweep. Both are idempotent.
 *
 * **Unlink never runs on dbLane.** The unlink loop is hoisted onto [Lane.IO]; v0 performed it
 * inside the dbLane block two lines below its own "partBytes does fs I/O" rule.
 */
class CacheManager(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    // READ-ONLY view: AppContainer's combine block is the SINGLE WRITER of protectedKeys
    // (current + next-up + in-flight + upgrade sources). CacheManager only reads .value, and
    // republishes the result into the protected_keys table inside its own transactions.
    // Do not add another writer; cross-agent writes would race the evict guard.
    val protectedKeys: StateFlow<Set<SongKey>>,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val log: dylan.diag.LogBuffer,
) {
    val inFlightJobKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    val upgradeSourceKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    var notifyOnce: (String) -> Unit = {}

    /**
     * Bumped by every mutation this class makes, so the reactive views below re-read. A write
     * that lands without a bump (the download engine's COMMIT) is always immediately followed by
     * an `enforceBudget`, which bumps.
     */
    private val revision = MutableStateFlow(0L)

    /**
     * The byte budget actually enforced. `cacheMaxBytes` is 2 GB but 300 tracks at 320 kbps are
     * ~255 MB, so the *file* cap binds about six times earlier and the byte figure is
     * unreachable — which is what left the pinned sub-pool demotion loop with nothing to do.
     */
    val byteBudget: Long = minOf(cfg.cacheMaxBytes, cfg.cacheMaxFiles * EXPECTED_MEAN_TRACK_BYTES)

    /** Pinned pool row budget, from the same fraction as the byte cap (see [enforceBudget]). */
    val pinnedRowBudget: Int = maxOf(1, (cfg.cacheMaxFiles * cfg.pinnedMaxFraction).toInt())

    val pinnedByteBudget: Long = (byteBudget * cfg.pinnedMaxFraction).toLong()

    val fileBudget: Int = cfg.cacheMaxFiles

    /**
     * Reactive Downloads-screen model: one JOIN, re-emitted when the protection set or the cache
     * changes. Replaces `selectAllCached()` + a `selectSong()` per row, which is 301 round trips
     * through the single-threaded dbLane on every screen open at the cap.
     */
    val downloads: Flow<List<DownloadEntry>> =
        combine(protectedKeys, inFlightJobKeys, upgradeSourceKeys, revision) { _, _, _, _ -> Unit }
            .map { readDownloads() }

    /** What W3-D wants: a key is removable when nothing depends on it. Reactive, one read. */
    val removableKeys: Flow<Set<SongKey>> =
        downloads.map { rows -> rows.filter { it.removable }.map { it.key }.toSet() }

    /** The non-reactive answer to the same question, for callers already holding the flows. */
    fun isRemovable(key: SongKey): Boolean = key !in liveProtection(emptySet())

    /**
     * Bring the cache back inside its budgets.
     *
     * [pendingRows] is the number of rows that are about to be inserted but are not in the table
     * yet, and it is a parameter rather than something inferred. v0 always reserved one
     * (`count + 1`), including from the four call sites whose row was already committed, so at
     * the file cap every completed download and every favourite destroyed one extra unrelated
     * file. Default 0 = reserve nothing = never destroy a file that was not accounted for.
     *
     * [netNewBytes] is the only legitimate reservation: bytes a transfer is about to write.
     */
    suspend fun enforceBudget(
        netNewBytes: Long = 0,
        pendingRows: Int = 0,
        exemptKeys: Set<SongKey> = emptySet(),
    ) = withContext(disp.on(Lane.IO)) {
        disp.assertInContext(Lane.IO)
        check(pendingRows >= 0) { "pendingRows must not be negative" }
        val demoted = demotePins()
        val claimed = claimLruVictims(netNewBytes, pendingRows, exemptKeys)
        if (claimed.isEmpty() && demoted == 0) return@withContext
        if (claimed.isNotEmpty()) {
            val freed = claimed.sumOf { it.bytes }
            log.i("cache", "evicting ${claimed.size} rendition(s) ${freed}B, $demoted pin(s) demoted")
            claimed.forEach { log.d("cache", "victim ${it.key.provider}:${it.key.songId} bytes=${it.bytes}") }
        }
        val spared = claimed.filterNot { it.key in liveProtection(exemptKeys) }
        val (unlinked, failed) = unlinkAll(spared)
        finishReap(unlinked, failed)
        if (demoted > 0) notifyOnce(FOOTER_PIN_BUDGET)
    }

    /** Tapping "clear cache" then hitting play must not delete the file that just became current. */
    suspend fun clearCacheExcludingProtected(): Long =
        withContext(disp.on(Lane.IO)) {
            disp.assertInContext(Lane.IO)
            val doomed =
                withContext(disp.on(Lane.DB)) {
                    db.transactionWithResult { db.dylanQueries.claimAllUnprotected().executeAsList() }
                }.map { it.toClaim() }
            if (doomed.isEmpty()) return@withContext 0L
            val spared = doomed.filterNot { it.key in liveProtection(emptySet()) }
            val (unlinked, failed) = unlinkAll(spared)
            finishReap(unlinked, failed)
            val freed = unlinked.sumOf { it.bytes }
            log.i(
                "cache",
                "clear-cache removed ${unlinked.size} rendition(s) ${freed}B kept=${doomed.size - unlinked.size}" +
                    " failed=${failed.size}",
            )
            freed
        }

    /**
     * The only correct way to remove a download. The protection check is inside the statement
     * that claims the row, so a platform that calls this instead of `deleteCached` + `fs.delete`
     * cannot delete the file it is currently playing.
     */
    suspend fun evictOne(key: SongKey): Boolean =
        withContext(disp.on(Lane.IO)) {
            disp.assertInContext(Lane.IO)
            val claimed =
                withContext(disp.on(Lane.DB)) {
                    db.transactionWithResult {
                        publishProtection(emptySet())
                        db.dylanQueries.claimOne(key.provider, key.songId).executeAsList()
                    }
                }.map { it.toClaim() }
            if (claimed.isEmpty()) {
                log.w("cache", "evictOne refused (protected or not cached) ${key.provider}:${key.songId}")
                return@withContext false
            }
            val (unlinked, failed) = unlinkAll(claimed)
            finishReap(unlinked, failed)
            if (unlinked.isEmpty()) return@withContext false
            log.i("cache", "evictOne ${key.provider}:${key.songId} freed=${unlinked.sumOf { it.bytes }}B")
            true
        }

    suspend fun touch(
        key: SongKey,
        nowMs: Long,
    ) = withContext(disp.on(Lane.DB)) {
        db.dylanQueries.touchUsed(nowMs, key.provider, key.songId)
    }

    /**
     * Recompute the `.part` totals from the audio directory. This is the ONE place the directory
     * is walked for part bytes: v0 walked it on every single budget check.
     */
    suspend fun refreshPartTotals() =
        withContext(disp.on(Lane.IO)) {
            val sizes =
                runCatching { fs.list(paths.audioDir) }
                    .getOrDefault(emptyList<Path>())
                    .filter { CachePath.isPart(it.name) }
                    .mapNotNull { p -> fs.metadataOrNull(p)?.size }
            withContext(disp.on(Lane.DB)) { db.dylanQueries.setPartTotals(sizes.sum(), sizes.size.toLong()) }
        }

    /** For a caller that already tracks its own parts (the download engine): O(1), no directory walk. */
    suspend fun setPartTotals(
        bytes: Long,
        count: Int,
    ) = withContext(disp.on(Lane.DB)) { db.dylanQueries.setPartTotals(bytes, count.toLong()) }

    /** Recomputes the materialised totals from the rows themselves. Boot-sweep self-heal. */
    suspend fun syncTotals() = withContext(disp.on(Lane.DB)) { db.dylanQueries.syncTotals() }

    /**
     * Crash recovery for the EVICTING half of the state machine: a process death between the claim
     * and the unlink leaves claimed rows behind, and nothing else would ever revisit them. A
     * rendition whose file is already gone is simply dropped; a failed unlink returns to READY.
     * Idempotent, so a second call is a no-op.
     */
    suspend fun reapEvicting(limit: Int = REAP_BATCH): Int =
        withContext(disp.on(Lane.IO)) {
            val stale =
                withContext(disp.on(Lane.DB)) { db.dylanQueries.evictingObjects().executeAsList() }
                    .take(limit)
                    .map { it.toClaim() }
            if (stale.isEmpty()) return@withContext 0
            val present = stale.filter { fs.exists(pathOf(it)) }
            val (unlinked, failed) = unlinkAll(present)
            val vanished = stale - present.toSet()
            finishReap(unlinked, failed + vanished)
            log.i("cache", "reaped ${unlinked.size + vanished.size}/${stale.size} interrupted eviction(s)")
            unlinked.size + vanished.size
        }

    // ---- phase 1: claim ---------------------------------------------------------------------

    /**
     * Loop of bounded `lruVictims` batches. Each batch is ONE statement that both selects and
     * claims, so there is no per-viction re-serialisation of the exclusion list and no mid-pass
     * re-read: v0's `SELECT ... LIMIT 1` loop re-serialised the whole `NOT IN ?` list per victim,
     * i.e. 200 full sorts and ~20 000 bound parameters for a 200-row bulk eviction.
     *
     * **The loop must discount what it has already claimed.** `cache_totals` is maintained by the
     * library triggers, so it only moves when a row is *dropped* — and the drop deliberately
     * happens after the unlink, in [finishReap], once this function has returned. A row that this
     * pass has claimed (state READY -> EVICTING) is therefore still counted in the totals the next
     * iteration reads. Without the discount below, iteration N+1 derives byte-for-byte the same
     * over-budget figures as iteration N, `need` never shrinks, and the loop keeps claiming until
     * `lruVictims` runs dry: the WHOLE eligible pool is destroyed instead of the excess. That is
     * exactly what a stale-materialisation bug looks like from the outside, and it is why
     * `unplayedEvictsBeforeRecentlyPlayed` saw zero survivors rather than a wrong victim.
     *
     * A claim whose unlink later FAILS is restored to READY with its row and its bytes still in
     * the totals, so the next `enforceBudget` re-derives the overage and retries it. The discount
     * is per-pass and never touches `cache_totals`, so that retry semantics is unchanged.
     */
    private suspend fun claimLruVictims(
        netNewBytes: Long,
        pendingRows: Int,
        exempt: Set<SongKey>,
    ): List<Claim> {
        val out = mutableListOf<Claim>()
        var claimedBytes = 0L
        var claimedRows = 0L
        while (true) {
            val totals = withContext(disp.on(Lane.DB)) { db.dylanQueries.cachedCountAndBytes().executeAsOne() }
            val rowCount = (totals.song_count - claimedRows).coerceAtLeast(0L)
            val byteCount = (totals.total_bytes - claimedBytes).coerceAtLeast(0L)
            val partBytes = withContext(disp.on(Lane.DB)) { db.dylanQueries.partTotals().executeAsOne() }
            val usage = byteCount + partBytes.part_bytes + netNewBytes
            val rows = rowCount + pendingRows
            val overRows = rows - fileBudget
            val overBytes = usage - byteBudget
            if (overRows <= 0 && overBytes <= 0) return out
            val batch = claimBatch(overRows, overBytes, rowCount, byteCount, exempt)
            if (batch.isEmpty()) return out
            out += batch
            // One claimed rendition == one library row == the bytes it reports: `media_objects`
            // carries the library row's active rendition (trg_library_asset_{ins,upd}), so dropping
            // it removes exactly this row and this many bytes from the totals.
            claimedRows += batch.size
            claimedBytes += batch.sumOf { it.bytes }
        }
    }

    private suspend fun claimBatch(
        overRows: Long,
        overBytes: Long,
        rowCount: Long,
        byteCount: Long,
        exempt: Set<SongKey>,
    ): List<Claim> {
        val mean = if (rowCount > 0) (byteCount / rowCount).coerceAtLeast(1L) else 1L
        val need = maxOf(overRows, ceilDiv(overBytes, mean)).coerceIn(1L, EVICT_BATCH.toLong())
        return withContext(disp.on(Lane.DB)) {
            db.transactionWithResult {
                publishProtection(exempt)
                db.dylanQueries.lruVictims(need).executeAsList()
            }
        }.map { it.toClaim() }
    }

    /**
     * Pinned-pool budget enforcement. v0 looped on bytes only, so 300 favourites x 4 MB sat under
     * the 1.5 GB cap, the loop ran zero iterations, `lruVictims` (WHERE pinned = 0) matched
     * nothing, `?: break` exited, and the 300-file cap became permanently unenforceable with no
     * user-visible signal. Hence a ROW budget from the same fraction, and a hard floor of one
     * survivor: the user's only offline copy of an explicitly favourited track is never demoted
     * by its own pin. When the floor blocks the cap we say so.
     */
    private suspend fun demotePins(): Int {
        var demoted = 0
        while (true) {
            val (pinnedCount, pinnedBytes) =
                withContext(disp.on(Lane.DB)) {
                    db.dylanQueries.pinnedCount().executeAsOne() to db.dylanQueries.pinnedBytes().executeAsOne()
                }
            if (pinnedCount <= pinnedRowBudget && pinnedBytes <= pinnedByteBudget) return demoted
            if (pinnedCount <= 1) {
                log.w("cache", "pinned pool over budget: $pinnedCount rows / $pinnedBytes B, last favourite kept")
                return demoted
            }
            val victims =
                withContext(disp.on(Lane.DB)) {
                    db.transactionWithResult {
                        publishProtection(emptySet())
                        db.dylanQueries.oldestPinned(1L).executeAsList()
                    }
                }
            if (victims.isEmpty()) return demoted
            withContext(disp.on(Lane.DB)) { db.dylanQueries.demotePin(victims[0].provider, victims[0].song_id) }
            demoted++
        }
    }

    // ---- phase 2: unlink, then finalise -------------------------------------------------------

    private fun pathOf(claim: Claim): Path = paths.final(claim.key, claim.bitrate, claim.ext)

    private fun unlinkAll(claims: List<Claim>): Pair<List<Claim>, List<Claim>> {
        val unlinked = mutableListOf<Claim>()
        val failed = mutableListOf<Claim>()
        claims.forEach { claim ->
            // A failed unlink keeps the row: the next sweep retries, and nothing is lost.
            val ok = runCatching { fs.delete(pathOf(claim)) }.isSuccess
            if (ok) unlinked += claim else failed += claim
        }
        failed.forEach { log.e("cache", "unlink failed, row kept for retry: ${it.key.provider}:${it.key.songId}") }
        return unlinked to failed
    }

    private suspend fun finishReap(
        unlinked: List<Claim>,
        failed: List<Claim>,
    ) {
        if (unlinked.isEmpty() && failed.isEmpty()) return
        withContext(disp.on(Lane.DB)) {
            db.transaction {
                unlinked.forEach { drop(it) }
                failed.forEach { db.dylanQueries.restoreObject(it.key.provider, it.key.songId, it.bits(), it.ext) }
            }
        }
        if (unlinked.isNotEmpty() || failed.isNotEmpty()) bump()
    }

    // ---- protection ---------------------------------------------------------------------------

    private fun drop(claim: Claim) {
        db.dylanQueries.dropObject(claim.key.provider, claim.key.songId, claim.bitrate.toLong(), claim.ext)
    }

    private fun Claim.bits(): Long = bitrate.toLong()

    private fun liveProtection(exempt: Set<SongKey>): Set<SongKey> {
        val published = protectedKeys.value
        return published + inFlightJobKeys.value + upgradeSourceKeys.value + exempt
    }

    /**
     * Republish the live protection set into the table. Called at the head of every transaction
     * that can destroy a file, which is what makes the guard and the destruction share a snapshot.
     */
    private fun publishProtection(exempt: Set<SongKey>) {
        db.dylanQueries.clearProtectedKeys()
        protectedKeys.value.forEach { db.dylanQueries.putProtectedKey(it.provider, it.songId, REASON_PLAYING) }
        inFlightJobKeys.value.forEach { db.dylanQueries.putProtectedKey(it.provider, it.songId, REASON_INFLIGHT) }
        upgradeSourceKeys.value.forEach { db.dylanQueries.putProtectedKey(it.provider, it.songId, REASON_UPGRADE) }
        exempt.forEach { db.dylanQueries.putProtectedKey(it.provider, it.songId, REASON_EXEMPT) }
    }

    private suspend fun readDownloads(): List<DownloadEntry> {
        val protection = liveProtection(emptySet())
        return withContext(disp.on(Lane.DB)) {
            db.dylanQueries
                .selectDownloads()
                .executeAsList()
                .map { row ->
                    DownloadEntry(
                        key = SongKey(row.provider, row.song_id),
                        song =
                            Song(
                                key = SongKey(row.provider, row.song_id),
                                title = row.title,
                                subtitle = row.subtitle,
                                albumId = row.albumId,
                                albumName = row.albumName,
                                artUrl150 = row.artUrl150,
                                artUrl500 = row.artUrl500,
                                durationS = row.durationS,
                                has320 = row.has320 == 1L,
                                resolveRef = row.resolveRef,
                                permaToken = row.permaToken,
                            ),
                        bitrate = row.bitrate.toInt(),
                        bytes = row.bytes,
                        pinned = row.pinned == 1L,
                        removable = SongKey(row.provider, row.song_id) !in protection,
                    )
                }
        }
    }

    private fun bump() {
        revision.value = revision.value + 1
    }

    private fun ceilDiv(
        a: Long,
        b: Long,
    ): Long = if (a <= 0L) 0L else (a + b - 1) / b

    private companion object {
        /** 320 kbps x 200 s / 8, rounded up. See [byteBudget]. */
        const val EXPECTED_MEAN_TRACK_BYTES = 1_000_000L
        const val EVICT_BATCH = 64
        const val REAP_BATCH = 256
        const val FOOTER_PIN_BUDGET =
            "Favorites exceed the offline budget - oldest moved out of guaranteed storage."
        const val REASON_PLAYING = "PLAYING"
        const val REASON_INFLIGHT = "INFLIGHT"
        const val REASON_UPGRADE = "UPGRADE_SOURCE"
        const val REASON_EXEMPT = "EXEMPT"
    }
}

/** One claimed rendition on its way out. */
data class Claim(
    val key: SongKey,
    val bitrate: Int,
    val ext: String,
    val bytes: Long,
)

private fun dylan.db.Media_objects.toClaim(): Claim = Claim(SongKey(provider, song_id), bitrate.toInt(), ext, bytes)

private fun dylan.db.LruVictims.toClaim(): Claim = Claim(SongKey(provider, song_id), bitrate.toInt(), ext, bytes)

private fun ClaimAllUnprotected.toClaim(): Claim = Claim(SongKey(provider, song_id), bitrate.toInt(), ext, bytes)

private fun dylan.db.ClaimOne.toClaim(): Claim = Claim(SongKey(provider, song_id), bitrate.toInt(), ext, bytes)
