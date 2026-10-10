package dylan.provider.saavn

import dylan.provider.saavn.dto.SongDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `playlist`, `show` and `episode` cards are not results this app can act on, so they are not
 * results.
 *
 * The reported symptom was "greyed out results" in search: a show and a playlist arrived, had no
 * screen to open, were drawn dimmed — and dimmed is the visual language a *disabled song* uses, so
 * they read as broken songs. `mapMini` used to map them anyway and hand the list a row that could
 * not be opened or played.
 *
 * The shapes here are not invented. `fixtures/autocomplete_podcast.json` is a live response
 * captured 2026-10-09; see the `_capture` block inside it. Two things that capture settles:
 *
 *  * `shows` and `playlists` are their **own sections**, so they are not some mislabelled album or
 *    artist — the origin is answering a different question.
 *  * `topquery` — the "did you mean the top hit" slot — can also carry `type: "show"`, so a
 *    blocklist keyed on the *section* would have missed one of the two routes a show arrives by.
 *
 * `episodes` is **not** covered by a captured card: the section came back empty in six separate
 * queries. That is precisely why the rule in `mapMini` is an allowlist and not a blocklist — a type
 * nobody has seen is dropped by default rather than researched first.
 */
class UnsupportedTypeMappingTest {
    private fun fixture(name: String): String = File(System.getProperty("user.dir"), "../fixtures/$name").readText()

    private val json = Json { ignoreUnknownKeys = true }

    /** Every card in one section of the live autocomplete response. */
    private fun section(section: String): List<SongDto> =
        json
            .parseToJsonElement(fixture("autocomplete_podcast.json"))
            .jsonObject[section]!!
            .jsonObject["data"]!!
            .jsonArray
            .map { json.decodeFromJsonElement(SongDto.serializer(), it) }

    @Test
    fun theLiveShowCardsAreNotResults() {
        val shows = section("shows")
        assertTrue(shows.isNotEmpty(), "capture carries no show cards")
        assertTrue(shows.all { it.type == "show" }, "wrong types: ${shows.map { it.type }}")
        for (d in shows) {
            assertNull(mapMini(d), "a show card must not become a row: ${d.id} / ${d.title}")
            assertEquals(UNSUPPORTED_TYPE, miniDropReason(d), "show card $d")
        }
    }

    @Test
    fun theLivePlaylistCardsAreNotResults() {
        val playlists = section("playlists")
        assertTrue(playlists.isNotEmpty(), "capture carries no playlist cards")
        assertTrue(playlists.all { it.type == "playlist" }, "wrong types: ${playlists.map { it.type }}")
        for (d in playlists) {
            assertNull(mapMini(d), "a playlist card must not become a row: ${d.id} / ${d.title}")
            assertEquals(UNSUPPORTED_TYPE, miniDropReason(d), "playlist card $d")
        }
    }

    /**
     * `topquery` can carry a show, so the allowlist has to hold on that route too. A rule keyed on
     * the section a card arrived in — rather than on the card's own `type` — would let this one
     * through.
     */
    @Test
    fun aShowArrivingThroughTopQueryIsStillNotAResult() {
        val tops = section("topquery")
        val shows = tops.filter { it.type == "show" }
        assertTrue(shows.isNotEmpty(), "capture carries no show in topquery")
        for (d in shows) {
            assertNull(mapMini(d), "a show in topquery must not become a row: ${d.id} / ${d.title}")
            assertEquals(UNSUPPORTED_TYPE, miniDropReason(d), "topquery show $d")
        }
    }

    /**
     * The types this app does support keep mapping — and this capture pins *why* the album and
     * artist rows here still do not appear, which is a different reason from the one under test.
     *
     * The autocomplete response carries `url` on album and artist cards but **no `perma_url`**
     * (compare `fixtures/artists.json`, the `search.getArtistResults` response, which does carry it),
     * and [permaAlbumToken] / [permaArtistToken] read `perma_url` only. So those cards were already
     * dropped as [NO_PERMA_TOKEN] before this change and still are. The point of the assertion is
     * that the reason is *not* [UNSUPPORTED_TYPE] — the allowlist must not over-reach.
     */
    @Test
    fun theSupportedTypesInTheSameCaptureStillMap() {
        val songs = section("songs")
        assertTrue(songs.isNotEmpty(), "capture carries no song cards")
        assertTrue(songs.all { mapMini(it) != null }, "a song in this capture failed to map")

        for (s in "albums artists".split(" ")) {
            val cards = section(s)
            assertTrue(cards.isNotEmpty(), "capture carries no $s cards")
            for (d in cards) {
                assertNull(mapMini(d), "an autocomplete $s card maps: ${d.id}")
                assertEquals(NO_PERMA_TOKEN, miniDropReason(d), "$s card $d")
            }
        }
    }

    /** An unknown type is dropped by the allowlist, and says so, without anyone having seen one. */
    @Test
    fun aTypeNobodyHasSeenIsDroppedAndNamedRatherThanMapped() {
        val d = SongDto(id = "999", title = "Some Future Entity", type = "whatever")
        assertNull(mapMini(d))
        assertEquals(UNSUPPORTED_TYPE, miniDropReason(d))
    }

    /** The two answers cannot drift: a card [mapMini] keeps must not be reported as unsupported. */
    @Test
    fun theDropReasonAgreesWithTheMapperForEveryCardInTheCapture() {
        val all = "songs albums artists playlists shows topquery".split(" ").flatMap(::section)
        assertTrue(all.isNotEmpty(), "capture is empty")
        for (d in all) {
            val why = miniDropReason(d)
            if (mapMini(d) == null) {
                assertTrue(why.isNotEmpty(), "mapMini dropped a card and miniDropReason said nothing: $d")
            } else {
                assertTrue(why.isEmpty(), "miniDropReason reported '$why' for a card mapMini kept: $d")
            }
        }
    }
}
