package dylan.cache

import okio.Buffer

/**
 * The one grammar for a name in the audio directory:
 *
 * ```
 * <provider>_<songId>_<bitrate>.<ext>     a finished file
 * <provider>_<songId>_<bitrate>.part      an in-flight transfer
 * ```
 *
 * `Paths`, the Reconciler's orphan sweep and the download engine's `.part` parser all go through
 * here, which is what makes the filename round-trippable. Two properties matter:
 *
 * 1. **Every segment is sanitised, not just the song id.** §8.2's adapter-boundary rule applied
 *    to half the key: a provider id containing `/` or `..` walked straight out of the audio
 *    directory, and an ext came from a Content-Type header.
 * 2. **No segment contains `_`.** That is what makes the name unambiguously splittable —
 *    `parsePartName`'s split-on-first/last-underscore round trip was ambiguous exactly because
 *    `sanitizeId` passed underscores through.
 *
 * [parse] is deliberately strict: it is the *allowlist* the orphan sweep uses before deleting
 * anything, so a `cover.jpg` or a half-restored file can never match it.
 */
object CachePath {
    /** Not a legal character in any segment, so it is a safe key separator for the SQL side. */
    const val SEPARATOR: Char = '#'

    const val PART_EXT: String = "part"

    private const val NAME_SEPARATOR = '_'
    private const val SEGMENTS = 3
    private const val FIRST_BITRATE = 1
    private val SEGMENT_RE = Regex("[A-Za-z0-9-]+")
    private val MEDIA_EXTS = setOf("aac", "flac", "m4a", "m4b", "mp3", "ogg", "opus", "wav", "webm")

    /** §8.2 adapter-boundary rule: path- and token-safe charset, else SHA-256 hex fallback. */
    fun segment(raw: String): String = if (raw.isNotEmpty() && raw.matches(SEGMENT_RE)) raw else sha256(raw)

    fun fileName(
        provider: String,
        songId: String,
        bitrate: Int,
        ext: String,
    ): String = "${segment(provider)}$NAME_SEPARATOR${segment(songId)}$NAME_SEPARATOR$bitrate.${segment(ext)}"

    fun partName(
        provider: String,
        songId: String,
        bitrate: Int,
    ): String = "${segment(provider)}$NAME_SEPARATOR${segment(songId)}$NAME_SEPARATOR$bitrate.$PART_EXT"

    fun isPart(name: String): Boolean = name.endsWith(".$PART_EXT")

    /** Null for anything this app did not write — the orphan sweep's delete precondition. */
    fun parse(name: String): CacheFileName? {
        val parts = name.split(NAME_SEPARATOR)
        if (parts.size != SEGMENTS) return null
        val ext = name.substringAfterLast('.', "")
        if (ext != PART_EXT && ext !in MEDIA_EXTS) return null
        val bitrate = parts[2].substringBefore('.').toIntOrNull() ?: return null
        return if (bitrate >= FIRST_BITRATE) CacheFileName(parts[0], parts[1], bitrate, ext) else null
    }

    private fun sha256(raw: String): String = Buffer().writeUtf8(raw).sha256().hex()
}

/** A parsed audio-directory file name. */
data class CacheFileName(
    val provider: String,
    val songId: String,
    val bitrate: Int,
    val ext: String,
)
