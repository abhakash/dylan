@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import dylan.model.SongKey
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.atomics.AtomicReference

/** What [JobQueue.offer] decided, and the only thing an enqueue caller needs to know. */
sealed interface EnqueueResult {
    data class Queued(
        val id: JobId,
    ) : EnqueueResult

    /**
     * An equal-or-better attempt for the same key already exists — queued or already running — so
     * this request is dropped. It must NOT touch the key's published state: the live attempt owns
     * that, and overwriting a `Done` with `Queued` is how a waiter watches a finished download
     * restart forever.
     */
    data object SupersededByHigherPriority : EnqueueResult

    /** A lower-priority attempt was displaced; the caller cancels the victim's worker. */
    data class Preempted(
        val victim: DownloadJob,
    ) : EnqueueResult

    /** The queue is at capacity and nothing running is worth displacing for this job. */
    data object Dropped : EnqueueResult
}

/**
 * The download queue as data.
 *
 * Five sites used to re-derive priority independently and two of them disagreed: `enqueue`
 * compared `reason.ordinal` and then hand-sorted, while `requeueLater` removed a same-key job with
 * no priority check at all — so a `USER_NOW` enqueued between a 429 and its retry timer was
 * silently downgraded to a background retry. Here there is one ordering ([compare]: rank, then
 * attempt id) and one owner of it, so the disagreement is unrepresentable.
 *
 * The whole queue is one immutable snapshot behind a CAS, because three of its callers are
 * non-suspending (`enqueue` is called from the state lane). That is also what removes the
 * unlocked-triple race: the preemption decision is made on a snapshot the queue owns, so
 * `enqueue` never reaches into the engine's in-flight job.
 *
 * Two invariants make the pool correct at any width: `pending` holds at most one job per key, and
 * [claimNext] never hands out a key a worker already owns.
 */
class JobQueue(
    private val capacity: Int,
) {
    private data class Snapshot(
        val pending: List<DownloadJob> = emptyList(),
        val owners: Map<SongKey, DownloadJob> = emptyMap(),
        val nextSeq: Long = 1L,
    )

    private val snap = AtomicReference<Snapshot>(Snapshot())

    val size: Int get() = snap.load().pending.size

    val inFlight: List<DownloadJob>
        get() = snap.load().owners.values.toList()

    /**
     * The only admission path. Allocates this request's attempt id, dedupes by key across both the
     * pending list and the in-flight index, and returns what the caller must do about it.
     */
    fun offer(job: DownloadJob): EnqueueResult =
        mutate { s ->
            val incoming = job.copy(id = JobId(s.nextSeq + 1))
            val running = s.owners[incoming.key]
            val queued = s.pending.firstOrNull { it.key == incoming.key }
            val incumbent = running ?: queued
            if (incumbent != null && incoming.rank >= incumbent.rank) {
                return@mutate s to EnqueueResult.SupersededByHigherPriority
            }
            if (incumbent != null) {
                // Same key, strictly better: the incumbent yields the slot but keeps its `.part`
                // and its attempt budget, because it is the same logical download.
                val next = s.withPending(incoming)
                val outcome = if (running != null) EnqueueResult.Preempted(running) else EnqueueResult.Queued(incoming.id)
            return@mutate next to outcome
            }
            // Displace the least valuable running attempt when the newcomer outranks it. This is
            // what makes a three-second skip cost a prefetch's remainder instead of a whole
            // transfer: with two slots, a USER_NOW behind an in-flight PREFETCH_NEXT cancels it.
            val victim = s.preemptable(incoming.rank)
            if (victim == null && s.pending.size >= capacity) {
                return@mutate s to EnqueueResult.Dropped
            }
            val queued2 = if (victim == null) s else s.withPending(victim)
            val next = queued2.withPending(incoming)
            next to if (victim == null) EnqueueResult.Queued(incoming.id) else EnqueueResult.Preempted(victim)
        }

    /** A job coming back from a timed deferral or a preemption: same attempt id, same budget. */
    fun readmit(job: DownloadJob): Unit = mutate { it.withPending(job) to Unit }

    fun remove(key: SongKey): DownloadJob? =
        mutate { s ->
            val hit = s.pending.firstOrNull { it.key == key }
            if (hit == null) s to null else s.copy(pending = s.pending - hit) to hit
        }

    fun release(key: SongKey) {
        mutate { s -> if (s.owners.containsKey(key)) s.copy(owners = s.owners - key) to Unit else s to Unit }
    }

    /**
     * Take and claim in one atomic step: the best job by (rank, id) whose key no worker owns. The
     * old guard compared the head against a single `executingKey`, which could therefore only ever
     * be `key == null`; this one is correct at any pool width.
     */
    fun claimNext(): DownloadJob? =
        mutate { s ->
            val at = s.pending.indexOfFirst { !s.owners.containsKey(it.key) }
            if (at < 0) {
                s to null
            } else {
                val job = s.pending[at]
                s.copy(pending = s.pending - job, owners = s.owners + (job.key to job)) to job
            }
        }

    fun ownerOf(key: SongKey): DownloadJob? = snap.load().owners[key]

    fun byId(id: JobId): DownloadJob? {
        val s = snap.load()
        return s.owners.values.firstOrNull { it.id == id } ?: s.pending.firstOrNull { it.id == id }
    }

    /** The attempt a caller should await for [key] right now: running, or queued behind one. */
    fun activeAttempt(key: SongKey): JobId? {
        val s = snap.load()
        return (s.owners[key] ?: s.pending.firstOrNull { it.key == key })?.id
    }

    fun inFlightKeys(): Set<SongKey> = snap.load().owners.keys

    private fun Snapshot.withPending(job: DownloadJob): Snapshot {
        val rest = pending.filterNot { it.key == job.key }
        val at = rest.indexOfFirst { compare(it, job) > 0 }.let { if (it < 0) rest.size else it }
        return copy(pending = rest.take(at) + job + rest.drop(at), nextSeq = nextSeq + 1)
    }

    /** The running job with the lowest priority, newest first, so the least invested is sacrificed. */
    private fun Snapshot.preemptable(incomingRank: Int): DownloadJob? =
        owners.values
            .filter { it.rank > incomingRank }
            .minWithOrNull(compareBy({ -it.rank }, { -it.id.seq }))

    private inline fun <T> mutate(block: (Snapshot) -> Pair<Snapshot, T>): T {
        while (true) {
            val cur = snap.load()
            val (next, out) = block(cur)
            if (next === cur || snap.compareAndSet(cur, next)) return out
        }
    }

    private companion object {
        /** FIFO within a rank by attempt id, so a wall-clock step cannot reorder the queue. */
        fun compare(
            a: DownloadJob,
            b: DownloadJob,
        ): Int {
            val byRank = a.rank.compareTo(b.rank)
            return if (byRank != 0) byRank else a.id.seq.compareTo(b.id.seq)
        }
    }
}

/**
 * A counting gate for the byte-copy step, built on a buffered [Channel] so permit handoff is FIFO
 * and cancellation-safe on every target.
 *
 * It bounds concurrent *transfers* rather than concurrent *jobs*, which is the difference the
 * config asked for: a job parked in VERIFY or COMMIT holds no permit, and a 6 MB `USER_NOW` and a
 * 400 KB `PREFETCH_NEXT` do not contend for the same resource for the same wall-clock time.
 */
class TransferGate(
    permits: Int,
) {
    private val tokens = Channel<Unit>(permits.coerceAtLeast(1))

    init {
        repeat(permits.coerceAtLeast(1)) { tokens.trySend(Unit) }
    }

    suspend fun acquire() {
        tokens.receive()
    }

    fun release() {
        tokens.trySend(Unit)
    }
}
