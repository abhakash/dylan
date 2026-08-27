package dylan.cache

import dylan.model.SongKey
import okio.Buffer
import okio.FileSystem
import okio.Path

class Paths(
    val audioDir: Path,
    private val fs: FileSystem,
) {
    init {
        fs.createDirectories(audioDir)
    }

    fun sanitize(id: String): String = sanitizeId(id)

    fun final(
        key: SongKey,
        bits: Int,
        ext: String,
    ): Path = audioDir / "${key.provider}_${sanitize(key.songId)}_$bits.$ext"

    fun part(
        key: SongKey,
        bits: Int,
    ): Path = audioDir / "${key.provider}_${sanitize(key.songId)}_$bits.part"

    companion object {
        private val ID_RE = Regex("[A-Za-z0-9_-]+")

        /** §8.2 adapter-boundary rule: path- and token-safe charset, else SHA-256 hex fallback. */
        fun sanitizeId(id: String): String = if (id.matches(ID_RE)) id else Buffer().writeUtf8(id).sha256().hex()
    }
}
