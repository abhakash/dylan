package dylan.provider.saavn

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.model.Album
import dylan.model.Artist
import dylan.model.ErrorCode
import dylan.model.HomeFeed
import dylan.model.HomeSection
import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Quality
import dylan.model.Song
import dylan.provider.CatalogBody
import dylan.provider.CatalogResult
import dylan.provider.MusicProvider
import dylan.provider.ResilientClient
import dylan.provider.SignedStream
import dylan.provider.err
import dylan.provider.saavn.dto.AuthDto
import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * The JioSaavn catalog.
 *
 * Two structural changes from the version this replaces:
 *
 *  - **Every method answers a [CatalogResult].** "no connectivity", "rate limited", "geo-blocked",
 *    "bot-walled", "timed out" and "no matches" were six different events that all surfaced as
 *    `null` or an empty list, so `AlbumScreen`'s `try/catch` was dead code and five unrelated root
 *    causes rendered as "Check your connection".
 *  - **Decoding is element-wise.** The `runCatching` used to wrap the *whole* page, so one card with
 *    `"id": null` discarded nineteen good songs and the user saw "Songs 0 of 0" — byte-identical to
 *    a network failure. One bad card is now one dropped card plus one [dylan.provider.Drift].
 *
 * [scope] and [disp] are injected, not owned: a component never creates its own
 * [kotlinx.coroutines.Job], and the graph owns the [kotlinx.coroutines.SupervisorJob] they descend
 * from.
 */
class SaavnProvider(
    http: HttpClient,
    private val cfg: AppConfig,
    scope: CoroutineScope,
    private val disp: AppDispatchers,
    private val net: NetMonitor,
    log: LogBuffer,
) : MusicProvider {
    private val rc =
        ResilientClient(
            http = http,
            cfg = cfg,
            net = net,
            scope = scope,
            disp = disp,
            log = log,
        )

    // ── songs ────────────────────────────────────────────────────────────────────────────────

    override suspend fun searchPage(
        query: String,
        page: Int,
    ): CatalogResult<Paged<Song>> {
        val q = query.trim()
        if (q.isEmpty()) return CatalogResult.Ok(Paged(emptyList(), 0, page))
        val body =
            rc.get(
                listOf(
                    CALL to EP_SEARCH_SONGS,
                    "q" to q,
                    "p" to page.toString(),
                    "n" to cfg.submitPageSize.toString(),
                ),
            )
        return mapOnIo(body) { text ->
            val rows = decodeSongPage(text, EP_SEARCH_SONGS, page)
            CatalogResult.Ok(rows.items, rows.drift)
        }
    }

    // ── albums / artists ─────────────────────────────────────────────────────────────────────

    override suspend fun searchAlbumPage(
        query: String,
        page: Int,
    ): CatalogResult<Paged<MiniEntity>> = searchMinis(EP_SEARCH_ALBUMS, TYPE_ALBUM, query, page)

    override suspend fun searchArtistPage(
        query: String,
        page: Int,
    ): CatalogResult<Paged<MiniEntity>> = searchMinis(EP_SEARCH_ARTISTS, TYPE_ARTIST, query, page)

    private suspend fun searchMinis(
        endpoint: String,
        wantType: String,
        query: String,
        page: Int,
    ): CatalogResult<Paged<MiniEntity>> {
        val q = query.trim()
        if (q.isEmpty()) return CatalogResult.Ok(Paged(emptyList(), 0, page))
        val body =
            rc.get(
                listOf(
                    CALL to endpoint,
                    "q" to q,
                    "p" to page.toString(),
                    "n" to cfg.submitPageSize.toString(),
                ),
            )
        return mapOnIo(body) { text ->
            val rows = decodeMiniPage(text, wantType, endpoint, page)
            CatalogResult.Ok(rows.items, rows.drift)
        }
    }

    /**
     * Album detail. A 200 that decodes into a blank album is reported as [ErrorCode.NOT_FOUND], not
     * as a successful empty album — that empty shell *was* the "Check your connection" screen.
     */
    override suspend fun albumDetail(id: String): CatalogResult<Album> {
        val key = id.trim()
        if (key.isEmpty()) return err(ErrorCode.NOT_FOUND, "empty album id", retryable = false)
        val request = albumRequest(key)
        return rc.cached("album:$key", cfg.albumCacheTtlMs) {
            when (val body = rc.get(request)) {
                is CatalogResult.Err -> body
                is CatalogResult.Ok -> decodeAlbum(body.value.text, endpointOf(request))
            }
        }
    }

    /**
     * Artist detail, routed by [artistRequest].
     *
     * A numeric id is *refused* rather than sent: there is no numeric-artist route on this API
     * (verified live 2026-09-28 — see [ArtistRoute.UnsupportedNumericId]) and the old code sent one
     * anyway, got a 200 empty shell, and rendered "Check your connection". The mapper no longer mints
     * numeric ids, so this is a second line of defence rather than the first.
     */
    override suspend fun artistDetail(id: String): CatalogResult<Artist> {
        val route = artistRoute(id)
        if (route is ArtistRoute.UnsupportedNumericId) {
            return err(
                ErrorCode.NOT_FOUND,
                "no numeric-artist route (id='${route.id}'); the mapper must carry a perma token",
                retryable = false,
            )
        }
        val key = id.trim()
        val request = artistRequest(route)
        return rc.cached("artist:$key", cfg.albumCacheTtlMs) {
            when (val body = rc.get(request)) {
                is CatalogResult.Err -> body
                is CatalogResult.Ok -> decodeArtist(body.value.text, endpointOf(request))
            }
        }
    }

    // ── home / top searches ──────────────────────────────────────────────────────────────────

    override suspend fun homeFeed(): CatalogResult<HomeFeed> {
        val endpoint = EP_HOME
        val request = listOf(CALL to endpoint, "entity_type" to "album", "entity_language" to "hindi")
        return rc.cached("home", cfg.homeCacheTtlMs) {
            when (val body = rc.get(request)) {
                is CatalogResult.Err -> body
                is CatalogResult.Ok ->
                    mapOnIo(body) { text ->
                        val rows = decodeMinis(text, endpoint)
                        CatalogResult.Ok(HomeFeed(listOf(HomeSection("Trending", rows.items))), rows.drift)
                    }
            }
        }
    }

    override suspend fun topSearchList(): CatalogResult<List<MiniEntity>> {
        val endpoint = EP_TOP_SEARCHES
        return rc.cached("topSearches", cfg.homeCacheTtlMs) {
            when (val body = rc.get(listOf(CALL to endpoint))) {
                is CatalogResult.Err -> body
                is CatalogResult.Ok ->
                    mapOnIo(body) { text ->
                        val rows = decodeMinis(text, endpoint)
                        CatalogResult.Ok(rows.items, rows.drift)
                    }
            }
        }
    }

    // ── resolve ──────────────────────────────────────────────────────────────────────────────

    override suspend fun resolve(
        resolveRef: String,
        q: Quality,
    ): CatalogResult<SignedStream> {
        val ref = resolveRef.trim()
        if (ref.isEmpty()) return err(ErrorCode.NO_SOURCE, "blank resolve_ref", retryable = false)
        val body =
            rc.get(
                listOf(
                    CALL to CALL_RESOLVE,
                    "url" to ref,
                    "bitrate" to q.bits.toString(),
                ),
            )
        return mapOnIo(body) { text ->
            val auth =
                runCatching { rc.json.decodeFromString(AuthDto.serializer(), text) }.getOrNull()
                    ?: return@mapOnIo err(ErrorCode.DRIFT, "undecodable auth payload", retryable = false)
            val stream =
                mapAuth(auth)
                    ?: return@mapOnIo err(
                        ErrorCode.NO_SOURCE,
                        "generateAuthToken status=${auth.status} type=${auth.type}",
                        retryable = false,
                    )
            CatalogResult.Ok(stream)
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────────────────

    /**
     * Decode a fetched body **on the io lane**.
     *
     * Mapping is the expensive half of a catalog call — a 20-card page with `more_info` trees is a
     * few hundred KB of JSON — and it is CPU-bound with no suspension inside, so running it on the
     * caller's lane delays whatever else that lane carries. In this app the caller's lane is
     * `state`, which also carries the orchestrator inbox, the 10 Hz position ticker and the
     * protected-key collector. Only the finished value crosses back.
     */
    private suspend inline fun <T> mapOnIo(
        body: CatalogResult<CatalogBody>,
        crossinline map: suspend (String) -> CatalogResult<T>,
    ): CatalogResult<T> {
        if (body is CatalogResult.Err) return body
        val ok = body as CatalogResult.Ok
        return withContext(disp.on(Lane.IO)) {
            disp.assert(Lane.IO)
            map(ok.value.text)
        }
    }

    private fun endpointOf(request: List<Pair<String, String>>): String =
        request.firstOrNull { it.first == CALL }?.second ?: "unknown"

    companion object {
        internal const val CALL = "__call"
        internal const val EP_SEARCH_SONGS = "search.getResults"
        internal const val EP_SEARCH_ALBUMS = "search.getAlbumResults"
        internal const val EP_SEARCH_ARTISTS = "search.getArtistResults"
        internal const val EP_TOP_SEARCHES = "content.getTopSearches"
        internal const val EP_HOME = "content.getTrending"
        internal const val CALL_RESOLVE = "song.generateAuthToken"
    }
}

// ── routing ────────────────────────────────────────────────────────────────────────────────────

/**
 * Album lookup routing [HAR-2 + live 2026-08-24]: webapi.get wants the perma TOKEN
 * (e9NTAB1tQ9M_); NUMERIC album ids — what songs store in album_id and what the history carousel
 * navigates with — must go through content.getAlbumDetails (re-verified live 2026-09-28: numeric
 * 79121261 returns the full album). A numeric token yields a 200 empty-shell payload that parses
 * into a blank album, which surfaced as "Check your connection" on the album screen.
 */
internal fun albumRequest(id: String): List<Pair<String, String>> =
    if (id.isNotEmpty() && id.all { it.isDigit() }) {
        listOf("__call" to "content.getAlbumDetails", "albumid" to id)
    } else {
        listOf("__call" to "webapi.get", "token" to id, "type" to "album", "includeMetaTags" to "0")
    }

/** What [artistRequest] decided, so the caller can log the route and the failure can name it. */
sealed interface ArtistRoute {
    data class WebapiToken(
        val token: String,
    ) : ArtistRoute

    /**
     * A numeric artist id. There is **no** numeric-artist route on the web API — verified live
     * 2026-09-28: `content.getArtistDetails` answers
     * `{"error":{"code":"INPUT_INVALID","msg":"getArtistDetails operation is not supported"}}`,
     * `artist.getArtistMoreSong` answers an empty shell, and `webapi.get&token=610240&type=artist`
     * answers 200 with `{"artistId":null,"name":"","topSongs":[]}`. So the right answer is to
     * refuse the id, not to guess an endpoint — and the mapper must not mint one (see [mapMini]).
     */
    data class UnsupportedNumericId(
        val id: String,
    ) : ArtistRoute
}

internal fun artistRoute(id: String): ArtistRoute {
    val token = id.trim()
    if (token.isEmpty()) return ArtistRoute.UnsupportedNumericId(token)
    return if (token.all { it.isDigit() }) {
        ArtistRoute.UnsupportedNumericId(token)
    } else {
        ArtistRoute.WebapiToken(token)
    }
}

internal fun artistRequest(route: ArtistRoute): List<Pair<String, String>> =
    when (route) {
        is ArtistRoute.WebapiToken ->
            listOf("__call" to "webapi.get", "token" to route.token, "type" to "artist", "includeMetaTags" to "0")
        is ArtistRoute.UnsupportedNumericId -> emptyList()
    }

/**
 * `song.generateAuthToken` → a signed CDN URL. Lives with its only production caller; the fixture
 * tests import it from here, which is the same package.
 */
fun mapAuth(a: dylan.provider.saavn.dto.AuthDto): SignedStream? =
    if (a.status == "success" && !a.authUrl.isNullOrBlank()) SignedStream(a.authUrl, a.type ?: "mp4") else null
