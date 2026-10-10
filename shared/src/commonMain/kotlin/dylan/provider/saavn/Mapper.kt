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

private val json = dylanJson

/**
 * A primitive's content, or null. **Total by construction**: `jsonPrimitive` *throws* on an object
 * or an array, and this function is called on fields the API has shipped in more than one shape
 * (`image`, `year`, `duration`, `total`, `start`), so a `{"150x150": …}` object used to escape the
 * whole mapper as an `IllegalArgumentException` — out of `decodeSongPage`, out of `searchPage`,
 * through `MusicProvider`'s `CatalogResult` contract, into whatever the UI had. Nothing in this file
 * may throw on catalog data; an unusable field is a field that is not there.
 */
internal fun JsonElement?.str(): String? =
    when (this) {
        null, is JsonNull -> null
        else -> (this as? JsonPrimitive)?.contentOrNull
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
    val song = mapSong(dto, moreInfo)
    if (song == null) {
        // `coerceInputValues` means a `"id": null` no longer fails the *decode* — it becomes `""`,
        // and the card then died in `mapSong`'s identity guard with nothing recorded. The page was
        // still short a row and the drift list still said the page was whole, which is the one thing
        // `Ok(drift)` exists to make impossible.
        into += Drift(endpoint, BLANK_IDENTITY, "id='${dto.id}' title='${dto.title.take(DRIFT_DETAIL_CHARS)}'")
    }
    return song
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
        artUrlOf(d.image)
            .takeIf { it.isNotBlank() }
            .orEmpty()
    val primary = primaryArtist(mi.artistMap)
    return Song(
        key = SongKey("saavn", CachePath.segment(d.id)),
        title = displayTitle(d.title),
        subtitle = displaySubtitle(d.subtitle),
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

/**
 * One card, one decode attempt. A card that will not decode costs only itself.
 */
private fun JsonElement.toDto(): SongDto? {
    val serializer = SongDto.serializer()
    return runCatching { json.decodeFromJsonElement(serializer, this) }.getOrNull()
}

/**
 * The card's display identity: its **name** when it has one, its `title` otherwise.
 *
 * The one place both spellings are reconciled, so the two callers that need a non-empty identity
 * ([mapMini] and [miniDropReason]) cannot disagree about which field a card's identity lives in.
 *
 * `name` outranks `title` and the fallback runs **one way only**. Reversing it would be the
 * original defect again: an artist card has no `title`, so a `title`-first lookup answers "blank"
 * and drops the row. Nothing typed `song` or `album` has ever been observed carrying `name`, so
 * preferring it cannot take a title away from a card that has one; and if the origin ever starts
 * shipping `name` on song cards, this is the field it will ship, because it is the field it ships
 * on the artist cards that already exist.
 *
 * Runs through [displayTitle] for the same reason [mapMiniOf] does: entity decoding and trimming
 * belong at the boundary, not in a composable.
 */
internal fun SongDto.cardTitle(): String = displayTitle(name.ifBlank { title })

/**
 * One card to a [MiniEntity], with its navigable type.
 *
 * The perma token is *required* for albums and artists. It used to fall back to the numeric id,
 * which mints an id the API cannot resolve: `webapi.get&token=610240&type=artist` answers 200 with
 * `{"artistId":null,"name":"","topSongs":[]}` and the screen renders "Check your connection"
 * (verified live 2026-09-28, and the reason `artistRequest` has no numeric branch).
 */
fun mapMini(d: SongDto): MiniEntity? {
    if (d.id.isBlank() || d.cardTitle().isBlank()) return null
    val type = d.type ?: TYPE_SONG
    return when (type) {
        TYPE_SONG -> mapMiniOf(type, d, CachePath.segment(d.id))
        TYPE_ALBUM -> permaAlbumToken(d.permaUrl)?.let { mapMiniOf(type, d, it) }
        TYPE_ARTIST -> permaArtistToken(d.permaUrl)?.let { mapMiniOf(type, d, it) }
        // Everything else is a catalog entity this app has no screen for, and — for shows and
        // episodes — no media path either. The live autocomplete response carries `playlist`, `show`
        // and `episode` as their own sections (captured 2026-10-09, fixtures/autocomplete_podcast.json:
        // `shows` 3/3 type=show, `playlists` 3/3 type=playlist, `episodes` empty in 6 queries). Shows
        // resolve their audio through `encrypted_media_url`, not the song path, so they could not
        // play even behind a screen.
        //
        // It is an allowlist rather than a blocklist on purpose: a type nobody has seen yet is
        // dropped by default instead of reaching the list as a dead, dimmed, un-openable row — which
        // is exactly how the reported "greyed out results" got there.
        //
        // Keep this in step with [miniDropReason]: both answer the same question about the same card,
        // and the two disagreeing once is what produced the artist bug.
        else -> null
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
        title = displayTitle(d.cardTitle()),
        subtitle = displaySubtitle(d.subtitle),
        type = type,
        image = artUrlOf(d.image),
        permaToken = d.permaUrl,
    )

/**
 * Why a mini card was dropped, or `""` for a card that maps.
 *
 * This is the *whole* answer, not just the perma-token case: a card with no id and no title also
 * returns null from [mapMini], and it used to reach the caller as a silently missing row — the
 * `Ok(drift)` contract says a short page is a page that says so.
 *
 * The blank-identity test reads [cardTitle], not `title`, so it can never classify a card as
 * droppable that [mapMini] would have kept (or the reverse). That is the whole reason
 * [cardTitle] exists: before it, `miniDropReason` answered `BLANK_IDENTITY` for every artist card
 * while the live payload was sitting right there with a `name` on it.
 */
internal fun miniDropReason(d: SongDto): String =
    when {
        d.id.isBlank() || d.cardTitle().isBlank() -> BLANK_IDENTITY
        else ->
            when (val type = d.type ?: TYPE_SONG) {
                TYPE_SONG -> ""
                TYPE_ALBUM -> if (permaAlbumToken(d.permaUrl) == null) NO_PERMA_TOKEN else ""
                TYPE_ARTIST -> if (permaArtistToken(d.permaUrl) == null) NO_PERMA_TOKEN else ""
                // Mirrors [mapMini]'s allowlist. A dropped card reports *why* instead of arriving at
                // the caller as a silently missing row, per the `Ok(drift)` contract.
                else -> UNSUPPORTED_TYPE
            }
    }

/**
 * The detail of a dropped mini card: which card, and **which field was unusable**.
 *
 * It was `"artist:610240"` — type and id — which cannot tell an absent `perma_url` from an empty
 * one from a shape nobody has seen. Those are three different bugs with three different fixes, and
 * the search screen logs this string, so the difference is the whole diagnostic.
 */
private fun dropDetail(d: SongDto): String {
    val perma = d.permaUrl ?: ABSENT_FIELD
    return "${d.type ?: TYPE_SONG}:${d.id} perma_url=$perma".take(DRIFT_DETAIL_CHARS)
}

// ── pages ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Element-wise page decode. One unusable card costs one card; the envelope's `total`/`start` are
 * read from the same tree so no second parse happens.
 *
 * Renditions of one track are folded here, at the page boundary, and each fold is drift rather than
 * a silent omission: this origin mints a different songId per album, so three albums of one track
 * arrive as three distinct `SongKey`s and would otherwise render as three rows — the defect
 * `RenditionIdentity.kt` exists to fix. Folding at the boundary rather than only in the search list
 * also means the search list's cross-page fold only has to handle a rendition repeated *between*
 * pages, and `SaavnProviderTest.mappingOnePageCostsTheStateLaneThisMuch` (which replicates one card
 * four times) still sees all four arrive at the fold.
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
    val cards = root.resultsArray().mapNotNull { mapCard(it, endpoint, drift) }
    val songs = foldRenditions(cards, endpoint, drift)
    return Rows(
        Paged(
            items = songs,
            total = root["total"].str()?.toLongOrNull() ?: songs.size.toLong(),
            page = root["start"].str()?.toIntOrNull() ?: fallbackPage,
        ),
        drift,
    )
}

/**
 * Album/artist submit results share the {total,start,results} envelope; keep only the wanted type.
 *
 * An **error envelope** — `{"error":{"code":…,"msg":…}}`, what this origin answers when an endpoint
 * is unsupported or the caller is geo-blocked — is valid JSON, so it used to decode as an empty
 * page: zero rows, zero drift, no error. An empty section was therefore indistinguishable from a
 * query with no matches, which is the state issue #9 was reported in and the reason its cause could
 * not be established from the app at all. It is [ORIGIN_ERROR] now.
 */
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
    root.originErrorDrift(endpoint)?.let { drift += it }
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
                    drift += Drift(endpoint, why, dropDetail(dto))
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
        title = displayTitle(a.title),
        subtitle = a.subtitle,
        artUrl150 = artUrlOf(a.image),
        artUrl500 = art500(artUrlOf(a.image)).orEmpty(),
        year = a.year.str(),
        songs = songs,
    )
}

fun mapArtist(
    a: ArtistDto,
    drift: MutableList<Drift> = mutableListOf(),
): Artist? {
    if (a.name.isBlank()) return null
    val img150 = artUrlOf(a.image)
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
                    drift += Drift(endpoint, why, dropDetail(dto))
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
        drift += Drift(endpoint, why, dropDetail(dto))
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

/**
 * Stable [Drift] reasons. Tests assert on these strings, so they are part of the contract.
 *
 * `ORIGIN_ERROR` and `RENDITION_DUPLICATE` are declared with the code that produces them, in
 * `PageEnvelope.kt` and `RenditionIdentity.kt` respectively.
 */
internal const val CARD_DECODE = "CARD_DECODE"
internal const val NO_MORE_INFO = "NO_MORE_INFO"
internal const val NO_PERMA_TOKEN = "NO_PERMA_TOKEN"
internal const val BLANK_IDENTITY = "BLANK_IDENTITY"

/** A card whose `type` this app has no screen and no media path for: `playlist`, `show`, `episode`. */
internal const val UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"

internal const val DURATION_UNPARSED = "DURATION_UNPARSED"
internal const val BODY_NOT_JSON = "BODY_NOT_JSON"
internal const val FRAME_NOT_JSON = "FRAME_NOT_JSON"
internal const val FRAME_NOT_SEARCH = "FRAME_NOT_SEARCH"
internal const val FRAME_NO_RESP = "FRAME_NO_RESP"

/** What a [Drift] detail prints for a field the payload did not carry at all. */
private const val ABSENT_FIELD = "<null>"

internal const val TYPE_SONG = "song"
internal const val TYPE_ALBUM = "album"
internal const val TYPE_ARTIST = "artist"
internal const val ACTION_SEARCH = "search"
internal const val ENDPOINT_ALBUM = "content.getAlbumDetails"
internal const val ENDPOINT_ARTIST = "webapi.get"
internal const val ENDPOINT_SUGGEST = "autocomplete.get"
internal const val ENDPOINT_SUGGEST_WS = "ws.autocomplete.get"

/**
 * Not `private`: the drift detail cap is shared by the mapper and by the envelope and rendition
 * files it now shares the package with, and three copies of `120` would be three chances to disagree.
 */
internal const val DRIFT_DETAIL_CHARS = 120
