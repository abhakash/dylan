package dylan.provider.saavn.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A catalog card.
 *
 * [image], [year], [playCount] and [moreInfo] are deliberately *untyped* on the DTO so a card
 * decodes at all. Every field the live API has ever changed shape on (image: string → object →
 * null; year: number → string; 320kbps: bool → number → string) is coerced at the mapper instead
 * of at decode time, and [moreInfo] is decoded element-wise from the result list.
 */
@Serializable
data class SongDto(
    val id: String = "",
    val title: String = "",
    /**
     * The card's name, which is where an **artist** card carries it.
     *
     * The live `search.getArtistResults` response (captured 2026-10-09, 20/20 cards) ships
     * `{"name":"Arijit Singh","id":"459320","perma_url":…,"type":"artist"}` and **no `title` at
     * all**. A DTO without `name` therefore decoded every artist card with a blank title, and
     * `mapMini` — which guarded on `title` — dropped all of them: the artist section rendered
     * empty with `EMPTY_MAPPING` as the only trace, and the live drift gate reported it.
     *
     * Present on [SongDto] rather than on a separate DTO because the three card types share one
     * decoder on purpose (`toDto`): a second type would be a second decode path to keep in step,
     * and the field is inert on song and album cards, which never carry it.
     */
    val name: String = "",
    val subtitle: String? = null,
    val type: String? = null,
    @SerialName("perma_url") val permaUrl: String? = null,
    val image: JsonElement? = null,
    val language: String? = null,
    val year: JsonElement? = null,
    @SerialName("play_count") val playCount: JsonElement? = null,
    @SerialName("more_info") val moreInfo: JsonElement? = null,
)

@Serializable
data class MoreInfoDto(
    val music: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    val album: String? = null,
    @SerialName("320kbps") val has320: JsonElement? = null,
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String? = null,
    val duration: JsonElement? = null,
    val rights: JsonElement? = null,
    @SerialName("artistMap") val artistMap: JsonElement? = null,
    @SerialName("song_count") val songCount: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
)

/**
 * The `{total,start,results}` envelope, **typed**.
 *
 * Deliberately *not* the shape the network path uses. A `List<SongDto>` decode is all-or-nothing:
 * one card with a field the decoder dislikes discards the whole 20-card page, which is the
 * "Songs 0 of 0" defect. So the provider never decodes this — it reads the envelope as a
 * `JsonObject` and decodes each card separately (`decodeSongPage` / `decodeMiniPage` in `Mapper.kt`).
 *
 * This type is kept for the fixture and contract-drift layer, which wants typed cards
 * (`jvmTest/tools/ContractDrift.kt` reads `dto.results.map { it.id }`). It is also the control in
 * `SaavnProviderTest`: the same payload that costs one card on the network path is fatal here, and
 * that difference is the whole point.
 */
@Serializable
data class ResultsDto(
    val total: JsonElement? = null,
    val start: JsonElement? = null,
    val results: List<SongDto> = emptyList(),
)

/** [list] is element-wise for the same reason as [ResultsDto.results]. */
@Serializable
data class AlbumDto(
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val image: JsonElement? = null,
    val year: JsonElement? = null,
    val list: List<JsonElement> = emptyList(),
)

@Serializable
data class ArtistDto(
    @SerialName("artistId") val artistId: JsonElement? = null,
    val name: String = "",
    val subtitle: String? = null,
    val image: JsonElement? = null,
    val type: String? = null,
    @SerialName("topSongs") val topSongs: List<JsonElement> = emptyList(),
)

@Serializable
data class AuthDto(
    @SerialName("auth_url") val authUrl: String? = null,
    val type: String? = null,
    val status: String? = null,
)

/**
 * A WebSocket frame.
 *
 * [resp] is [JsonElement], not `String`, because the server has shipped the payload both ways
 * (verified: `fixtures/autocomplete_ws_frame.json` carries it as a nested JSON *string*). Typed as
 * `String` the object shape throws on the whole frame, so every keystroke yields "no suggestions"
 * — with no log line distinguishing it from a genuinely empty result.
 *
 * [action] is read: a keepalive / non-`search` frame is not a result set and must not be rendered.
 */
@Serializable
data class WsFrameDto(
    val action: String? = null,
    val resp: JsonElement? = null,
)
