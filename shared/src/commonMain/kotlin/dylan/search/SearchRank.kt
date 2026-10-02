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
 *
 * The comparator itself lives in [relevanceOrder] so every entry point below shares one ordering
 * rather than three copies of the same one.
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
 * Input positions of [titles] in relevance order.
 *
 * The one ordering every surface shares. It was written out three times — [rankSongs]/[rankMinis]
 * here, the merged submit list on Android, and a Swift mirror of the band function on iOS — and
 * the copies normalised titles differently, so "best match" could mean different orders per
 * platform. Returning positions rather than items lets one implementation serve both a typed list
 * and a merged one.
 */
fun relevanceOrder(
    query: String,
    titles: List<String>,
): List<Int> {
    val nq = normTitle(query)
    return titles
        .mapIndexed { i, t -> Ranked(i, band(nq, t), Unit) }
        .sortedWith(bandOrder())
        .map { it.index }
}

/**
 * Interleave heterogeneous buckets into one relevance order.
 *
 * The submit list is songs ++ albums ++ artists ranked by a single shared band so an album never
 * hides below its songs. Written once per platform, and the two copies disagreed — see
 * [relevanceOrder].
 */
fun <T> rankMerged(
    query: String,
    buckets: List<List<T>>,
    title: (T) -> String,
): List<T> {
    val flat = ArrayList<T>(buckets.sumOf { it.size })
    buckets.forEach { flat.addAll(it) }
    return rankByBand(query, flat, title)
}

/**
 * The single comparator, shared by every entry point above: band ascending, then input position
 * ascending.
 *
 * The index tiebreaker is what makes the sort stable without relying on `sortedWith`'s
 * documented-stability guarantee — the comparator is total and has no equal-elements left to order
 * arbitrarily.
 */
private fun <T> bandOrder(): Comparator<Ranked<T>> = compareBy({ it.band }, { it.index })

private data class Ranked<T>(
    val index: Int,
    val band: Int,
    val item: T,
)

/** [relevanceOrder] over one bucket, so every entry point above shares one comparator. */
private fun <T> rankByBand(
    query: String,
    items: List<T>,
    title: (T) -> String,
): List<T> {
    val nq = normTitle(query)
    return items
        .mapIndexed { i, item -> Ranked(i, band(nq, title(item)), item) }
        .sortedWith(bandOrder())
        .map { it.item }
}

/**
 * Shared band score so every surface (submit sections, merged hits, suggestions) ranks identically:
 * exact (0) → prefix (1) → contains (2) → other (3). Reached through [relevanceOrder] /
 * [relevanceBand] only — there is no second copy of this ladder on either platform.
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
