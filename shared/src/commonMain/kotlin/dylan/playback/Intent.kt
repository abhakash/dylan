package dylan.playback

import dylan.model.Quality
import dylan.model.Song

sealed interface Intent {
    data class PlayNow(
        val songs: List<Song>,
        val startIndex: Int,
    ) : Intent

    data class PlayNext(
        val song: Song,
    ) : Intent

    data class AddLast(
        val song: Song,
    ) : Intent

    data object TogglePlayPause : Intent

    data class Seek(
        val ms: Long,
    ) : Intent

    data object Next : Intent

    data object Previous : Intent

    data object ToggleShuffle : Intent

    data object CycleRepeat : Intent

    data class RemoveAt(
        val queuePos: Int,
    ) : Intent

    data class MoveWithinQueue(
        val from: Int,
        val to: Int,
    ) : Intent

    data object ClearUpNext : Intent

    data class SetQuality(
        val q: Quality,
    ) : Intent
}
