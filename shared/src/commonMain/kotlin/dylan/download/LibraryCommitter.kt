package dylan.download

import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.model.Quality
import dylan.model.SongKey
import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.withContext

/**
 * The library-table collaborator: the three statements the download pipeline owns outside the
 * job's own state machine.
 *
 * They live here so [DownloadEngine] stays a scheduler rather than a database client, and so the
 * cached row is read as a named projection ([PrevRow]) instead of a generated type: `selectCached`
 * reads a view over a table the cache wave is migrating, and a download engine that fails to
 * compile when a sibling renames a table is a worse coupling than six named columns.
 */
internal class LibraryCommitter(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val log: LogBuffer,
) {
    suspend fun previousRow(key: SongKey): PrevRow? =
        withContext(disp.on(Lane.DB)) {
            val p = key.provider
            val id = key.songId
            db.dylanQueries
                .selectCached(p, id) { _, _, bits, ext, bytes, _, lastUsed, plays, pinned, pinnedAt ->
                    PrevRow(bits.toInt(), ext, bytes, lastUsed, plays, pinned == 1L, pinnedAt)
                }.executeAsOneOrNull()
        }

    /** Replace the row for [key] in one transaction, preserving engagement and pin state. */
    suspend fun commit(
        key: SongKey,
        quality: Quality,
        ext: String,
        finalSize: Long,
        now: Long,
        prev: PrevRow?,
        favorited: Boolean,
    ): Boolean {
        val ok =
            runCatching {
                withContext(disp.on(Lane.DB)) {
                    db.transaction {
                        db.dylanQueries.deleteCached(key.provider, key.songId)
                        db.dylanQueries.insertCached(
                            key.provider,
                            key.songId,
                            quality.bits.toLong(),
                            ext,
                            finalSize,
                            now,
                            prev?.lastUsedMs,
                            prev?.playCount ?: 0L,
                            if (favorited || prev?.pinned == true) 1L else 0L,
                            prev?.pinnedAtMs ?: if (favorited) now else null,
                        )
                    }
                }
            }.isSuccess
        if (!ok) log.e("dl", "commit failed for ${key.provider}:${key.songId}")
        return ok
    }

    /** The reconciler's resume record: what was asked for, and why. */
    suspend fun writeIntent(job: DownloadJob) {
        withContext(disp.on(Lane.DB)) {
            runCatching {
                db.dylanQueries.upsertIntent(
                    job.key.provider,
                    job.key.songId,
                    job.reason.wire,
                    job.bitrate.toLong(),
                    job.enqueuedAtMs,
                )
            }
        }
    }

    suspend fun dropIntent(key: SongKey) {
        withContext(disp.on(Lane.DB)) {
            runCatching { db.dylanQueries.deleteIntent(key.provider, key.songId) }
        }
    }
}
