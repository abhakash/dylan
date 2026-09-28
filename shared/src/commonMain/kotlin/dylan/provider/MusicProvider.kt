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

/**
 * The catalog seam. Every method answers a [CatalogResult], so a failure is a *value* carrying an
 * [dylan.model.ErrorCode] — not a `null` that a geo-block, a rate limit, a bot-wall HTML page, a
 * decode break and a genuine "no matches" all shared.
 *
 * Every method has a default, so a partial implementation compiles: the defaults say "this catalog
 * does not offer that operation", which is a different statement from "that operation failed".
 */
interface CatalogApi {
    /** Song results for a submitted query (paged). */
    suspend fun searchPage(
        query: String,
        page: Int,
    ): CatalogResult<Paged<Song>> = unsupported("searchPage")

    /** Album results for a submitted query (paged). */
    suspend fun searchAlbumPage(
        query: String,
        page: Int,
    ): CatalogResult<Paged<MiniEntity>> = CatalogResult.Ok(Paged(emptyList(), 0, page))

    /** Artist results for a submitted query (paged). */
    suspend fun searchArtistPage(
        query: String,
        page: Int,
    ): CatalogResult<Paged<MiniEntity>> = CatalogResult.Ok(Paged(emptyList(), 0, page))

    suspend fun albumDetail(id: String): CatalogResult<Album> = unsupported("albumDetail")

    suspend fun artistDetail(id: String): CatalogResult<Artist> = unsupported("artistDetail")

    suspend fun homeFeed(): CatalogResult<HomeFeed> = CatalogResult.Ok(HomeFeed(emptyList()))

    suspend fun topSearchList(): CatalogResult<List<MiniEntity>> = CatalogResult.Ok(emptyList())

    /**
     * Exchange a `resolve_ref` for a signed CDN URL.
     *
     * **This is the signature W3-E (`download/`) must adapt to**: it replaces
     * `resolveStream(ref, q): SignedStream?` at `DownloadEngine.kt:398`, and it is the only place
     * the four root causes a resolve can have — `RATE_LIMITED` (honour `Retry-After`), `EXPIRED`,
     * `FORBIDDEN_REGION`, `NETWORK`/`NETWORK_TIMEOUT` — become distinguishable instead of one
     * `signed == null` that reports `ErrorCode.NETWORK` for all of them.
     */
    suspend fun resolve(
        resolveRef: String,
        q: Quality,
    ): CatalogResult<SignedStream> = unsupported("resolve")
}

private fun unsupported(op: String): CatalogResult.Err =
    CatalogResult.Err(dylan.model.ErrorCode.UNSUPPORTED, "$op not offered by this catalog", retryable = false)

/**
 * [CatalogApi] plus the **legacy null-returning views**, which exist only for the two call sites
 * this wave does not own. They are default implementations over the typed API, so an implementation
 * only ever writes the typed half.
 *
 * ## Delete each legacy method together with its last caller
 *
 * | Legacy (nullable) | Typed (use) | Still referenced by |
 * |---|---|---|
 * | `search(q, page)` | `searchPage(q, page)` | `jvmTest/support/GraphHarness.StubProvider` (read-only here) |
 * | `album(id)` | `albumDetail(id)` | `GraphHarness.StubProvider` |
 * | `artist(id)` | `artistDetail(id)` | `GraphHarness.StubProvider` |
 * | `home()` | `homeFeed()` | `GraphHarness.StubProvider` |
 * | `topSearches()` | `topSearchList()` | `GraphHarness.StubProvider` |
 * | `resolveStream(ref, q)` | `resolve(ref, q)` | `download/DownloadEngine.kt:398` (W3-E), and the iOS bridge |
 * | `searchAlbums` / `searchArtists` | `searchAlbumPage` / `searchArtistPage` | `iosApp/.../DylanBridge.swift` |
 *
 * The legacy default loses exactly the information this interface exists to carry: which
 * [dylan.model.ErrorCode] happened and whether a retry could succeed. `DownloadEngine` currently
 * retries a `RATE_LIMITED` resolve as if it were a generic `NETWORK`, and reports `OFFLINE` as
 * `NETWORK`.
 */
interface MusicProvider : CatalogApi {
    suspend fun search(
        query: String,
        page: Int,
    ): Paged<Song> = searchPage(query, page).orDefault(Paged(emptyList(), 0, page))

    suspend fun searchAlbums(
        query: String,
        page: Int,
    ): Paged<MiniEntity> = searchAlbumPage(query, page).orDefault(Paged(emptyList(), 0, page))

    suspend fun searchArtists(
        query: String,
        page: Int,
    ): Paged<MiniEntity> = searchArtistPage(query, page).orDefault(Paged(emptyList(), 0, page))

    suspend fun album(id: String): Album? = albumDetail(id).valueOrNull()

    suspend fun artist(id: String): Artist? = artistDetail(id).valueOrNull()

    suspend fun home(): HomeFeed = homeFeed().orDefault(HomeFeed(emptyList()))

    suspend fun topSearches(): List<MiniEntity> = topSearchList().orDefault(emptyList())

    suspend fun resolveStream(
        resolveRef: String,
        q: Quality,
    ): SignedStream? = resolve(resolveRef, q).valueOrNull()
}
