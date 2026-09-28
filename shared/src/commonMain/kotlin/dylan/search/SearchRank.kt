package dylan.search

import dylan.model.MiniEntity
import dylan.model.Song
import dylan.provider.saavn.dedupKey
import dylan.provider.saavn.normTitle

/**
 * Client-side relevance ranking for submitted search results.
 *
 * The server returns API order, which buries exact matches (e.g. searching an album name surfaces
 * its songs before the album-adjacent title match). Rank: exact title match first, then
 * title-prefix, then contains, then everything else — stable, so server order survives within each
 * band.
 *
 * The band is computed **once per element**, not once per comparison. `sortedBy { band(q, t) }`
 * expands to `sortedWith(compareBy(selector))`, which re-evaluates the selector O(n log n) times,
 * and `relevanceBand` itself did two `trim().lowercase()` allocations on every one of those calls.
 * With the `withIndex` form the ordering is identical and the selector runs n times.
 */
fun rankSongs(
    query: String,
    songs: List<Song>,
): List<Song> = rankByBand(query, songs) { it.title }

fun rankMinis(
    query: String,
    minis: List<MiniEntity>,
): List<MiniEntity> = rankByBand(query, minis) { it.title }

/**
 * Stable sort by relevance band, preserving input order inside a band.
 *
 * The index is the tiebreaker, which is what makes it stable without relying on
 * `sortedWith`'s documented-stability guarantee: the sort is total and the comparator has no
 * equal-elements left to order arbitrarily.
 */
private inline fun <T> rankByBand(
    query: String,
    items: List<T>,
    title: (T) -> String,
): List<T> {
    val n = normTitle(query)
    return items
        .mapIndexed { i, item -> Ranked(i, band(n, title(item)), item) }
        .sortedWith(compareBy({ it.band }, { it.index }))
        .map { it.item }
}

private data class Ranked<T>(
    val index: Int,
    val band: Int,
    val item: T,
)

/**
 * Shared band score so every surface (submit sections, merged hits, suggestions, iOS rankBand
 * mirror) ranks identically: exact (0) → prefix (1) → contains (2) → other (3).
 *
 * [query] must already be normalised — pass it through [normTitle] once, not per comparison. The
 * public form below still normalises, so an external caller cannot get a wrong answer, it just pays
 * two allocations.
 */
internal fun band(
    normalizedQuery: String,
    rawTitle: String,
): Int {
    if (normalizedQuery.isEmpty()) return BAND_OTHER
    val t = normTitle(rawTitle)
    if (t.isEmpty()) return BAND_OTHER
    return when {
        t == normalizedQuery -> BAND_EXACT
        t.startsWith(normalizedQuery) -> BAND_PREFIX
        t.contains(normalizedQuery) -> BAND_CONTAINS
        else -> BAND_OTHER
    }
}

fun relevanceBand(
    query: String,
    title: String,
): Int = band(normTitle(query), title)

/** Rank, then drop cross-bucket duplicates, using the one shared [dedupKey]. */
fun rankMinisDistinct(
    query: String,
    minis: List<MiniEntity>,
): List<MiniEntity> = rankByBand(query, minis.distinctBy { dedupKey(it) }) { it.title }

private const val BAND_EXACT = 0
private const val BAND_PREFIX = 1
private const val BAND_CONTAINS = 2
private const val BAND_OTHER = 3
