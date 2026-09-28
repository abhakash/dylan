package dylan.download

import okio.FileHandle
import okio.FileSystem
import okio.Path

/** Container families the download engine verifies and the playback window probes for. */
internal enum class Container { MP4, MP3 }

/**
 * Bytes the recogniser reads. The engine used to check 4 (`ftyp` alone) while the playback window
 * checked 12, so a 4–11 byte file passed verification and was then rejected at playback — the
 * engine committed a file it had not really proven playable.
 */
internal const val CONTAINER_MAGIC_BYTES = 12

/** An iso box is a 4-byte size, then the four-letter type, so `ftyp` sits at offset 4. */
private const val BOX_SIZE = 4
private const val TYPE_SIZE = 4
private const val FTYP_AT = BOX_SIZE
private const val FTYP_END = FTYP_AT + TYPE_SIZE
private const val HEAD_END = FTYP_END + TYPE_SIZE

private const val FTYP = "ftyp"
private const val ID3_AT = 0
private const val ID3_END = 3
private const val FRAME_SYNC_BYTE = 0xFF
private const val FRAME_SYNC_MASK = 0xE0
private const val PRINTABLE_MIN = 0x20
private const val PRINTABLE_MAX = 0x7E

/**
 * One recogniser for both call sites, so "verified by the engine" and "accepted by playback" are
 * the same predicate by construction.
 *
 * Body magic is the truth, not `Content-Type`: a CDN error page served as `200 audio/mpeg` used to
 * take the `ext = "mp3"` path, skip the ftyp gate entirely, and get committed as playable (DL-7).
 * This function is the gate, and the engine runs it first.
 */
internal fun sniffContainer(
    fs: FileSystem,
    path: Path,
): Container? {
    val buf = ByteArray(CONTAINER_MAGIC_BYTES)
    if (readHead(fs, path, buf) < CONTAINER_MAGIC_BYTES) return null
    return when {
        isMp4(buf) -> Container.MP4
        isMp3(buf) -> Container.MP3
        else -> null
    }
}

/** `ftyp` at 4, then a printable major brand: the bytes every mp4 carries. */
private fun isMp4(b: ByteArray): Boolean {
    val type = ascii(b, FTYP_AT, FTYP_END)
    return type == FTYP && ascii(b, FTYP_END, HEAD_END).isNotEmpty()
}

/** An ID3v2 tag, or a bare MPEG audio frame sync (eleven set bits). */
private fun isMp3(b: ByteArray): Boolean = ascii(b, ID3_AT, ID3_END) == "ID3" || isFrameSync(b)

private fun isFrameSync(b: ByteArray): Boolean {
    val sync = b[1].toInt() and FRAME_SYNC_MASK
    return b[0] == FRAME_SYNC_BYTE.toByte() && sync == FRAME_SYNC_MASK
}

/** Printable ASCII, or empty when any byte in the window is not. */
private fun ascii(
    b: ByteArray,
    from: Int,
    to: Int,
): String {
    if (to > b.size) return ""
    val sb = StringBuilder(to - from)
    for (i in from until to) {
        val c = b[i].toInt()
        if (c < PRINTABLE_MIN || c > PRINTABLE_MAX) return ""
        sb.append(c.toChar())
    }
    return sb.toString()
}

/** Short reads are a failure, not a partial answer: an under-length file is not playable. */
private fun readHead(
    fs: FileSystem,
    path: Path,
    buf: ByteArray,
): Int =
    runCatching {
        val h: FileHandle = fs.openReadOnly(path)
        try {
            var read = 0
            while (read < buf.size) {
                val n = h.read(read.toLong(), buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
            read
        } finally {
            runCatching { h.close() }
        }
    }.getOrDefault(0)
