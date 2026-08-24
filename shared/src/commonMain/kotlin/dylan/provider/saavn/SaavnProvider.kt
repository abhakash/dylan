package dylan.provider.saavn

import dylan.config.AppConfig
import dylan.model.Album
import dylan.model.Artist
import dylan.model.HomeFeed
import dylan.model.HomeSection
import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Quality
import dylan.model.Song
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.provider.saavn.dto.AlbumDto
import dylan.provider.saavn.dto.ArtistDto
import dylan.provider.saavn.dto.AuthDto
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class SaavnProvider(
    private val http: HttpClient,
    private val cfg: AppConfig,
) : MusicProvider {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private suspend fun get(vararg params: Pair<String, String>): String? {
        val resp =
            http.get(cfg.apiBaseUrl) {
                params.forEach { (k, v) -> parameter(k, v) }
                cfg.commonParams.forEach { (k, v) -> parameter(k, v) }
                header(HttpHeaders.UserAgent, cfg.userAgent)
            }
        if (!resp.status.isSuccess()) return null
        val text = resp.body<String>()
        return text.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
    }

    override suspend fun search(
        query: String,
        page: Int,
    ): Paged<Song> {
        val text =
            get("__call" to "search.getResults", "q" to query, "p" to page.toString(), "n" to cfg.submitPageSize.toString())
                ?: return Paged(emptyList(), 0, page)
        return runCatching { mapPaged(json.decodeFromString(ResultsDto.serializer(), text)) }.getOrDefault(Paged(emptyList(), 0, page))
    }

    override suspend fun album(id: String): Album? {
        val text = get(*albumRequest(id).toTypedArray()) ?: return null
        return runCatching { mapAlbum(json.decodeFromString(AlbumDto.serializer(), text)) }.getOrNull()
    }

    override suspend fun artist(id: String): Artist? {
        val text = get("__call" to "webapi.get", "token" to id, "type" to "artist", "includeMetaTags" to "0") ?: return null
        return runCatching { mapArtist(json.decodeFromString(ArtistDto.serializer(), text)) }.getOrNull()
    }

    override suspend fun topSearches(): List<MiniEntity> {
        val text = get("__call" to "content.getTopSearches") ?: return emptyList()
        return runCatching { json.decodeFromString<List<SongDto>>(text) }
            .getOrDefault(emptyList())
            .mapNotNull(::mapMini)
    }

    override suspend fun home(): HomeFeed {
        val text =
            get("__call" to "content.getTrending", "entity_type" to "album", "entity_language" to "hindi") ?: return HomeFeed(emptyList())
        val items =
            runCatching { json.decodeFromString<List<SongDto>>(text) }
                .getOrDefault(emptyList())
                .mapNotNull(::mapMini)
        return HomeFeed(listOf(HomeSection("Trending", items)))
    }

    override suspend fun resolveStream(
        resolveRef: String,
        q: Quality,
    ): SignedStream? {
        val text = get("__call" to "song.generateAuthToken", "url" to resolveRef, "bitrate" to q.bits.toString()) ?: return null
        return runCatching { mapAuth(json.decodeFromString(AuthDto.serializer(), text)) }.getOrNull()
    }
}

/**
 * Album lookup routing [HAR-2 + live 2026-08-24]: webapi.get wants the perma TOKEN
 * (e9NTAB1tQ9M_); NUMERIC album ids — what songs store in album_id and what the
 * history carousel navigates with — must go through content.getAlbumDetails.
 * A numeric token yields a 200 empty-shell payload that parses into a blank album,
 * which surfaced as "Check your connection" on the album screen.
 */
internal fun albumRequest(id: String): List<Pair<String, String>> =
    if (id.isNotEmpty() && id.all { it.isDigit() }) {
        listOf("__call" to "content.getAlbumDetails", "albumid" to id)
    } else {
        listOf("__call" to "webapi.get", "token" to id, "type" to "album", "includeMetaTags" to "0")
    }
