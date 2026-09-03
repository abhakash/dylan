package dylan.provider.saavn

import dylan.cache.Paths
import dylan.model.Album
import dylan.model.Artist
import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Song
import dylan.model.SongKey
import dylan.provider.SignedStream
import dylan.provider.saavn.dto.AlbumDto
import dylan.provider.saavn.dto.ArtistDto
import dylan.provider.saavn.dto.AuthDto
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import dylan.provider.saavn.dto.WsFrameDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private fun JsonElement?.str(): String? =
    when (this) {
        null, is JsonNull -> null
        else -> jsonPrimitive.contentOrNull
    }

fun art500(url: String?): String? {
    val u = url?.takeIf { it.isNotBlank() } ?: return null
    return if (u.contains("150x150")) u.replace("150x150", "500x500") else u
}

/**
 * Coerces the live "320kbps" field across every shape the API has shipped:
 * Boolean literal (true), numeric (1/0) and strings ("true"/"false"/"1"/"0").
 * Anything unrecognized (null, absent, garbage) is false — never throw on catalog data.
 */
internal fun coerceHas320(el: JsonElement?): Boolean {
    val p = el as? JsonPrimitive ?: return false
    p.booleanOrNull?.let { return it }
    p.longOrNull?.let { return it == 1L }
    p.doubleOrNull?.let { return it == 1.0 }
    return when (p.content.trim().lowercase()) {
        "true", "1" -> true
        else -> false
    }
}

// webapi.get&token= wants the PERMA TOKEN (perma_url last segment), not the numeric id
// [verified: HAR-2 e9NTAB1tQ9M_ / live 8Ps4qqBA6,Y_]; numeric ids yield a 200-empty shell.
// Stored normalized (last path segment) at map time so DB rows already carry the usable
// token; bare tokens (no '/') pass through as-is.
internal fun normalizePermaToken(permaUrl: String?): String? {
    val u = permaUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if ('/' !in u) return u
    return u
        .substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
        .takeIf { it.isNotBlank() } ?: u
}

fun mapSong(d: SongDto): Song? {
    if (d.id.isBlank() || d.title.isBlank()) return null
    val mi = d.moreInfo ?: return null
    // NOTE: more_info.rights (incl. the legacy "cacheable" flag) is deliberately NOT
    // mapped: post-cacheable-removal every mapped song is treated as downloadable, and
    // resolveRef presence (not rights) decides NO_SOURCE vs resolvable downstream. The
    // DTO keeps the `rights` field so lenient parsing of live payloads is unaffected.
    val img150 =
        d.image
            .str()
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
    val primary = primaryArtist(mi.artistMap)
    return Song(
        key = SongKey("saavn", Paths.sanitizeId(d.id)),
        title = d.title,
        subtitle = d.subtitle.orEmpty(),
        albumId = mi.albumId,
        albumName = mi.album,
        artUrl150 = img150,
        artUrl500 = art500(img150) ?: img150,
        durationS = mi.duration.str()?.toLongOrNull() ?: 0L,
        has320 = coerceHas320(mi.has320),
        resolveRef = mi.encryptedMediaUrl?.takeIf { it.isNotBlank() },
        permaToken = normalizePermaToken(d.permaUrl),
        artistName = primary?.first,
        artistToken = primary?.second,
    )
}

// webapi.get&token= wants the PERMA TOKEN (perma_url last segment), not the numeric id
// [verified: HAR-2 e9NTAB1tQ9M_ / live 8Ps4qqBA6,Y_]; numeric ids yield a 200-empty shell.
internal fun permaAlbumToken(permaUrl: String?): String? {
    val u = permaUrl ?: return null
    val i = u.indexOf("/album/")
    if (i < 0) return null
    val seg = u.substring(i + "/album/".length).substringAfter('/').substringBefore('?')
    return seg.takeIf { it.isNotBlank() }
}

// Same rule for artists [verified live: token -f6Su9-0agk_ → full payload, numeric 610240 → empty shell]
internal fun permaArtistToken(permaUrl: String?): String? {
    val u = permaUrl ?: return null
    val i = u.indexOf("/artist/")
    if (i < 0) return null
    val seg = u.substring(i + "/artist/".length).substringAfter('/').substringBefore('?')
    return seg.takeIf { it.isNotBlank() }
}

private fun primaryArtist(map: JsonElement?): Pair<String, String>? =
    ((map as? JsonObject)?.get("primary_artists") as? JsonArray)
        ?.firstOrNull()
        ?.let { it as? JsonObject }
        ?.let { o ->
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull
            val token = permaArtistToken((o["perma_url"] as? JsonPrimitive)?.contentOrNull)
            if (!name.isNullOrBlank() && !token.isNullOrBlank()) name to token else null
        }

fun mapMini(d: SongDto): MiniEntity? {
    if (d.id.isBlank() || d.title.isBlank()) return null
    val type = d.type ?: "song"
    return MiniEntity(
        songKey = if (type == "song") SongKey("saavn", Paths.sanitizeId(d.id)) else null,
        albumId = if (type == "album") permaAlbumToken(d.permaUrl) ?: d.id else null,
        artistId = if (type == "artist") permaArtistToken(d.permaUrl) ?: d.id else null,
        title = d.title,
        subtitle = d.subtitle.orEmpty(),
        type = type,
        image = d.image.str().orEmpty(),
        permaToken = d.permaUrl,
    )
}

fun mapPaged(r: ResultsDto): Paged<Song> = Paged(r.results.mapNotNull(::mapSong), r.total.str()?.toLongOrNull() ?: 0L, r.start.str()?.toIntOrNull() ?: 1)

fun mapAlbum(a: AlbumDto): Album? {
    if (a.id.isBlank()) return null
    val songs = a.list.mapNotNull(::mapSong)
    val first = songs.firstOrNull()
    return Album(
        id = a.id,
        title = a.title,
        subtitle = a.subtitle,
        artUrl150 = a.image.str().orEmpty(),
        artUrl500 = art500(a.image.str()).orEmpty(),
        year = a.year.str(),
        songs = songs,
    )
}

fun mapArtist(a: ArtistDto): Artist? {
    if (a.name.isBlank()) return null
    val img150 = a.image.str().orEmpty()
    return Artist(
        id = a.artistId.str().orEmpty(),
        name = a.name,
        subtitle = a.subtitle,
        artUrl150 = img150,
        artUrl500 = art500(img150) ?: img150,
        songs = a.topSongs.mapNotNull(::mapSong),
    )
}

fun mapAuth(a: AuthDto): SignedStream? = if (a.status == "success" && !a.authUrl.isNullOrBlank()) SignedStream(a.authUrl, a.type ?: "mp4") else null

fun mapSuggestionPayload(innerJson: String): List<MiniEntity> {
    val obj = runCatching { json.parseToJsonElement(innerJson).jsonObject }.getOrNull() ?: return emptyList()
    return obj.values
        .filterIsInstance<JsonArray>()
        .flatMap { arr ->
            arr.mapNotNull { el ->
                runCatching { json.decodeFromJsonElement(SongDto.serializer(), el.jsonObject) }.getOrNull()
            }
        }.mapNotNull(::mapMini)
}

fun mapSuggestions(frameJson: String): List<MiniEntity> {
    val frame = runCatching { json.decodeFromString(WsFrameDto.serializer(), frameJson) }.getOrNull() ?: return emptyList()
    return mapSuggestionPayload(frame.resp ?: return emptyList())
}
