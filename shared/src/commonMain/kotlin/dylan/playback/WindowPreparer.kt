package dylan.playback

import dylan.cache.Paths
import dylan.db.Cached_files
import dylan.db.Dylan
import dylan.download.sniffContainer
import dylan.model.SongKey
import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

internal class WindowPreparer(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val disp: AppDispatchers,
) {
    /** `path | size | lastModified` of the last sniff, so an unchanged file is opened once. */
    private data class Stamp(
        val path: String,
        val size: Long,
        val modifiedMs: Long,
    )

    private data class Verdict(
        val stamp: Stamp,
        val ok: Boolean,
    )

    private val verified = mutableMapOf<SongKey, Verdict>()

    /**
     * The itemId the engine last reported as current. A re-announce of the live item must not be
     * asked to occupy the up-next slot, and a re-prepare of the same window must not be treated as
     * "a different next".
     */
    val currentItemId: String?
        get() = current

    private var current: String? = null

    suspend fun cachedRow(key: SongKey): Cached_files? =
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
        }

    /** Every cached row for [keys] in a single `dbLane` pass — the N+1-free form of [cachedRow]. */
    suspend fun cachedRows(keys: Collection<SongKey>): Map<SongKey, Cached_files> {
        val wanted = keys.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return withContext(disp.on(Lane.DB)) {
            val out = LinkedHashMap<SongKey, Cached_files>(wanted.size)
            for (k in wanted) {
                db.dylanQueries
                    .selectCached(k.provider, k.songId)
                    .executeAsOneOrNull()
                    ?.let { out[k] = it }
            }
            out
        }
    }

    /**
     * Blocking `stat`/`open`/`read`/`close` — four syscalls per call, 2-4 calls per track change —
     * so it runs on the io lane, never on the single state lane. Memoised per
     * (path, size, lastModified) **and** per verdict: an unchanged file is opened once, whether it
     * passed or failed, which turns the re-prepare path of a track change from four syscalls into
     * one hash lookup. The stamp already carries size and mtime, so a file that is rewritten is
     * re-sniffed without the cache going stale.
     *
     * The predicate is [sniffContainer] — the engine's own — so "verified by the engine" and
     * "accepted by playback" are one function. The copy that used to live here disagreed with it in
     * both directions: it accepted any file with `ftyp` at offset 4 (no major brand) where the
     * engine demands one, and it rejected a bare MPEG frame sync the engine commits.
     */
    suspend fun sniffOk(row: Cached_files): Boolean {
        val key = SongKey(row.provider, row.song_id)
        if (row.bytes <= 0L) {
            verified.remove(key)
            return false
        }
        val path = paths.final(key, row.bitrate.toInt(), row.ext)
        val stamp = stampOf(path, row.bytes)
        if (stamp == null) {
            verified.remove(key)
            return false
        }
        verified[key]?.let { if (it.stamp == stamp) return it.ok }
        val ok = withContext(disp.on(Lane.IO)) { sniffContainer(fs, path) != null }
        remember(key, stamp, ok)
        return ok
    }

    /**
     * Bounded: one entry per song ever sniffed, on a component that lives for the process. A plain
     * `mutableMapOf` grew for the life of the app; dropping the whole map at the ceiling is a hash
     * clear, not a re-sniff storm, because a re-added entry is one io-lane hop.
     */
    private fun remember(
        key: SongKey,
        stamp: Stamp,
        ok: Boolean,
    ) {
        if (verified.size >= SNIFF_CACHE_MAX) verified.clear()
        verified[key] = Verdict(stamp, ok)
    }

    private fun stampOf(
        path: Path,
        expectedBytes: Long,
    ): Stamp? {
        val meta = runCatching { fs.metadataOrNull(path) }.getOrNull() ?: return null
        val size = meta.size ?: return null
        if (size != expectedBytes) return null
        return Stamp(path.toString(), size, meta.lastModifiedAtMillis ?: UNKNOWN_MTIME)
    }

    /** Called from the `TrackChanged` handler and from detach, which clears it. */
    fun noteEngineCurrent(itemId: String?) {
        current = itemId
    }

    private companion object {
        const val UNKNOWN_MTIME = -1L
        const val SNIFF_CACHE_MAX = 512
    }
}
