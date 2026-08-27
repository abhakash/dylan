package dylan.playback

import dylan.cache.Paths
import dylan.db.Dylan
import dylan.model.SongKey
import dylan.util.AppDispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem

internal class WindowPreparer(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val disp: AppDispatchers,
) {
    suspend fun cachedRow(key: SongKey) =
        withContext(disp.dbLane) {
            db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
        }

    fun sniffOk(row: dylan.db.Cached_files): Boolean {
        val key = SongKey(row.provider, row.song_id)
        val path = paths.final(key, row.bitrate.toInt(), row.ext)
        val meta = runCatching { fs.metadataOrNull(path) }.getOrNull() ?: return false
        if (meta.size ?: -1L != row.bytes) return false
        return runCatching {
            val h = fs.openReadOnly(path)
            try {
                val buf = ByteArray(12)
                var read = 0
                while (read < 12) {
                    val n = h.read(read.toLong(), buf, read, 12 - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 12) return@runCatching false
                val head = buf.decodeToString(4, 8)
                head == "ftyp" || buf[0] == 'I'.code.toByte() && buf[1] == 'D'.code.toByte() && buf[2] == '3'.code.toByte()
            } finally {
                runCatching { h.close() }
            }
        }.getOrDefault(false)
    }

    suspend fun localTrackFor(song: dylan.model.Song): LocalTrack? {
        val row = cachedRow(song.key) ?: return null
        if (!sniffOk(row)) return null
        val path = paths.final(song.key, row.bitrate.toInt(), row.ext)
        return LocalTrack(
            itemId = song.key.itemId(row.bitrate.toInt()),
            path = path.toString(),
            durationHintMs = song.durationS * 1000,
            title = song.title,
            artist = song.artistName ?: song.subtitle,
            artworkUri = song.artUrl500.takeIf { it.isNotBlank() } ?: song.artUrl150.takeIf { it.isNotBlank() },
        )
    }
}
