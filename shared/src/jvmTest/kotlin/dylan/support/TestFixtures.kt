package dylan.support

import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.LocalTrack

/**
 * Fixtures shaped exactly like the ones production builds: `WindowPreparer.localTrackFor` produces
 * `itemId = provider:songId:bitrate` and `durationHintMs = durationS * 1000`, and the provider maps
 * a `Song` with a non-blank `resolveRef`. A fixture that does *not* look like these is how a test
 * ends up exercising a shape the app never creates.
 */
fun testSong(
    id: String,
    durationS: Long = 100L,
    has320: Boolean = true,
    provider: String = "saavn",
): Song =
    Song(
        key = SongKey(provider, id),
        title = id,
        subtitle = "",
        albumId = null,
        albumName = null,
        artUrl150 = "",
        artUrl500 = "",
        durationS = durationS,
        has320 = has320,
        resolveRef = "enc-$id",
        permaToken = null,
    )

fun testTrack(
    itemId: String,
    durationMs: Long,
): LocalTrack = LocalTrack(itemId = itemId, path = "/tmp/$itemId.m4a", durationHintMs = durationMs, title = itemId)

fun testTracks(
    vararg itemIds: String,
    durationMs: Long = 10_000L,
): List<LocalTrack> = itemIds.map { testTrack(it, durationMs) }
