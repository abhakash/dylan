package dylan.download

import dylan.model.DylanFailure
import dylan.model.SongKey

enum class Priority { USER_NOW, USER_BULK, PREFETCH_NEXT, QUALITY_UPGRADE }

data class DownloadJob(
    val key: SongKey,
    val reason: Priority,
    val bitrate: Int,
    val enqueuedAtMs: Long,
)

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
