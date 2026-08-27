package dylan

import dylan.model.Quality
import dylan.provider.saavn.art500
import dylan.provider.saavn.coerceHas320
import dylan.provider.saavn.dto.AlbumDto
import dylan.provider.saavn.dto.ArtistDto
import dylan.provider.saavn.dto.AuthDto
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
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun fixture(name: String): String = java.io.File(System.getProperty("user.dir"), "../fixtures/$name").readText()

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
        val list = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SongDto.serializer()), fixture("top_searches.json"))
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

    @Test
    fun malformedFieldsNeverThrow() {
        val s = mapSong(json.decodeFromString(SongDto.serializer(), fixture("malformed_fields.json")))
        assertNotNull(s)
        assertEquals(0L, s.durationS)
    }

    @Test
    fun wsFrameParsesSuggestions() {
        val frame = fixture("autocomplete_ws_frame.json")
        val suggestions = mapSuggestions(frame)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.type == "album" || it.songKey != null })
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
        assertEquals("-f6Su9-0agk_", dylan.provider.saavn.permaArtistToken("https://www.jiosaavn.com/artist/eminem-songs/-f6Su9-0agk_"))
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
        assertTrue(sid.matches(Regex("[A-Za-z0-9_-]+")), "raw id must never reach SongKey (SQL token + filename safety)")
        assertEquals(64, sid.length, "unsafe id falls back to SHA-256 hex")
        assertEquals("Q72cSWjq", mapMini(SongDto(id = "Q72cSWjq", title = "t", type = "song"))!!.songKey!!.songId)
        val song =
            mapSong(
                SongDto(
                    id = "a:b",
                    title = "t",
                    moreInfo =
                        dylan.provider.saavn.dto
                            .MoreInfoDto(),
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
}
