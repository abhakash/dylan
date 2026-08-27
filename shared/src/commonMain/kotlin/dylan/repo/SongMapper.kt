package dylan.repo

import dylan.db.Songs
import dylan.model.Song
import dylan.model.SongKey

/**
 * Single shared songs-table <-> model mapper.
 *
 * Repos, IosGraph.libraryDownloads and Android LibraryScreen all route through here so
 * row interpretation (notably has_320 and nullable resolve_ref/perma_token) can never
 * drift between surfaces again. Public (not internal) so the androidApp module can call
 * it — internal would be invisible across the module boundary.
 *
 * Orchestrator keeps its own private toSong (playback-agent owned); do not consolidate
 * without that agent.
 */
fun Songs.toSong(): Song =
    Song(
        key = SongKey(provider, song_id),
        title = title,
        subtitle = subtitle,
        albumId = album_id,
        albumName = album_name,
        artUrl150 = art_url_150,
        artUrl500 = art_url_500,
        durationS = duration_s,
        has320 = has_320 == 1L,
        resolveRef = resolve_ref,
        permaToken = perma_token,
    )

/** Ordered params for the explicit-column songs INSERTs (see dylan.sq insertSong). */
data class SongRowParams(
    val provider: String,
    val songId: String,
    val title: String,
    val subtitle: String,
    val albumId: String?,
    val albumName: String?,
    val artUrl150: String,
    val artUrl500: String,
    val durationS: Long,
    val has320: Long,
    val resolveRef: String?,
    val permaToken: String?,
    val updatedAtMs: Long,
)

fun Song.toRow(nowMs: Long): SongRowParams =
    SongRowParams(
        provider = key.provider,
        songId = key.songId,
        title = title,
        subtitle = subtitle,
        albumId = albumId,
        albumName = albumName,
        artUrl150 = artUrl150,
        artUrl500 = artUrl500,
        durationS = durationS,
        has320 = if (has320) 1L else 0L,
        resolveRef = resolveRef,
        permaToken = permaToken,
        updatedAtMs = nowMs,
    )
