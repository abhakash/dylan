package dylan

import dylan.model.MiniEntity
import dylan.model.Song
import dylan.model.SongKey
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import dylan.provider.saavn.mapMiniPaged
import dylan.search.rankMinis
import dylan.search.rankSongs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRankTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun song(title: String) =
        Song(
            key = SongKey("saavn", title),
            title = title,
            subtitle = "",
            albumId = null,
            albumName = null,
            artUrl150 = "",
            artUrl500 = "",
            durationS = 0,
            has320 = false,
            resolveRef = null,
            permaToken = null,
        )

    @Test
    fun exactBeatsPrefixBeatsContains() {
        val inOrder =
            listOf(
                song("Laado Rano"),
                song("Phir Se Laado"),
                song("Laado"),
                song("Laado (Remix)"),
            )
        val ranked = rankSongs("laado", inOrder).map { it.title }
        assertEquals(listOf("Laado", "Laado Rano", "Laado (Remix)", "Phir Se Laado"), ranked)
    }

    @Test
    fun rankingIsStableWithinBand() {
        val inOrder = listOf(song("B Song"), song("A Song"))
        assertEquals(listOf("B Song", "A Song"), rankSongs("song", inOrder).map { it.title })
    }

    @Test
    fun miniPagedKeepsOnlyWantedType() {
        val dto =
            ResultsDto(
                total = null,
                start = null,
                results =
                    listOf(
                        SongDto(id = "1", title = "Animal", type = "album", permaUrl = "https://www.jiosaavn.com/album/animal/abc"),
                        SongDto(id = "2", title = "Animal Song", type = "song"),
                        SongDto(id = "3", title = "Arijit", type = "artist", permaUrl = "https://www.jiosaavn.com/artist/arijit/xyz"),
                    ),
            )
        val albums = mapMiniPaged(dto, "album", 1)
        assertEquals(1, albums.items.size)
        assertEquals("Animal", albums.items[0].title)
        val artists: List<MiniEntity> = mapMiniPaged(dto, "artist", 1).items
        assertEquals(listOf("Arijit"), artists.map { it.title })
        assertTrue(rankMinis("ani", albums.items + artists).first().title == "Animal")
    }

    @Test
    fun `miniPaged falls back to page size total`() {
        val paged = mapMiniPaged(ResultsDto(results = emptyList()), "album", 2)
        assertEquals(0, paged.items.size)
        assertEquals(2, paged.page)
    }
}
