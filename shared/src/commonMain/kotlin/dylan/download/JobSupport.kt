@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import dylan.config.AppConfig
import dylan.model.Quality
import dylan.model.SongKey
import dylan.util.fsRename
import kotlin.concurrent.atomics.AtomicReference

/**
 * The quality decision, as a pure-ish function so it is testable without a graph.
 *
 * Two callers, two answers, and both are pinned by tests so they cannot drift:
 *  - an explicit [Priority.QUALITY_UPGRADE] is authoritative — `job.bitrate` is a *request* and it is
 *    honoured. It used to be read exactly once, for an intent row, and the rendition recomputed
 *    from the user's preference, so the idle `QUALITY_UPGRADE` scanner enqueued 320, `Step.QUALITY`
 *    answered 128 from the pref, and `Step.DEDUPE` short-circuited to `Done` without touching the
 *    network. The upgrade scanner was a no-op for exactly the users who had not manually chosen 320.
 *  - everything else takes the *session's* quality, floored by the request: the metered ceiling on
 *    a metered link, the preference otherwise, never below what the caller asked for. Every
 *    playback-path caller already derives its request from that same policy (`targetBits()`), so
 *    this changes nothing in production — and it means a stale or over-cautious request bitrate can
 *    never hand the user a rendition below the one their own setting names.
 *
 * Metered never upgrades, whatever the reason.
 */
internal suspend fun chooseQuality(
    job: DownloadJob,
    songRow: dylan.db.Songs?,
    cfg: AppConfig,
    netClass: () -> dylan.util.NetClass,
    qualityPref: suspend () -> Quality,
): Quality {
    if (songRow?.has_320 != 1L) return Quality.BITRATE_128
    val metered = netClass() == dylan.util.NetClass.METERED
    if (metered) return minQuality(cfg.meteredQuality, Quality.of(job.bitrate))
    if (job.reason == Priority.QUALITY_UPGRADE) return Quality.of(job.bitrate)
    return maxQuality(qualityPref(), Quality.of(job.bitrate))
}

private fun maxQuality(
    a: Quality,
    b: Quality,
): Quality = if (a.bits >= b.bits) a else b

private fun minQuality(
    a: Quality,
    b: Quality,
): Quality = if (a.bits <= b.bits) a else b

/**
 * Display headroom and the two-sided size band both use this. `Quality.bps` is already ~27% padded
 * for real-world VBR, which is why this is a *band* and never a lower bound (DL-6).
 */
internal fun estimateBytes(
    songRow: dylan.db.Songs?,
    quality: Quality,
): Long {
    val seconds = songRow?.duration_s ?: return 0L
    return kotlin.math.ceil(seconds.toDouble() * quality.bps.toDouble()).toLong()
}

internal fun paddedEstimate(
    cfg: AppConfig,
    songRow: dylan.db.Songs?,
    quality: Quality,
): Long = (estimateBytes(songRow, quality) * cfg.estimatePadding).toLong()

/** The low and high edges of the sanity band, as a fraction of `duration × bps`. */
internal fun sizeBand(rawEstimate: Long): Pair<Long, Long> {
    val lo = rawEstimate * BAND_LO_NUM / BAND_DEN
    val hi = rawEstimate * BAND_HI_NUM / BAND_DEN
    return lo to hi
}

private const val BAND_LO_NUM = 4L
private const val BAND_HI_NUM = 13L
private const val BAND_DEN = 10L

/**
 * CAS mutation for the maps this package shares. `kotlin.concurrent.atomics.AtomicReference` has
 * no `update`, and `MutableStateFlow.update` is the wrong tool off a flow; a monitor is worse
 * because some of these are written from non-suspending callers.
 */
internal inline fun <K, V> AtomicReference<Map<K, V>>.mutate(block: (Map<K, V>) -> Map<K, V>) {
    while (true) {
        val cur = load()
        val next = block(cur)
        if (next === cur || compareAndSet(cur, next)) return
    }
}

/** `saavn:s1` — the key half of [DownloadJob.label], for the lines that only hold a key. */
internal val SongKey.label: String get() = "$provider:$songId"

internal fun markPreempted(
    marks: AtomicReference<Set<JobId>>,
    id: JobId,
) {
    while (true) {
        val cur = marks.load()
        if (marks.compareAndSet(cur, cur + id)) return
    }
}

internal fun clearMark(
    marks: AtomicReference<Set<JobId>>,
    id: JobId,
) {
    while (true) {
        val cur = marks.load()
        if (marks.compareAndSet(cur, cur - id)) return
    }
}

/** Small, non-suspending filesystem verbs the job body needs. */
internal fun fileSize(
    fs: okio.FileSystem,
    p: okio.Path,
): Long = runCatching { fs.metadataOrNull(p)?.size ?: 0L }.getOrDefault(0L)

internal fun deleteQuietly(
    fs: okio.FileSystem,
    p: okio.Path,
) {
    runCatching { fs.delete(p, false) }
}

/**
 * Returns false when the truncate did not happen, so a caller never resets its offset and writes a
 * new body over a stale file (DL-9). The old version returned `Unit` and the caller reset `partB`
 * regardless.
 */
internal fun truncatePart(
    fs: okio.FileSystem,
    p: okio.Path,
): Boolean =
    runCatching {
        if (!fs.exists(p)) return@runCatching true
        val h = fs.openReadWrite(p)
        try {
            h.resize(0)
            true
        } finally {
            runCatching { h.close() }
        }
    }.getOrDefault(false)

/** `fsRename` is `check(rc == 0)` on iOS, so it throws; the caller needs a verdict, not a crash. */
internal fun renamePart(
    from: String,
    to: String,
    log: dylan.diag.LogBuffer,
    rename: (String, String) -> Unit = ::fsRename,
): Boolean =
    runCatching {
        rename(from, to)
        true
    }.onFailure { log.e("dl", "rename failed: ${it.message}") }
        .getOrDefault(false)

/** A malformed signed URL must be a terminal verdict, not an `IllegalArgumentException` out of `Url`. */
internal fun hostOf(
    url: String,
    log: dylan.diag.LogBuffer,
): String? =
    runCatching {
        io.ktor.http
            .Url(url)
            .host
    }.onFailure { log.w("dl", "unparseable stream url: ${url.take(URL_LOG_CHARS)}") }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

private const val URL_LOG_CHARS = 120

/** Did the origin claim audio? Used only to pick between two user-facing failure codes. */
internal fun audioClaimed(
    contentType: String?,
    signedType: String?,
): Boolean {
    val ct = contentType?.lowercase()
    return ct?.contains("audio") == true || signedType == "mp3" || signedType == "mp4"
}
