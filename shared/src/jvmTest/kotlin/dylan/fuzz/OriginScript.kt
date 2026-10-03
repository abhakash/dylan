package dylan.fuzz

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.write
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** One response the origin will give, consumed in order. The last entry repeats. */
sealed interface OriginStep {
    /** A complete body. */
    data class Ok(
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    ) : OriginStep

    /** A status with no useful body — 4xx/5xx and the rate-limit shapes. */
    data class Status(
        val code: Int,
        val retryAfter: String? = null,
        val body: ByteArray = ByteArray(0),
    ) : OriginStep

    /** `206 Partial Content` for a `Range`, with the `Content-Range` the client must match. */
    data class Range(
        val from: Long,
        val to: Long,
        val total: Long,
        val etag: String? = null,
    ) : OriginStep

    /** Headers, [stopAfterBytes] of body, then an `IOException` out of the channel. FI-07. */
    data class Reset(
        val stopAfterBytes: Int,
        val declared: Int,
    ) : OriginStep

    /** Headers, [stopAfterBytes] of body, then a clean EOF with the full length declared. FI-08. */
    data class Truncate(
        val stopAfterBytes: Int,
        val declared: Int,
    ) : OriginStep

    /** Headers, then never another byte. FI-15 — the watchdog's job. */
    data class Stall(
        val declared: Int,
    ) : OriginStep

    /** Headers and a declared length, then a clean EOF with *nothing* declared as reached. FI-10. */
    data class WrongContentLength(
        val declared: Long,
    ) : OriginStep
}

/** One recorded request, so a scenario can assert the *protocol* and not only the outcome. */
data class RecordedRequest(
    val url: String,
    val range: String?,
    val ifRange: String?,
    val acceptEncoding: String?,
    val status: Int,
)

/**
 * What a single read off the feeder channel produced. [Eof] exists so that **a clean close is
 * distinguishable from a reset**: `Channel.receive` reports both as a throw, and collapsing them
 * turns a server that hung up early into a transport error — which is precisely the
 * truncation-equals-ambiguity the engine's `StreamErr` taxonomy exists to remove.
 */
private sealed interface Feed {
    class Chunk(
        val bytes: ByteArray,
    ) : Feed

    data object Eof : Feed
}

/**
 * The scripted origin: what the network does. Knows nothing about the app.
 *
 * **The mid-stream seam is real and verified.** ktor 3.5.2's `MockEngine` `respond` overload takes a
 * `ByteReadChannel`, so the body is produced through a `RawSource` over a `Channel<ByteArray>` that
 * the feeder closes — cleanly, with an `IOException`, or not at all. That is what makes `Reset`,
 * `Truncate` and `Stall` expressible at all; `respond(content = ByteArray)` cannot fail mid-body.
 * `M0SeamsTest` pins this against the *real* `DownloadEngine`, which is why the design does not
 * need the `HttpClientEngine` fallback the plan hedged on.
 *
 * Determinism: [requests] is the ordered record, and [plan] is consumed by index, so a scenario can
 * assert "request #2 carried `Range: bytes=10800-`" without counting on a clock. [chunkBytes] and
 * [chunkDelayMs] are constructor parameters rather than system properties, so nothing about a
 * scenario can be changed from outside the process that runs it.
 */
class OriginScript(
    private val scope: CoroutineScope,
    private val body: ByteArray,
    var plan: List<OriginStep> = listOf(OriginStep.Ok(body)),
    var repeatLast: Boolean = true,
    /** Chunk size of a scripted body. One chunk per response by default — no wall time at all. */
    val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    /** Delay between chunks. Non-zero is how the stall watchdog is put under test. */
    val chunkDelayMs: Long = 0L,
    /**
     * How long a feeder may be silent before the read is a **failure**. Silence is not EOF: the
     * previous harness in `DownloadEngineTest` returned EOF after a timeout, so a hung origin read
     * as "truncated, and truncation is success" — the very bug the engine's size check was written
     * for.
     *
     * **Limitation, stated because it matters for M4.** `readAtMostTo` is not a suspend function,
     * so the receive is bridged with `runBlocking`, which does not observe the outer coroutine's
     * cancellation. A [OriginStep.Stall] therefore ends the copy when the *stall watchdog* cancels
     * the copy coroutine, but the blocked `readAtMostTo` only unwinds once the receive itself
     * returns or this ceiling fires. Any stall scenario must set this ceiling comfortably above
     * `cfg.stallTimeoutMs`, or the test measures this constant instead of the watchdog.
     */
    val receiveCeilingMs: Long = DEFAULT_RECEIVE_CEILING_MS,
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val feeders = CopyOnWriteArrayList<Job>()

    /** (hasRange, hasIfRange, status) → how many requests landed in that protocol bucket. */
    val buckets: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

    fun bucketCount(): Int = buckets.size

    override fun close() = feeders.forEach { it.cancel() }

    private fun stepAt(i: Int): OriginStep = plan.getOrNull(i) ?: plan.lastOrNull()?.takeIf { repeatLast } ?: OriginStep.Ok(body)

    val mock: MockEngine =
        MockEngine { request ->
            val i = requests.size
            val headers = request.headers.entries().associate { it.key to it.value.joinToString(",") }
            val range = headers.entries.firstOrNull { it.key.equals(HttpHeaders.Range, true) }?.value
            val ifRange = headers.entries.firstOrNull { it.key.equals(HttpHeaders.IfRange, true) }?.value
            val step = stepAt(i)
            val status = statusOf(step, range)
            requests +=
                RecordedRequest(
                    url = request.url.toString(),
                    range = range,
                    ifRange = ifRange,
                    acceptEncoding =
                        headers.entries.firstOrNull { it.key.equals(HttpHeaders.AcceptEncoding, true) }?.value,
                    status = status,
                )
            buckets.computeIfAbsent(bucket(range, ifRange, status)) { AtomicInteger() }.incrementAndGet()
            val (content, hdrs) = payload(step, range)
            respond(
                content = content,
                status = HttpStatusCode.fromValue(status),
                headers = headersOf(*hdrs.map { (k, v) -> k to listOf(v) }.toTypedArray()),
            )
        }

    private fun bucket(
        range: String?,
        ifRange: String?,
        status: Int,
    ): String = "range=${range != null},ifRange=${ifRange != null},status=$status"

    private fun statusOf(
        step: OriginStep,
        range: String?,
    ): Int =
        when (step) {
            is OriginStep.Status -> step.code
            // A Range answered with 200 is a legal-but-hostile origin (FI-11); `Restart` is the
            // contract, and the status is what the scenario reads to prove the branch was taken.
            is OriginStep.Ok -> if (range != null) 200 else 200
            is OriginStep.Range -> if (range != null) 206 else 200
            else -> 200
        }

    private fun payload(
        step: OriginStep,
        range: String?,
    ): Pair<ByteReadChannel, Map<String, String>> =
        when (step) {
            is OriginStep.Ok -> ByteReadChannel(SliceSource(step.body).buffered()) to mp4(step.body.size, step.headers)
            is OriginStep.Status ->
                ByteReadChannel(SliceSource(step.body).buffered()) to
                    buildMap {
                        put("Content-Length", step.body.size.toString())
                        step.retryAfter?.let { put("Retry-After", it) }
                    }
            is OriginStep.Range -> {
                val asked = askedOffset(range)
                // Clamped, never `to - from`: a script that names a range the request did not ask for
                // must produce a *wrong but well-formed* 206 (which is FI-12), not a
                // `ByteArray.copyOfRange` throw from inside the fixture. A harness that dies on its
                // own incoherent script reports the fixture's bug as the app's.
                val start = asked.coerceIn(0L, body.size.toLong()).toInt()
                val stop = (step.to + 1).coerceIn(start.toLong(), body.size.toLong()).toInt()
                val slice = body.copyOfRange(start, stop)
                ByteReadChannel(SliceSource(slice).buffered()) to
                    buildMap {
                        put("Content-Type", "audio/mp4")
                        put("Content-Length", slice.size.toString())
                        // The *declared* start is the script's, not the request's, so a scenario can
                        // answer a Range with a Content-Range that starts elsewhere — the splice DL-3
                        // is about.
                        put("Content-Range", "bytes ${step.from}-${step.to}/${step.total}")
                        step.etag?.let { put("ETag", it) }
                    }
            }
            is OriginStep.Reset ->
                scriptedBody(step.declared, step.stopAfterBytes, throwAtEnd = true) to
                    mp4(step.declared)
            is OriginStep.Truncate ->
                scriptedBody(step.declared, step.stopAfterBytes, throwAtEnd = false) to
                    mp4(step.declared)
            is OriginStep.Stall ->
                scriptedBody(step.declared, -1, throwAtEnd = false) to
                    mp4(step.declared)
            is OriginStep.WrongContentLength ->
                scriptedBody(body.size, -1, throwAtEnd = false) to
                    mp4(step.declared.toInt())
        }

    private fun mp4(
        declared: Int,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        buildMap {
            put("Content-Type", "audio/mp4")
            put("Content-Length", declared.toString())
            putAll(extra)
        }

    /**
     * A channel fed [limit] bytes of [declared]-length body. `limit < 0` means "everything, then
     * close" — which for [OriginStep.Stall] the feeder never does, so the watchdog is the only
     * thing that can end it.
     */
    private fun scriptedBody(
        declared: Int,
        limit: Int,
        throwAtEnd: Boolean,
    ): ByteReadChannel {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val stopAt = if (limit < 0) declared else limit
        feeders +=
            scope.launch {
                var off = 0
                while (off < stopAt) {
                    val n = min(chunkBytes, stopAt - off)
                    chunks.send(body.copyOfRange(off, off + n))
                    off += n
                    if (chunkDelayMs > 0) kotlinx.coroutines.delay(chunkDelayMs)
                }
                // `limit < 0` means "send everything and never close" — the stall shape.
                if (limit < 0 && stopAt >= declared) return@launch
                chunks.close(if (throwAtEnd) RESET_CAUSE else null)
            }
        return ByteReadChannel(FeedSource(chunks, throwAtEnd, receiveCeilingMs).buffered())
    }

    /**
     * Delivers a fixed array.
     *
     * `Buffer.write(ByteArray, startIndex, endIndex)`'s third argument is an **end index**, not a
     * length. Passing the length — which is what this did — makes the second read of any body
     * larger than one channel segment throw `IllegalArgumentException: startIndex (8192) >
     * endIndex (3808)` from inside the fixture. That exception was observed surfacing to the
     * download engine as `JobState.Failed(NETWORK)`, i.e. the harness's own arithmetic was being
     * reported as the application's transport failure.
     */
    private class SliceSource(
        private val bytes: ByteArray,
    ) : RawSource {
        private var off = 0

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (off >= bytes.size) return -1L
            val n = min(byteCount.toInt(), bytes.size - off)
            sink.write(bytes, off, off + n)
            off += n
            return n.toLong()
        }

        override fun close() = Unit
    }

    /**
     * Delivers from a feeder channel, and is where the three body-endings are kept apart:
     *
     *  * a **clean** close is EOF (`-1`), because `Channel.receive` reports it as a throw and
     *    treating that as a failure turns a server that hung up early into a transport error. That
     *    is the whole distinction `StreamErr.Truncated` and `StreamErr.Network` exist to make.
     *  * a close **with a cause** is that cause, re-thrown as the `IOException` a mid-stream reset
     *    produces.
     *  * silence past the ceiling is a **failure**, not an EOF: the old harness in
     *    `DownloadEngineTest` returned EOF after 60 s of silence, so a hung origin read as
     *    "truncated, and truncation is success" — the exact bug the engine was fixed for.
     */
    private class FeedSource(
        private val chunks: Channel<ByteArray>,
        private val throwAtEnd: Boolean,
        private val ceilingMs: Long,
    ) : RawSource {
        /**
         * `readAtMostTo` is not a `suspend` function, so the receive is bridged with `runBlocking`.
         * See the class KDoc for the cancellation limitation that follows from it.
         */
        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val fed =
                runBlocking {
                    withTimeoutOrNull(ceilingMs) {
                        try {
                            Feed.Chunk(chunks.receive())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (closed: ClosedReceiveChannelException) {
                            if (throwAtEnd) throw IOException(RESET_DETAIL, closed) else Feed.Eof
                        } catch (e: Throwable) {
                            throw IOException(RESET_DETAIL, e)
                        }
                    }
                } ?: throw IOException("scripted body went silent for ${ceilingMs}ms")
            return when (fed) {
                Feed.Eof -> -1L
                is Feed.Chunk -> {
                    sink.write(fed.bytes)
                    fed.bytes.size.toLong()
                }
            }
        }

        override fun close() = Unit
    }
}

/** The `Range:` offset a request asked for, or 0 for a cold (non-resuming) request. */
private fun askedOffset(range: String?): Long = range?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L

/** Message carried by a mid-stream reset, asserted on by name in the M0 scenarios. */
const val RESET_DETAIL = "scripted connection reset"

private val RESET_CAUSE: IOException = IOException(RESET_DETAIL)

private const val DEFAULT_CHUNK_BYTES = 65_536
private const val DEFAULT_RECEIVE_CEILING_MS = 20_000L

/** The default scripted body: a real `ftyp` head of [size] bytes. */
fun mp4Body(size: Int): ByteArray {
    val b = ByteArray(size)
    "ftyp".encodeToByteArray().copyInto(b, 4, 0, minOf(4, size - 4).coerceAtLeast(0))
    "M4A ".encodeToByteArray().copyInto(b, 8, 0, minOf(4, size - 8).coerceAtLeast(0))
    return b
}
