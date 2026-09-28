package dylan.download

import dylan.model.DylanFailure
import dylan.model.SongKey

/**
 * Scheduling order is [rank], never `ordinal`. The persisted `intent.reason` strings the
 * reconciler reads are [wire], which is the constant's own name — so reordering or renaming a
 * constant can no longer silently change what the scheduler does with persisted rows.
 */
enum class Priority(
    val rank: Int,
    val wire: String,
) {
    USER_NOW(0, "USER_NOW"),
    USER_BULK(1, "USER_BULK"),
    PREFETCH_NEXT(2, "PREFETCH_NEXT"),
    QUALITY_UPGRADE(3, "QUALITY_UPGRADE"),
    ;

    companion object {
        /** Tolerant parse of a persisted `intent.reason`; null leaves the default to the caller. */
        fun fromWire(name: String?): Priority? = entries.firstOrNull { it.wire == name }
    }
}

/**
 * Identity of one *attempt*, allocated by the queue from a monotonic counter and never reused.
 * A waiter that captured an id is therefore never woken by a later attempt for the same song —
 * the defect PB-2 describes, where a `Failed` track can never be retried in the same session
 * because the awaiter cannot tell which attempt it was waiting for.
 */
data class JobId(
    val seq: Long,
) {
    companion object {
        /** What a caller-built job carries before the queue has seen it. */
        val UNASSIGNED = JobId(UNASSIGNED_SEQ)
    }
}

/**
 * One unit of download work.
 *
 * [attempts] travels *with the job* rather than living in a local of the worker body: a deferred
 * requeue (429, breaker wait, truncation) hands the job back to the queue, so a counter kept in
 * the body restarts at zero and a 429 with no `Retry-After` becomes an infinite 5-second loop.
 */
data class DownloadJob(
    val key: SongKey,
    val reason: Priority,
    val bitrate: Int,
    /** Wall clock at enqueue — display/diagnostics only; ordering uses [id]. */
    val enqueuedAtMs: Long,
    val id: JobId = JobId.UNASSIGNED,
    val attempts: Int = 0,
) {
    val rank: Int get() = reason.rank

    /** `saavn:s1 prio=USER_NOW` — one label for every log line, so nothing re-spells it. */
    val label: String get() = "${key.provider}:${key.songId} prio=$reason"
}

sealed interface JobState {
    data object Queued : JobState

    data object Resolving : JobState

    data class Downloading(
        val loadedB: Long,
        val totalB: Long?,
    ) : JobState

    data object Verifying : JobState

    data class Done(
        val bytes: Long,
        val bitrate: Int,
    ) : JobState

    data class Failed(
        val err: DylanFailure,
        val willRetry: Boolean,
    ) : JobState

    data object Cancelled : JobState
}

/** True for the states a job never leaves. Terminal entries are what the engine retains. */
internal fun JobState.isTerminal(): Boolean =
    this is JobState.Done || this is JobState.Failed || this is JobState.Cancelled

private const val UNASSIGNED_SEQ = -1L
