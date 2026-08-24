package dylan.android.ui.components

import dylan.model.Song

// Catalog-level identity: JioSaavn serves one recording under multiple song ids (album vs
// search entries — PROGRESS §4, identical MD5 across ids). Title + primary artist + exact
// duration is stable across those ids, so lists dedupe on this instead of SongKey alone.
fun Song.recordingKey(): String {
    val title =
        title
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    val artist =
        (artistName ?: subtitle)
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    return "$title|$artist|$durationS"
}

fun List<Song>.distinctRecordings(): List<Song> = distinctBy { it.recordingKey() }

fun List<Song>.containsRecording(song: Song): Boolean = any { it.recordingKey() == song.recordingKey() }
