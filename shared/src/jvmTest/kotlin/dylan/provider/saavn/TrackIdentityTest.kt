package dylan.provider.saavn

import dylan.model.Song
import dylan.model.SongKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #8: `MOCKINGBIRD` appeared three times in one search.
 *
 * Dedup was on `SongKey` = `(provider, songId)`, and this origin mints a **different songId per
 * album rendition** of the same recording — so the same track from Encore, from the Mockingbird
 * single and from Curtain Call was three distinct keys, three rows, and three rows of page budget.
 *
 * These tests pin the replacement identity (title + primary artist + duration) at both of its
 * boundaries: it must collapse the renditions of one track, and it must **not** collapse two
 * different recordings that happen to share a title.
 */
class TrackIdentityTest {
    // ── the reported defect ─────────────────────────────────────────────────────────────────────

    /**
     * The exact shape of the report: one track, three albums, three ids, one row.
     *
     * The payload mirrors what the origin actually sends — the same `duration`, the same
     * `artistMap`, a different `id`, `perma_url` and `album_id` per rendition — because a test card
     * that differed in some *other* field could collapse for the wrong reason.
     */
    @Test
    fun threeRenditionsOfOneTrackCollapseToOneRow() {
        val page =
            pageOf(
                rendition("m1", "Encore", "1"),
                rendition("m2", "Mockingbird (Single)", "2"),
                rendition("m3", "Curtain Call", "3"),
            )
        val rows = decodeSongPage(page, "search.getResults", 1)
        assertEquals(
            listOf("m1"),
            rows.items.items.map { it.key.songId },
            "three renditions of one track must leave one row, and the first is the one kept",
        )
        assertEquals(
            2,
            rows.drift.count { it.reason == RENDITION_DUPLICATE },
            "a collapsed rendition is a dropped card, and a dropped card says so: ${rows.drift}",
        )
        assertEquals(
            1,
            dedupeRenditions(rows.items.items).size,
            "the fold that runs across pages has nothing left to do",
        )
    }

    /**
     * Cross-page is the case a single page decode cannot see: page 1 held one rendition and page 2
     * brings another. `dedupeRenditions` is the primitive the search fold runs over held + fresh.
     */
    @Test
    fun aRenditionThatArrivesOnALaterPageCollapsesIntoTheOneAlreadyHeld() {
        val held = decodeSongPage(pageOf(rendition("m1", "Encore", "1")), "p1", 1).items.items
        val fresh = decodeSongPage(pageOf(rendition("m2", "Curtain Call", "3")), "p2", 2).items.items
        val folded = dedupeRenditions(held + fresh)
        assertEquals(1, folded.size, "the fold is what the search list runs, so it is what must collapse")
        assertEquals("m1", folded.single().key.songId, "the rendition already on screen is the one kept")
    }

    // ── the boundary that must not move ─────────────────────────────────────────────────────────

    /**
     * Two different songs CAN share a title and an artist: a live cut and the studio cut, or two
     * recordings of one standard. Duration is the only field that tells them apart, so a key without
     * it would silently delete one of them.
     */
    @Test
    fun twoDifferentRecordingsWithTheSameTitleAndArtistDoNotCollapse() {
        val page =
            pageOf(
                rendition("l1", "Live at Wembley", "9", duration = "262"),
                rendition("l2", "Encore", "1", duration = "251"),
            )
        val rows = decodeSongPage(page, "search.getResults", 1)
        assertEquals(
            listOf("l1", "l2"),
            rows.items.items.map { it.key.songId },
            "262 s and 251 s are two recordings, not two renditions",
        )
        assertTrue(rows.drift.none { it.reason == RENDITION_DUPLICATE }, "…and nothing was collapsed: ${rows.drift}")
    }

    /**
     * A card with no artist at all has no identity, and an unidentified card is **never** collapsed.
     *
     * The safe direction is chosen deliberately: a duplicate row is the bug being fixed, but a
     * vanished song is worse, and title+duration with no artist is exactly where that would happen.
     */
    @Test
    fun aCardWithNoArtistIsNeverCollapsed() {
        val page =
            pageOf(
                rendition("n1", "Encore", "1", artist = null, artistToken = null),
                rendition("n2", "Curtain Call", "3", artist = null, artistToken = null),
            )
        val rows = decodeSongPage(page, "search.getResults", 1)
        assertEquals(
            listOf("n1", "n2"),
            rows.items.items.map { it.key.songId },
            "no artist at all means no identity means no collapse",
        )
    }

    /** Same title, same duration, no artist *name* — but both carry the artist's perma token. */
    @Test
    fun anArtistTokenStandsInForAMissingArtistName() {
        val page =
            pageOf(
                rendition("t1", "Encore", "1", artist = null, artistToken = "-f6Su9-0agk_"),
                rendition("t2", "Curtain Call", "3", artist = null, artistToken = "-f6Su9-0agk_"),
            )
        assertEquals(1, dedupeRenditions(decode(page)).size, "the token identifies the artist as reliably as the name")
    }

    /**
     * The boundary this key cannot cross, pinned so it stays a known limitation.
     *
     * `fixtures/search_getresults_p1.json` carries the same recording twice — `Yeh Awarapan` and
     * `Yeh Awarapan (From "Awarapan 2")`, both 298 s by Amaal Mallik, two ids, two albums — because
     * the origin rewrites the title per album. No title-based key sees through a rewritten title, so
     * both rows stay. Pinned here because the fix must not later be "improved" into dropping one of
     * them by loosening the key.
     */
    @Test
    fun aRenditionWhoseTitleTheOriginRewroteIsAKnownNonCollapse() {
        val rows = decode(fixture("search_getresults_p1.json"))
        val pair = rows.filter { it.title.startsWith("Yeh Awarapan") }
        assertEquals(
            listOf("Yeh Awarapan", "Yeh Awarapan (From \"Awarapan 2\")"),
            pair.map { it.title },
            "same recording, rewritten title",
        )
        assertEquals(pair.first().durationS, pair.last().durationS, "…identical duration")
        assertEquals(5, rows.size, "the fixture's five cards stay five rows, this pair included")
    }

    // ── the identity itself ─────────────────────────────────────────────────────────────────────

    @Test
    fun identityNormalisesTheTitleTheWayTheOriginAndUiDo() {
        assertEquals(
            trackIdentity(song("1", "Mockingbird", 339, "Eminem")),
            trackIdentity(song("2", "Mockingbird  ", 339, "eminem")),
            "trailing space and case are not a different track",
        )
    }

    @Test
    fun aBlankTitleHasNoIdentity() {
        assertNull(trackIdentity(song("1", "   ", 339, "Eminem")))
    }

    @Test
    fun dedupeKeepsEveryRowWhenNoneOfThemHasAnIdentity() {
        val rows = listOf(song("1", "  ", 0, null), song("2", "", 0, null))
        assertEquals(2, dedupeRenditions(rows).size)
    }

    /**
     * The LazyColumn key is `provider:songId`, so the fold must not leave two rows sharing one.
     * Rendition collapse only ever removes rows, and this asserts the invariant that depends on.
     */
    @Test
    fun theFoldedListNeverCarriesTwoRowsWithOneSongKey() {
        val folded = dedupeRenditions(listOf(song("1", "A", 10, "X"), song("2", "B", 20, "X"), song("3", "A", 10, "X")))
        assertEquals(listOf("1", "2"), folded.map { it.key.songId }, "the third row is the first one's other rendition")
        assertEquals(
            folded.size,
            folded.map { it.key }.distinct().size,
            "…and every rendered row still has its own key",
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun fixture(name: String): String {
        val base = System.getProperty("user.dir")
        return java.io.File(base, "../fixtures/$name").readText()
    }

    private fun pageOf(vararg cards: String): String {
        val joined = cards.joinToString(",")
        return """{"total":"${cards.size}","start":"1","results":[$joined]}"""
    }

    private fun decode(page: String): List<Song> = decodeSongPage(page, "search.getResults", 1).items.items

    /**
     * One song card as this origin ships it. [duration] and [artist] are the only two fields the
     * identity reads, so they are the only ones worth parameterising.
     *
     * Not `@Suppress("LongMethod")`-ed: it is well under the threshold in `config/detekt.yml`, and
     * a suppression of a finding that does not exist is how the next real one hides.
     */
    private fun rendition(
        id: String,
        album: String,
        albumId: String,
        duration: String = "339",
        artist: String? = "Eminem",
        artistToken: String? = "-f6Su9-0agk_",
    ): String {
        val primary =
            when {
                artist == null && artistToken == null -> "[]"
                else ->
                    """[{"id":"442","name":"${artist.orEmpty()}",""" +
                        """"perma_url":"https://www.jiosaavn.com/artist/eminem-songs/${artistToken.orEmpty()}"}]"""
            }
        return """{"id":"$id","title":"Mockingbird","type":"song",""" +
            """"perma_url":"https://www.jiosaavn.com/song/mockingbird/$id",""" +
            """"more_info":{"duration":"$duration","album":"$album","album_id":"$albumId",""" +
            """"artistMap":{"primary_artists":$primary}}}"""
    }

    private fun song(
        id: String,
        title: String,
        durationS: Long,
        artist: String?,
    ): Song =
        Song(
            key = SongKey("saavn", id),
            title = title,
            subtitle = "",
            albumId = null,
            albumName = null,
            artUrl150 = "",
            artUrl500 = "",
            durationS = durationS,
            has320 = false,
            resolveRef = null,
            permaToken = null,
            artistName = artist,
            artistToken = null,
        )
}
