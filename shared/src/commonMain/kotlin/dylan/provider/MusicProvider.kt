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
    /** Song results for a submitted query (paged). */
    suspend fun search(
        query: String,
        page: Int,
    ): Paged<Song>

    /** Album results for a submitted query (paged). Default: unsupported. */
    suspend fun searchAlbums(
        query: String,
        page: Int,
    ): Paged<MiniEntity> = Paged(emptyList(), 0, page)

    /** Artist results for a submitted query (paged). Default: unsupported. */
    suspend fun searchArtists(
        query: String,
        page: Int,
    ): Paged<MiniEntity> = Paged(emptyList(), 0, page)

    suspend fun album(id: String): Album?

    suspend fun artist(id: String): Artist?

    suspend fun home(): HomeFeed

    suspend fun topSearches(): List<MiniEntity>

    suspend fun resolveStream(
        resolveRef: String,
        q: Quality,
    ): SignedStream?
}
