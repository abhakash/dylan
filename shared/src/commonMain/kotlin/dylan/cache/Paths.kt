package dylan.cache

import dylan.model.SongKey
import okio.FileSystem
import okio.Path

class Paths(
    val audioDir: Path,
    private val fs: FileSystem,
) {
    init {
        fs.createDirectories(audioDir)
    }

    /**
     * Kept for the download engine's `.part` prefix scans. Delegates to the shared grammar so
     * the prefix it builds is the same prefix [CachePath] would produce.
     */
    fun sanitize(id: String): String = CachePath.segment(id)

    fun final(
        key: SongKey,
        bits: Int,
        ext: String,
    ): Path = audioDir / CachePath.fileName(key.provider, key.songId, bits, ext)

    fun part(
        key: SongKey,
        bits: Int,
    ): Path = audioDir / CachePath.partName(key.provider, key.songId, bits)

    /** Null for a file this app did not write; the orphan sweep never deletes those. */
    fun parseFileName(name: String): CacheFileName? = CachePath.parse(name)
}
