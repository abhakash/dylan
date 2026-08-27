package dylan.provider.saavn.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SongDto(
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val type: String? = null,
    @SerialName("perma_url") val permaUrl: String? = null,
    val image: JsonElement? = null,
    val language: String? = null,
    val year: JsonElement? = null,
    @SerialName("play_count") val playCount: JsonElement? = null,
    @SerialName("more_info") val moreInfo: MoreInfoDto? = null,
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
    @SerialName("song_count") val songCount: JsonElement? = null,
    @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
data class ResultsDto(
    val total: JsonElement? = null,
    val start: JsonElement? = null,
    val results: List<SongDto> = emptyList(),
)

@Serializable
data class AlbumDto(
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val image: JsonElement? = null,
    val year: JsonElement? = null,
    val list: List<SongDto> = emptyList(),
)

@Serializable
data class ArtistDto(
    @SerialName("artistId") val artistId: JsonElement? = null,
    val name: String = "",
    val subtitle: String? = null,
    val image: JsonElement? = null,
    val type: String? = null,
    @SerialName("topSongs") val topSongs: List<SongDto> = emptyList(),
)

@Serializable
data class AuthDto(
    @SerialName("auth_url") val authUrl: String? = null,
    val type: String? = null,
    val status: String? = null,
)

@Serializable
data class WsFrameDto(
    val action: String? = null,
    val resp: String? = null,
)
