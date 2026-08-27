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
import dylan.util.nowMs
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

    // In-memory LRU (24 entries) — eliminates repeat network on tab return / album reopen.
    // TTL: home/topSearches = homeCacheTtlMs (6h), album/artist = albumCacheTtlMs (7d).
    private data class Entry(
        val value: Any,
        val at: Long,
    )

    private val mem = LinkedHashMap<String, Entry>()
    private val memCap = 24

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> memGet(
        key: String,
        ttlMs: Long,
    ): T? {
        val e = mem[key] ?: return null
        if (nowMs() - e.at > ttlMs) {
            mem.remove(key)
            return null
        }
        mem.remove(key)
        mem[key] = e
        return e.value as? T
    }

    private fun memPut(
        key: String,
        value: Any,
    ) {
        mem[key] = Entry(value, nowMs())
        if (mem.size > memCap) {
            val it = mem.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
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
        memGet<Album>("album:$id", cfg.albumCacheTtlMs)?.let { return it }
        val text = get(*albumRequest(id).toTypedArray()) ?: return null
        val parsed = runCatching { mapAlbum(json.decodeFromString(AlbumDto.serializer(), text)) }.getOrNull() ?: return null
        memPut("album:$id", parsed)
        return parsed
    }

    override suspend fun artist(id: String): Artist? {
        memGet<Artist>("artist:$id", cfg.albumCacheTtlMs)?.let { return it }
        val text = get("__call" to "webapi.get", "token" to id, "type" to "artist", "includeMetaTags" to "0") ?: return null
        val parsed = runCatching { mapArtist(json.decodeFromString(ArtistDto.serializer(), text)) }.getOrNull() ?: return null
        memPut("artist:$id", parsed)
        return parsed
    }

    override suspend fun topSearches(): List<MiniEntity> {
        memGet<List<MiniEntity>>("topSearches", cfg.homeCacheTtlMs)?.let { return it }
        val text = get("__call" to "content.getTopSearches") ?: return emptyList()
        val parsed =
            runCatching { json.decodeFromString<List<SongDto>>(text) }
                .getOrDefault(emptyList())
                .mapNotNull(::mapMini)
        if (parsed.isNotEmpty()) memPut("topSearches", parsed)
        return parsed
    }

    override suspend fun home(): HomeFeed {
        memGet<HomeFeed>("home", cfg.homeCacheTtlMs)?.let { return it }
        val text =
            get("__call" to "content.getTrending", "entity_type" to "album", "entity_language" to "hindi") ?: return HomeFeed(emptyList())
        val items =
            runCatching { json.decodeFromString<List<SongDto>>(text) }
                .getOrDefault(emptyList())
                .mapNotNull(::mapMini)
        val feed = HomeFeed(listOf(HomeSection("Trending", items)))
        if (items.isNotEmpty()) memPut("home", feed)
        return feed
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
