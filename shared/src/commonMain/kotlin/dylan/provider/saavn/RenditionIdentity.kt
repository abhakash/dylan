package dylan.provider.saavn

import dylan.model.Song
import dylan.provider.Drift

/*
 * Track identity — one recording, however many keys the origin mints for it.
 *
 * Split out of `Mapper.kt` deliberately: this is a **list policy** about which rows are the same
 * track, where the mapper is about what one card is. It runs at two levels and neither alone is
 * enough — inside one page ([decodeSongPage]) and across pages by the search list, whose held rows
 * outlive any single page.
 */

/**
 * One identity for the **track**, where [Song.key] identifies one **rendition** of it.
 *
 * `SongKey` is `(provider, songId)`, and this origin mints a *different songId per album*: searching
 * `Eminem` returned `Mockingbird` from Encore, from the Mockingbird single and from Curtain Call as
 * three distinct keys, so one track occupied three rows and spent three rows of page budget — which
 * is also why "Show more" looked like it had loaded nothing new.
 *
 * The key is normalised title + primary artist + duration, and each part is load-bearing:
 *
 *  - **title**, through [normTitle], because the payload ships titles with trailing spaces and the UI
 *    uppercases them, so the raw string is not even stable within one page;
 *  - **artist**, the name when there is one and the perma token otherwise. Two unrelated songs can
 *    share a title; far fewer share a title *and* an artist;
 *  - **duration**, in whole seconds. This is what separates two genuinely different recordings that
 *    share a title and an artist — a live cut runs seconds longer than the studio one — so the key
 *    is deliberately not title+artist alone.
 *
 * Exact seconds rather than a tolerance: renditions of one master carry the same integer duration,
 * and a ±1 s bucket would collapse precisely the near-identical pairs the duration exists to keep
 * apart. `TrackIdentityTest` pins both sides of that decision, so revisiting it is a change of
 * mind rather than an accident.
 *
 * **Null means "not identifiable", and null is never collapsed.** A card with no usable title, or
 * with no artist at all, keeps its row: a duplicate row is the bug being fixed, but a song that
 * disappears is worse, and title+duration with no artist is exactly where that would happen.
 *
 * Known limitation: a rendition whose *title* the origin rewrote per album does not collapse —
 * `fixtures/search_getresults_p1.json` carries `Yeh Awarapan` and `Yeh Awarapan (From "Awarapan 2")`,
 * same 298 s, same artist, two ids. No title-based key sees through a rewritten title.
 */
fun trackIdentity(song: Song): String? {
    val title = normTitle(song.title)
    if (title.isEmpty()) return null
    val artist =
        song.artistName?.let(::normTitle)?.takeIf { it.isNotEmpty() }
            ?: song.artistToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    // NUL as the separator, not '|': the key is only ever compared, and a title or an artist name
    // may legitimately contain any printable character including a pipe.
    return "$title\u0000$artist\u0000${song.durationS}"
}

/** The fold plus the rows it removed, so every removal can be recorded as the drop it is. */
private class RenditionFold(
    val kept: List<Song>,
    val collapsed: List<Song>,
)

/**
 * One row per [trackIdentity]: the first rendition of each survives, later ones are dropped.
 *
 * Order-preserving. The first *is* kept rather than the "best" one because there is no better: the
 * renditions differ only in album, and the first is the one the origin ranked highest. A card with
 * no identity ([trackIdentity] returning null) is never collapsed, and never appears in
 * [RenditionFold.collapsed] either — it was not a duplicate, it was simply not identifiable.
 */
private fun fold(songs: List<Song>): RenditionFold {
    val seen = HashSet<String>(songs.size)
    val kept = ArrayList<Song>(songs.size)
    val collapsed = ArrayList<Song>()
    songs.forEach { song ->
        val id = trackIdentity(song)
        if (id == null || seen.add(id)) kept += song else collapsed += song
    }
    return RenditionFold(kept, collapsed)
}

/**
 * One row per track, with every collapsed rendition recorded as drift on [into].
 *
 * This is the page-boundary half of the policy, and it is what [decodeSongPage] calls: folding here
 * means a collapse is visible as drift in the decode's own `Rows`, and the cross-page fold
 * ([dedupeRenditions], which has nowhere to put drift) only has to handle a rendition repeated
 * *between* pages.
 */
internal fun foldRenditions(
    songs: List<Song>,
    endpoint: String,
    into: MutableList<Drift>,
): List<Song> {
    val folded = fold(songs)
    folded.collapsed.forEach { into += renditionDrift(endpoint, it) }
    return folded.kept
}

/**
 * One row per track, and no drift channel — for a caller that holds rows across pages and folds
 * the fresh page into them.
 *
 * The search list is that caller: its held rows outlive any single page, so it folds `held + fresh`
 * through here rather than relying on either page's own fold.
 */
fun dedupeRenditions(songs: List<Song>): List<Song> = fold(songs).kept

/** Why one rendition lost to another, in enough detail to confirm it was the same recording. */
private fun renditionDrift(
    endpoint: String,
    song: Song,
): Drift =
    Drift(
        endpoint,
        RENDITION_DUPLICATE,
        "'${song.title.take(DRIFT_DETAIL_CHARS)}' songId=${song.key.songId} " +
            "album=${song.albumId ?: "?"} duration=${song.durationS}",
    )

/**
 * A second rendition of a track already in this list was dropped by [dedupeRenditions].
 *
 * A drop and not a note: the user is not shown this row, so the page delivered fewer tracks than it
 * carried cards, and that difference has to be countable rather than inferred.
 */
internal const val RENDITION_DUPLICATE = "RENDITION_DUPLICATE"
