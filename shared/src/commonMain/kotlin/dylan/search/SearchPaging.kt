package dylan.search

import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Song
import dylan.provider.saavn.rowKey

/**
 * One section's rows after a page has been folded in, plus the verdict that page implies.
 *
 * The rows are the *whole* held list plus the new rows, so a caller never has to append to its own
 * copy and can never forget to.
 */
data class SectionRows<T>(
    val items: List<T>,
    val total: Long,
    val exhausted: Boolean,
)

/**
 * Fold a page into a section's rows, dropping rows already held.
 *
 * A page that adds nothing new is the origin's way of saying it has no more of this type, whatever
 * its `total` claimed — so exhaustion is observed rather than predicted. A page that never arrived
 * returns null: a failed request is not evidence of anything, so the caller leaves its page counter
 * and its exhausted flag alone.
 *
 * @param keyOf the row's identity **within its own section**, which is the rendition key for songs
 *   (`Song.key`) and the namespaced [rowKey] for albums and artists. Never a title: two albums of
 *   one name are one row, and two *different* albums that share a name are not.
 */
fun <T, K> foldPage(
    page: Paged<T>?,
    held: List<T>,
    keyOf: (T) -> K,
): SectionRows<T>? {
    page ?: return null
    val seen = held.mapTo(HashSet<K>()) { keyOf(it) }
    val fresh = page.items.filter { seen.add(keyOf(it)) }
    return SectionRows(held + fresh, page.total, fresh.isEmpty())
}

/**
 * The three submit sections' paging budgets, as one value.
 *
 * These used to live in `androidApp`'s `ResultState` as nine separate `mutableStateOf` fields, which
 * meant the arithmetic had no test that could run off a device. The rules did not change when they
 * moved; the test that could not exist before does now.
 *
 * The three `exhausted` flags are per-section, replacing a single `hasMore` derived from the
 * origin's totals, and they exist because those totals could not answer the question.
 * [dylan.provider.saavn.deliveredTotal] decides "there may be more" by comparing the *kept* rows
 * against the requested page size, which is only valid when the page contained nothing but the
 * wanted type. The album and artist endpoints return a **mixed** envelope, so a 20-row page might
 * hold 3 albums and 17 songs; `kept (3) >= pageSize (20)` is false, the section declared itself
 * exhausted after one page, and paging silently stopped. Meanwhile songs kept paging and kept
 * growing, because cross-rendition duplicates (`Mockingbird` from three albums) are distinct
 * `songId`s and so count as distinct rows against the budget. Exhaustion is therefore observed
 * rather than predicted: a section is done when a page brings back no rows it did not already have.
 * That needs no assumption about the origin's `total`.
 *
 * **Exhaustion is counted per section and never by row count.** That is the invariant F-25 broke
 * people's trust in: one section coming back empty must not stop the other two from extending,
 * which is why `growing` is an OR over the three flags and why each section is asked for
 * independently in `SearchSections.foldMore`.
 *
 * [loadingFirst] is the other half of that: a section's budget says nothing while *page 1* has not
 * arrived, because there is nothing yet for a page 2 to be folded into.
 */
data class SearchBudgets(
    val songPage: Int = 1,
    val albumPage: Int = 1,
    val artistPage: Int = 1,
    val songsExhausted: Boolean = false,
    val albumsExhausted: Boolean = false,
    val artistsExhausted: Boolean = false,
    /** Page 1 of the current submit has been asked for and has not landed yet. */
    val loadingFirst: Boolean = false,
    /** A page 2+ request is in flight. */
    val loadingMore: Boolean = false,
) {
    /**
     * "This list is still growing", for the footer spinner and for [canExtend].
     *
     * Deliberately true *while page 1 is in flight* as far as the footer spinner is concerned —
     * that is precisely when the user wants a spinner. The paging decision is [canExtend].
     */
    val growing: Boolean
        get() = !songsExhausted || !albumsExhausted || !artistsExhausted

    /**
     * "A next page may be worth asking for", read at the moment it is asked.
     *
     * A function rather than a value computed by the caller, because the caller that consumes it is
     * a scroll listener which captures one composition's flags: on a new submit that composition has
     * still not run [reset], so a captured value is the *previous* query's answer — and for a query
     * that was paged to the end that answer is a permanent "no".
     */
    fun canExtend(): Boolean = !loadingFirst && !loadingMore && growing

    /**
     * A new query: fresh budgets.
     *
     * Deliberately does **not** touch [loadingFirst]: the submit path sets it before it changes
     * anything, and clearing it here would reopen the window in which the scroll listener can ask
     * for page 2 before page 1 exists.
     */
    fun reset(): SearchBudgets =
        copy(
            songPage = 1,
            albumPage = 1,
            artistPage = 1,
            loadingMore = false,
            songsExhausted = false,
            albumsExhausted = false,
            artistsExhausted = false,
        )

    /** Called synchronously by every submit path, before that path changes anything else. */
    fun beginSubmit(): SearchBudgets = copy(loadingFirst = true)

    /** One section's fold, and the page counter it lands on. */
    private fun fold(
        songs: SectionRows<Song>?,
        albums: SectionRows<MiniEntity>?,
        artists: SectionRows<MiniEntity>?,
        nextSongPage: Int,
        nextAlbumPage: Int,
        nextArtistPage: Int,
    ): SearchBudgets =
        copy(
            songsExhausted = songs?.exhausted ?: this.songsExhausted,
            albumsExhausted = albums?.exhausted ?: this.albumsExhausted,
            artistsExhausted = artists?.exhausted ?: this.artistsExhausted,
            songPage = if (songs != null) nextSongPage else this.songPage,
            albumPage = if (albums != null) nextAlbumPage else this.albumPage,
            artistPage = if (artists != null) nextArtistPage else this.artistPage,
        )

    /**
     * The first page has landed.
     *
     * Cleared here and nowhere else, and deliberately **not** by a later page: a first fetch
     * cancelled by the submit that replaced it must leave the flag set, because the fetch that
     * superseded it is the one that owns clearing it.
     */
    internal fun foldFirst(
        songs: SectionRows<Song>?,
        albums: SectionRows<MiniEntity>?,
        artists: SectionRows<MiniEntity>?,
    ): SearchBudgets =
        fold(songs, albums, artists, nextSongPage = 1, nextAlbumPage = 1, nextArtistPage = 1)
            .copy(loadingFirst = false)

    internal fun foldMore(
        songs: SectionRows<Song>?,
        albums: SectionRows<MiniEntity>?,
        artists: SectionRows<MiniEntity>?,
    ): SearchBudgets =
        fold(
            songs,
            albums,
            artists,
            nextSongPage = songPage + 1,
            nextAlbumPage = albumPage + 1,
            nextArtistPage = artistPage + 1,
        )
}

/**
 * The submitted list's rows and its paging budgets.
 *
 * Every field is a plain value: grouping them is only so that the paging arithmetic — which has to
 * stay consistent across the three sections — lives in one place. The Android screen owns one
 * instance as a snapshot state; it owns the *work* (the fetches) and this owns the *decisions*.
 */
data class SearchSections(
    val songs: List<Song> = emptyList(),
    val albums: List<MiniEntity> = emptyList(),
    val artists: List<MiniEntity> = emptyList(),
    val songTotal: Long = 0L,
    val albumTotal: Long = 0L,
    val artistTotal: Long = 0L,
    val budgets: SearchBudgets = SearchBudgets(),
) {
    /**
     * A first page, folded. A page that never arrived is `null` and changes nothing — which is what
     * makes a failed request look like nothing happened, as it should.
     */
    fun foldFirst(
        songs: Paged<Song>?,
        albums: Paged<MiniEntity>?,
        artists: Paged<MiniEntity>?,
    ): SearchSections {
        val songFold = songs?.let { foldPage(it, emptyList()) { s -> s.key } }
        val albumFold = albums?.let { foldPage(it, emptyList()) { a -> a.rowKey } }
        val artistFold = artists?.let { foldPage(it, emptyList()) { a -> a.rowKey } }
        return copy(
            songs = songFold?.items ?: this.songs,
            songTotal = songFold?.total ?: this.songTotal,
            albums = albumFold?.items ?: this.albums,
            albumTotal = albumFold?.total ?: this.albumTotal,
            artists = artistFold?.items ?: this.artists,
            artistTotal = artistFold?.total ?: this.artistTotal,
            budgets = budgets.foldFirst(songFold, albumFold, artistFold),
        )
    }

    /**
     * A later page, folded into what is already held.
     *
     * Each section is gated by its own flag, so an exhausted section is left strictly alone and an
     * empty one cannot stop the others from extending.
     */
    fun foldMore(
        songs: Paged<Song>?,
        albums: Paged<MiniEntity>?,
        artists: Paged<MiniEntity>?,
    ): SearchSections {
        val songFold = songs?.let { foldPage(it, this.songs) { s -> s.key } }
        val albumFold = albums?.let { foldPage(it, this.albums) { a -> a.rowKey } }
        val artistFold = artists?.let { foldPage(it, this.artists) { a -> a.rowKey } }
        return copy(
            songs = songFold?.items ?: this.songs,
            songTotal = songFold?.total ?: this.songTotal,
            albums = albumFold?.items ?: this.albums,
            albumTotal = albumFold?.total ?: this.albumTotal,
            artists = artistFold?.items ?: this.artists,
            artistTotal = artistFold?.total ?: this.artistTotal,
            budgets = budgets.foldMore(songFold, albumFold, artistFold),
        )
    }

    /**
     * A new query: fresh budgets, and nothing left over from the last one.
     *
     * Leaves [SearchBudgets.loadingFirst] alone, for the reason [SearchBudgets.reset] gives.
     */
    fun reset(): SearchSections =
        copy(
            songs = emptyList(),
            albums = emptyList(),
            artists = emptyList(),
            songTotal = 0L,
            albumTotal = 0L,
            artistTotal = 0L,
            budgets = budgets.reset(),
        )

    /**
     * Called synchronously by every submit path, before that path changes anything else.
     *
     * The keyboard's submit drops the song rows below, and dropping rows wakes the scroll listener.
     * Without this flag that listener would see "there may be more" and request page 2 while
     * `fetchFirst` was still asking for page 1 — and the page-2 fold would then *overwrite* the
     * page-1 rows rather than extend them. On the chip and suggestion paths there is nothing to
     * clear, but the flag is set there too so that "a first page is pending" is true by the time any
     * listener can run, without depending on which effect the runtime happens to dispatch first.
     */
    fun beginSubmit(): SearchSections = copy(budgets = budgets.beginSubmit())

    /**
     * The keyboard's submit drops the song rows synchronously, before the refetch has run.
     *
     * Only the songs: that is what this path has always cleared, and the album and artist rows are
     * replaced a frame later by [reset] anyway.
     */
    fun clearSongs(): SearchSections = copy(songs = emptyList())

    /** Page 1 of the current submit has been asked for and has not landed yet. */
    val loadingFirst: Boolean
        get() = budgets.loadingFirst

    /** A page 2+ request is in flight. */
    val loadingMore: Boolean
        get() = budgets.loadingMore

    /** "A next page may be worth asking for", read at the moment it is asked. */
    fun canExtend(): Boolean = budgets.canExtend()

    fun withLoadingMore(value: Boolean): SearchSections = copy(budgets = budgets.copy(loadingMore = value))
}
