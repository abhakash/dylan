package dylan.playback

import dylan.cache.Paths
import dylan.db.Cached_files
import dylan.db.Dylan
import dylan.model.SongKey
import dylan.util.AppDispatchers
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
    ) {
        companion object {
            val NONE = Stamp("", -1L, -1L)
        }
    }

    private val verified = mutableMapOf<SongKey, Stamp>()

    /**
     * The itemId the engine last reported as current. A re-announce of the live item must not be
     * asked to occupy the up-next slot, and a re-prepare of the same window must not be treated as
     * "a different next".
     */
    val currentItemId: String?
        get() = current

    private var current: String? = null

    suspend fun cachedRow(key: SongKey): Cached_files? =
        withContext(disp.dbLane) {
            db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
        }

    /** Every cached row for [keys] in a single `dbLane` pass — the N+1-free form of [cachedRow]. */
    suspend fun cachedRows(keys: Collection<SongKey>): Map<SongKey, Cached_files> {
        val wanted = keys.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return withContext(disp.dbLane) {
            val out = LinkedHashMap<SongKey, Cached_files>(wanted.size)
            for (k in wanted) {
                db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull()?.let { out[k] = it }
            }
            out
        }
    }

    /**
     * Blocking `stat`/`open`/`read`/`close` — four syscalls per call, 2-4 calls per track change —
     * so it runs on the io lane, never on the single state lane. Memoised per
     * (path, size, lastModified): a file that has not changed is sniffed once, which turns the
     * re-prepare path of a track change from four syscalls into one hash lookup.
     */
    suspend fun sniffOk(row: Cached_files): Boolean {
        val key = SongKey(row.provider, row.song_id)
        val path = paths.final(key, row.bitrate.toInt(), row.ext)
        val stamp = stampOf(path, row.bytes)
        if (stamp == null) {
            verified.remove(key)
            return false
        }
        if (verified[key] == stamp) return true
        val ok = withContext(disp.io) { headIsAudio(path, row.bytes) }
        verified[key] = if (ok) stamp else Stamp.NONE
        return ok
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

    private fun headIsAudio(
        path: Path,
        expectedBytes: Long,
    ): Boolean {
        if (expectedBytes <= 0) return false
        return runCatching {
            fs.openReadOnly(path).use { h ->
                val buf = ByteArray(SNIFF_BYTES)
                var read = 0
                while (read < SNIFF_BYTES) {
                    val n = h.read(read.toLong(), buf, read, SNIFF_BYTES - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < SNIFF_BYTES) return@use false
                buf.decodeToString(FTYP_AT, FMP4_AT) == "ftyp" || isId3(buf)
            }
        }.getOrDefault(false)
    }

    private fun isId3(buf: ByteArray) =
        buf[0] == 'I'.code.toByte() && buf[1] == 'D'.code.toByte() && buf[2] == '3'.code.toByte()

    /** Called from the `TrackChanged` handler and from detach, which clears it. */
    fun noteEngineCurrent(itemId: String?) {
        current = itemId
    }

    private companion object {
        const val UNKNOWN_MTIME = -1L
        const val SNIFF_BYTES = 12
        const val FTYP_AT = 4
        const val FMP4_AT = 8
    }
}
