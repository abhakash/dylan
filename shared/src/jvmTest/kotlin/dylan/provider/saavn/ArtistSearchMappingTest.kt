package dylan.provider.saavn

import dylan.provider.saavn.dto.SongDto
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The artist search section returned **zero** results, live, with a payload that was right there.
 *
 * The live `search.getArtistResults` response (captured 2026-10-09, 20 cards, committed as
 * `fixtures/artists.json`) ships every card as
 * `{"name":"Arijit Singh","id":"459320","perma_url":"…","type":"artist"}` — `name`, never
 * `title`. [SongDto] had no `name` field, so every card decoded with a blank title, and both
 * [mapMini] and [miniDropReason] guarded on `title`: 20 cards in, 0 rows out, and the live drift
 * gate reported exactly that (`search.getArtistResults | EMPTY_MAPPING | no 'artist' cards mapped
 * from 20 results`).
 *
 * These tests read the live capture itself rather than a hand-written card, because the defect was
 * in the *shape* and a hand-written card is where the guess would go.
 */
class ArtistSearchMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String = File(System.getProperty("user.dir"), "../fixtures/$name").readText()

    // ── the live capture, end to end ─────────────────────────────────────────────────────────────

    /**
     * The page that produced the defect. ASSERT_RED before the fix: `decodeMiniPage` mapped 0 of 20
     * cards, so `items` was empty and the assertion failed on the size.
     */
    @Test
    fun theLiveArtistSearchPageMapsEveryCard() {
        val rows = decodeMiniPage(fixture("artists.json"), "artist", "search.getArtistResults", 1)
        assertEquals(20, rows.items.items.size, "every card in the live page must arrive: ${rows.drift}")
        assertTrue(rows.items.items.all { it.type == "artist" }, "wrong types: ${rows.items.items.map { it.type }}")
        assertTrue(
            rows.items.items.all { it.artistId != null },
            "a mapped artist row must carry the token the artist screen can resolve",
        )
        assertTrue(rows.drift.isEmpty(), "a page that maps in full records no drift: ${rows.drift}")
        assertEquals(
            "Arijit Singh",
            rows.items.items
                .first()
                .title,
        )
        assertEquals(
            "LlRWpHzy3Hk_",
            rows.items.items
                .first()
                .artistId,
        )
    }

    /**
     * The same page through the entry point the drift gate uses ([mapMiniPaged] over a typed
     * `ResultsDto`), which is the path that reported `EMPTY_MAPPING`.
     */
    @Test
    fun theLiveArtistPageMapsThroughTheTypedEnvelopeToo() {
        val dto =
            json.decodeFromString(
                dylan.provider.saavn.dto.ResultsDto
                    .serializer(),
                fixture("artists.json"),
            )
        val paged = mapMiniPaged(dto, "artist", 1)
        assertEquals(dto.results.size, paged.items.size, "mapped=${paged.items.size} of ${dto.results.size}")
        assertTrue(paged.items.isNotEmpty(), "the drift gate reports EMPTY_MAPPING when this is empty")
    }

    /**
     * A `song` filter over the same page must stay empty: the fix widens the artist section, it does
     * not let artist cards leak into the others.
     */
    @Test
    fun artistCardsDoNotLeakIntoTheSongOrAlbumSections() {
        val rows = decodeMiniPage(fixture("artists.json"), "song", "search.getArtistResults", 1)
        assertTrue(rows.items.items.isEmpty(), "a song filter over artist cards yields nothing")
        val albumRows = decodeMiniPage(fixture("artists.json"), "album", "search.getArtistResults", 1)
        assertTrue(albumRows.items.items.isEmpty(), "…and neither does an album filter")
    }

    // ── which field the name lives in ───────────────────────────────────────────────────────────

    /** The card shape the origin actually ships: `name`, no `title`. */
    @Test
    fun anArtistCardNamesItselfWithNameAndNoTitle() {
        val m =
            mapMini(
                SongDto(
                    id = "459320",
                    type = "artist",
                    permaUrl = "https://www.jiosaavn.com/artist/arijit-singh-songs/LlRWpHzy3Hk_",
                    name = "Arijit Singh",
                ),
            )
        assertNotNull(m, "an artist card with a name and no title is a card, not a drop")
        assertEquals("Arijit Singh", m.title)
        assertEquals("artist", m.type)
        assertEquals("LlRWpHzy3Hk_", m.artistId)
        assertNull(m.songKey, "an artist row is not a song")
    }

    /**
     * `name` outranks `title` and the fallback runs one way only. A card carrying both is the shape
     * the origin *would* ship if it unified the two; asserting the direction pins which field wins
     * so the fix cannot be "re-fixed" into a `title`-first lookup, which is the original defect.
     */
    @Test
    fun nameOutranksTitleNeverTheReverse() {
        val both =
            mapMini(
                SongDto(
                    id = "1",
                    title = "The Title",
                    name = "The Name",
                    type = "artist",
                    permaUrl = "https://www.jiosaavn.com/artist/x/some-token_",
                ),
            )
        assertEquals("The Name", both?.title, "name first; a title-first lookup is the defect this replaced")
        val titleOnly =
            mapMini(SongDto(id = "2", title = "Only A Title", type = "album", permaUrl = "https://x/album/y/T_"))
        assertEquals("Only A Title", titleOnly?.title, "a card with no name still has its title")
    }

    /** A blank `name` falls back rather than blanking the row: `name` is `""` by construction. */
    @Test
    fun aBlankNameFallsBackToTheTitle() {
        val m = mapMini(SongDto(id = "3", title = "Falls Back", name = "   ", type = "song"))
        assertEquals("Falls Back", m?.title)
    }

    /** Neither field is the drop reason it used to be — but no identity at all still is. */
    @Test
    fun aCardWithNeitherNameNorTitleIsStillDroppedAndNamed() {
        val rows =
            decodeMiniPage(
                """{"total":"1","start":"1","results":[{"id":"x","type":"artist","perma_url":"/artist/a/B_"}]}""",
                "artist",
                "search.getArtistResults",
                1,
            )
        assertTrue(rows.items.items.isEmpty())
        assertEquals(1, rows.drift.count { it.reason == BLANK_IDENTITY }, "the drop must be named: ${rows.drift}")
    }

    // ── the two guards cannot disagree ──────────────────────────────────────────────────────────

    /**
     * [miniDropReason] answers "why did this card not map" and [mapMini] answers "did it map". They
     * read the same field now ([cardTitle]); before, `miniDropReason` answered `BLANK_IDENTITY` for
     * every artist card while the live payload carried a `name`, which is why the drift log named the
     * wrong cause.
     */
    @Test
    fun theDropReasonAndTheMapperAgreeOnEveryShape() {
        val shapes =
            listOf(
                "a live artist card" to
                    SongDto(
                        id = "459320",
                        name = "Arijit Singh",
                        type = "artist",
                        permaUrl = "https://www.jiosaavn.com/artist/arijit-singh-songs/LlRWpHzy3Hk_",
                    ),
                "a title-only album card" to
                    SongDto(id = "1", title = "Album", type = "album", permaUrl = "https://x/album/y/Z_"),
                "an artist card with no perma_url" to SongDto(id = "610240", name = "Eminem", type = "artist"),
                "a card with no identity at all" to SongDto(id = "9", type = "artist"),
                "a song card" to SongDto(id = "10", title = "Song", type = "song"),
            )
        shapes.forEach { (label, d) ->
            val why = miniDropReason(d)
            val mapped = mapMini(d)
            assertTrue(
                why.isEmpty() == (mapped != null),
                "$label: reason='$why' mapped=${mapped != null} — the two guards must agree",
            )
        }
    }

    // ── the corpus ──────────────────────────────────────────────────────────────────────────────

    /**
     * Every artist card in the live capture carries a perma_url that yields a token, so the fix is
     * not rescuing cards that would be dropped again one line later.
     */
    @Test
    fun everyLiveArtistCardCarriesAPermaUrlThatYieldsAToken() {
        val dto =
            json.decodeFromString(
                dylan.provider.saavn.dto.ResultsDto
                    .serializer(),
                fixture("artists.json"),
            )
        val cards = dto.results
        assertEquals(20, cards.size)
        cards.forEach { c ->
            assertNotNull(permaArtistToken(c.permaUrl), "id=${c.id} name='${c.name}' perma_url=${c.permaUrl}")
            assertFalse(c.name.isBlank(), "every live artist card names itself")
        }
    }
}
