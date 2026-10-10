package dylan.search

import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Song
import dylan.model.SongKey
import dylan.provider.saavn.rowKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The submit list's paging, without a device.
 *
 * These rules used to live in `androidApp`'s `ResultState` as nine separate snapshot states, which
 * meant nothing off a device could exercise them — and the two defects reported against the search
 * list (F-24, F-25) were both in exactly that arithmetic. The rules moved to
 * [dylan.search.SearchPaging]; the tests came with them.
 */
class SearchPagingTest {
    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun song(id: String) =
        Song(
            key = SongKey("saavn", id),
            title = "Song $id",
            subtitle = "",
            albumId = null,
            albumName = null,
            artUrl150 = "",
            artUrl500 = "",
            durationS = 0L,
            has320 = false,
            resolveRef = null,
            permaToken = null,
        )

    private fun mini(
        id: String,
        type: String,
    ) = MiniEntity(
        songKey = null,
        albumId = null,
        title = "$type $id",
        subtitle = "",
        type = type,
        image = "",
        permaToken = null,
        artistId = null,
    )

    private fun songs(vararg ids: String) = ids.map(::song)

    private fun albums(vararg ids: String) = ids.map { mini(it, "album") }

    private fun artists(vararg ids: String) = ids.map { mini(it, "artist") }

    private fun page(items: List<Song>) = Paged(items, items.size.toLong(), 1)

    private fun pageAlbums(items: List<MiniEntity>) = Paged(items, items.size.toLong(), 1)

    private fun pageArtists(items: List<MiniEntity>) = Paged(items, items.size.toLong(), 1)

    // ── F-25: all three sections keep extending ─────────────────────────────────────────────────

    /**
     * The F-25 assertion, asked for by name: **all three** sections still extend while any of them
     * has fresh rows.
     *
     * Before the shared seam this was unprovable off a device; the property it protects is that one
     * section running dry — or never mapping anything at all — is not allowed to stop the others.
     */
    @Test
    fun allThreeSectionsKeepExtending() {
        var s = SearchSections().beginSubmit()
        s = s.foldFirst(page(songs("s1", "s2")), pageAlbums(albums("a1")), pageArtists(artists("r1")))
        assertTrue(s.canExtend(), "one page is not the end of anything")

        s = s.foldMore(page(songs("s3", "s4")), pageAlbums(albums("a2")), pageArtists(artists("r2")))
        assertEquals(listOf("s1", "s2", "s3", "s4"), s.songs.map { it.key.songId })
        assertEquals(2, s.albums.size)
        assertEquals(2, s.artists.size)
        assertTrue(s.canExtend(), "a second page is not the end either")

        s = s.foldMore(page(songs("s5")), pageAlbums(albums("a3")), pageArtists(artists("r3")))
        assertEquals(5, s.songs.size, "songs keep arriving")
        assertEquals(3, s.albums.size, "albums keep arriving")
        assertEquals(3, s.artists.size, "artists keep arriving")
    }

    /**
     * The lead that F-25 was told to verify, and which it does **not** support: an artist section
     * that maps nothing at all does not turn later pages into "albums only".
     *
     * An artist page with no rows exhausts *that* section (a page with nothing new is the origin
     * saying it has no more of this type) and leaves the other two flags untouched, so songs and
     * albums both keep being asked for. What F-25 observed — songs and artists stopping while albums
     * carried on — is two sections exhausting, not one empty section blocking the rest; the song half
     * of it is the rendition fold, which drops a page whose rows are all renditions of rows already
     * held, and the artist half was F-27.
     */
    @Test
    fun anArtistSectionThatMapsNothingDoesNotStopTheOtherTwo() {
        var s = SearchSections().beginSubmit()
        s = s.foldFirst(page(songs("s1", "s2")), pageAlbums(albums("a1")), pageArtists(emptyList()))
        assertTrue(s.songs.isNotEmpty(), "songs arrived")
        assertTrue(s.albums.isNotEmpty(), "albums arrived")
        assertTrue(s.artists.isEmpty(), "the artist section is empty")
        assertTrue(s.budgets.artistsExhausted, "an empty first page is a section with nothing more")
        assertFalse(s.budgets.songsExhausted, "…and says nothing about songs")
        assertFalse(s.budgets.albumsExhausted, "…or about albums")

        /**
         * `null`, not a page: the Android caller gates the *fetch* on the flag, so an exhausted
         * section is simply never asked for. The reducer folds whatever it is handed, so the gate
         * has to be upstream of it — which is why the test drives it the way `extend` does.
         */
        s = s.foldMore(page(songs("s3")), pageAlbums(albums("a2")), null)
        assertEquals(3, s.songs.size, "songs keep extending while the artist section is dry")
        assertEquals(2, s.albums.size, "so do albums")
        assertTrue(s.artists.isEmpty(), "the dry artist section is not asked for at all")
        assertTrue(s.canExtend(), "one dry section must not close the list")
    }

    /** …and the mirror: a dry song section does not stop albums or artists. */
    @Test
    fun aSongSectionThatIsDryDoesNotStopAlbumsOrArtists() {
        var s = SearchSections().beginSubmit()
        s = s.foldFirst(page(songs("s1")), pageAlbums(albums("a1")), pageArtists(artists("r1")))
        s = s.foldMore(page(emptyList()), pageAlbums(albums("a2")), pageArtists(artists("r2")))
        assertTrue(s.budgets.songsExhausted, "a song page with nothing new ends the song section")
        assertEquals(2, s.albums.size, "albums do not care")
        assertEquals(2, s.artists.size, "artists do not care")
        assertTrue(s.canExtend(), "there is still something to ask for")

        s = s.foldMore(page(emptyList()), pageAlbums(albums("a3")), pageArtists(artists("r3")))
        assertEquals(3, s.albums.size)
        assertEquals(3, s.artists.size)
    }

    // ── exhaustion is observed, per section ─────────────────────────────────────────────────────

    /** A page that brings nothing new ends its own section and no other. */
    @Test
    fun aPageWithNothingNewExhaustsOnlyItsOwnSection() {
        var s = SearchSections().beginSubmit()
        s = s.foldFirst(page(songs("s1")), pageAlbums(albums("a1")), pageArtists(artists("r1")))
        s = s.foldMore(page(songs("s1")), pageAlbums(albums("a1")), pageArtists(artists("r1")))
        assertTrue(s.budgets.songsExhausted)
        assertTrue(s.budgets.albumsExhausted)
        assertTrue(s.budgets.artistsExhausted)
        assertEquals(1, s.songs.size, "a repeated page adds nothing")
        assertEquals(1, s.albums.size)
        assertEquals(1, s.artists.size)
        assertFalse(s.canExtend(), "nothing left to ask for")
    }

    /** A page that never arrived is not evidence of anything. */
    @Test
    fun aPageThatNeverArrivedChangesNothing() {
        var s = SearchSections().beginSubmit()
        s = s.foldFirst(page(songs("s1")), pageAlbums(albums("a1")), pageArtists(artists("r1")))
        val before = s
        s = s.foldMore(null, null, null)
        assertEquals(before, s, "a failed request must leave rows, totals, pages and flags alone")
        assertFalse(s.budgets.songsExhausted, "a failure is not exhaustion")
        assertTrue(s.canExtend())
    }

    // ── the two in-flight flags ─────────────────────────────────────────────────────────────────

    /** Page 2 must not be askable while page 1 is pending, whatever the scroll listener is doing. */
    @Test
    fun nothingExtendsWhileTheFirstPageIsPending() {
        val s = SearchSections().beginSubmit()
        assertTrue(s.loadingFirst)
        assertFalse(s.canExtend(), "a page 2 request while page 1 is in flight would overwrite page 1")
        assertFalse(SearchSections().beginSubmit().clearSongs().canExtend())
    }

    /** …and not while a page 2+ request is already in flight either. */
    @Test
    fun nothingExtendsWhileAPageIsInFlight() {
        var s =
            SearchSections()
                .beginSubmit()
                .foldFirst(page(songs("s1")), pageAlbums(albums("a1")), pageArtists(artists("r1")))
                .withLoadingMore(true)
        assertTrue(s.canExtend().not())
        s = s.withLoadingMore(false)
        assertTrue(s.canExtend())
    }

    /** The footer's spinner is a different question from the paging decision. */
    @Test
    fun growingIsAQuestionAboutSectionsNotAboutInFlightRequests() {
        assertTrue(SearchSections().beginSubmit().budgets.growing, "page 1 in flight is still 'growing'")
        val dry =
            SearchSections()
                .beginSubmit()
                .foldFirst(page(emptyList()), pageAlbums(emptyList()), pageArtists(emptyList()))
        assertFalse(dry.budgets.growing, "three empty sections are not growing")
    }

    /** `reset` must not reopen the window the pending-first-page flag exists to close. */
    @Test
    fun resetLeavesThePendingFirstPageFlagAlone() {
        val s = SearchSections().beginSubmit().reset()
        assertTrue(s.loadingFirst, "a new submit is still waiting for its page 1")
        assertTrue(s.songs.isEmpty() && s.albums.isEmpty() && s.artists.isEmpty())
        assertEquals(0L, s.songTotal + s.albumTotal + s.artistTotal)
        assertFalse(s.canExtend())
    }

    /** `beginSubmit` is the flag's only setter, and it is set before anything else changes. */
    @Test
    fun beginSubmitIsSetSynchronouslyBeforeTheRowsMove() {
        val withRows =
            SearchSections()
                .beginSubmit()
                .foldFirst(page(songs("s1")), pageAlbums(albums("a1")), pageArtists(artists("r1")))
        val submitted = withRows.beginSubmit().clearSongs()
        assertTrue(submitted.loadingFirst)
        assertTrue(submitted.songs.isEmpty(), "the rows go before the fetch, so the screen shows intent")
        assertEquals(1, submitted.albums.size, "only the songs, exactly as the UI path does")
    }

    // ── the fold ───────────────────────────────────────────────────────────────────────────────

    /** Rows are kept and new rows appended, so no caller appends to its own copy. */
    @Test
    fun foldPageAppendsAndReportsExhaustion() {
        val fold = foldPage(page(songs("s2", "s3")), songs("s1")) { it.key }
        assertNotNull(fold)
        assertEquals(listOf("s1", "s2", "s3"), fold.items.map { it.key.songId })
        assertFalse(fold.exhausted)
    }

    /** Identity is per section: a song and an album that share an id are not the same row. */
    @Test
    fun eachSectionFoldsOnItsOwnIdentity() {
        val songFold = foldPage(page(songs("x")), emptyList<Song>()) { it.key }
        val albumFold = foldPage(pageAlbums(albums("x")), emptyList<MiniEntity>()) { it.rowKey }
        assertEquals(1, songFold?.items?.size)
        assertEquals(1, albumFold?.items?.size)
    }

    /** The page counter only moves for a page that actually arrived. */
    @Test
    fun thePageCounterMovesOnlyForAPageThatArrived() {
        var s = SearchSections().beginSubmit()
        s = s.foldFirst(page(songs("s1")), null, null)
        assertEquals(1, s.budgets.songPage)
        assertEquals(1, s.budgets.albumPage, "no album page, no album page counter")
        assertEquals(1, s.budgets.artistPage)
        s = s.foldMore(page(songs("s2")), pageAlbums(albums("a1")), null)
        assertEquals(2, s.budgets.songPage)
        assertEquals(2, s.budgets.albumPage)
        assertEquals(1, s.budgets.artistPage)
    }

    /** A null page is not a fold, so there is no `SectionRows` to argue about. */
    @Test
    fun foldPageOfANullPageIsNull() {
        assertNull(foldPage(null, songs("s1")) { it.key })
    }

    /** Running totals follow the pages, including a first page that delivered nothing. */
    @Test
    fun totalsFollowThePages() {
        val s =
            SearchSections()
                .beginSubmit()
                .foldFirst(
                    Paged(songs("s1", "s2"), 134L, 1),
                    Paged(albums("a1"), 2168L, 1),
                    Paged(artists("r1"), 436L, 1),
                )
        assertEquals(134L, s.songTotal)
        assertEquals(2168L, s.albumTotal)
        assertEquals(436L, s.artistTotal)
    }
}
