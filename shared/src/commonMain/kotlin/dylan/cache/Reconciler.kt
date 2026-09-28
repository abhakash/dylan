package dylan.cache

import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.Priority
import dylan.model.SongKey
import dylan.repo.SettingsStore
import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Boot sweep and weekly full sweep.
 *
 * **Boot is now four indexed queries and one directory listing.** v0 did three full directory
 * listings plus two `stat` calls per cached row on *every* cold start (~600 blocking syscalls at
 * the 300-file cap), re-read `cached_files` twice, and computed `finals = files.filter { it !in
 * parts }` as a 300x300 Path scan. With `media_objects.state` and `verified_at_ms` the questions
 * it was asking are index lookups, and for a healthy library every one of them answers "nothing".
 *
 * **Size verification moved to the weekly sweep**, which walks the directory anyway. The boot
 * query only looks at rows that carry no verification stamp at all, so it is empty for a library
 * the last full sweep has seen.
 */
class Reconciler(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val engine: DownloadEngine,
    private val cacheManager: CacheManager,
    private val log: dylan.diag.LogBuffer,
    // Defaults to a private instance because AppContainer (owned by the DI wave) still builds this
    // with the pre-settings argument list. One-line fix, in the wave's report: pass data.settings.
    private val settings: SettingsStore = SettingsStore(db, disp, cfg),
) {
    private val clock = cfg.clock

    suspend fun run() =
        withContext(disp.on(Lane.IO)) {
            disp.assertInContext(Lane.IO)
            val t0 = clock.nowMs()
            val now = t0
            // Finishes an eviction whose process died between the claim and the unlink.
            val claimed = cacheManager.reapEvicting()
            val reaped = reapInterruptedWrites()
            val unstamped = verifyUnstampedObjects(now)
            val full = fullSweepDue(now)
            val swept = if (full) fullSweep(now) else null
            cacheManager.refreshPartTotals()
            cacheManager.syncTotals()
            resumeIntents()
            cacheManager.enforceBudget()
            log.i(
                "reconciler",
                "sweep: claimed=$claimed reaped=$reaped unstamped=$unstamped full=$full " +
                    "dir=${swept?.describe() ?: "-"} ms=${clock.nowMs() - t0}",
            )
        }

    /**
     * Renditions that are neither READY nor the library row's active one: an interrupted write, a
     * failed verification, or a leftover from a superseded upgrade. The file is unlinked and only
     * then is the row dropped, so a failed unlink keeps the row and is retried.
     */
    private suspend fun reapInterruptedWrites(): Int {
        val rows =
            withContext(disp.on(Lane.DB)) { db.dylanQueries.reapableObjects(REAP_BATCH.toLong()).executeAsList() }
        var dropped = 0
        rows.forEach { row ->
            val key = SongKey(row.provider, row.song_id)
            val gone = runCatching { fs.delete(paths.final(key, row.bitrate.toInt(), row.ext)) }.isSuccess
            withContext(disp.on(Lane.DB)) {
                db.dylanQueries.dropObject(row.provider, row.song_id, row.bitrate, row.ext)
            }
            if (gone) {
                dropped++
            } else {
                log.e("reconciler", "could not unlink ${row.state.lowercase()} rendition ${key.provider}:${key.songId}")
            }
        }
        if (rows.isNotEmpty()) log.i("reconciler", "reaped ${rows.size} non-playable rendition(s), $dropped unlinked")
        return dropped
    }

    /** Rows that have never been stat'ed since they were written. Empty for a swept library. */
    private suspend fun verifyUnstampedObjects(now: Long): Int {
        val rows =
            withContext(disp.on(Lane.DB)) { db.dylanQueries.unstampedObjects(REAP_BATCH.toLong()).executeAsList() }
        rows.forEach { row -> checkOne(row.provider, row.song_id, row.bitrate.toInt(), row.ext, row.bytes, now) }
        return rows.size
    }

    /**
     * The one directory walk v0 did on every boot, now weekly, and the only place a cached file's
     * size is compared against its row. Two things it must not do: delete a file whose name is not
     * one of ours (a `cover.jpg` or a half-restored download is not garbage), or treat a `.part`
     * with a future mtime as immortal.
     */
    private suspend fun fullSweep(now: Long): SweepCount {
        val rows = withContext(disp.on(Lane.DB)) { db.dylanQueries.selectAllCached().executeAsList() }
        val known = rows.map { Triple(SongKey(it.provider, it.song_id), it.bitrate.toInt(), it.ext) }
        val knownPaths = known.map { (key, bits, ext) -> paths.final(key, bits, ext) }.toHashSet()
        val intents = withContext(disp.on(Lane.DB)) { db.dylanQueries.allIntents().executeAsList() }
        val pendingParts = intents.map { paths.part(SongKey(it.provider, it.song_id), it.bitrate.toInt()) }.toHashSet()

        var partsDeleted = 0
        var orphansDeleted = 0
        var checked = 0
        for (file in fs.list(paths.audioDir)) {
            if (paths.parseFileName(file.name) == null) continue
            if (CachePath.isPart(file.name)) {
                val grace = cfg.partGraceHours * 3_600_000L
                if (file !in pendingParts && isStale(file, now, grace)) partsDeleted += delete(file)
            } else if (file in knownPaths) {
                checked++
            } else if (isStale(file, now, ORPHAN_GRACE_MS)) {
                orphansDeleted += delete(file)
            }
        }
        known.forEach { (key, bits, ext) -> checkOne(key.provider, key.songId, bits, ext, 0, now, stampOnly = true) }
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries.deleteOrphanHistory()
            db.dylanQueries.deleteOrphanLibrary()
            db.dylanQueries.deleteOrphanObjects()
        }
        runCatching { settings.put(FULL_SWEEP_KEY, now.toString()) }
        return SweepCount(partsDeleted, orphansDeleted, checked)
    }

    /**
     * One file, one stat. A mismatch drops the row only after a successful unlink — v0 deleted the
     * row even when `fs.delete` failed, so one EACCES/EBUSY/EMFILE permanently lost the track and
     * the next launch's orphan sweep then deleted the file for real (CA-4).
     */
    private suspend fun checkOne(
        provider: String,
        songId: String,
        bitrate: Int,
        ext: String,
        expectedBytes: Long,
        now: Long,
        stampOnly: Boolean = false,
    ) {
        val key = SongKey(provider, songId)
        val path = paths.final(key, bitrate, ext)
        val size = fs.metadataOrNull(path)?.size
        val recorded = if (stampOnly) currentBytes(provider, songId, bitrate, ext) else expectedBytes
        if (size != null && size == recorded) {
            withContext(disp.on(Lane.DB)) { db.dylanQueries.markVerified(now, provider, songId, bitrate.toLong(), ext) }
            return
        }
        log.w("reconciler", "cached file missing or short ${key.provider}:${key.songId} (row=$recorded B)")
        val gone = runCatching { fs.delete(path) }.isSuccess
        withContext(disp.on(Lane.DB)) { db.dylanQueries.dropObject(provider, songId, bitrate.toLong(), ext) }
        if (!gone) log.e("reconciler", "unlink failed, row kept for retry: ${key.provider}:${key.songId}")
    }

    private suspend fun currentBytes(
        provider: String,
        songId: String,
        bitrate: Int,
        ext: String,
    ): Long? =
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries
                .selectCached(provider, songId)
                .executeAsOneOrNull()
                ?.takeIf { it.bitrate == bitrate.toLong() && it.ext == ext }
                ?.bytes
        }

    /**
     * v0 tested `mtime in 1 until now - grace`, which is false for `age == 0` and false for a
     * future mtime — so a clock jump left every `.part` charged against the budget forever. The
     * comparison is age-based, and an mtime far in the future counts as garbage rather than as
     * infinitely young.
     */
    private fun delete(file: Path): Int = if (runCatching { fs.delete(file) }.isSuccess) 1 else 0

    private fun isStale(
        file: Path,
        now: Long,
        graceMs: Long,
    ): Boolean {
        val mtime = fs.metadataOrNull(file)?.lastModifiedAtMillis ?: return true
        val age = now - mtime
        return age > graceMs || age < -FUTURE_MTIME_TOLERANCE_MS
    }

    private suspend fun resumeIntents() {
        val intents = withContext(disp.on(Lane.DB)) { db.dylanQueries.allIntents().executeAsList() }
        if (intents.isEmpty()) return
        val cached =
            withContext(disp.on(Lane.DB)) {
                db.dylanQueries
                    .selectAllCached()
                    .executeAsList()
                    .map { SongKey(it.provider, it.song_id) }
                    .toHashSet()
            }
        intents.forEach { intent ->
            val key = SongKey(intent.provider, intent.song_id)
            if (key in cached) {
                engine.dropIntent(key)
            } else {
                log.i("reconciler", "resuming ${key.provider}:${key.songId} reason=${intent.reason}")
                engine.enqueue(
                    DownloadJob(
                        key = key,
                        reason = runCatching { Priority.valueOf(intent.reason) }.getOrDefault(Priority.USER_BULK),
                        bitrate = intent.bitrate.toInt(),
                        enqueuedAtMs = intent.enqueued_at_ms,
                    ),
                )
            }
        }
    }

    private suspend fun fullSweepDue(now: Long): Boolean {
        val last = runCatching { settings.get(FULL_SWEEP_KEY)?.toLongOrNull() ?: 0L }.getOrDefault(0L)
        return now - last >= FULL_SWEEP_INTERVAL_MS
    }

    private data class SweepCount(
        val partsDeleted: Int,
        val orphansDeleted: Int,
        val checked: Int,
    ) {
        fun describe(): String = "${partsDeleted}p/${orphansDeleted}o/${checked}c"
    }

    private companion object {
        const val FULL_SWEEP_KEY = "reconcile_full_ms"
        const val FULL_SWEEP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        const val ORPHAN_GRACE_MS = 60_000L
        const val FUTURE_MTIME_TOLERANCE_MS = 5L * 60 * 60 * 1000
        const val REAP_BATCH = 256
    }
}
