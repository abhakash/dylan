@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.db.Songs
import dylan.model.DylanFailure
import dylan.model.ErrorCode
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.util.AppDispatchers
import dylan.util.Clock
import dylan.util.DISK_UNKNOWN
import dylan.util.Lane
import dylan.util.NetClass
import dylan.util.freeDiskBytes
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.max
import kotlin.time.TimeSource

/** Size verdicts, split so the two-sided band is only consulted when the origin declared nothing. */
internal enum class SizeVerdict { Exact, Short, Oversize, BandOk, BandLow, BandHigh }

/**
 * The size check, as a pure function.
 *
 * `duration × bitrate` used to be a hard *lower* bound at 90%. `Quality.BITRATE_128.bps` is already
 * 27% padded, so that floor deterministically rejected every legitimate 128 kbps m4a whose response
 * was chunked — the exact tier the app picks by default on metered (DL-6). The origin's own
 * `Content-Length` / `Content-Range` total, now persisted in [Breakpoint], is authoritative when it
 * exists; the estimate survives only as a two-sided sanity band, and only when the origin declared
 * nothing at all.
 */
internal fun sizeVerdict(
    finalBytes: Long,
    totalBytes: Long?,
    estimateLo: Long,
    estimateHi: Long,
): SizeVerdict =
    if (totalBytes != null) {
        when {
            finalBytes == totalBytes -> SizeVerdict.Exact
            finalBytes < totalBytes -> SizeVerdict.Short
            else -> SizeVerdict.Oversize
        }
    } else {
        when {
            finalBytes < estimateLo -> SizeVerdict.BandLow
            finalBytes > estimateHi -> SizeVerdict.BandHigh
            else -> SizeVerdict.BandOk
        }
    }

/**
 * Time-throttled, no-op-skipping progress publisher. One instance per attempt, so the throttle
 * table dies with the attempt instead of holding one entry per song for the life of the process.
 *
 * The old predicate was `if (n - last < 250 && loaded < denom) return`: once a resumed transfer
 * passed its denominator the byte condition stopped short-circuiting, so on a fast link every
 * 64 KB chunk emitted — and each emission is a full Map copy that recomposes five Compose screens.
 * Time is the throttle now; the percentage is only a filter that drops no-op changes.
 */
internal class ProgressThrottle(
    private val clock: Clock,
    private val intervalMs: Long,
) {
    private var lastEmitMs = 0L
    private var lastPct = NO_EMIT

    /** Percentage for [loaded], or null when this tick must not produce a StateFlow emission. */
    fun take(
        loaded: Long,
        denom: Long?,
    ): Int? {
        val pct =
            if (denom != null && denom > 0L) {
                ((loaded * PERCENT_SCALE) / denom).coerceIn(0L, PERCENT_CEILING).toInt()
            } else {
                0
            }
        if (pct == lastPct) return null
        val now = clock.nowMs()
        if (lastPct != NO_EMIT && now - lastEmitMs < intervalMs) return null
        lastEmitMs = now
        lastPct = pct
        return pct
    }

    private companion object {
        const val NO_EMIT = -1
        const val PERCENT_SCALE = 100L
        const val PERCENT_CEILING = 99L
    }
}

/** The cached row, projected to the six fields COMMIT actually needs. */
internal data class PrevRow(
    val bitrate: Int,
    val ext: String,
    val bytes: Long,
    val lastUsedMs: Long?,
    val playCount: Long,
    val pinned: Boolean,
    val pinnedAtMs: Long?,
) {
    /** The refetch short-circuit, named so it is one condition rather than four. */
    fun isSameArtifact(
        bits: Int,
        otherExt: String,
        otherBytes: Long,
    ): Boolean = bitrate == bits && ext == otherExt && bytes == otherBytes

    fun isOtherRendition(
        bits: Int,
        otherExt: String,
    ): Boolean = bitrate != bits || ext != otherExt
}

/**
 * The download engine: a priority queue, a small worker pool, and one job body that is a state
 * machine over [Step].
 *
 * Concurrency. `loop()` used to run exactly one job at a time (`j.join()`) while
 * `cfg.maxConcurrentParts = 3` and 45 lines of part-cap machinery carefully maintained up to three
 * parts — the config promised concurrency the engine did not have. It now runs [WORKER_COUNT] jobs
 * at once, which is the audit's minimum for a track boundary: with one slot a `USER_NOW` cannot
 * preempt an equal-priority `USER_NOW`, so a three-second skip pays for a whole transfer. The
 * bounded resource is the *transfer* (see [TransferGate]), not the job, so a job parked in VERIFY
 * or COMMIT holds no permit.
 *
 * The bytes-on-disk ↔ bytes-on-wire relationship is [Breakpoint], a value. See that file for the
 * six defects that were all statements about it.
 */
class DownloadEngine(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val provider: MusicProvider,
    private val bulk: HttpClient,
    private val breakers: Breakers,
    private val cacheManager: CacheManager,
    private val netClass: () -> NetClass,
    private val qualityPref: suspend () -> Quality,
    private val otherEndpointsHealthy: () -> Boolean = { true },
    private val log: dylan.diag.LogBuffer,
) {
    // The scope is rebuilt on every start() so a stop()/start() cycle relaunches into a live
    // scope. Cancelling a constructor-owned SupervisorJob made start() a no-op forever after one
    // stop, which is what AppContainer.stop() used to do (audit DI-1).
    private val engineFailure =
        kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
            log.c("dl", "engine job crashed: ${t.message ?: t::class.simpleName}")
            dylan.util.logErr("dylan-engine: ${t.message ?: t::class.simpleName}")
        }
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + disp.io + engineFailure)

    private val clock: Clock = cfg.clock
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val queue = JobQueue(QUEUE_CAPACITY)
    private val gate = TransferGate(minOf(WORKER_COUNT, cfg.maxConcurrentParts.coerceAtLeast(1)))
    private val started = AtomicBoolean(false)
    private val parts = PartStore(db, fs, paths, cfg, disp, log)
    private val library = LibraryCommitter(db, disp, log)
    private val transfer =
        Transfer(
            fs = fs,
            cfg = cfg,
            bulk = bulk,
            breakers = breakers,
            gate = gate,
            clock = clock,
            log = log,
            publishProgress = { key, pct -> progress.update { it + (key to pct) } },
            defer = { job, delayMs -> requeueLater(job, delayMs) },
        )

    /** A *set*: preemption is no longer single-slot, so one global key is no longer the truth. */
    private val preempted = AtomicReference<Set<JobId>>(emptySet())

    /** Coroutine handles of the jobs in flight, so a preemption can cancel the right one. */
    private val handles = AtomicReference<Map<SongKey, Job>>(emptyMap())

    val states = MutableStateFlow<Map<SongKey, JobState>>(emptyMap())
    val progress = MutableStateFlow<Map<SongKey, Int>>(emptyMap())

    /** Terminal state per *attempt*. This is what W3-D awaits; see [awaitAttempt]. */
    val attemptStates = MutableStateFlow<Map<JobId, JobState>>(emptyMap())

    fun stop() {
        started.store(false)
        scope.cancel()
        preempted.store(emptySet())
        handles.store(emptyMap())
    }

    fun start() {
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + disp.io + engineFailure)
        }
        if (!started.compareAndSet(false, true)) return
        parts.loadFromDisk()
        repeat(WORKER_COUNT) { index -> scope.launch { worker(index) } }
    }

    /**
     * Admit a job. The queue owns the decision and hands back what the caller must do, so nothing
     * here reaches into the in-flight worker — which is what removed the unlocked-triple race
     * rather than papering over it with a lock.
     */
    fun enqueue(job: DownloadJob): EnqueueResult {
        log.i("dl", "enqueue ${job.label} prio=${job.reason} bits=${job.bitrate}")
        val result = queue.offer(job)
        when (result) {
            is EnqueueResult.Queued -> {
                states.update { it - job.key }
                scope.launch { library.writeIntent(job) }
                poke()
            }
            is EnqueueResult.Preempted -> {
                states.update { it - result.victim.key }
                scope.launch { library.writeIntent(job) }
                log.i("dl", "preempt ${result.victim.label} for ${job.label}")
                markPreempted(preempted, result.victim.id)
                cancelWorker(result.victim.key)
                poke()
            }
            EnqueueResult.SupersededByHigherPriority ->
                log.d("dl", "enqueue superseded ${job.label} prio=${job.reason}")
            EnqueueResult.Dropped ->
                log.w("dl", "enqueue dropped (queue full) ${job.label} prio=${job.reason}")
        }
        // The part sweep is fire-and-forget: it is gated and incremental, so an enqueue never blocks
        // the caller's lane on a directory walk.
        scope.launch { enforcePartCap() }
        return result
    }

    /** Cancel every attempt for [key], running or queued. */
    fun cancel(
        key: SongKey,
        keepPart: Boolean,
    ) {
        val target = queue.remove(key) ?: queue.ownerOf(key)
        target?.let { markPreempted(preempted, it.id) }
        target?.let { cancelWorker(it.key) }
        states.update { it + (key to JobState.Cancelled) }
        progress.update { it - key }
        if (!keepPart) scope.launch { parts.deleteParts(key) }
    }

    /**
     * Cancel exactly the attempt [id] names. This is what a generation change in playback wants: it
     * knows the id it was handed and must be able to abandon that attempt without touching a newer
     * one for the same song.
     */
    fun cancelAttempt(
        id: JobId,
        keepPart: Boolean,
    ) {
        val job = queue.byId(id) ?: return
        queue.remove(job.key)
        markPreempted(preempted, job.id)
        cancelWorker(job.key)
        if (!keepPart) scope.launch { parts.deleteParts(job.key) }
    }

    /** The attempt a caller should await for [key] right now: running, or queued behind one. */
    fun attemptOf(key: SongKey): JobId? = queue.activeAttempt(key)

    /**
     * Terminal state of one specific attempt, or null if it had not settled inside [timeoutMs].
     *
     * A displaced attempt settles as [JobState.Cancelled] immediately, so this cannot hang the way
     * a key-keyed wait does when a job is preempted and re-run — which is PB-2's "a failed track
     * can never be retried in the same session" from the other side.
     */
    suspend fun awaitAttempt(
        id: JobId,
        timeoutMs: Long,
    ): JobState? = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { attemptStates.first { id in it } }?.get(id)

    suspend fun dropIntent(key: SongKey) = library.dropIntent(key)

    /**
     * Keep the number of `.part` files on disk within `cfg.maxConcurrentParts`. See [PartStore] for
     * why this is a gated sweep over a maintained inventory rather than a walk per enqueue, and for
     * the off-by-one it fixes. Pass `force` for an explicit sweep.
     */
    suspend fun enforcePartCap(force: Boolean = false) {
        parts.enforce(cfg.maxConcurrentParts.coerceAtLeast(1), force, queue.inFlightKeys()) { dropIntent(it) }
    }

    // ---- workers ---------------------------------------------------------------------------

    private suspend fun worker(index: Int) {
        disp.assert(Lane.IO)
        while (true) {
            val job = queue.claimNext()
            if (job == null) {
                wake.receive()
                continue
            }
            val handle = currentHandle()
            handles.mutate { it + (job.key to handle) }
            log.d("dl", "w$index start ${job.label} attempt=${job.attempts}")
            try {
                runJob(job)
            } finally {
                handles.mutate { it - job.key }
                queue.release(job.key)
                poke()
            }
        }
    }

    private suspend fun currentHandle(): Job = kotlin.coroutines.coroutineContext[Job] ?: error("worker without a job")

    private fun poke() {
        // CONFLATED wake cannot drop unless closed; log loudly if it ever does.
        if (wake.trySend(Unit).isFailure) log.w("dl", "wake channel closed, workers may stall")
    }

        private fun cancelWorker(key: SongKey) {
        handles.load()[key]?.cancel(PreemptSignal())
    }

    private fun requeueLater(
        job: DownloadJob,
        delayMs: Long,
    ) {
        val wait = delayMs.coerceIn(MIN_REQUEUE_DELAY_MS, REQUEUE_MAX_DELAY_MS)
        scope.launch {
            delay(wait)
            queue.readmit(job)
            poke()
        }
    }

    // ---- the job ---------------------------------------------------------------------------

    private suspend fun runJob(job: DownloadJob) {
        disp.assert(Lane.IO)
        val key = job.key
        val t0 = clock.nowMs()
        log.d("dl", "exec ${job.label} attempt=${job.attempts}")
        cacheManager.inFlightJobKeys.update { it + key }
        // The source of an in-flight upgrade is the user's only copy of a track they already had
        // offline. `upgradeSourceKeys` had six reads and no writes, so it was permanently empty and
        // an interrupted 128→320 left them with nothing at all.
        if (job.reason == Priority.QUALITY_UPGRADE) cacheManager.upgradeSourceKeys.update { it + key }
        var attempts = job.attempts
        var resolveCount = 0
        var rangeRestarts = 0
        var songRow: Songs? = null
        var quality = Quality.of(job.bitrate)
        var ext = DEFAULT_EXT
        var signed: SignedStream? = null
        var contentType: String? = null
        var step = Step.HYDRATE
        val live = AtomicReference(Breakpoint.fresh(quality, clock.nowMs()))

        try {
            while (true) {
                val bpNow = live.load()
                log.d("dl", "step=${step.name} ${job.label} attempts=$attempts partB=${bpNow.partBytes}")
                when (step) {
                    Step.HYDRATE -> {
                        publish(key, JobState.Queued)
                        val row =
                            withContext(disp.dbLane) {
                                db.dylanQueries.selectSong(key.provider, key.songId).executeAsOneOrNull()
                            }
                        songRow = row
                        if (row == null || row.resolve_ref.isNullOrBlank()) {
                            return fail(key, job, DylanFailure(ErrorCode.NO_SOURCE, key), job.id)
                        }
                        step = Step.QUALITY
                    }

                    Step.QUALITY -> {
                        quality = chooseQuality(job, songRow, cfg, netClass, qualityPref)
                        step = Step.DEDUPE
                    }

                    Step.DEDUPE -> {
                        // A cached entry at or above the wanted bitrate IS the deliverable:
                        // re-fetching burns bandwidth, and on metered it spends cellular.
                        val entry =
                            withContext(disp.dbLane) {
                                db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
                            }
                        val metered = netClass() == NetClass.METERED
                        val sufficient = entry != null && entry.bitrate >= quality.bits.toLong()
                        if (entry != null && (sufficient || metered)) {
                            log.i("dl", "dedupe-hit ${job.label} cached=${entry.bitrate} wanted=${quality.bits}")
                            cacheManager.touch(key, clock.nowMs())
                            return finish(key, JobState.Done(entry.bytes, entry.bitrate.toInt()), job.id)
                        }
                        step = Step.SIZE
                    }

                    Step.SIZE -> {
                        val part = parts.partOf(key, quality.bits)
                        val onDisk = fileSize(fs, part)
                        live.store(parts.note(key, quality, onDisk, job.reason))
                        val need = max(0L, (live.load().totalBytes ?: paddedEstimate(cfg, songRow, quality)) - onDisk)
                        cacheManager.enforceBudget(netNewBytes = need)
                        val free = freeDiskBytes(paths.audioDir.toString())
                        if (free != DISK_UNKNOWN && free < max(cfg.diskFloorBytes, 2 * need)) {
                            return fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id)
                        }
                        step = Step.RESOLVE
                    }

                    Step.RESOLVE -> {
                        publish(key, JobState.Resolving)
                        resolveCount++
                        if (resolveCount > cfg.resolveCapPerJob) {
                            return fail(key, job, DylanFailure(ErrorCode.RESOLVE_LIMIT, key), job.id)
                        }
                        val resolveRef = songRow?.resolve_ref
                        if (resolveRef.isNullOrBlank()) {
                            return fail(key, job, DylanFailure(ErrorCode.NO_SOURCE, key), job.id)
                        }
                        val origin = provider.resolveStream(resolveRef, quality)
                        if (origin == null) {
                            log.w("dl", "resolve failed $resolveCount/${cfg.resolveCapPerJob} ${job.label}")
                            val outOfResolves = resolveCount >= cfg.resolveCapPerJob
                            if (outOfResolves) return fail(key, job, DylanFailure(ErrorCode.NETWORK, key), job.id)
                            delay(cfg.dlBackoffBaseMs * resolveCount)
                        } else {
                            signed = origin
                            step = Step.REQUEST
                        }
                    }

                    Step.REQUEST -> {
                        val origin = signed ?: return fail(key, job, DylanFailure(ErrorCode.NO_SOURCE, key), job.id)
                        val ctx = AttemptCtx(key, quality, songRow, resolveCount, rangeRestarts, attempts)
                        val outcome = transfer.run(job, origin, parts.partOf(key, quality.bits), live, ctx, contentType)
                        contentType = outcome.contentType ?: contentType
                        rangeRestarts = outcome.rangeRestarts
                        attempts = outcome.attempts
                        when (outcome) {
                            is TransferResult.Done -> step = Step.VERIFY
                            is TransferResult.Retry -> {
                                // Linear, deterministic, bounded: the breaker owns the backoff shape
                                // for a sick host, this only spaces out an immediate retry.
                                val spent = outcome.attempts - attempts
                                if (spent > 0) delay(cfg.dlBackoffBaseMs * spent)
                                step = if (outcome.reResolve) Step.RESOLVE else Step.REQUEST
                            }
                            is TransferResult.Defer -> {
                                requeueLater(job.copy(attempts = attempts), outcome.delayMs)
                                return
                            }
                            is TransferResult.Failed ->
                                return fail(key, job, DylanFailure(downgrade(outcome.code), key, outcome.why), job.id)
                        }
                    }

                    Step.VERIFY -> {
                        publish(key, JobState.Verifying)
                        val part = parts.partOf(key, quality.bits)
                        val bp = live.load()
                        val finalSize = fileSize(fs, part)
                        val (lo, hi) = sizeBand(estimateBytes(songRow, quality))
                        val verdict = sizeVerdict(finalSize, bp.totalBytes, lo, hi)
                        if (verdict != SizeVerdict.Exact && verdict != SizeVerdict.BandOk) {
                            step = onSizeMismatch(verdict, part, live, key, job, attempts)
                            attempts = attempts
                            if (step == Step.VERIFY) {
                                return fail(key, job, DylanFailure(ErrorCode.CORRUPT_SIZE, key), job.id)
                            }
                            continue
                        }
                        // Body magic decides the container, before any header: a CDN error page served
                        // as `200 audio/mpeg` used to take the mp3 path, skip the ftyp gate and be
                        // committed as a playable file (DL-7).
                        val container = sniffContainer(fs, part)
                        if (container == null) {
                            deleteQuietly(fs, part)
                            parts.forget(key)
                            val claimed = audioClaimed(contentType, signed?.type)
                            val code = if (claimed) ErrorCode.CORRUPT_CONTAINER else ErrorCode.UNSUPPORTED
                            return fail(key, job, DylanFailure(code, key), job.id)
                        }
                        ext = container.name.lowercase()
                        step = Step.COMMIT
                    }

                    Step.COMMIT -> {
                        step = Step.HYDRATE
                        return commit(key, quality, ext, t0, job)
                    }
                }
            }
        } catch (e: CancellationException) {
            onCancelled(job, key, attempts)
            throw e
        } catch (expected: Throwable) {
            // DL-4: nothing in here may die without a terminal state. A malformed signed URL, the
            // `check(rc == 0)` inside the iOS fsRename, any SQLDelight call and `fs.list` can all
            // throw. The consequence used to be a coroutine that died silently while `states[key]`
            // kept a non-terminal Downloading, so playback's withTimeoutOrNull(readyTimeoutMs) sat
            // there before reporting NETWORK_TIMEOUT.
            val what = expected.message ?: expected::class.simpleName
            log.e("dl", "job crashed ${job.label}: $what")
            dylan.util.logErr("dylan-dl: $what")
            fail(key, job, DylanFailure(ErrorCode.NETWORK, key, what), job.id)
        } finally {
            parts.persist(key, live.load())
            cacheManager.inFlightJobKeys.update { it - key }
            cacheManager.upgradeSourceKeys.update { it - key }
        }
    }

    /** What to do about a size that does not match: retry while the budget lasts, else give up. */
    private fun onSizeMismatch(
        verdict: SizeVerdict,
        part: Path,
        live: AtomicReference<Breakpoint>,
        key: SongKey,
        job: DownloadJob,
        attempts: Int,
    ): Step {
        val oversize = verdict == SizeVerdict.Oversize || verdict == SizeVerdict.BandHigh
        log.w("dl", "size ${verdict.name} for ${key.provider}:${key.songId} attempt=${attempts + 1}/${cfg.dlRetries}")
        if (attempts + 1 > cfg.dlRetries) return Step.VERIFY
        if (oversize) {
            if (!truncatePart(fs, part)) {
                scope.launch { fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id) }
                return Step.VERIFY
            }
            live.store(live.load().wrote(0L))
        }
        return Step.REQUEST
    }

    private suspend fun onCancelled(
        job: DownloadJob,
        key: SongKey,
        attempts: Int,
    ) {
        if (!preempted.load().contains(job.id)) {
            publishTerminal(key, JobState.Cancelled, job.id)
            progress.update { it - key }
            return
        }
        clearMark(preempted, job.id)
        // Not terminal for the key: the same attempt goes back with its `.part` and its budget
        // intact, so waiters and the UI keep seeing a live entry until the fresh Done lands.
        log.i("dl", "preempted ${job.label} requeued attempt=$attempts")
        queue.readmit(job.copy(attempts = attempts))
        publish(key, JobState.Queued)
    }

    private suspend fun commit(
        key: SongKey,
        quality: Quality,
        ext: String,
        t0: Long,
        job: DownloadJob,
    ) {
        val part = parts.partOf(key, quality.bits)
        val finalPath = paths.final(key, quality.bits, ext)
        val finalSize = fileSize(fs, part)
        val now = clock.nowMs()
        val favorited = withContext(disp.dbLane) { db.dylanQueries.isFavorite(key.provider, key.songId).executeAsOne() }
        val prev = library.previousRow(key)
        if (prev?.isSameArtifact(quality.bits, ext, finalSize) == true) {
            if (!renamePart(part.toString(), finalPath.toString(), log)) {
                return fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id)
            }
            parts.forget(key)
            log.i("dl", "done(refetch) ${job.label} bytes=$finalSize ms=${now - t0}")
            return finish(key, JobState.Done(finalSize, quality.bits), job.id)
        }
        if (!renamePart(part.toString(), finalPath.toString(), log)) {
            return fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id)
        }
        parts.forget(key)
        if (!library.commit(key, quality, ext, finalSize, now, prev, favorited)) {
            deleteQuietly(fs, finalPath)
            return fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id)
        }
        if (prev != null && prev.isOtherRendition(quality.bits, ext)) {
            deleteQuietly(fs, paths.final(key, prev.bitrate, prev.ext))
        }
        cacheManager.enforceBudget(netNewBytes = 0, exemptKeys = setOf(key))
        log.i("dl", "done ${job.label} bits=${quality.bits} ext=$ext bytes=$finalSize ms=${now - t0}")
        finish(key, JobState.Done(finalSize, quality.bits), job.id)
    }

    /**
     * `selectCached` reads a view over a table the cache wave is migrating, so this projects the six
     * columns COMMIT needs instead of naming a generated row type: a download engine that fails to
     * compile when a sibling renames a table is a worse coupling than six named columns.
     */
    private suspend fun finish(
        key: SongKey,
        state: JobState,
        attempt: JobId,
    ) {
        progress.update { it - key }
        publishTerminal(key, state, attempt)
        dropIntent(key)
    }

    private suspend fun fail(
        key: SongKey,
        job: DownloadJob,
        err: DylanFailure,
        attempt: JobId,
    ) {
        log.e("dl", "failed ${job.label} code=${err.code} detail=${err.detail ?: "-"}")
        // Resumable failures keep the `.part` so the next process resumes it; permanent ones must
        // not leak bytes until the reconciler's grace window. CORRUPT_SIZE is deliberately *not* in
        // this set any more: a size mismatch is a symptom of a truncated or spliced transfer, and
        // deleting the only resumable bytes made recovery impossible (DL-2/DL-6).
        if (err.code in NON_RESUMABLE_CODES) parts.deleteParts(key)
        progress.update { it - key }
        publishTerminal(key, state = JobState.Failed(err, willRetry = false), attempt = attempt)
        dropIntent(key)
    }

    /**
     * A refused signed URL is a geo-block only if the rest of the API is healthy; when it is not,
     * the honest answer is that the URL expired.
     */
    private fun downgrade(code: ErrorCode): ErrorCode {
        if (code != ErrorCode.FORBIDDEN_REGION || otherEndpointsHealthy()) return code
        return ErrorCode.EXPIRED
    }

    private fun publish(
        key: SongKey,
        state: JobState,
    ) {
        // Re-insert on every publish so the map's iteration order is recency: that is what lets the
        // retention pass drop the *oldest* entry rather than an arbitrary one.
        states.update { bounded(it - key + (key to state), TERMINAL_RETENTION) }
    }

    private fun publishTerminal(
        key: SongKey,
        state: JobState,
        attempt: JobId,
    ) {
        publish(key, state)
        attemptStates.update { bounded(it - attempt + (attempt to state), TERMINAL_RETENTION) }
    }

    private companion object {
        /**
         * Two slots, not one. One slot means a `USER_NOW` cannot preempt an equal-priority
         * `USER_NOW`, so a three-second skip costs the remainder of a whole transfer; two means one
         * in flight and one queued, which is the minimum that makes a track boundary not stall.
         */
        const val WORKER_COUNT = 2
        const val QUEUE_CAPACITY = 256
        const val REQUEUE_MAX_DELAY_MS = 60_000L
        const val MIN_REQUEUE_DELAY_MS = 250L
        const val DEFAULT_EXT = "m4a"
        const val TERMINAL_RETENTION = 128

        /**
         * Size mismatch is a *symptom*; only the magic sniff is genuinely non-resumable. CORRUPT_SIZE
         * used to be in this set, which is how a short body deleted the very bytes a resume needed.
         */
        val NON_RESUMABLE_CODES =
            setOf(
                ErrorCode.NOT_CACHEABLE,
                ErrorCode.NO_SOURCE,
                ErrorCode.NOT_FOUND,
                ErrorCode.UNSUPPORTED,
                ErrorCode.CORRUPT_CONTAINER,
            )
    }
}

/**
 * Terminal-state retention. `states` and `attemptStates` used to grow for the life of the process
 * with entries removed only on the next enqueue, and `MutableStateFlow.update` copies the whole map,
 * so every transition cost O(songs ever downloaded) and every emission recomposed five Compose
 * screens. The retention depth is far above the number of jobs that can settle inside a caller's
 * timeout, and the awaited entry is always the most recent, so a waiter cannot miss it.
 */
internal fun <K, V> bounded(
    next: Map<K, V>,
    limit: Int,
): Map<K, V> {
    if (next.size <= limit) return next
    val keep = LinkedHashMap<K, V>(limit * 2)
    var drop = next.size - limit
    for (e in next) {
        if (drop > 0) {
            drop--
        } else {
            keep[e.key] = e.value
        }
    }
    return keep
}

private enum class Step { HYDRATE, QUALITY, DEDUPE, SIZE, RESOLVE, REQUEST, VERIFY, COMMIT }

/** A preemption is a cancellation, but a *typed* one: the victim re-queues instead of reporting. */
private class PreemptSignal : CancellationException("preempted")

// Wall cap = time-to-download at a conservative 8 KB/s (expectedBytes/8 ms), floored.
// Rate-based — a flowing-but-slow link is never killed; only a trickle below that
// average rate is. Replaces the old `expectedB/20` bytes-as-ms accident.
internal fun stallWallCapMs(
    floorMs: Long,
    expectedBytes: Long,
): Long = max(floorMs, expectedBytes / 8)

// Watchdog fires when EITHER the stream shows no fresh bytes for stallTimeoutMs
// (true stall) OR the whole transfer outlives its rate-based wall cap (trickle).
internal fun stallTripped(
    sinceChunkMs: Long,
    totalElapsedMs: Long,
    wallCapMs: Long,
    stallTimeoutMs: Long,
): Boolean = sinceChunkMs > stallTimeoutMs || totalElapsedMs > wallCapMs
