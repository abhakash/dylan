package dylan.provider.saavn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #9: search showed **0 artists**, and the cause was unproven.
 *
 * `Mapper.mapMini` drops an artist card whose `permaUrl` yields no token, and `permaArtistToken`
 * requires the literal substring `/artist/`. If Saavn's artist cards carried some other shape, every
 * artist would be dropped silently. The live API is unreachable from the machine this was written
 * on, so this file does two things instead of guessing:
 *
 *  1. it pins the **current** behaviour of [permaArtistToken] for every plausible `permaUrl` shape,
 *     so the assumption is written down and falsifiable rather than folklore; and
 *  2. it proves what the search path does with a dropped card, which is where the actual defect is:
 *     the drop reason never reaches any log.
 *
 * The corpus test at the end is the evidence for *not* widening the extraction: every artist-typed
 * card in every payload captured in this repo carries `/artist/`, in all of them.
 */
class PermaArtistTokenTest {
    // ── 1. what the extraction does today, shape by shape ───────────────────────────────────────

    /**
     * The three shapes this repo has actually captured, from three different endpoints.
     *
     * `webapi.getBrowseHoverDetails` (`2.har`) and the autocomplete WS frame ship the full URL;
     * `webapi.getFooterDetails` ships the same token as a host-relative `action` path, which is the
     * shape that would break on a naive `https?://` assumption and does not here.
     */
    @Test
    fun theShapesTheRepoHasCapturedAllYieldAToken() {
        assertEquals(
            "LlRWpHzy3Hk_",
            permaArtistToken("https://www.jiosaavn.com/artist/arijit-singh-songs/LlRWpHzy3Hk_"),
        )
        assertEquals(
            "pUky52hsXJQ_",
            permaArtistToken("https://www.jiosaavn.com/artist/awadhesh-premi-yadav-songs/pUky52hsXJQ_"),
        )
        assertEquals("LlRWpHzy3Hk_", permaArtistToken("/artist/arijit-singh-songs/LlRWpHzy3Hk_"))
        // `search.topAlbumsoftheYear` ships staging hosts; the host is irrelevant to the token.
        assertEquals(
            "nQKQiNRsTKs_",
            permaArtistToken("https://staging.jiosaavn.com/artist/mithoon-songs/nQKQiNRsTKs_"),
        )
    }

    /** A query string after the token is not part of it. */
    @Test
    fun aQueryStringAfterTheTokenIsStripped() {
        assertEquals(
            "LlRWpHzy3Hk_",
            permaArtistToken("https://www.jiosaavn.com/artist/arijit-singh-songs/LlRWpHzy3Hk_?utm=x"),
        )
    }

    /**
     * Every shape that yields **nothing** today. This is the list a future change has to justify
     * itself against: if any of these becomes acceptable, it is because a captured payload proved
     * it is a real artist card and not a guess.
     */
    @Test
    fun theShapesThatYieldNothingTodayArePinned() {
        assertNull(permaArtistToken(null), "absent field")
        assertNull(permaArtistToken(""), "empty string")
        assertNull(permaArtistToken("   "), "blank string")
        assertNull(permaArtistToken("610240"), "a bare numeric id: no numeric-artist route exists (see artistRoute)")
        assertNull(
            permaArtistToken("https://www.jiosaavn.com/album/awarapan-2/e9NTAB1tQ9M_"),
            "an album URL on an artist card",
        )
        assertNull(
            permaArtistToken("https://www.jiosaavn.com/song/awarapan/B1oAUhNbQ0Y"),
            "a song URL on an artist card",
        )
        assertNull(
            permaArtistToken("https://www.jiosaavn.com/artist/eminem-songs/"),
            "the artist path with an empty token",
        )
        assertNull(permaArtistToken("artist/eminem-songs/-f6Su9-0agk_"), "no leading slash before 'artist'")
        assertNull(
            permaArtistToken("https://www.jiosaavn.com/ARTIST/eminem-songs/-f6Su9-0agk_"),
            "the match is case-sensitive",
        )
        assertNull(
            permaArtistToken("https://www.jiosaavn.com/artists/eminem-songs/-f6Su9-0agk_"),
            "a plural path segment",
        )
    }

    /**
     * Two shapes that yield a **wrong** token rather than none, which is sharper than a drop: the id
     * is minted, the row renders, and the artist screen then requests a token the origin cannot
     * resolve and shows a 200 empty shell.
     *
     * Both are pinned rather than fixed. No captured payload has either shape, so "fixing" them
     * would be a guess about a payload nobody has seen — and the guess that put a numeric id in this
     * code is exactly what produced "Check your connection" on the artist screen.
     */
    @Test
    fun theShapesThatYieldAWrongTokenArePinnedAsKnownEdges() {
        assertEquals(
            "eminem-songs",
            permaArtistToken("https://www.jiosaavn.com/artist/eminem-songs"),
            "the slug is taken as the token",
        )
        assertEquals(
            "-f6Su9-0agk_/",
            permaArtistToken("https://www.jiosaavn.com/artist/eminem-songs/-f6Su9-0agk_/"),
            "the trailing slash joins the token",
        )
    }

    // ── 2. what happens to an artist card the mapper drops ───────────────────────────────────────

    /**
     * A well-formed artist card maps, and its id is the token the artist screen can resolve.
     *
     * Both card shapes are pinned, because the live card carries `name` and the fallback carries
     * `title`, and both must map: the second is F-27's defect, and it is asserted here as well as
     * in `ArtistSearchMappingTest` because this is the file that pins the drop path.
     */
    @Test
    fun aWellFormedArtistCardStillMaps() {
        val url = "https://www.jiosaavn.com/artist/eminem-songs/-f6Su9-0agk_"
        val m = mapMini(artistCard(permaUrl = url))
        assertNotNull(m)
        assertEquals("-f6Su9-0agk_", m.artistId)
        assertEquals("artist", m.type)
        assertEquals("Eminem", m.title)
        val titled = mapMini(artistCardWithTitle(permaUrl = url))
        assertNotNull(titled, "a title-carrying artist card must keep mapping")
        assertEquals("-f6Su9-0agk_", titled.artistId)
    }

    /**
     * The reason an artist card is dropped is available to the caller — this is what makes #9
     * diagnosable at all. It was true before this change too; the finding is that the *search path
     * throws it away* (see [anArtistDropIsClassifiedSoItCanBeDiagnosed] and the report).
     *
     * The reason must be the same whichever field carries the name, because a reason computed from
     * a different field than the mapper reads is a reason that names the wrong cause — which is
     * exactly what happened to F-27.
     */
    @Test
    fun anArtistDropIsClassifiedSoItCanBeDiagnosed() {
        assertEquals(
            NO_PERMA_TOKEN,
            miniDropReason(artistCard(permaUrl = "https://www.jiosaavn.com/artist/eminem-songs/")),
        )
        assertEquals(NO_PERMA_TOKEN, miniDropReason(artistCard(permaUrl = null)))
        assertEquals(NO_PERMA_TOKEN, miniDropReason(artistCard(permaUrl = "")))
        assertEquals(
            "",
            miniDropReason(artistCard(permaUrl = "https://www.jiosaavn.com/artist/eminem-songs/-f6Su9-0agk_")),
        )
        assertEquals(
            "",
            miniDropReason(artistCardWithTitle(permaUrl = "https://www.jiosaavn.com/artist/eminem-songs/-f6Su9-0agk_")),
        )
    }

    /**
     * The shape of that drop must reach the log with the URL in it.
     *
     * `Drift.detail` was `"artist:610240"` — type and id — which cannot distinguish "no perma_url
     * field", "an empty one" and "a shape we have never seen". The whole point of instrumenting the
     * drop is that the third of those is recognisable in a logcat line, so the raw URL (or its
     * absence) is part of the detail now.
     */
    @Test
    fun theDropDetailCarriesThePermaUrlThatFailedToYieldAToken() {
        val rows =
            decodeMiniPage(
                """{"total":"2","start":"1","results":[
                  {"id":"1","title":"Eminem","type":"artist",
                  "perma_url":"https://www.jiosaavn.com/artist/eminem-songs/"},
                  {"id":"2","title":"Nobody","type":"artist"}]}""",
                "artist",
                "search.getArtistResults",
                1,
            )
        val drops = rows.drift.filter { it.reason == NO_PERMA_TOKEN }
        assertEquals(2, drops.size, "both cards were dropped: ${rows.drift}")
        assertTrue(
            drops.any { it.detail?.contains("/artist/eminem-songs/") == true },
            "the URL that failed must be in the detail: ${drops.map { it.detail }}",
        )
        assertTrue(
            drops.any { it.detail?.contains("perma_url=<null>") == true },
            "an absent field must be distinguishable from an unusable one: ${drops.map { it.detail }}",
        )
    }

    /**
     * The other way a section can come back empty, and the one this machine actually reproduced:
     * the origin answers `{"error":{"code":"INPUT_INVALID",…}}`.
     *
     * That body is a valid JSON **object**, so it parsed as an empty page: zero rows, zero drift,
     * no error — byte-identical to "no artists for this query". This is the silent path, and it is
     * now a named drift.
     */
    @Test
    fun anErrorEnvelopeIsDriftAndNotAnEmptyPage() {
        val rows =
            decodeMiniPage(
                """{"error":{"code":"INPUT_INVALID","msg":" operation is not supported"}}""",
                "artist",
                "search.getArtistResults",
                1,
            )
        assertTrue(rows.items.items.isEmpty(), "…there is nothing in that body to deliver")
        val drift = rows.drift.singleOrNull()
        assertNotNull(drift, "…and it must say why: an empty section is not an answer")
        assertEquals(ORIGIN_ERROR, drift.reason)
        assertTrue(
            drift.detail.orEmpty().contains("INPUT_INVALID"),
            "…naming the code the origin sent: ${drift.detail}",
        )
    }

    /** The control: a section that genuinely has nothing is not drift, or the log means nothing. */
    @Test
    fun aGenuinelyEmptySectionIsNotDrift() {
        val rows = decodeMiniPage("""{"total":"0","start":"1","results":[]}""", "artist", "search.getArtistResults", 1)
        assertTrue(rows.items.items.isEmpty())
        assertTrue(rows.drift.isEmpty(), "no cards, no drops, no drift: ${rows.drift}")
    }

    // ── 3. the evidence for not widening the extraction ─────────────────────────────────────────

    /**
     * Every artist-typed card in every payload captured in this repo carries `/artist/` in its
     * `perma_url` — across `content.getTrending`, `content.getTopSearches`, `search.getResults`,
     * `webapi.get` (album and artist detail), `search.topAlbumsoftheYear` and the autocomplete WS
     * frame. If the artist *search* endpoint deviates, it deviates in a way none of these does.
     *
     * This is why [permaArtistToken] was **not** widened: there is no captured counterexample to
     * fix, and widening on a guess would mint a token for a URL shape nobody has observed.
     */
    @Test
    fun everyCapturedArtistCardThatCarriesAPermaUrlYieldsAToken() {
        val dir = File(System.getProperty("user.dir"), "../fixtures")
        val files = dir.listFiles { f: File -> f.name.endsWith(".json") }?.toList().orEmpty()
        assertTrue(files.isNotEmpty(), "the fixture corpus must exist: $dir")
        val walk = CorpusWalk()
        files.forEach { file -> walk.visit(file, artistNodes(parseTree(file.readText()), "")) }
        walk.assertClean()
        println("[corpus] ${walk.checked} artist cards across ${files.size} fixtures")
    }

    /**
     * Tally for [everyCapturedArtistCardThatCarriesAPermaUrlYieldsAToken].
     *
     * Split out because the nested `files.forEach { … forEach { … when { … } } }` was four levels
     * deep, and the assertions belong to the tally rather than to the traversal.
     */
    private class CorpusWalk {
        var checked = 0
        val unexpected = mutableListOf<String>()
        val withoutToken = mutableSetOf<String>()

        fun visit(
            file: File,
            nodes: List<Pair<String, JsonObject>>,
        ) {
            nodes.forEach { (path, node) -> check(file, path, node) }
        }

        private fun check(
            file: File,
            path: String,
            node: JsonObject,
        ) {
            val permaUrl = (node["perma_url"] as? JsonPrimitive)?.contentOrNull
            if (permaUrl != null) {
                checked++
                assertNotNull(
                    permaArtistToken(permaUrl),
                    "${file.name}$path: an artist card with no derivable token (perma_url=$permaUrl)",
                )
                return
            }
            // The only two artist-typed nodes in the corpus that carry no perma_url are not cards:
            // the root of an artist *detail* payload (the artist you asked for) and the "More
            // Artists" chip in the WS frame's `modules` array, which mapSuggestionPayload skips by
            // shape. A results card without the field would be dropped by mapMini, so this is
            // asserted rather than assumed.
            when {
                node["artistId"] != null -> withoutToken += "detail payload"
                node["params"] != null -> withoutToken += "modules chip"
                else -> unexpected += "${file.name}$path"
            }
        }

        fun assertClean() {
            assertTrue(checked > 50, "the corpus should hold many artist cards, found $checked")
            assertEquals(emptyList(), unexpected, "an artist-typed node with no perma_url and no known reason for it")
            assertEquals(
                setOf("detail payload", "modules chip"),
                withoutToken,
                "the corpus's two non-card artist nodes",
            )
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * An artist card in the shape the live endpoint ships it: `name`, **no `title`**.
     *
     * This helper carried `title = "Eminem"` until F-27. That is not cosmetic: with a `title` on
     * it, every card in this file mapped fine, so nothing here could see the defect — the live
     * `search.getArtistResults` payload has no `title` at all (`fixtures/artists.json`), which is
     * exactly why `search.getArtistResults` returned zero results while these tests stayed green.
     * The helper now mirrors the payload.
     */
    private fun artistCard(permaUrl: String?) =
        dylan.provider.saavn.dto.SongDto(
            id = "610240",
            name = "Eminem",
            type = "artist",
            permaUrl = permaUrl,
        )

    /** The fallback shape: an artist card that carries `title` and no `name`. */
    private fun artistCardWithTitle(permaUrl: String?) =
        dylan.provider.saavn.dto.SongDto(
            id = "610240",
            title = "Eminem",
            type = "artist",
            permaUrl = permaUrl,
        )

    /** Every `{"type":"artist"}` node anywhere in a payload, at any depth, with its path. */
    private fun artistNodes(
        el: JsonElement?,
        path: String,
    ): List<Pair<String, JsonObject>> {
        if (el == null) return emptyList()
        val here =
            if (el is JsonObject && el["type"]?.let { it as? JsonPrimitive }?.contentOrNull == "artist") {
                listOf(path to el)
            } else {
                emptyList()
            }
        val nested =
            when (el) {
                is JsonObject -> el.values.flatMap { artistNodes(it, "$path.$it") }
                is JsonArray -> el.flatMapIndexed { i, v -> artistNodes(v, "$path[$i]") }
                // The WS frame carries its payload as a nested JSON *string* (`resp`), and that is
                // where the autocomplete artist cards live.
                is JsonPrimitive ->
                    el.contentOrNull
                        ?.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
                        ?.let { runCatching { artistNodes(parseTree(it), "$path#json") }.getOrDefault(emptyList()) }
                        ?: emptyList()

                else -> emptyList()
            }
        return here + nested
    }

    private fun parseTree(text: String): JsonElement? {
        val parsed = runCatching { json.parseToJsonElement(text) }
        return parsed.getOrNull()
    }
}
