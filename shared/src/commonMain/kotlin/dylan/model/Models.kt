package dylan.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

data class SongKey(
    val provider: String,
    val songId: String,
) {
    fun itemId(bits: Int) = "$provider:$songId:$bits"
}

enum class Quality(
    val bits: Int,
    val bps: Long,
) {
    BITRATE_128(128, 20_400),
    BITRATE_320(320, 40_400),
    ;

    companion object {
        fun of(bits: Int) = if (bits >= 320) BITRATE_320 else BITRATE_128
    }
}

data class Song(
    val key: SongKey,
    val title: String,
    val subtitle: String,
    val albumId: String?,
    val albumName: String?,
    val artUrl150: String,
    val artUrl500: String,
    val durationS: Long,
    val has320: Boolean,
    val cacheable: Boolean,
    val resolveRef: String?,
    val permaToken: String?,
    val artistName: String? = null,
    val artistToken: String? = null,
)

data class Artist(
    val id: String,
    val name: String,
    val subtitle: String?,
    val artUrl150: String,
    val artUrl500: String,
    val songs: List<Song>,
)

data class Album(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artUrl150: String,
    val artUrl500: String,
    val year: String?,
    val songs: List<Song>,
)

data class MiniEntity(
    val songKey: SongKey?,
    val albumId: String?,
    val title: String,
    val subtitle: String,
    val type: String,
    val image: String,
    val permaToken: String?,
    val artistId: String? = null,
)

data class Paged<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
)

data class SearchSections(
    val topSearches: List<MiniEntity>,
    val trending: List<MiniEntity>,
)

data class HomeSection(
    val title: String,
    val items: List<MiniEntity>,
)

data class HomeFeed(
    val sections: List<HomeSection>,
)

enum class Repeat { OFF, ALL, ONE }

enum class ErrorCode {
    OFFLINE,
    NOT_FOUND,
    NO_SOURCE,
    NOT_CACHEABLE,
    EXPIRED,
    FORBIDDEN_REGION,
    NETWORK,
    NETWORK_TIMEOUT,
    STORAGE,
    CORRUPT_SIZE,
    CORRUPT_CONTAINER,
    UNSUPPORTED,
    RATE_LIMITED,
    RESOLVE_LIMIT,
    TOO_MANY_FAILURES,
    DRIFT,
}

data class DylanFailure(
    val code: ErrorCode,
    val songKey: SongKey? = null,
    val detail: String? = null,
)

/** Single user-facing copy source — Android toasts, NP sheet, and iOS failureMessage all route here. */
fun DylanFailure.message(): String =
    when (code) {
        ErrorCode.OFFLINE -> "You're offline — saved music still plays."
        ErrorCode.NOT_FOUND -> "This track seems unavailable."
        ErrorCode.NO_SOURCE -> "No playable source for this track."
        ErrorCode.NOT_CACHEABLE, ErrorCode.UNSUPPORTED -> "This track can't be saved for offline play."
        ErrorCode.EXPIRED, ErrorCode.RESOLVE_LIMIT -> "Couldn't refresh this track. Try again."
        ErrorCode.FORBIDDEN_REGION -> "Not available in your region."
        ErrorCode.NETWORK, ErrorCode.NETWORK_TIMEOUT, ErrorCode.DRIFT -> "Check your connection and try again."
        ErrorCode.STORAGE -> "Not enough space. Free up storage or clear cache."
        ErrorCode.CORRUPT_SIZE, ErrorCode.CORRUPT_CONTAINER -> "That file didn't download cleanly. Retrying…"
        ErrorCode.RATE_LIMITED -> "Slow down a moment…"
        ErrorCode.TOO_MANY_FAILURES -> "Several tracks failed to load. Check your connection."
    }

sealed interface Phase {
    data object Idle : Phase

    data class Resolving(
        val key: SongKey,
    ) : Phase

    data class Downloading(
        val key: SongKey,
    ) : Phase

    data class Ready(
        val key: SongKey,
    ) : Phase

    data class Playing(
        val key: SongKey,
    ) : Phase

    data class Paused(
        val key: SongKey,
    ) : Phase

    data class Error(
        val failure: DylanFailure,
    ) : Phase
}

data class PlayerState(
    val phase: Phase = Phase.Idle,
    val current: Song? = null,
    val queue: PersistentList<Song> = persistentListOf(),
    val index: Int = -1,
    val shuffleOn: Boolean = false,
    val shuffleOrder: PersistentList<Int>? = null,
    val repeat: Repeat = Repeat.OFF,
) {
    val nextUp: Song?
        get() =
            when {
                queue.isEmpty() || repeat == Repeat.ONE -> current
                shuffleOn ->
                    shuffleOrder?.let { order ->
                        val pos = order.indexOf(index)
                        if (pos < 0) null else order.getOrNull(pos + 1)?.let { queue.getOrNull(it) }
                    }
                index + 1 < queue.size -> queue[index + 1]
                repeat == Repeat.ALL -> queue.firstOrNull()
                else -> null
            }
}
