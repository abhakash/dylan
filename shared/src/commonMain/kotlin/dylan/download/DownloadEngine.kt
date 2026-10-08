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
import dylan.util.fsRename
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * preempt an equal-priority `USER_NOW`, so a three-second skip pays for a whole transfer.
 *
 * [WORKER_COUNT] — not [TransferGate] — is what "one in flight and one queued" means, and it is the
 * only bound that binds with the shipped defaults. See the [gate] comment for the other one.
 *
 * The bytes-on-disk ↔ bytes-on-wire relationship is [Breakpoint], a value. See that file for the
 * six defects that were all statements about it.
 *
 * The long parameter list is deliberate and suppressed rather than fixed. detekt's
 * `LongParameterList` (threshold 12, `ignoreDefaultParameters` on) counts the twelve *required*
 * collaborators below, and every route under the threshold is worse than the finding: a bag object
 * would hide which dependencies the engine actually reads — the one thing this list is honest about
 * — and defaulting any of the last three would let a mis-wired graph build silently with the wrong
 * logger, rename or free-disk probe. The signature is also a contract rather than an accident of
 * growth: nine call sites in the test sources construct it by name.
 */
class DownloadEngine
    @Suppress("LongParameterList")
    constructor(
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
        /**
         * Free bytes on the audio volume. [dylan.util.freeDiskBytes] is a top-level `expect fun`, so
         * without this parameter the `diskFloorBytes` branch below is unreachable from a test and
         * `cfg.diskFloorBytes` is untested config. Defaulted, so production behaviour is unchanged.
         */
        private val freeDisk: (String) -> Long = ::freeDiskBytes,
        /**
         * The `.part` → final rename. `dylan.util.fsRename` goes through `java.nio` on the JVM, which an
         * okio `FileSystem` decorator cannot intercept, so the "the rename failed" half of the
         * `STORAGE` verdict is otherwise untestable. Defaulted; behaviour unchanged without it.
         */
        private val rename: (String, String) -> Unit = ::fsRename,
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

        // The scope *is* the IO lane, so the workers' `assertInContext(Lane.IO)` holds by construction
        // rather than by remembering to write `disp.on(Lane.IO)` at each launch. It used to carry the
        // bare `disp.io`, which scheduled them on the io dispatcher but published no lane.
        private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + disp.on(Lane.IO) + engineFailure)

        private val clock: Clock = cfg.clock
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private val queue = JobQueue(QUEUE_CAPACITY)

        /**
         * Ceiling on **open response bodies**, and nothing else. [TransferGate] is taken around the
         * request-and-copy in `Transfer.run`, not around the job, so a job parked in RESOLVE, VERIFY or
         * COMMIT holds no permit and a 6 MB `USER_NOW` does not occupy the same resource for the same
         * wall-clock time as a 400 KB `PREFETCH_NEXT`.
         *
         * What it is *not* is the engine's concurrency: only [WORKER_COUNT] coroutines can reach it, so
         * with the shipped defaults (3 permits, 2 workers) it cannot block and the workers are the
         * binding bound. It becomes binding the moment the permit count drops below the worker count —
         * `DownloadEngineTest.theTransferGateBoundsConcurrentCopiesNotConcurrentJobs` pins that at
         * `maxConcurrentParts = 1`. Two earlier comments here claimed the gate reserved "one in flight
         * and one queued"; that reservation is [WORKER_COUNT] and this line is the other one.
         */
        private val gate = TransferGate(cfg.maxConcurrentParts.coerceAtLeast(1))
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
                publishProgress = { key, pct -> progress.update { bounded(it + (key to pct), PROGRESS_RETENTION) } },
                defer = { job, delayMs -> requeueLater(job, delayMs) },
            )

        /**
         * A *set*: preemption is no longer single-slot, so one global key is no longer the truth.
         *
         * An entry lives from `markPreempted` until the `onCancelled` of that attempt. The one path that
         * never reaches `onCancelled` is a victim that finished before its worker handle was taken, so
         * the enqueue path drops the mark itself in that case — the set is bounded by the number of
         * live preemptions, not by the number of jobs the process has ever run.
         */
        private val preempted = AtomicReference<Set<JobId>>(emptySet())

        /**
         * Coroutine handles of the **attempts** in flight, so a preemption can cancel the right one.
         *
         * "Attempt", not "worker": an entry is the child [Job] one worker launched for one claimed
         * job, never the worker's own `Job`. `Job.cancel` cancels the job it is called on *and that
         * job's children*, so a handle that was the worker's own also cancelled the `while (true)`
         * around it — see [worker].
         */
        private val handles = AtomicReference<Map<SongKey, Job>>(emptyMap())

        /** At most one part sweep in flight, however many enqueues arrive. */
        private val sweepPending = AtomicBoolean(false)

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
                scope = CoroutineScope(SupervisorJob() + disp.on(Lane.IO) + engineFailure)
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
                    // No handle means the victim finished between the queue's snapshot and here, so no
                    // cancellation was delivered and `onCancelled` — the only place the mark is cleared
                    // — will never run for that id. Left in the set it accumulated one entry per such
                    // race, for the life of the process.
                    if (!cancelWorker(result.victim.key)) clearMark(preempted, result.victim.id)
                    poke()
                }
                EnqueueResult.SupersededByHigherPriority ->
                    log.d("dl", "enqueue superseded ${job.label} prio=${job.reason}")
                EnqueueResult.Dropped ->
                    log.w("dl", "enqueue dropped (queue full) ${job.label} prio=${job.reason}")
            }
            // The part sweep is fire-and-forget, and *coalesced*: one enqueue used to launch one
            // coroutine, so a 5 000-row enqueue burst queued 5 000 suspended coroutines that all woke
            // and did the same gated work. One sweep at a time, and a burst asks for one more after it.
            if (sweepPending.compareAndSet(false, true)) {
                scope.launch { sweepParts() }
            }
            return result
        }

        private suspend fun sweepParts() {
            try {
                enforcePartCap()
            } finally {
                sweepPending.store(false)
            }
        }

        /**
         * Cancel every attempt for [key], running or queued, and report the cancellation.
         *
         * This is *not* a preemption, so it deliberately does not mark the attempt displaced: a
         * displaced attempt goes back on the queue with its `.part` and its budget, which is right when
         * a better-priority job took its slot and wrong when the user skipped the track — that
         * combination re-queued the abandoned download *and* published `Cancelled` for it, so the
         * abandoned job ran again and overwrote the cancellation. Playback's generation change cancels;
         * [EnqueueResult.Preempted] requeues.
         */
        fun cancel(
            key: SongKey,
            keepPart: Boolean,
        ) {
            // A stopped engine has no live attempt to cancel, so it has nothing to report. Publishing
            // `Cancelled` from a stopped engine is not merely noise: it is indistinguishable from a real
            // cancellation, and `stop()`/`start()` is the one window where a caller can observe it.
            if (!started.load()) return
            val target = queue.remove(key) ?: queue.ownerOf(key)
            target?.let { cancelWorker(it.key) }
            states.update { it + (key to JobState.Cancelled) }
            attemptStates.update { m -> target?.let { m + (it.id to JobState.Cancelled) } ?: m }
            progress.update { it - key }
            if (!keepPart) scope.launch { parts.deleteParts(key) }
        }

        /**
         * Cancel exactly the attempt [id] names. This is what a generation change in playback wants: it
         * knows the id it was handed and must be able to abandon that attempt without touching a newer
         * one for the same song. The attempt settles as [JobState.Cancelled] — see [cancel] for why it
         * is not a preemption.
         */
        fun cancelAttempt(
            id: JobId,
            keepPart: Boolean,
        ) {
            if (!started.load()) return
            val job = queue.byId(id) ?: return
            queue.remove(job.key)
            cancelWorker(job.key)
            attemptStates.update { it + (id to JobState.Cancelled) }
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
            disp.assertInContext(Lane.IO)
            while (true) {
                val job = queue.claimNext()
                if (job == null) {
                    wake.receive()
                    continue
                }
                // **The handle is the attempt's own child job, never this coroutine.** `Job.cancel`
                // cancels the job it is called on *and that job's children*, so a handle taken from
                // `coroutineContext[Job]` here is this worker's own job: cancelling a displaced
                // attempt also cancelled the `while (true)` around it, `runJob`'s
                // `catch (CancellationException) { …; throw }` then left through both, and the worker
                // was gone. Nothing replaces a worker and `start()` is a one-shot CAS, so
                // `WORKER_COUNT` preemptions bricked every download for the life of the process and
                // the only symptom was one "engine job crashed" line from the sibling.
                //
                // LAZY so the handle is registered before the job body can execute a single
                // instruction; a preemption that lands after this registration always finds a live
                // handle, and one that lands before it is the pre-existing "finished between the
                // queue's snapshot and here" case `enqueue` already handles.
                val attempt = scope.launch(start = CoroutineStart.LAZY) { runJob(job) }
                handles.mutate { it + (job.key to attempt) }
                try {
                    log.d("dl", "w$index start ${job.label} attempt=${job.attempts}")
                    attempt.start()
                    // `join` does not rethrow what the attempt failed with, and the attempt is a child
                    // of the engine's SupervisorJob, so neither can end this loop. The pool cannot
                    // shrink below WORKER_COUNT while the scope is live: the only thing that ends a
                    // worker is scope cancellation, i.e. stop(). The `finally` is what keeps a
                    // stop()/start() cycle re-claimable — the engine outlives its scope, so a claimed
                    // key left in `owners` would make that song permanently unclaimable in the next.
                    attempt.join()
                } finally {
                    handles.mutate { it - job.key }
                    queue.release(job.key)
                    poke()
                }
            }
        }

        private fun poke() {
            // CONFLATED wake cannot drop unless closed; log loudly if it ever does.
            if (wake.trySend(Unit).isFailure) log.w("dl", "wake channel closed, workers may stall")
        }

        /** False when no *attempt* held [key], i.e. no cancellation was delivered. */
        private fun cancelWorker(key: SongKey): Boolean {
            val handle = handles.load()[key] ?: return false
            handle.cancel(PreemptSignal())
            return true
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
            disp.assertInContext(Lane.IO)
            val key = job.key
            val t0 = clock.nowMs()
            log.d("dl", "exec ${job.label} attempt=${job.attempts}")
            cacheManager.inFlightJobKeys.update { it + key }
            // The source of an in-flight upgrade is the user's only copy of a track they already had
            // offline. `upgradeSourceKeys` had six reads and no writes, so it was permanently empty and
            // an interrupted 128→320 left them with nothing at all.
            if (job.reason == Priority.QUALITY_UPGRADE) cacheManager.upgradeSourceKeys.update { it + key }
            val m = StepMachine(job, t0)

            try {
                while (true) {
                    val bpNow = m.live.load()
                    log.d("dl", "step=${m.step.name} ${job.label} attempts=${m.attempts} partB=${bpNow.partBytes}")
                    // Null is how a state says the attempt is over: every exit from the machine except
                    // "advance" published a terminal state ([fail], [finish]) or handed the job back to
                    // the queue ([TransferResult.Defer]). The machine itself is the `while`.
                    m.step = m.advance() ?: return
                }
            } catch (e: CancellationException) {
                onCancelled(job, key, m.attempts)
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
                parts.persist(key, m.live.load())
            }
        }

        /**
         * The state machine over [Step], one function per state — [runJob]'s `when`, split out.
         *
         * It was one function because the eight arms share one set of `var`s; it is now eight because a
         * single function carrying all of them was 150 lines, cyclomatic 39, nested four deep and had
         * twelve `return`s, which is not a shape any reader or linter can reason about. Nothing about the
         * machine changed: same states, same order, same guard clauses, same terminal states, same
         * `catch`/`finally`. Each arm returns **the next state, or null when the attempt is over**, and
         * `runJob` owns the loop and the two `catch` clauses exactly as before.
         *
         * An `inner` class rather than eight private methods because [DownloadEngine] is already within
         * two functions of detekt's `TooManyFunctions` threshold (25) — adding them there would trade one
         * finding for another — and because every arm needs the engine's own collaborators (`parts`,
         * `transfer`, `db`, `fail`), which a top-level extractor could not reach at all.
         *
         * A plain mutable holder, not an immutable value threaded through eight signatures: the states
         * read each other's writes (`quality` is set by QUALITY and read by DEDUPE, SIZE, RESOLVE,
         * REQUEST, VERIFY and COMMIT), so a copy-per-state machine would be a different program to
         * reason about than the one that ships. Field order below is the original declaration order.
         */
        private inner class StepMachine(
            val job: DownloadJob,
            val t0: Long,
        ) {
            val key: SongKey = job.key
            var attempts: Int = job.attempts
            var resolveCount: Int = 0
            var rangeRestarts: Int = 0
            var songRow: Songs? = null
            var quality: Quality = Quality.of(job.bitrate)
            var ext: String = DEFAULT_EXT
            var signed: SignedStream? = null
            var contentType: String? = null
            var step: Step = Step.HYDRATE
            val live = AtomicReference(Breakpoint.fresh(quality, clock.nowMs()))

            /** The handler for [step], and the state to run next — or null when it settled the attempt. */
            suspend fun advance(): Step? =
                when (step) {
                    Step.HYDRATE -> onHydrate()
                    Step.QUALITY -> onQuality()
                    Step.DEDUPE -> onDedupe()
                    Step.SIZE -> onSize()
                    Step.RESOLVE -> onResolve()
                    Step.REQUEST -> onRequest()
                    Step.VERIFY -> onVerify()
                    Step.COMMIT -> onCommit()
                }

            private suspend fun onHydrate(): Step? {
                publish(key, JobState.Queued)
                val row =
                    withContext(disp.on(Lane.DB)) {
                        db.dylanQueries.selectSong(key.provider, key.songId).executeAsOneOrNull()
                    }
                songRow = row
                if (row == null || row.resolve_ref.isNullOrBlank()) {
                    fail(key, job, DylanFailure(ErrorCode.NO_SOURCE, key), job.id)
                    return null
                }
                return Step.QUALITY
            }

            private suspend fun onQuality(): Step {
                quality = chooseQuality(job, songRow, cfg, netClass, qualityPref)
                return Step.DEDUPE
            }

            private suspend fun onDedupe(): Step? {
                // A cached entry at or above the wanted bitrate IS the deliverable:
                // re-fetching burns bandwidth, and on metered it spends cellular.
                val entry =
                    withContext(disp.on(Lane.DB)) {
                        db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
                    }
                val metered = netClass() == NetClass.METERED
                val sufficient = entry != null && entry.bitrate >= quality.bits.toLong()
                if (entry != null && (sufficient || metered)) {
                    log.i("dl", "dedupe-hit ${job.label} cached=${entry.bitrate} wanted=${quality.bits}")
                    cacheManager.touch(key, clock.nowMs())
                    finish(key, JobState.Done(entry.bytes, entry.bitrate.toInt()), job.id)
                    return null
                }
                return Step.SIZE
            }

            private suspend fun onSize(): Step? {
                val part = parts.partOf(key, quality.bits)
                val onDisk = fileSize(fs, part)
                live.store(parts.note(key, quality, onDisk, job.reason))
                val need = max(0L, (live.load().totalBytes ?: paddedEstimate(cfg, songRow, quality)) - onDisk)
                cacheManager.enforceBudget(netNewBytes = need)
                val free = freeDisk(paths.audioDir.toString())
                if (free != DISK_UNKNOWN && free < max(cfg.diskFloorBytes, 2 * need)) {
                    fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id)
                    return null
                }
                return Step.RESOLVE
            }

            private suspend fun onResolve(): Step? {
                publish(key, JobState.Resolving)
                resolveCount++
                if (resolveCount > cfg.resolveCapPerJob) {
                    fail(key, job, DylanFailure(ErrorCode.RESOLVE_LIMIT, key), job.id)
                    return null
                }
                val resolveRef = songRow?.resolve_ref
                if (resolveRef.isNullOrBlank()) {
                    fail(key, job, DylanFailure(ErrorCode.NO_SOURCE, key), job.id)
                    return null
                }
                val origin =
                    provider.resolveStream(resolveRef, quality)
                        ?: return onResolveFailed()
                signed = origin
                return Step.REQUEST
            }

            /**
             * The origin had no stream for this ref.
             *
             * Split out of [onResolve] so that function's exits stay guards plus one tail: the
             * retry-after-backoff exit was its only non-guard return, which is what pushed it over
             * `ReturnCount`. Logic is unchanged, including the order — resolve cap is checked before
             * the backoff, and the counter is read for the delay before anything else can change it.
             */
            private suspend fun onResolveFailed(): Step? {
                log.w("dl", "resolve failed $resolveCount/${cfg.resolveCapPerJob} ${job.label}")
                val outOfResolves = resolveCount >= cfg.resolveCapPerJob
                if (outOfResolves) {
                    fail(key, job, DylanFailure(ErrorCode.NETWORK, key), job.id)
                    return null
                }
                delay(cfg.dlBackoffBaseMs * resolveCount)
                // Same state again: the loop re-enters RESOLVE, which is where the attempt count
                // this backoff is derived from lives.
                return Step.RESOLVE
            }

            private suspend fun onRequest(): Step? {
                val origin = signed
                if (origin == null) {
                    fail(key, job, DylanFailure(ErrorCode.NO_SOURCE, key), job.id)
                    return null
                }
                val ctx = AttemptCtx(key, quality, songRow, resolveCount, rangeRestarts, attempts)
                val outcome = transfer.run(job, origin, parts.partOf(key, quality.bits), live, ctx, contentType)
                contentType = outcome.contentType ?: contentType
                rangeRestarts = outcome.rangeRestarts
                // Read the counter off the outcome *before* it is overwritten: the backoff is
                // the attempt number the transfer just settled on, and the old code read it
                // as `outcome.attempts - attempts` after the assignment below, which is zero
                // by construction — so every retry re-issued instantly under a comment that
                // claimed a backoff.
                attempts = outcome.attempts
                return when (outcome) {
                    is TransferResult.Done -> Step.VERIFY
                    is TransferResult.Retry -> {
                        // Linear in the cumulative attempt count, so the n-th re-issue waits
                        // n × base; `cfg.dlRetries` is what bounds it. The breaker owns the
                        // backoff shape for a sick host, this only spaces out a re-issue.
                        if (attempts > 0) delay(cfg.dlBackoffBaseMs * attempts)
                        if (outcome.reResolve) Step.RESOLVE else Step.REQUEST
                    }
                    is TransferResult.Defer -> {
                        requeueLater(job.copy(attempts = attempts), outcome.delayMs)
                        null
                    }
                    is TransferResult.Failed -> {
                        fail(key, job, DylanFailure(downgrade(outcome.code), key, outcome.why), job.id)
                        null
                    }
                }
            }

            private suspend fun onVerify(): Step? {
                publish(key, JobState.Verifying)
                val part = parts.partOf(key, quality.bits)
                val bp = live.load()
                val finalSize = fileSize(fs, part)
                val (lo, hi) = sizeBand(estimateBytes(songRow, quality))
                val verdict = sizeVerdict(finalSize, bp.totalBytes, lo, hi)
                if (verdict != SizeVerdict.Exact && verdict != SizeVerdict.BandOk) {
                    return onSizeMismatchStep(verdict, part)
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
                    fail(key, job, DylanFailure(code, key), job.id)
                    return null
                }
                ext = container.ext
                return Step.COMMIT
            }

            /**
             * The size-mismatch exit, split out of [onVerify] for the reason [onResolveFailed] is:
             * three answers, and folding them back into the arm put it one over `ReturnCount`.
             *
             * A size mismatch spends an attempt like any other retry: the body has to be fetched
             * again. `attempts = attempts` incremented nothing, so `attempts + 1 > cfg.dlRetries`
             * never advanced and this loop was unbounded.
             */
            private suspend fun onSizeMismatchStep(
                verdict: SizeVerdict,
                part: Path,
            ): Step? {
                val retry = onSizeMismatch(verdict, part, live, key, job, attempts)
                attempts++
                // Null is [onSizeMismatch]'s own "the attempt is over and I already published it".
                // It used to answer `Step.VERIFY` here instead — the *other* meaning of that value,
                // budget spent — so a `truncatePart` it could not perform published a `STORAGE`
                // from inside the call and a `CORRUPT_SIZE` from here: one attempt, two terminal
                // states for the same attempt id, and whichever landed last decided the code the
                // user saw.
                if (retry == null) return null
                if (retry == Step.VERIFY) {
                    fail(key, job, DylanFailure(ErrorCode.CORRUPT_SIZE, key), job.id)
                    return null
                }
                return retry
            }

            /**
             * Terminal. [commit] publishes `Done` or `Failed` itself, so there is no next state — which
             * is also why the arm used to assign `Step.HYDRATE` immediately before returning: nothing
             * could read it again.
             */
            private suspend fun onCommit(): Step? {
                commit(key, quality, ext, t0, job)
                return null
            }
        }

        /**
         * What to do about a size that does not match: retry while the budget lasts, else give up.
         *
         * Returns the next state, [Step.VERIFY] as the *budget spent* sentinel
         * [onSizeMismatchStep] turns into the single `CORRUPT_SIZE`, or **null when this function has
         * already published a terminal state** — the same "settled" convention every arm of
         * [advance] follows. The two were previously one value, which is the whole bug: a
         * `truncatePart` that could not be done published a `STORAGE` here and then returned
         * `Step.VERIFY`, so the caller published a second terminal state of its own.
         *
         * `suspend` for that reason. It was not, because [fail] is, and the answer was to launch a
         * coroutine on the engine scope and call [fail] inside it: a *sibling* of the attempt, which nothing
         * joins and no cancellation reaches. Two `fail` calls then raced each other and the
         * worker's own `finally` — two `dropIntent`, two `publishTerminal`, and whichever landed
         * last decided the code the user saw.
         */
        private suspend fun onSizeMismatch(
            verdict: SizeVerdict,
            part: Path,
            live: AtomicReference<Breakpoint>,
            key: SongKey,
            job: DownloadJob,
            attempts: Int,
        ): Step? {
            val oversize = verdict == SizeVerdict.Oversize || verdict == SizeVerdict.BandHigh
            log.w(
                "dl",
                "size ${verdict.name} for ${key.provider}:${key.songId} " +
                    "attempt=${attempts + 1}/${cfg.dlRetries}",
            )
            if (attempts + 1 > cfg.dlRetries) return Step.VERIFY
            if (oversize) {
                if (!truncatePart(fs, part)) {
                    // Awaited, and terminal here. Re-requesting would splice a new body over a
                    // file that could not be emptied — the reason `truncatePart` returns a verdict
                    // at all (DL-9) — and `STORAGE` is the code this branch always meant to
                    // publish: it is resumable, so the part survives and the reconciler re-enqueues
                    // it, which is exactly what a full or failing volume wants.
                    fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id)
                    return null
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
            // Terminal FOR THE ATTEMPT, not for the key — the two are different facts and only the
            // attempt one was being published. This branch published neither, so a waiter holding the
            // displaced attempt's id (which is what `attemptOf` handed it a moment earlier) sat in
            // `withTimeoutOrNull` until its deadline and was then told "nothing happened", which is
            // indistinguishable from a slow download: playback's generation change had nothing to act
            // on. The key deliberately stays live below — the `.part` and the budget go back.
            attemptStates.update { bounded(it - job.id + (job.id to JobState.Cancelled), TERMINAL_RETENTION) }
            // A strictly better request may already own the key (the same-key preemption: the USER_NOW
            // that displaced this attempt is parked in the queue), and then this one is retired instead
            // — reporting a requeue that did not happen is how the log and the UI end up describing a
            // job nobody is running.
            if (!queue.readmit(job.copy(attempts = attempts))) {
                log.i("dl", "preempted ${job.label} retired: a better request owns ${key.provider}:${key.songId}")
                return
            }
            log.i("dl", "preempted ${job.label} requeued attempt=$attempts")
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
            val favorited =
                withContext(disp.on(Lane.DB)) { db.dylanQueries.isFavorite(key.provider, key.songId).executeAsOne() }
            val prev = library.previousRow(key)
            if (prev?.isSameArtifact(quality.bits, ext, finalSize) == true) {
                if (!renamePart(part.toString(), finalPath.toString(), log, rename)) {
                    return fail(key, job, DylanFailure(ErrorCode.STORAGE, key), job.id)
                }
                parts.forget(key)
                log.i("dl", "done(refetch) ${job.label} bytes=$finalSize ms=${now - t0}")
                return finish(key, JobState.Done(finalSize, quality.bits), job.id)
            }
            if (!renamePart(part.toString(), finalPath.toString(), log, rename)) {
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
            // Drop the reconciler's record *before* the terminal state is published. A `Done` that still
            // has an intent row is a download the reconciler will re-enqueue on the next boot, and a
            // waiter woken by the state saw exactly that for as long as the write took.
            dropIntent(key)
            publishTerminal(key, state, attempt)
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
            dropIntent(key)
            publishTerminal(key, state = JobState.Failed(err, willRetry = false), attempt = attempt)
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
            // Release the guards *before* the state is published. A waiter woken by the terminal state
            // must not still see this attempt as in flight: the `finally` that used to do it ran after
            // the publish, so `upgradeSourceKeys` held the source file of a completed upgrade for an
            // unbounded moment and a caller could not trust the guard it had just been told was over.
            cacheManager.inFlightJobKeys.update { it - key }
            cacheManager.upgradeSourceKeys.update { it - key }
            // And release the key. The worker released it in its own `finally`, which runs *after* this
            // publish, so an immediate retry of a just-failed key found the key still owned and was
            // silently answered `SupersededByHigherPriority` — the "a failed track can never be retried"
            // class, one microtask wide.
            queue.release(key)
            publish(key, state)
            attemptStates.update { bounded(it - attempt + (attempt to state), TERMINAL_RETENTION) }
        }

        private companion object {
            /**
             * Two slots, not one. One slot means a `USER_NOW` cannot preempt an equal-priority
             * `USER_NOW`, so a three-second skip costs the remainder of a whole transfer; two means one
             * in flight and one queued, which is the minimum that makes a track boundary not stall.
             *
             * A hard floor, not a starting size: a preemption cancels an *attempt*, and [worker] is a
             * `while (true)` around the attempt rather than the attempt itself, so no cancellation a
             * caller can trigger retires a worker.
             */
            const val WORKER_COUNT = 2
            const val QUEUE_CAPACITY = 256
            const val REQUEUE_MAX_DELAY_MS = 60_000L
            const val MIN_REQUEUE_DELAY_MS = 250L
            const val DEFAULT_EXT = "m4a"
            const val TERMINAL_RETENTION = 128

            /**
             * The progress map is keyed by song and its entries are removed on a terminal state, so in
             * steady state it holds one entry per *active* download. The retention is the backstop for
             * the case that removal misses — a preemption, a `stop()` mid-transfer — because
             * `MutableStateFlow.update` copies the whole map and every emission recomposes the UI, so
             * an unbounded map makes every chunk more expensive than the last. Same bound and same
             * rationale as [TERMINAL_RETENTION] for the two state maps; they are one policy, not two.
             */
            const val PROGRESS_RETENTION = 128

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
): Long = max(floorMs, expectedBytes / TRICKLE_BYTES_PER_MS)

// Watchdog fires when EITHER the stream shows no fresh bytes for stallTimeoutMs
// (true stall) OR the whole transfer outlives its rate-based wall cap (trickle).
internal fun stallTripped(
    sinceChunkMs: Long,
    totalElapsedMs: Long,
    wallCapMs: Long,
    stallTimeoutMs: Long,
): Boolean = sinceChunkMs > stallTimeoutMs || totalElapsedMs > wallCapMs

/**
 * Bytes per millisecond an attempt is allowed to average: 8 KB/s, far below any real link, so
 * [stallWallCapMs] asks "could this track have finished at *any* plausible rate?" rather than
 * "is this link fast?".
 *
 * In bytes-per-millisecond because that is the unit the cap divides in — the KDoc above quotes the
 * friendlier 8 KB/s. Do not round it to a power of two: the cap is stated in terms of this constant
 * and a rounder value would claim a rate the constant no longer describes.
 */
private const val TRICKLE_BYTES_PER_MS = 8L
