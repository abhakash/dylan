package dylan.provider

import dylan.model.Album
import dylan.model.Artist
import dylan.model.HomeFeed
import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Quality
import dylan.model.Song

data class SignedStream(
    val url: String,
    val type: String,
)

interface MusicProvider {
    suspend fun search(
        query: String,
        page: Int,
    ): Paged<Song>

    suspend fun album(id: String): Album?

    suspend fun artist(id: String): Artist?

    suspend fun home(): HomeFeed

    suspend fun topSearches(): List<MiniEntity>

    suspend fun resolveStream(
        resolveRef: String,
        q: Quality,
    ): SignedStream?
}
