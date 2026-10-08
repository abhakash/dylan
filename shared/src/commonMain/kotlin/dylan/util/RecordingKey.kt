package dylan.util

import dylan.model.Song

/*
 * Catalog-level identity for one *recording*, not for one row.
 *
 * JioSaavn serves the same recording under several song ids (the album entry and the search
 * entry carry an identical MD5 — PROGRESS §4), so a list cannot dedupe on `SongKey` alone: title +
 * primary artist + exact duration is the triple that is stable across those ids.
 *
 * This lives in `commonMain` rather than in the UI file that first used it, for two reasons:
 *
 *  1. It is *tested* here. `androidApp` has no test source set and cannot get one without a
 *     dependency change to its build script, so the guarantee is pinned on the shared helper the
 *     Android file calls — the same shape `dylan.util.launchSettled` and `dylan.util.VersionedCell`
 *     took out of `ArtworkBitmapLoader` and `DylanMediaService` (see the resolution log in
 *     `docs/review-android-shared.md`, H5 and H3).
 *  2. The rule is domain logic, not presentation: "which characters may differ between two
 *     renditions of the same track" is a property of a multi-script catalogue, and this one is
 *     substantially Hindi, Arabic and Punjabi. Deciding that in a `ui/components` file is what
 *     allowed the defect below to survive for two audits.
 *
 * F-04 (was live, S2): the keep-set used to be `[^a-z0-9 ]` — ASCII-only — and ran *after*
 * `lowercase()`. A Devanagari, Arabic or CJK title and its artist therefore both normalised to
 * `""`, so the key degenerated to `"|" + durationS`. Two unrelated songs of the same duration then
 * shared one identity, which `containsRecording` reports as a match and HomeScreen obeys by
 * filtering a genuinely favourited track out of *Your favourites*.
 *
 * The fold is a normalisation, not an ASCII filter: keep every letter, digit and combining mark of
 * every script, plus the space, and drop only punctuation, symbols and format characters. Latin
 * behaviour is unchanged — for an ASCII title the two keep-sets are byte-for-byte the same, and
 * that is what `RecordingKeyTest.latinFoldingIsUnchangedForAsciiTitles` pins.
 *
 * F-05: both patterns are file-private `val`s, so nothing is compiled per call. The old shape
 * built four `Regex` objects on every invocation (two per field), which HomeScreen's favourites
 * check multiplied by F × (J + 1) — about 8 400 compiles at 100 favourites × 20 jump-back rows.
 */

/** Everything that survives the fold: any letter, any number, any combining mark, plus a space. */
private val KEEP_RECORDING_IDENTITY = Regex("[^\\p{L}\\p{N}\\p{M} ]")

/** Collapses a run of spaces down to one. Only spaces reach it — see [KEEP_RECORDING_IDENTITY]. */
private val SPACE_RUN = Regex("\\s+")

/** Fold one text field into the form the recording identity compares. */
internal fun recordingField(raw: String): String =
    raw
        .lowercase()
        .replace(KEEP_RECORDING_IDENTITY, "")
        .replace(SPACE_RUN, " ")
        .trim()

/**
 * The identity of one recording: `title|artist|durationS`, with both text fields folded by
 * [recordingField]. The `|` separator is load-bearing — it cannot appear in either folded field,
 * because it is punctuation and the fold drops punctuation — so a title can never borrow a
 * character from the artist column.
 */
fun recordingIdentity(
    title: String,
    artist: String,
    durationS: Long,
): String = "${recordingField(title)}|${recordingField(artist)}|$durationS"

/**
 * Catalog-level identity for one [Song]. The artist column falls back to the subtitle, which is
 * the only artist this app has for rows whose payload carried no `primary_artists` entry.
 */
fun Song.recordingKey(): String = recordingIdentity(title, artistName ?: subtitle, durationS)

/** One [Song] per distinct recording, in list order. */
fun List<Song>.distinctRecordings(): List<Song> = distinctBy { it.recordingKey() }

/** Whether [song] is the same recording as any element — not whether it is the same row. */
fun List<Song>.containsRecording(song: Song): Boolean = any { it.recordingKey() == song.recordingKey() }
