package dylan.search

import dylan.model.MiniEntity
import dylan.model.Song

/**
 * Client-side relevance ranking for submitted search results.
 *
 * The server returns API order, which buries exact matches (e.g. searching an
 * album name surfaces its songs before the album-adjacent title match). Rank:
 * exact title match first, then title-prefix, then contains, then everything
 * else — stable, so server order survives within each band.
 */
fun rankSongs(
    query: String,
    songs: List<Song>,
): List<Song> = songs.sortedBy { relevanceBand(query, it.title) }

fun rankMinis(
    query: String,
    minis: List<MiniEntity>,
): List<MiniEntity> = minis.sortedBy { relevanceBand(query, it.title) }

/**
 * Shared band score so every surface (submit sections, merged hits,
 * suggestions, iOS rankBand mirror) ranks identically: exact (0) →
 * prefix (1) → contains (2) → other (3).
 */
fun relevanceBand(
    query: String,
    title: String,
): Int {
    val q = query.trim().lowercase()
    val t = title.trim().lowercase()
    if (q.isEmpty() || t.isEmpty()) return 3
    return when {
        t == q -> 0
        t.startsWith(q) -> 1
        t.contains(q) -> 2
        else -> 3
    }
}
