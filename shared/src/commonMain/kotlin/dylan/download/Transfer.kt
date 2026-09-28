@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.model.ErrorCode
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.SignedStream
import dylan.util.Clock
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okio.FileHandle
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.TimeSource

/** Per-attempt inputs, so no call site grows a parameter list. */
internal data class AttemptCtx(
    val key: SongKey,
    val quality: Quality,
    val songRow: dylan.db.Songs?,
    val resolveCount: Int,
    val rangeRestarts: Int,
    val attempts: Int,
)

/** What one body copy was asked to produce. */
internal data class Segment(
    val partPath: Path,
    val startAt: Long,
    /** Where the bytes must end for this to be a whole object, when the origin said so. */
    val expectedEnd: Long?,
    /** Display and disk-headroom only. Never a bound. */
    val displayTotal: Long?,
)

/**
 * Why the copy stopped. `Ok` means every declared byte arrived. A premature socket close is
 * [Truncated] — resumable, not a failure. The taxonomy used to collapse clean EOF, a mid-transfer
 * reset and a server hanging up early into one value, so a short file either deleted its own
 * resumable `.part` as `CORRUPT_SIZE` or, with no `Content-Length`, was committed as playable (DL-2).
 */
internal enum class StreamErr { Ok, Truncated, Network, Storage, Stall }

internal enum class BreakerWait { Proceed, Defer, Fail }

/**
 * Copy outcome. [written] is what the writer actually flushed, and it is the only thing allowed to
 * move `Breakpoint.partBytes` — the writer feeds it back after every attempt, so a retry re-sends
 * `Range:` from where the bytes really are rather than from a stale local (DL-1).
 */
internal data class CopyOutcome(
    val written: Long,
    val err: StreamErr,
)

/** What a finished (or failed) attempt means for the job. */
internal sealed interface TransferResult {
    val rangeRestarts: Int
    val attempts: Int
    val contentType: String?

    /** Body complete: verify next. */
    data class Done(
        override val rangeRestarts: Int,
        override val attempts: Int,
        override val contentType: String?,
    ) : TransferResult

    /** Try again, same origin or a fresh one. */
    data class Retry(
        override val rangeRestarts: Int,
        override val attempts: Int,
        override val contentType: String?,
        val reResolve: Boolean,
    ) : TransferResult

    /** Hand the job back to the queue with a delay; the worker slot is released now. */
    data class Defer(
        override val attempts: Int,
        val delayMs: Long,
        override val contentType: String?,
    ) : TransferResult {
        override val rangeRestarts: Int = 0
    }

    /** Terminal for this attempt. */
    data class Failed(
        val code: ErrorCode,
        val why: String,
        override val rangeRestarts: Int = 0,
        override val attempts: Int = 0,
        override val contentType: String? = null,
    ) : TransferResult
}

/**
 * One request plus one body copy, and the whole error taxonomy that follows.
 *
 * This is the collaborator the audit's abandoned `Fetcher.kt` facade was reaching for. It holds no
 * state across calls, so the retry / truncate / re-resolve bookkeeping lives with the attempt that
 * caused it and cannot drift.
 *
 * The bounded resource is here too: [gate] is taken around the *transfer*, not around a job, so a
 * job parked in VERIFY or COMMIT holds no permit and a 6 MB `USER_NOW` does not contend with a
 * 400 KB `PREFETCH_NEXT` for the same wall-clock time.
 */
internal class Transfer(
    private val fs: FileSystem,
    private val cfg: AppConfig,
    private val bulk: HttpClient,
    private val breakers: Breakers,
    private val gate: TransferGate,
    private val clock: Clock,
    private val log: LogBuffer,
    private val publishProgress: (SongKey, Int) -> Unit,
    private val defer: suspend (DownloadJob, Long) -> Unit,
) {
    /**
     * Run one attempt. [live] is the attempt's [Breakpoint]; it is the *only* thing that decides
     * the `Range` offset, and the writer folds the bytes it flushed back into it on every chunk.
     */
    suspend fun run(
        job: DownloadJob,
        origin: SignedStream,
        partPath: Path,
        live: AtomicReference<Breakpoint>,
        ctx: AttemptCtx,
        priorContentType: String?,
    ): TransferResult {
        val key = ctx.key
        val host = hostOf(origin.url, log) ?: return unparseable(ctx, priorContentType)
        val breaker = breakers.forHost(host)
        val bp = live.load()
        val startedAt = TimeSource.Monotonic.markNow()
        val lastMark = AtomicReference(startedAt)
        val throttle = ProgressThrottle(clock, PROGRESS_INTERVAL_MS)
        var restarts = ctx.rangeRestarts
        var contentType = priorContentType
        var rejected: Resume.Fail? = null
        var retryAfterMs: Long? = null
        var copied: CopyOutcome? = null
        var refetch = false
        var hostFailure: String? = null
        var settled = false
        var held = false
        when (breakerWait(job, breaker)) {
            BreakerWait.Fail -> return TransferResult.Failed(ErrorCode.RATE_LIMITED, "host is blocked")
            BreakerWait.Defer -> return TransferResult.Defer(job.attempts, REQUEUE_MAX_DELAY_MS, contentType)
            BreakerWait.Proceed -> Unit
        }
        gate.acquire()
        held = true
        try {
            bulk
                .prepareGet(origin.url) {
                    header(HttpHeaders.AcceptEncoding, "identity")
                    if (bp.resumable) header(HttpHeaders.Range, "bytes=${bp.partBytes}-")
                    // The persisted validator is what makes a cold resume — the normal path,
                    // since the reconciler re-enqueues `.part` files from previous processes —
                    // safe rather than a blind byte-offset append.
                    bp.etag?.let { header(HttpHeaders.IfRange, it) }
                    header(HttpHeaders.UserAgent, cfg.userAgent)
                    header(HttpHeaders.Referrer, cfg.apiBaseUrl.substringBefore("/api.php") + "/")
                }.execute { r ->
                    val parsed = r.toReply(clock.nowMs())
                    contentType = parsed.contentType
                    val outcome = serve(r, parsed, bp, live, partPath, ctx, throttle, startedAt, lastMark, restarts)
                    restarts = outcome.restarts
                    when (outcome) {
                        is Serve.Rejected -> {
                            rejected = outcome.fail
                            retryAfterMs = parsed.retryAfterMs
                        }
                        is Serve.Copied -> copied = outcome.outcome
                        is Serve.Refetch -> refetch = true
                    }
                }
            settled = true
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // Any transport failure is expected at this seam — connection refused, TLS failure, a
            // reset before the first byte — and it is the host that is unwell, not the disk.
            // `ensureActive` re-raises a cancellation, so this cannot swallow a preemption.
            currentCoroutineContext().ensureActive()
            hostFailure = expected.message ?: expected::class.simpleName
            log.w("dl", "request failed ${key.label}: $hostFailure")
            // The write-set is folded in per chunk, but a reset can land between a flush and its
            // fold, and then the resume offset is behind the file. One `stat` per *failed* attempt
            // is the cheap correction, and the filesystem — not a local — is the authority on what
            // is on disk, which is the same rule `PartStore.note` follows.
            val onDisk = fileSize(fs, partPath)
            if (onDisk > live.load().partBytes) live.store(live.load().wrote(onDisk))
        } finally {
            if (!settled) breaker.release()
            if (held) gate.release()
        }
        val next = ctx.copy(rangeRestarts = restarts)
        return classify(job, breaker, rejected, retryAfterMs, copied, hostFailure, contentType, refetch, next)
    }

    private sealed interface Serve {
        val restarts: Int

        data class Copied(
            val outcome: CopyOutcome,
            override val restarts: Int,
        ) : Serve

        data class Rejected(
            val fail: Resume.Fail,
            override val restarts: Int,
        ) : Serve

        data class Refetch(
            override val restarts: Int,
        ) : Serve
    }

    /** Turn one response into bytes-on-disk, a rejection, or a request to come back without a range. */
    private suspend fun serve(
        r: HttpResponse,
        reply: Reply,
        bp: Breakpoint,
        live: AtomicReference<Breakpoint>,
        part: Path,
        ctx: AttemptCtx,
        throttle: ProgressThrottle,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        restarts: Int,
    ): Serve {
        val decision = resumeDecision(bp, reply)
        if (decision is Resume.Fail) return Serve.Rejected(decision, restarts)
        // The body in hand is a range, or there is none: drop the held bytes and ask again without
        // a Range. Streaming a 206 body from zero would write the object's tail over its head,
        // which is the splice DL-3 is about.
        if (decision is Resume.Refetch) return serveRefetch(bp, live, part, ctx, restarts)
        return serveBody(r, decision, reply, bp, live, part, ctx, throttle, startedAt, lastMark, restarts)
    }

    private fun serveRefetch(
        bp: Breakpoint,
        live: AtomicReference<Breakpoint>,
        part: Path,
        ctx: AttemptCtx,
        restarts: Int,
    ): Serve {
        val counted = countRestart(bp, restarts)
        if (counted > cfg.rangeRestartsCap) return refused("resume", counted, restarts)
        if (!truncatePart(fs, part)) return Serve.Copied(CopyOutcome(0L, StreamErr.Storage), restarts)
        live.store(Breakpoint.fresh(ctx.quality, live.load().originResolvedAtMs).wrote(0L))
        return Serve.Refetch(counted)
    }

    private suspend fun serveBody(
        r: HttpResponse,
        decision: Resume,
        reply: Reply,
        bp: Breakpoint,
        live: AtomicReference<Breakpoint>,
        part: Path,
        ctx: AttemptCtx,
        throttle: ProgressThrottle,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        restarts: Int,
    ): Serve {
        val append = decision is Resume.Append
        // A 200 answered to a `Range` is a usable whole object, so the held bytes go and this body
        // is streamed from zero: no wasted round trip.
        val counted = if (append) restarts else countRestart(bp, restarts)
        if (counted > cfg.rangeRestartsCap) return refused("Range", counted, restarts)
        val from = if (append) decision.at else 0L
        if (from == 0L && !truncatePart(fs, part)) return Serve.Copied(CopyOutcome(0L, StreamErr.Storage), restarts)
        val total = if (append) decision.totalBytes else reply.total ?: reply.length
        live.store(bp.wrote(from, total, if (append) decision.etag else reply.etag))
        val display = total ?: paddedEstimate(cfg, ctx.songRow, ctx.quality)
        val segment = Segment(part, from, reply.expectedEnd(from), display)
        return Serve.Copied(copyBody(r, segment, live, ctx.key, startedAt, lastMark, throttle), counted)
    }

    private fun unparseable(
        ctx: AttemptCtx,
        contentType: String?,
    ): TransferResult {
        val why = "unparseable stream url"
        return TransferResult.Failed(ErrorCode.NETWORK, why, ctx.rangeRestarts, ctx.attempts, contentType)
    }

    private fun countRestart(
        bp: Breakpoint,
        restarts: Int,
    ): Int = if (bp.resumable) restarts + 1 else restarts

    private fun refused(
        what: String,
        counted: Int,
        restarts: Int,
    ): Serve = Serve.Rejected(Resume.Fail(ErrorCode.NETWORK, "origin would not $what ($counted times)"), restarts)

    /**
     * The whole error taxonomy, in one function, so the host-health signal and the user-visible code
     * cannot drift apart.
     */
    private fun classify(
        job: DownloadJob,
        breaker: Breaker,
        rejected: Resume.Fail?,
        retryAfterMs: Long?,
        copied: CopyOutcome?,
        hostFailure: String?,
        contentType: String?,
        refetch: Boolean,
        ctx: AttemptCtx,
    ): TransferResult {
        if (refetch) return TransferResult.Retry(ctx.rangeRestarts, ctx.attempts, contentType, reResolve = false)
        if (hostFailure != null) return retryOrGiveUp(breaker, ctx, ErrorCode.NETWORK, hostFailure, contentType)
        val copy = copied ?: return classifyRejection(job, breaker, rejected, retryAfterMs, contentType, ctx)
        return when (copy.err) {
            StreamErr.Ok -> {
                breaker.onSuccess()
                TransferResult.Done(ctx.rangeRestarts, ctx.attempts, contentType)
            }
            // A premature close is a dropped connection, not a corrupt file: count it against the
            // host and resume from the bytes that did land.
            StreamErr.Truncated -> retryOrGiveUp(breaker, ctx, ErrorCode.CORRUPT_SIZE, "body ended early", contentType)
            StreamErr.Network ->
                retryOrGiveUp(breaker, ctx, ErrorCode.NETWORK, "connection lost mid-transfer", contentType)
            StreamErr.Stall -> retryOrGiveUp(breaker, ctx, ErrorCode.NETWORK_TIMEOUT, "stall watchdog", contentType)
            StreamErr.Storage ->
                TransferResult.Failed(ErrorCode.STORAGE, "write failed", ctx.rangeRestarts, ctx.attempts, contentType)
        }
    }

    private fun classifyRejection(
        job: DownloadJob,
        breaker: Breaker,
        rejected: Resume.Fail?,
        retryAfterMs: Long?,
        contentType: String?,
        ctx: AttemptCtx,
    ): TransferResult {
        val rej = rejected ?: return retryOrGiveUp(breaker, ctx, ErrorCode.NETWORK, "no response", contentType)
        return when (rej.code) {
            ErrorCode.RATE_LIMITED -> rateLimited(job, breaker, retryAfterMs, contentType, ctx)
            ErrorCode.EXPIRED ->
                if (ctx.resolveCount < cfg.resolveCapPerJob) {
                    TransferResult.Retry(ctx.rangeRestarts, ctx.attempts, contentType, reResolve = true)
                } else {
                    TransferResult.Failed(
                        ErrorCode.FORBIDDEN_REGION,
                        "origin refused the signed url",
                        ctx.rangeRestarts,
                        ctx.attempts,
                        contentType,
                    )
                }
            ErrorCode.NOT_FOUND -> {
                val missing = "no such object"
                TransferResult.Failed(ErrorCode.NOT_FOUND, missing, ctx.rangeRestarts, ctx.attempts, contentType)
            }
            else -> retryOrGiveUp(breaker, ctx, rej.code, rej.why, contentType)
        }
    }

    private fun retryOrGiveUp(
        breaker: Breaker,
        ctx: AttemptCtx,
        code: ErrorCode,
        why: String,
        contentType: String?,
    ): TransferResult {
        breaker.onFailure(breakers.nowMs())
        val next = ctx.attempts + 1
        return if (next <= cfg.dlRetries) {
            TransferResult.Retry(ctx.rangeRestarts, next, contentType, reResolve = false)
        } else {
            TransferResult.Failed(code, why, ctx.rangeRestarts, next, contentType)
        }
    }

    /**
     * 429/503. A `USER_NOW` fails fast — the user asked for it now, and playback can skip. Anything
     * else goes back on the queue with its attempt count *carried*, so a 429 with no `Retry-After`
     * can no longer spin a five-second loop forever.
     */
    private fun rateLimited(
        job: DownloadJob,
        breaker: Breaker,
        retryAfterMs: Long?,
        contentType: String?,
        ctx: AttemptCtx,
    ): TransferResult {
        breaker.onRateLimited(breakers.nowMs(), retryAfterMs)
        val next = ctx.attempts + 1
        val giveUp = job.reason == Priority.USER_NOW || next > cfg.dlRetries
        return if (giveUp) {
            TransferResult.Failed(ErrorCode.RATE_LIMITED, "rate limited", ctx.rangeRestarts, next, contentType)
        } else {
            TransferResult.Defer(next, retryAfterMs ?: DEFAULT_RETRY_AFTER_MS, contentType)
        }
    }

    /**
     * Bounded breaker wait.
     *
     * The old loop was `delay(min(remaining, 5_000)); continue` with no attempt accounting and no
     * deadline, spinning while holding the engine's only slot: a `Retry-After: 3600` pinned the whole
     * process, and every other track — including a `USER_NOW` against a healthy host — queued behind
     * it. Short waits are absorbed inline; anything longer is deferred, which frees the slot, and the
     * job's attempt budget bounds how many times that can happen.
     */
    private suspend fun breakerWait(
        job: DownloadJob,
        breaker: Breaker,
    ): BreakerWait {
        if (breaker.tryAcquire(breakers.nowMs())) return BreakerWait.Proceed
        val remaining = breaker.view().openUntilMs - breakers.nowMs()
        if (remaining <= BREAKER_INLINE_WAIT_MS) {
            delay(remaining.coerceAtLeast(1L))
            return if (breaker.tryAcquire(breakers.nowMs())) BreakerWait.Proceed else BreakerWait.Defer
        }
        if (job.reason == Priority.USER_NOW || job.attempts >= cfg.dlRetries) return BreakerWait.Fail
        defer(job.copy(attempts = job.attempts + 1), minOf(remaining, REQUEUE_MAX_DELAY_MS))
        return BreakerWait.Defer
    }

    /**
     * Structured copy + watchdog: the copy child owns the bytes, the watchdog owns the deadline.
     *
     * The write-set is folded back into [live] on every chunk, which is what makes `Range:` on a
     * retry reflect the bytes that actually reached the disk rather than a local nobody updates.
     *
     * Network and storage failures are separated by *where* they are raised: a failed open or write
     * is the disk, and a failed read is left to propagate so [run] classifies it as the host's
     * problem, with retries.
     */
    private suspend fun copyBody(
        r: HttpResponse,
        segment: Segment,
        live: AtomicReference<Breakpoint>,
        key: SongKey,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        throttle: ProgressThrottle,
    ): CopyOutcome =
        supervisorScope {
            val ch: ByteReadChannel = r.bodyAsChannel()
            val copy = async { drain(ch, segment, live, key, lastMark, throttle) }
            val watchdog = launch { watchStalls(copy, segment, key, startedAt, lastMark) }
            try {
                copy.await()
            } finally {
                watchdog.cancel()
            }
        }

    /**
     * Copy the body and report what reached the disk.
     *
     * The end offset is read back from [live] — the [Breakpoint] the copy loop folds every flushed
     * chunk into — rather than from a local. A local in *this* function is not the one `pump`
     * advances, so it stayed at `segment.startAt`: every transfer reported `written = 0` and, since
     * `pos < expectedEnd`, every *complete* body was classified `Truncated`. That made a clean
     * 200 with a correct `Content-Length` retry until the range-restart cap tripped and fail as
     * `NETWORK: origin would not Range`.
     */
    private suspend fun drain(
        ch: ByteReadChannel,
        segment: Segment,
        live: AtomicReference<Breakpoint>,
        key: SongKey,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        throttle: ProgressThrottle,
    ): CopyOutcome {
        val buf = ByteArray(COPY_BUFFER_BYTES)
        var failure: StreamErr? = null
        var pumped = PumpResult(null, null)
        val sink =
            try {
                fs.openReadWrite(segment.partPath, mustCreate = false, mustExist = false)
            } catch (e: IOException) {
                log.w("dl", "open for write failed ${key.provider}:${key.songId}: ${e.message}")
                failure = StreamErr.Storage
                null
            }
        if (sink != null) {
            pumped = pump(sink, ch, buf, segment, live, key, lastMark, throttle, failure)
        }
        val end = live.load().partBytes
        val err = failure ?: pumped.failure ?: verdict(pumped.signal, end, segment.expectedEnd)
        return CopyOutcome(end - segment.startAt, err)
    }

    /**
     * The copy loop. A [StallSignal] is returned rather than thrown so the caller can tell a
     * watchdog trip (retryable, NETWORK_TIMEOUT) from a real I/O failure; an external cancellation
     * is not caught here at all, so it propagates untouched.
     *
     * The flush is inside the `try` on purpose: a `flush` that fails after every byte was accepted
     * is a storage failure, and swallowing it reported the transfer as complete — the truncation-
     * equals-success ambiguity the whole [Breakpoint] contract exists to remove.
     */
    private suspend fun pump(
        sink: FileHandle,
        ch: ByteReadChannel,
        buf: ByteArray,
        segment: Segment,
        live: AtomicReference<Breakpoint>,
        key: SongKey,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        throttle: ProgressThrottle,
        openFailure: StreamErr?,
    ): PumpResult {
        val written = Written(segment.startAt)
        var failure = openFailure
        var signal: StallSignal? = null
        try {
            failure = copyChunks(sink, ch, buf, segment, live, key, lastMark, throttle, written, failure)
            if (failure == null) {
                sink.flush()
            }
        } catch (stall: StallSignal) {
            signal = stall
        } finally {
            closeQuietly(sink)
        }
        return PumpResult(failure, signal)
    }

    /**
     * One loop, one early exit per condition and no `break`/`continue` pair: `null` from
     * [readChunk] means the channel woke with nothing, which is not an end of body.
     */
    private suspend fun copyChunks(
        sink: FileHandle,
        ch: ByteReadChannel,
        buf: ByteArray,
        segment: Segment,
        live: AtomicReference<Breakpoint>,
        key: SongKey,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        throttle: ProgressThrottle,
        written: Written,
        openFailure: StreamErr?,
    ): StreamErr? {
        var failure = openFailure
        while (failure == null) {
            val n = readChunk(ch, buf)
            if (n == null) continue
            if (n < 0) return failure
            if (!write(sink, written.at, buf, n, key)) {
                failure = StreamErr.Storage
            } else {
                written.at += n
                live.store(live.load().wrote(written.at))
                lastMark.store(TimeSource.Monotonic.markNow())
                throttle.take(written.at, segment.displayTotal)?.let { pct -> publishProgress(key, pct) }
            }
        }
        return failure
    }

    /** The mutable copy cursor, in one place so the loop above has no second `pos`. */
    private class Written(
        var at: Long,
    )

    private fun closeQuietly(sink: FileHandle) {
        // A close failure after a clean flush is not actionable and must not mask the verdict.
        runCatching { sink.close() }
    }

    /** What the copy loop left behind: an I/O failure, a watchdog trip, or neither. */
    private class PumpResult(
        val failure: StreamErr?,
        val signal: StallSignal?,
    )

    private fun write(
        sink: FileHandle,
        pos: Long,
        buf: ByteArray,
        n: Int,
        key: SongKey,
    ): Boolean =
        try {
            sink.write(pos, buf, 0, n)
            true
        } catch (expected: Exception) {
            log.w("dl", "write failed ${key.label} at $pos: ${expected.message}")
            false
        }

    private fun verdict(
        stalled: StallSignal?,
        pos: Long,
        expectedEnd: Long?,
    ): StreamErr =
        when {
            stalled != null -> StreamErr.Stall
            expectedEnd != null && pos < expectedEnd -> StreamErr.Truncated
            else -> StreamErr.Ok
        }

    private suspend fun watchStalls(
        copy: Deferred<CopyOutcome>,
        segment: Segment,
        key: SongKey,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
    ) {
        while (true) {
            delay(cfg.stallWatchdogTickMs)
            if (copy.isCompleted) return
            val sinceChunk = lastMark.load().elapsedNow().inWholeMilliseconds
            val totalElapsed = startedAt.elapsedNow().inWholeMilliseconds
            val cap = stallWallCapMs(cfg.stallWallFloorMs, segment.displayTotal ?: segment.expectedEnd ?: 0L)
            if (stallTripped(sinceChunk, totalElapsed, cap, cfg.stallTimeoutMs)) {
                log.w("dl", "stall ${key.label} idle=${sinceChunk}ms wall=${totalElapsed}ms cap=$cap")
                copy.cancel(StallSignal())
                return
            }
        }
    }

    /** Bytes read, -1 at EOF, or null when the channel woke with nothing. */
    private suspend fun readChunk(
        ch: ByteReadChannel,
        buf: ByteArray,
    ): Int? {
        if (!ch.awaitContent()) return -1
        val n = ch.readAvailable(buf, 0, buf.size)
        return if (n == 0) null else n
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val BREAKER_INLINE_WAIT_MS = 5_000L
        const val REQUEUE_MAX_DELAY_MS = 60_000L
        const val DEFAULT_RETRY_AFTER_MS = 5_000L
    }
}

/** Watchdog-to-copy stall signal: distinct from external cancellation by type. */
internal class StallSignal : CancellationException("stall")
