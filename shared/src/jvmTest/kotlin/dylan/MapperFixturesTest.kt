package dylan

import dylan.model.Quality
import dylan.provider.durationKnown
import dylan.provider.dylanJson
import dylan.provider.saavn.art500
import dylan.provider.saavn.coerceHas320
import dylan.provider.saavn.dto.AlbumDto
import dylan.provider.saavn.dto.ArtistDto
import dylan.provider.saavn.dto.AuthDto
import dylan.provider.saavn.dto.MoreInfoDto
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import dylan.provider.saavn.mapAlbum
import dylan.provider.saavn.mapArtist
import dylan.provider.saavn.mapAuth
import dylan.provider.saavn.mapMini
import dylan.provider.saavn.mapPaged
import dylan.provider.saavn.mapSong
import dylan.provider.saavn.mapSuggestions
import dylan.provider.saavn.normalizePermaToken
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapperFixturesTest {
    private val json = dylanJson

    private fun fixturesDir() = java.io.File(System.getProperty("user.dir"), "../fixtures")

    private fun testSources(): List<java.io.File> {
        val root = java.io.File(System.getProperty("user.dir"), "src")
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    private fun fixture(name: String): String =
        java.io
            .File(
                System.getProperty("user.dir"),
                "../fixtures/$name",
            ).readText()

    @Test
    fun albumDetailMapsAllSongs() {
        val album = mapAlbum(json.decodeFromString(AlbumDto.serializer(), fixture("album_detail_full.json")))
        assertNotNull(album)
        assertEquals("79121261", album.id)
        assertEquals("Awarapan 2", album.title)
        assertTrue(album.songs.isNotEmpty())
        val s = album.songs.first()
        assertTrue(s.has320)
        assertTrue(s.durationS > 0)
        assertNotNull(s.resolveRef)
        assertTrue(s.artUrl500.contains("500x500"))
    }

    @Test
    fun searchResultsPageMapsAndDedupesReady() {
        val paged = mapPaged(json.decodeFromString(ResultsDto.serializer(), fixture("search_getresults_p1.json")))
        assertEquals(5, paged.items.size)
        assertEquals(134L, paged.total)
        assertEquals(1, paged.page)
        assertTrue(paged.items.all { it.key.provider == "saavn" })
    }

    @Test
    fun emptySearchYieldsEmptyPage() {
        val paged = mapPaged(json.decodeFromString(ResultsDto.serializer(), fixture("search_empty.json")))
        assertEquals(0, paged.items.size)
        assertEquals(0L, paged.total)
    }

    @Test
    fun topSearchesMapToMiniEntities() {
        val list =
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(SongDto.serializer()),
                fixture("top_searches.json"),
            )
        val minis = list.mapNotNull(::mapMini)
        assertTrue(minis.isNotEmpty())
        assertTrue(minis.any { it.type == "album" && it.albumId != null })
        assertTrue(minis.any { it.type == "song" && it.songKey != null })
    }

    @Test
    fun authTokenMapsSignedUrl() {
        val auth = mapAuth(json.decodeFromString(AuthDto.serializer(), fixture("generate_auth_token_128.json")))
        assertNotNull(auth)
        assertTrue(auth.url.startsWith("https://"))
        assertTrue(auth.url.contains("_160.mp4") || auth.url.contains("_320.mp4"))
        assertEquals("mp4", auth.type)
    }

    @Test
    fun songWithout320Forces128Eligibility() {
        val s = mapSong(json.decodeFromString(SongDto.serializer(), fixture("song_no320.json")))
        assertNotNull(s)
        assertFalse(s.has320)
    }

    @Test
    fun legacyNonCacheableStillMapsAndDownloads() {
        // Post-cacheable-removal: the legacy rights.cacheable=false payload still maps, and
        // resolveRef presence (not rights) is what makes it downloadable. No cacheable assert.
        val s = mapSong(json.decodeFromString(SongDto.serializer(), fixture("song_not_cacheable.json")))
        assertNotNull(s)
        assertNotNull(s.resolveRef, "legacy non-cacheable song must still carry resolveRef")
        assertTrue(s.has320)
        assertEquals("QhgTazF3QmA", s.permaToken, "permaToken stored normalized (last segment)")
    }

    @Test
    fun has320CoercesBooleanStringAndNumber() {
        assertTrue(coerceHas320(Json.parseToJsonElement("true")))
        assertTrue(coerceHas320(Json.parseToJsonElement("\"true\"")))
        assertTrue(coerceHas320(Json.parseToJsonElement("\"1\"")))
        assertTrue(coerceHas320(Json.parseToJsonElement("1")))
        assertFalse(coerceHas320(Json.parseToJsonElement("false")))
        assertFalse(coerceHas320(Json.parseToJsonElement("\"false\"")))
        assertFalse(coerceHas320(Json.parseToJsonElement("0")))
        assertFalse(coerceHas320(Json.parseToJsonElement("\"0\"")))
        assertFalse(coerceHas320(Json.parseToJsonElement("\"yes\"")))
        assertFalse(coerceHas320(null))
    }

    @Test
    fun permaTokenNormalizesToLastSegment() {
        assertEquals("QhgTazF3QmA", normalizePermaToken("https://www.jiosaavn.com/song/ve-junoon/QhgTazF3QmA"))
        assertEquals("QhgTazF3QmA", normalizePermaToken("https://www.jiosaavn.com/song/ve-junoon/QhgTazF3QmA?x=1"))
        assertEquals("bare-token_", normalizePermaToken("bare-token_"))
        assertNull(normalizePermaToken(null))
        assertNull(normalizePermaToken("   "))
    }

    @Test
    fun songWithoutResolveRefIsNull() {
        val s = mapSong(json.decodeFromString(SongDto.serializer(), fixture("song_no_resolve_ref.json")))
        assertNotNull(s)
        assertNull(s.resolveRef)
    }

    /**
     * The contract is explicit rather than "never negative": an unparseable duration is a *named
     * drift*, and `durationKnown(0)` is false, so a caller can tell "unknown" from "zero-length".
     * Asserting `durationS == 0` could not distinguish a defensive fallback from a parsing
     * regression, because 0 is also a value a real payload produces.
     */
    @Test
    fun malformedFieldsYieldAnExplicitUnknownDurationNotASilentZero() {
        val s = mapSong(json.decodeFromString(SongDto.serializer(), fixture("malformed_fields.json")))
        assertNotNull(s)
        assertEquals(0L, s.durationS)
        assertFalse(durationKnown(s.durationS), "\"not-a-number\" is unknown, not zero-length")
        val drift = mutableListOf<dylan.provider.Drift>()
        val again =
            dylan.provider.saavn.mapCard(
                json.parseToJsonElement(fixture("malformed_fields.json")),
                "test",
                drift,
            )
        assertNotNull(again)
        assertTrue(
            drift.any { it.reason == dylan.provider.saavn.DURATION_UNPARSED },
            "the unparsed duration must be reported, not folded into the value: $drift",
        )
    }

    /**
     * A fixture no test imports is a bug. Three of fifteen were orphans on arrival
     * (`rate_limited_429.json`, `html_error_page.txt`, `expired_signature_403.txt`) and they were
     * precisely the provider's three error paths — the paths with no test file at all. This is the
     * 15-line test that would have caught them on day one.
     */
    @Test
    fun everyCommittedFixtureIsReferencedBySomeSourceFile() {
        val sources = testSources()
        assertTrue(sources.isNotEmpty(), "no test sources found from ${System.getProperty("user.dir")}")
        val corpus = sources.joinToString("\n") { it.readText() }
        val orphans =
            fixturesDir()
                .listFiles()
                .orEmpty()
                .filter { it.isFile }
                .map { it.name }
                .filterNot { corpus.contains("\"$it\"") || corpus.contains(it) }
                .sorted()
        assertEquals(emptyList(), orphans, "committed fixtures that nothing references")
    }

    @Test
    fun theFixtureSetIsTheOneTheSuiteExpects() {
        val names =
            fixturesDir()
                .listFiles()
                .orEmpty()
                .filter { it.isFile }
                .map { it.name }
                .toSortedSet()
        assertTrue(
            names.containsAll(
                listOf(
                    "rate_limited_429.json",
                    "html_error_page.txt",
                    "expired_signature_403.txt",
                    "autocomplete_ws_frame.json",
                    "malformed_fields.json",
                ),
            ),
            "the error-path fixtures must stay committed: $names",
        )
    }

    @Test
    fun wsFrameParsesSuggestions() {
        val frame = fixture("autocomplete_ws_frame.json")
        val rows = mapSuggestions(frame)
        assertTrue(rows.items.isNotEmpty(), "${rows.drift}")
        assertTrue(rows.items.any { it.type == "album" || it.songKey != null })
    }

    @Test
    fun artistDetailMapsHeroAndTopSongs() {
        val artist = mapArtist(json.decodeFromString(ArtistDto.serializer(), fixture("artist_detail.json")))
        assertNotNull(artist)
        assertEquals("610240", artist.id)
        assertEquals("Eminem", artist.name)
        assertTrue(artist.songs.size >= 2)
        assertTrue(artist.artUrl500.contains("500x500"))
        val s = artist.songs.first()
        assertEquals("Eminem", s.artistName)
        assertEquals("-f6Su9-0agk_", s.artistToken)
        assertTrue(s.durationS > 0)
        assertNotNull(s.resolveRef)
    }

    @Test
    fun permaArtistTokenDerivation() {
        assertEquals(
            "-f6Su9-0agk_",
            dylan.provider.saavn.permaArtistToken("https://www.jiosaavn.com/artist/eminem-songs/-f6Su9-0agk_"),
        )
        assertNull(dylan.provider.saavn.permaArtistToken(null))
        assertNull(dylan.provider.saavn.permaArtistToken("https://www.jiosaavn.com/album/x/abc_"))
    }

    @Test
    fun miniEntityCarriesArtistTokenForArtistType() {
        val m =
            mapMini(
                SongDto(
                    id = "610240",
                    title = "Eminem",
                    type = "artist",
                    permaUrl = "https://www.jiosaavn.com/artist/eminem-songs/-f6Su9-0agk_",
                ),
            )
        assertNotNull(m)
        assertEquals("-f6Su9-0agk_", m.artistId)
        assertNull(m.albumId)
        assertNull(m.songKey)
    }

    @Test
    fun art500RewritePreservesFallback() {
        assertEquals("https://x/abc-500x500.jpg", art500("https://x/abc-150x150.jpg"))
        assertEquals("https://x/plain.jpg", art500("https://x/plain.jpg"))
        assertNull(art500(null))
        assertNull(art500(""))
    }

    @Test
    fun songIdSanitizedAtAdapterBoundary() {
        val evil = mapMini(SongDto(id = "../evil:id:x", title = "t", type = "song"))
        assertNotNull(evil)
        val sid = evil.songKey!!.songId
        assertTrue(
            sid.matches(Regex("[A-Za-z0-9_-]+")),
            "raw id must never reach SongKey (SQL token + filename safety)",
        )
        assertEquals(64, sid.length, "unsafe id falls back to SHA-256 hex")
        assertEquals("Q72cSWjq", mapMini(SongDto(id = "Q72cSWjq", title = "t", type = "song"))!!.songKey!!.songId)
        val song =
            mapSong(
                SongDto(
                    id = "a:b",
                    title = "t",
                    moreInfo = Json.encodeToJsonElement(MoreInfoDto.serializer(), MoreInfoDto()),
                ),
            )
        assertNotNull(song)
        assertTrue(song.key.songId.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun qualityOfBits() {
        assertEquals(Quality.BITRATE_128, Quality.of(128))
        assertEquals(Quality.BITRATE_320, Quality.of(320))
        assertEquals(Quality.BITRATE_128, Quality.of(160))
    }

    // ── totality: the mapper may not throw, and may not drop silently ──────────────────────────

    /**
     * `JsonElement.jsonPrimitive` **throws** `IllegalArgumentException` on an object or an array,
     * and `image` is declared untyped on the DTO precisely because the API has shipped
     * `string → object → null`. So an object-shaped `image` (or `duration`, or a `total` that
     * arrived as an object) did not cost one card: the exception escaped `decodeSongPage`, and from
     * there `mapOnIo` and `searchPage` — past the `CatalogResult` contract that exists to make
     * failures *values*. The whole page went with it.
     */
    @Test
    fun anObjectShapedCardFieldCostsNoCardsAndThrowsNothing() {
        val rows =
            dylan.provider.saavn.decodeSongPage(
                """{"total":"2","start":"1","results":[
                  {"id":"a","title":"A","type":"song","image":{"150x150":"https://c.saavncdn.com/a-150x150.jpg"},
                   "more_info":{"duration":"10"}},
                  {"id":"b","title":"B","type":"song","image":{"150x150":"https://c.saavncdn.com/b-150x150.jpg"},
                   "more_info":{"duration":{"seconds":200}}}
                ]}""",
                "test",
                1,
            )
        assertEquals(listOf("A", "B"), rows.items.items.map { it.title }, "neither card may be lost: ${rows.drift}")
        val first = rows.items.items.first()
        assertEquals("https://c.saavncdn.com/a-150x150.jpg", first.artUrl150)
        assertEquals(
            "https://c.saavncdn.com/a-500x500.jpg",
            first.artUrl500,
            "the object shape still carries a usable picture, not a blank one",
        )
        assertFalse(durationKnown(rows.items.items[1].durationS), "an object duration is unknown, not zero-length")
        assertTrue(
            rows.drift.any { it.reason == dylan.provider.saavn.DURATION_UNPARSED },
            "…and says so: ${rows.drift}",
        )
    }

    /** The same shape on an envelope field: an unreadable `total` falls back, it does not throw. */
    @Test
    fun anObjectShapedEnvelopeFieldFallsBackInsteadOfThrowing() {
        val rows =
            dylan.provider.saavn.decodeSongPage(
                """{"total":{"n":5},"start":"1","results":[{"id":"a","title":"A","more_info":{"duration":"10"}}]}""",
                "test",
                1,
            )
        assertEquals(1, rows.items.items.size)
        assertEquals(1L, rows.items.total, "an unreadable total falls back to what we delivered")
    }

    /**
     * A short page must always say so.
     *
     * `coerceInputValues` means `"id": null` no longer fails the *decode* — it becomes `""` — so the
     * card died in the mapper's identity guard, where nothing recorded it. The page came back one row
     * short with an **empty** drift list: byte-identical to a page the origin really sent that way,
     * which is the exact indistinguishability `CatalogResult.Ok(drift)` exists to remove. (The
     * `"id": null` fixture the existing test used happened to also carry `"more_info": null`, so it
     * was counted by the NO_MORE_INFO branch and never reached this one.)
     */
    @Test
    fun aCardWithNoIdentityIsDriftNotASilentDrop() {
        val rows =
            dylan.provider.saavn.decodeSongPage(
                """{"total":"3","start":"1","results":[
                  {"id":null,"title":"No id","type":"song","more_info":{"duration":"10"}},
                  {"id":"b","title":"","type":"song","more_info":{"duration":"10"}},
                  {"id":"c","title":"C","type":"song","more_info":{"duration":"10"}}
                ]}""",
                "test",
                1,
            )
        assertEquals(listOf("C"), rows.items.items.map { it.title })
        assertEquals(
            2,
            rows.drift.count { it.reason == dylan.provider.saavn.BLANK_IDENTITY },
            "both unusable cards must be named: ${rows.drift}",
        )
    }

    /** The same hole in the mini path, which is where suggestion and top-search rows come from. */
    @Test
    fun aMiniCardWithNoIdentityIsDriftToo() {
        val rows =
            dylan.provider.saavn.decodeMiniPage(
                """{"total":"2","start":"1","results":[
                  {"id":null,"title":"No id","type":"song"},
                  {"id":"c","title":"C","type":"song"}
                ]}""",
                "album",
                "test",
                1,
            )
        assertTrue(
            rows.drift.any { it.reason == dylan.provider.saavn.BLANK_IDENTITY },
            "a mini that cannot be keyed must say so: ${rows.drift}",
        )
    }
}
