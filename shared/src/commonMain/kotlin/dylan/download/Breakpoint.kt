package dylan.download

import dylan.model.ErrorCode
import dylan.model.Quality
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.parse
import kotlinx.datetime.toInstant
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The bytes-on-disk ↔ bytes-on-wire contract, as a value.
 *
 * Six defects (DL-1, DL-2, DL-3, DL-6, DL-9 and the truncation-equals-success ambiguity) were all
 * statements about the relationship between what is on disk and what is on the wire, and all six
 * were invisible because that relationship lived in a mutable local that four sites wrote and the
 * writer never touched. It is a value here, so:
 *
 *  - [partBytes] has exactly one mutator, [wrote], and the copy loop calls it on every chunk. A
 *    `Range:` header is therefore derived from what was actually flushed, not from what a previous
 *    step hoped — a retry resumes instead of restarting from byte 0 (DL-1).
 *  - [totalBytes] is *persisted*, never re-derived. It replaces `duration × bitrate` as a hard
 *    lower bound, which deterministically rejected every legitimate 128 kbps m4a on a chunked
 *    response because `Quality.BITRATE_128.bps` is already 27% padded (DL-6).
 *  - [etag] survives the process, so a cold resume — the *normal* path, because the reconciler
 *    re-enqueues `.part` files left by previous processes — can send `If-Range` and can detect a
 *    server that answers a `Range` with a different rendition (DL-3).
 *  - [originResolvedAtMs] is when the signed URL was minted, so a 5-minute-TTL URL that expired
 *    mid-transfer is noticed as an expired origin instead of surfacing later as a truncation.
 */
data class Breakpoint(
    /** Exactly what is on disk: the end offset of the last successful write, never a wish. */
    val partBytes: Long,
    /** Declared by the origin (Content-Length, or `Content-Range: …/total`). Persisted as given. */
    val totalBytes: Long?,
    /** Strong validator. The `If-Range` source, and the check that two renditions never splice. */
    val etag: String?,
    val quality: Quality,
    val originResolvedAtMs: Long,
) {
    val resumable: Boolean get() = partBytes > 0L

    /**
     * Fold the writer's write-set back in — the *only* way [partBytes] changes. Anything that
     * wants to move the offset has to say which bytes it actually wrote.
     */
    fun wrote(
        at: Long,
        totalBytes: Long? = this.totalBytes,
        etag: String? = this.etag,
        originResolvedAtMs: Long = this.originResolvedAtMs,
    ): Breakpoint = copy(partBytes = at, totalBytes = totalBytes, etag = etag, originResolvedAtMs = originResolvedAtMs)

    /** True when a restart is required: the object we hold bytes of is not the object on offer. */
    fun contradicts(
        replyEtag: String?,
        replyTotal: Long?,
    ): Boolean {
        val etagChanged = etag != null && replyEtag != null && etag != replyEtag
        val totalChanged = totalBytes != null && replyTotal != null && totalBytes != replyTotal
        return etagChanged || totalChanged
    }

    companion object {
        fun fresh(
            quality: Quality,
            originResolvedAtMs: Long,
        ): Breakpoint = Breakpoint(0L, null, null, quality, originResolvedAtMs)
    }
}

/** What the engine may do with the bytes it holds, given what the origin just said. */
sealed interface Resume {
    /** The held bytes are valid: append the body at [at]. */
    data class Append(
        val at: Long,
        val totalBytes: Long?,
        val etag: String?,
    ) : Resume

    /**
     * The body in hand *is* the whole object (only a `200` can be), so truncate and stream it from
     * zero — no wasted round trip, which is what the old "200 answered a Range" path threw away.
     */
    data object Restart : Resume

    /**
     * The body in hand is a *range* or there is none, so the held bytes are discarded and the
     * request is repeated without a `Range`. This is a fourth case the three-case sketch in the
     * audit cannot express: streaming a `206` body from offset 0 after deciding to restart would
     * write the tail of the object over the head, which is the exact splice DL-3 is about.
     */
    data object Refetch : Resume

    /** No honest way to continue; [code] is terminal for this attempt. */
    data class Fail(
        val code: ErrorCode,
        val why: String,
    ) : Resume
}

/**
 * One response, reduced to the fields the decision needs. Kept as a value so [resumeDecision] is
 * testable with no HTTP client at all — which is exactly what the old suite could not do.
 */
data class Reply(
    val status: Int,
    val etag: String?,
    val contentType: String?,
    /** Content-Length of *this* response (a range's length, not the object's). */
    val length: Long?,
    val rangeStart: Long?,
    val rangeEnd: Long?,
    /** `Content-Range: bytes a-b/total` → total, or null when the origin wrote `*`. */
    val total: Long?,
    val retryAfterMs: Long?,
) {
    /**
     * Where the body ends if the origin is honest, so a premature EOF is detectable: the end of
     * this response when it declared a length, else the end of the whole object.
     */
    fun expectedEnd(from: Long): Long? =
        when {
            length != null -> from + length
            total != null -> total
            else -> null
        }
}

/**
 * The one decision every request path makes.
 *
 * `Restart` is the answer for a `200` sent to a `Range` (extremely common from CDNs): the body is
 * the whole object, so the held bytes are dropped and the body is used from zero. `Refetch` is the
 * answer when the body in hand is *not* the whole object — a `206` whose `Content-Range` start is
 * not [Breakpoint.partBytes] (the previously undetectable "206-from-0 against a 2 MB part", which
 * would otherwise splice two renditions into a file that passes both the size check and the
 * container sniff), a `206` whose validator or total contradicts the persisted one, or a `416`.
 */
fun resumeDecision(
    bp: Breakpoint?,
    reply: Reply,
): Resume {
    val part = bp?.partBytes ?: 0L
    return when (reply.status) {
        STATUS_PARTIAL ->
            when {
                reply.rangeStart == null -> Resume.Fail(ErrorCode.DRIFT, "206 without a Content-Range start")
                reply.rangeStart != part -> Resume.Refetch
                bp != null && bp.contradicts(reply.etag, reply.total) -> Resume.Refetch
                else ->
                    Resume.Append(
                        at = part,
                        totalBytes = reply.total ?: bp?.totalBytes,
                        etag = reply.etag ?: bp?.etag,
                    )
            }
        // A 200 in reply to a Range means the origin ignored the range, or the If-Range validator
        // no longer matches and it is re-sending the whole object. Either way: start over.
        STATUS_OK -> Resume.Restart
        STATUS_RANGE_NOT_SATISFIABLE ->
            if (part == 0L) {
                Resume.Fail(ErrorCode.NETWORK, "416 with no .part to resume")
            } else {
                Resume.Refetch
            }
        STATUS_TOO_MANY_REQUESTS, STATUS_SERVICE_UNAVAILABLE ->
            Resume.Fail(ErrorCode.RATE_LIMITED, "http ${reply.status}")
        STATUS_UNAUTHORIZED, STATUS_FORBIDDEN -> Resume.Fail(ErrorCode.EXPIRED, "http ${reply.status}")
        STATUS_NOT_FOUND -> Resume.Fail(ErrorCode.NOT_FOUND, "http ${reply.status}")
        else -> Resume.Fail(ErrorCode.NETWORK, "http ${reply.status}")
    }
}

/** HttpResponse overload: the single entry point the engine uses, so no path can bypass it. */
fun resumeDecision(
    bp: Breakpoint?,
    response: HttpResponse,
    nowMs: Long,
): Resume = resumeDecision(bp, response.toReply(nowMs))

internal fun HttpResponse.toReply(nowMs: Long): Reply {
    val range = headers[HttpHeaders.ContentRange]?.let(::parseContentRange)
    return Reply(
        status = status.value,
        etag = headers[HttpHeaders.ETag]?.trim()?.takeIf { it.isNotEmpty() },
        contentType = headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase(),
        length = headers[HttpHeaders.ContentLength]?.toLongOrNull(),
        rangeStart = range?.first,
        rangeEnd = range?.second,
        total = range?.third,
        retryAfterMs = parseRetryAfterMs(headers[HttpHeaders.RetryAfter], nowMs),
    )
}

/**
 * `bytes 200-1023/1024` → (200, 1023, 1024). The start offset is the whole point of this function;
 * the old parser threw it away, which is what made a 206-from-0 undetectable (DL-3).
 */
internal fun parseContentRange(value: String): Triple<Long, Long, Long?>? {
    val m = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
    val start = m.groupValues[1].toLongOrNull() ?: return null
    val end = m.groupValues[2].toLongOrNull() ?: return null
    val total = m.groupValues[TOTAL_GROUP].takeIf { it != WILDCARD }?.toLongOrNull()
    return Triple(start, end, total)
}

/**
 * `Retry-After` in both legal forms: delta-seconds and an HTTP-date. Returns milliseconds from
 * [nowMs], or null when the header is absent or unparseable (the caller then uses its own
 * default). A date already in the past yields 0, not a negative delay.
 */
internal fun parseRetryAfterMs(
    header: String?,
    nowMs: Long,
): Long? {
    val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) * MILLIS_PER_SECOND }
    // HTTP-date form. `DateTimeComponents.parse` is the RFC-1123 parser (it also accepts the two
    // obsolete RFC-850 and asctime shapes real servers still emit); a date carries no zone of its
    // own worth trusting beyond the GMT the format mandates, so it is read as UTC.
    val at =
        runCatching {
            val c = DateTimeComponents.parse(raw, DateTimeComponents.Formats.RFC_1123)
            val y = c.year
            val mo = c.monthNumber
            val d = c.dayOfMonth
            val h = c.hour ?: 0
            val mi = c.minute ?: 0
            val se = c.second ?: 0
            if (y == null || mo == null || d == null) return null
            LocalDateTime(y, mo, d, h, mi, se).toInstant(TimeZone.UTC).toEpochMilliseconds()
        }.getOrNull() ?: return null
    return (at - nowMs).coerceAtLeast(0L)
}

/**
 * The `.part.meta` sidecar: [Breakpoint] on disk, next to the `.part` it describes.
 *
 * A cold resume is the *normal* path — the reconciler re-enqueues `.part` files left by previous
 * processes — and before this there was nothing to resume *from*: `etag` was an in-memory `var`, so
 * the process that could have sent `If-Range` was gone by the time the bytes were needed (DL-3).
 *
 * The format is one `key=value` per line, written whole and never appended, so a torn write from a
 * crash costs the sidecar (the `.part` is still resumable, just without a validator) and never the
 * bytes. `bytes` is written for diagnostics only: the engine trusts the filesystem's size over it.
 */
internal object PartMeta {
    const val SUFFIX = ".meta"
    private const val VERSION = "1"

    fun sidecarOf(part: Path): Path = (part.toString() + SUFFIX).toPath()

    fun encode(bp: Breakpoint): String =
        buildString {
            append("v=").append(VERSION).append('\n')
            append("q=").append(bp.quality.name).append('\n')
            append("bytes=").append(bp.partBytes).append('\n')
            bp.totalBytes?.let { append("total=").append(it).append('\n') }
            bp.etag?.let { append("etag=").append(it).append('\n') }
            append("origin=").append(bp.originResolvedAtMs).append('\n')
        }

    fun decode(text: String): Breakpoint? {
        val fields = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) fields[line.substring(0, eq)] = line.substring(eq + 1)
        }
        if (fields["v"] != VERSION) return null
        val quality = fields["q"]?.let { q -> Quality.entries.firstOrNull { it.name == q } } ?: return null
        val bytes = fields["bytes"]?.toLongOrNull() ?: return null
        return Breakpoint(
            partBytes = bytes,
            totalBytes = fields["total"]?.toLongOrNull(),
            etag = fields["etag"]?.takeIf { it.isNotEmpty() },
            quality = quality,
            originResolvedAtMs = fields["origin"]?.toLongOrNull() ?: 0L,
        )
    }

    fun read(
        fs: FileSystem,
        sidecar: Path,
    ): Breakpoint? =
        runCatching {
            if (!fs.exists(sidecar)) return null
            val text = fs.read(sidecar) { readUtf8() } ?: return null
            decode(text)
        }.getOrNull()

    fun write(
        fs: FileSystem,
        sidecar: Path,
        bp: Breakpoint,
    ): Boolean =
        runCatching {
            fs.write(sidecar) { writeUtf8(encode(bp)) }
            true
        }.getOrDefault(false)

    fun delete(
        fs: FileSystem,
        sidecar: Path,
    ) {
        runCatching { fs.delete(sidecar, false) }
    }
}

private const val MILLIS_PER_SECOND = 1_000L

private const val STATUS_OK = 200
private const val STATUS_PARTIAL = 206
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
private const val STATUS_NOT_FOUND = 404
private const val STATUS_RANGE_NOT_SATISFIABLE = 416
private const val STATUS_TOO_MANY_REQUESTS = 429
private const val STATUS_SERVICE_UNAVAILABLE = 503

private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")

/** Group index of the total in [CONTENT_RANGE]; `*` means the origin would not say. */
private const val TOTAL_GROUP = 3
private const val WILDCARD = "*"
