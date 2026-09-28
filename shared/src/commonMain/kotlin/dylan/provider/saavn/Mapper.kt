package dylan.provider.saavn

import dylan.cache.CachePath
import dylan.model.Album
import dylan.model.Artist
import dylan.model.ErrorCode
import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Song
import dylan.model.SongKey
import dylan.provider.CatalogResult
import dylan.provider.Drift
import dylan.provider.Rows
import dylan.provider.UNKNOWN_DURATION_S
import dylan.provider.dylanJson
import dylan.provider.err
import dylan.provider.saavn.dto.AlbumDto
import dylan.provider.saavn.dto.ArtistDto
import dylan.provider.saavn.dto.MoreInfoDto
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import dylan.provider.saavn.dto.WsFrameDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val json = dylanJson

internal fun JsonElement?.str(): String? =
    when (this) {
        null, is JsonNull -> null
        else -> jsonPrimitive.contentOrNull
    }

fun art500(url: String?): String? {
    val u = url?.takeIf { it.isNotBlank() } ?: return null
    return if (u.contains("150x150")) u.replace("150x150", "500x500") else u
}
// ── cards ──────────────────────────────────────────────────────────────────────────────────────

/** Decode + map one card. Returns null for a card that cannot become a [Song]. */
internal fun mapCard(
    el: JsonElement,
    endpoint: String,
    into: MutableList<Drift>,
): Song? {
    val dto =
        el.toDto()
            ?: run {
                into += Drift(endpoint, CARD_DECODE, el.toString().take(DRIFT_DETAIL_CHARS))
                return null
            }
    val moreInfo =
        dto.moreInfo?.let {
            runCatching { json.decodeFromJsonElement(MoreInfoDto.serializer(), it) }.getOrNull()
        } ?: run {
            into += Drift(endpoint, NO_MORE_INFO, dto.id)
            return null
        }
    if (moreInfo.duration != null && parseDurationS(moreInfo.duration) == null) {
        // Not a drop: the card is playable. It is a contract fact, and it is the one the live probe
        // filters out (`songs.filter { it.durationS > 0 }`), so the harness is blind to it by design.
        into += Drift(endpoint, DURATION_UNPARSED, moreInfo.duration.toString().take(DRIFT_DETAIL_CHARS))
    }
    return mapSong(dto, moreInfo)
}

fun mapSong(
    d: SongDto,
    moreInfo: MoreInfoDto? =
        d.moreInfo?.let { runCatching { json.decodeFromJsonElement(MoreInfoDto.serializer(), it) }.getOrNull() },
): Song? {
    if (d.id.isBlank() || d.title.isBlank()) return null
    val mi = moreInfo ?: return null
    // NOTE: more_info.rights (incl. the legacy "cacheable" flag) is deliberately NOT mapped:
    // post-cacheable-removal every mapped song is treated as downloadable, and resolveRef presence
    // (not rights) decides NO_SOURCE vs resolvable downstream. The DTO keeps `rights` so lenient
    // parsing of live payloads is unaffected.
    val img150 =
        d.image
            .str()
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
    val primary = primaryArtist(mi.artistMap)
    return Song(
        key = SongKey("saavn", CachePath.segment(d.id)),
        title = d.title.trim(),
        subtitle = d.subtitle.orEmpty(),
        albumId = mi.albumId,
        albumName = mi.album,
        artUrl150 = img150,
        artUrl500 = art500(img150) ?: img150,
        durationS = parseDurationS(mi.duration) ?: UNKNOWN_DURATION_S,
        has320 = coerceHas320(mi.has320),
        resolveRef = mi.encryptedMediaUrl?.takeIf { it.isNotBlank() },
        permaToken = normalizePermaToken(d.permaUrl),
        artistName = primary?.name,
        artistToken = primary?.token,
    )
}

/** One card, one decode attempt. A card that will not decode costs only itself. */
private fun JsonElement.toDto(): SongDto? {
    val serializer = SongDto.serializer()
    return runCatching { json.decodeFromJsonElement(serializer, this) }.getOrNull()
}

/**
 * One card to a [MiniEntity], with its navigable type.
 *
 * The perma token is *required* for albums and artists. It used to fall back to the numeric id,
 * which mints an id the API cannot resolve: `webapi.get&token=610240&type=artist` answers 200 with
 * `{"artistId":null,"name":"","topSongs":[]}` and the screen renders "Check your connection"
 * (verified live 2026-09-28, and the reason `artistRequest` has no numeric branch).
 */
fun mapMini(d: SongDto): MiniEntity? {
    if (d.id.isBlank() || d.title.isBlank()) return null
    val type = d.type ?: TYPE_SONG
    return when (type) {
        TYPE_SONG -> mapMiniOf(type, d, CachePath.segment(d.id))
        TYPE_ALBUM -> permaAlbumToken(d.permaUrl)?.let { mapMiniOf(type, d, it) }
        TYPE_ARTIST -> permaArtistToken(d.permaUrl)?.let { mapMiniOf(type, d, it) }
        // playlist / show / episode: not navigable in this app, but they must still carry a stable,
        // non-empty identity — a row keyed on "" collides with every other such row in a
        // LazyColumn and takes the process down with a duplicate-key crash.
        else -> mapMiniOf(type, d, CachePath.segment(d.id))
    }
}

private fun mapMiniOf(
    type: String,
    d: SongDto,
    identity: String,
): MiniEntity =
    MiniEntity(
        songKey = if (type == TYPE_SONG) SongKey("saavn", identity) else null,
        albumId = if (type == TYPE_ALBUM) identity else null,
        artistId = if (type == TYPE_ARTIST) identity else null,
        title = d.title.trim(),
        subtitle = d.subtitle.orEmpty(),
        type = type,
        image = d.image.str().orEmpty(),
        permaToken = d.permaUrl,
    )

/** Why a mini card was dropped. */
internal fun miniDropReason(d: SongDto): String =
    when (d.type ?: TYPE_SONG) {
        TYPE_ALBUM -> if (permaAlbumToken(d.permaUrl) == null) NO_PERMA_TOKEN else ""
        TYPE_ARTIST -> if (permaArtistToken(d.permaUrl) == null) NO_PERMA_TOKEN else ""
        else -> ""
    }

// ── pages ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Element-wise page decode. One unusable card costs one card; the envelope's `total`/`start` are
 * read from the same tree so no second parse happens.
 */
fun decodeSongPage(
    text: String,
    endpoint: String,
    fallbackPage: Int,
): Rows<Paged<Song>> {
    val root =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Rows(Paged(emptyList(), 0L, fallbackPage), bodyNotJson(endpoint, text))
    val drift = mutableListOf<Drift>()
    val songs = root.resultsArray().mapNotNull { mapCard(it, endpoint, drift) }
    return Rows(
        Paged(
            items = songs,
            total = root["total"].str()?.toLongOrNull() ?: songs.size.toLong(),
            page = root["start"].str()?.toIntOrNull() ?: fallbackPage,
        ),
        drift,
    )
}

fun decodeMiniPage(
    text: String,
    wantType: String,
    endpoint: String,
    fallbackPage: Int,
): Rows<Paged<MiniEntity>> {
    val root =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Rows(Paged(emptyList(), 0L, fallbackPage), bodyNotJson(endpoint, text))
    val drift = mutableListOf<Drift>()
    val raw = root.resultsArray()
    val items =
        raw
            .mapNotNull { el ->
                val dto =
                    el.toDto()
                        ?: run {
                            drift += Drift(endpoint, CARD_DECODE, el.toString().take(DRIFT_DETAIL_CHARS))
                            return@mapNotNull null
                        }
                val why = miniDropReason(dto)
                if (why.isNotEmpty()) {
                    drift += Drift(endpoint, why, "${dto.type}:${dto.id}")
                    null
                } else {
                    mapMini(dto)
                }
            }.filter { it.type == wantType }
            .distinctBy { dedupKey(it) }
    val start = root["start"].str()?.toIntOrNull() ?: fallbackPage
    return Rows(Paged(items, deliveredTotal(start, raw.size, items.size), start), drift)
}

/**
 * A total the UI can actually make progress against.
 *
 * The envelope's `total` counts the *mixed* result set, so reporting it while delivering only the
 * wanted-type subset is what made `hasMore` true forever ("Show more" re-requesting the same pages
 * indefinitely). This reports "everything up to and including this page, plus one if this page was
 * full" — which converges exactly when the origin stops returning full pages.
 */
internal fun deliveredTotal(
    start: Int,
    rawPageSize: Int,
    kept: Int,
): Long {
    val before = (start - 1).coerceAtLeast(0).toLong() * rawPageSize.toLong()
    val more = if (kept >= rawPageSize && rawPageSize > 0) 1L else 0L
    return before + kept.toLong() + more
}

/** A body that is not a JSON object: a drift, and an empty page — never a bare empty list. */
private fun bodyNotJson(
    endpoint: String,
    text: String,
): List<Drift> = listOf(Drift(endpoint, BODY_NOT_JSON, text.take(DRIFT_DETAIL_CHARS)))

private fun JsonObject.resultsArray(): List<JsonElement> = (this["results"] as? JsonArray).orEmpty()

/**
 * Fixture/tool entry point over an already-typed envelope. **Not** the network path: decoding
 * `List<SongDto>` is all-or-nothing, which is the "one bad card discards the page" defect. The
 * provider uses [decodeSongPage]; this exists for the fixture and contract-drift layer, which wants
 * typed cards and owns its own decode tolerance.
 */
fun mapPaged(r: ResultsDto): Paged<Song> =
    Paged(
        r.results.mapNotNull { mapSong(it) },
        r.total.str()?.toLongOrNull() ?: 0L,
        r.start.str()?.toIntOrNull() ?: 1,
    )

/** Album/artist submit results share the {total,start,results} envelope; keep only the wanted type. */
fun mapMiniPaged(
    r: ResultsDto,
    wantType: String,
    fallbackPage: Int,
): Paged<MiniEntity> {
    val items =
        r.results
            .mapNotNull { mapMini(it) }
            .filter { it.type == wantType }
            .distinctBy { dedupKey(it) }
    val start = r.start.str()?.toIntOrNull() ?: fallbackPage
    return Paged(items, deliveredTotal(start, r.results.size, items.size), start)
}

fun mapAlbum(
    a: AlbumDto,
    drift: MutableList<Drift> = mutableListOf(),
): Album? {
    if (a.id.isBlank()) return null
    val songs = a.list.mapNotNull { mapCard(it, ENDPOINT_ALBUM, drift) }
    return Album(
        id = a.id,
        title = a.title.trim(),
        subtitle = a.subtitle,
        artUrl150 = a.image.str().orEmpty(),
        artUrl500 = art500(a.image.str()).orEmpty(),
        year = a.year.str(),
        songs = songs,
    )
}

fun mapArtist(
    a: ArtistDto,
    drift: MutableList<Drift> = mutableListOf(),
): Artist? {
    if (a.name.isBlank()) return null
    val img150 = a.image.str().orEmpty()
    return Artist(
        id = a.artistId.str().orEmpty(),
        name = a.name.trim(),
        subtitle = a.subtitle,
        artUrl150 = img150,
        artUrl500 = art500(img150) ?: img150,
        songs = a.topSongs.mapNotNull { mapCard(it, ENDPOINT_ARTIST, drift) },
    )
}

/**
 * Element-wise album decode.
 *
 * A 200 that decodes into a *blank* album is `NOT_FOUND`, not `Ok(blank)`: that empty shell is
 * exactly the "Check your connection" screen, and reporting it as a success is what let it happen
 * unnoticed.
 */
fun decodeAlbum(
    text: String,
    endpoint: String,
): CatalogResult<Album> {
    val dto =
        runCatching { json.decodeFromString(AlbumDto.serializer(), text) }.getOrNull()
            ?: return err(ErrorCode.DRIFT, "$endpoint: undecodable", retryable = false)
    val drift = mutableListOf<Drift>()
    val album =
        mapAlbum(dto, drift)
            ?: return err(ErrorCode.NOT_FOUND, "$endpoint: empty shell id='${dto.id}'", retryable = false)
    return CatalogResult.Ok(album, drift)
}

/** Element-wise artist decode; a name-less payload is the same 200 empty shell, same verdict. */
fun decodeArtist(
    text: String,
    endpoint: String,
): CatalogResult<Artist> {
    val dto =
        runCatching { json.decodeFromString(ArtistDto.serializer(), text) }.getOrNull()
            ?: return err(ErrorCode.DRIFT, "$endpoint: undecodable", retryable = false)
    val drift = mutableListOf<Drift>()
    val artist =
        mapArtist(dto, drift)
            ?: return err(ErrorCode.NOT_FOUND, "$endpoint: empty shell", retryable = false)
    return CatalogResult.Ok(artist, drift)
}

/** Element-wise list decode for `content.getTopSearches` / `content.getTrending`. */
fun decodeMinis(
    text: String,
    endpoint: String,
): Rows<List<MiniEntity>> {
    val arr =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
            ?: return Rows<List<MiniEntity>>(emptyList(), bodyNotJson(endpoint, text))
    val drift = mutableListOf<Drift>()
    val items =
        arr
            .mapNotNull { el ->
                val dto =
                    el.toDto()
                        ?: run {
                            drift += Drift(endpoint, CARD_DECODE, el.toString().take(DRIFT_DETAIL_CHARS))
                            return@mapNotNull null
                        }
                val why = miniDropReason(dto)
                if (why.isNotEmpty()) {
                    drift += Drift(endpoint, why, "${dto.type}:${dto.id}")
                    null
                } else {
                    mapMini(dto)
                }
            }.distinctBy { dedupKey(it) }
    return Rows(items, drift)
}

// ── autocomplete ──────────────────────────────────────────────────────────────────────────────

/**
 * Suggestion payload, in **both** shapes the origin ships.
 *
 * The WebSocket frame nests its buckets as top-level arrays (`data_0`, `data_1`, …) while the
 * plain-HTTP `autocomplete.get` answers `{"albums":{"data":[…]},"songs":{"data":[…]},…}`
 * (verified live 2026-09-28). The old code only looked at top-level arrays, so the HTTP fallback —
 * the path taken on every WS timeout — parsed to *nothing* and rendered as "no suggestions". The
 * `modules` array (titles, not cards) is skipped by shape, not by name.
 */
fun mapSuggestionPayload(
    innerJson: String,
    endpoint: String = ENDPOINT_SUGGEST,
): Rows<List<MiniEntity>> {
    val root =
        runCatching { json.parseToJsonElement(innerJson) }.getOrNull() as? JsonObject
            ?: return Rows<List<MiniEntity>>(emptyList(), bodyNotJson(endpoint, innerJson))
    val drift = mutableListOf<Drift>()
    val items =
        root
            .cardBuckets()
            .mapNotNull { it.toMini(endpoint, drift) }
            .distinctBy { dedupKey(it) }
    return Rows(items, drift)
}

private fun JsonObject.cardBuckets(): List<JsonElement> {
    val out = mutableListOf<JsonElement>()
    values.forEach { v ->
        when (v) {
            is JsonArray -> out.addAll(v)
            // HTTP shape: {"albums": {"data": [ … ], "position": 1}}
            is JsonObject -> (v["data"] as? JsonArray)?.let { out.addAll(it) }
            else -> Unit
        }
    }
    return out
}

/** Decode + map one card to a mini, with drift. */
private fun JsonElement.toMini(
    endpoint: String,
    drift: MutableList<Drift>,
): MiniEntity? {
    val dto =
        toDto()
            ?: run {
                drift += Drift(endpoint, CARD_DECODE, toString().take(DRIFT_DETAIL_CHARS))
                return null
            }
    val why = miniDropReason(dto)
    if (why.isNotEmpty()) {
        drift += Drift(endpoint, why, "${dto.type}:${dto.id}")
        return null
    }
    return mapMini(dto)
}

/**
 * A whole WebSocket frame.
 *
 * Two things the old version got wrong, both of which made *every* keystroke yield "no
 * suggestions" with nothing in the log:
 *  1. `resp` was typed `String`; the object shape threw on the frame.
 *  2. `action` was parsed and never read, so a keepalive frame was rendered as a result set.
 */
fun mapSuggestions(
    frameJson: String,
    endpoint: String = ENDPOINT_SUGGEST_WS,
): Rows<List<MiniEntity>> {
    val frame =
        runCatching { json.decodeFromString(WsFrameDto.serializer(), frameJson) }.getOrNull()
            ?: return Rows(
                emptyList<MiniEntity>(),
                listOf(Drift(endpoint, FRAME_NOT_JSON, frameJson.take(DRIFT_DETAIL_CHARS))),
            )
    if (frame.action != null && frame.action != ACTION_SEARCH) {
        return Rows(emptyList(), listOf(Drift(endpoint, FRAME_NOT_SEARCH, frame.action)))
    }
    val resp = frame.resp ?: return Rows(emptyList(), listOf(Drift(endpoint, FRAME_NO_RESP)))
    // Both shapes: a nested JSON string (what the origin sends) and an inline object.
    val inner =
        when (resp) {
            is JsonPrimitive -> resp.content
            is JsonObject, is JsonArray -> resp.toString()
            else -> return Rows(emptyList(), listOf(Drift(endpoint, FRAME_NO_RESP)))
        }
    return mapSuggestionPayload(inner, endpoint)
}

/** Stable [Drift] reasons. Tests assert on these strings, so they are part of the contract. */
internal const val CARD_DECODE = "CARD_DECODE"
internal const val NO_MORE_INFO = "NO_MORE_INFO"
internal const val NO_PERMA_TOKEN = "NO_PERMA_TOKEN"
internal const val DURATION_UNPARSED = "DURATION_UNPARSED"
internal const val BODY_NOT_JSON = "BODY_NOT_JSON"
internal const val FRAME_NOT_JSON = "FRAME_NOT_JSON"
internal const val FRAME_NOT_SEARCH = "FRAME_NOT_SEARCH"
internal const val FRAME_NO_RESP = "FRAME_NO_RESP"

internal const val TYPE_SONG = "song"
internal const val TYPE_ALBUM = "album"
internal const val TYPE_ARTIST = "artist"
internal const val ACTION_SEARCH = "search"
internal const val ENDPOINT_ALBUM = "content.getAlbumDetails"
internal const val ENDPOINT_ARTIST = "webapi.get"
internal const val ENDPOINT_SUGGEST = "autocomplete.get"
internal const val ENDPOINT_SUGGEST_WS = "ws.autocomplete.get"
private const val DRIFT_DETAIL_CHARS = 120
