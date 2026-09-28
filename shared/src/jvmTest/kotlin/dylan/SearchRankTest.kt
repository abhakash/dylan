package dylan

import dylan.model.MiniEntity
import dylan.model.Song
import dylan.model.SongKey
import dylan.provider.saavn.MiniKind
import dylan.provider.saavn.decodeMiniPage
import dylan.provider.saavn.dedupKey
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import dylan.provider.saavn.kind
import dylan.provider.saavn.mapMiniPaged
import dylan.provider.saavn.navigable
import dylan.provider.saavn.rowKey
import dylan.search.rankMinis
import dylan.search.rankMinisDistinct
import dylan.search.rankSongs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SearchRankTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
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

    private fun envelope(vararg cards: SongDto): ResultsDto = ResultsDto(total = null, start = null, results = cards.toList())

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

    /**
     * The pre-computed band must produce *exactly* the old ordering, including for titles that
     * differ only in case, padding, or an internal run of whitespace — the cases the old
     * per-comparison `trim().lowercase()` was quietly normalising away.
     */
    @Test
    fun precomputedBandMatchesPerComparisonNormalisation() {
        val titles =
            listOf(
                "  Laado  ",
                "LAADO",
                "Laado",
                "Laado\tRano",
                "Phir Se Laado",
                "Laado (Remix)",
            )
        val byOld = titles.sortedBy { dylan.search.relevanceBand("laado", it) }
        assertEquals(byOld, rankSongs("laado", titles.map(::song)).map { it.title })
        // "  Laado  ", "LAADO" and "Laado" are ONE band, so the stable sort keeps page order: the
        // winner is the first of them in the page, never a privileged spelling. (The mapper is what
        // trims a title; rankSongs must not mutate one.)
        assertEquals(titles.first(), rankSongs("laado", titles.map(::song)).first().title)
    }

    @Test
    fun miniPagedKeepsOnlyWantedType() {
        val dto =
            envelope(
                SongDto(id = "1", title = "Animal", type = "album", permaUrl = "https://www.jiosaavn.com/album/animal/abc"),
                SongDto(id = "2", title = "Animal Song", type = "song"),
                SongDto(id = "3", title = "Arijit", type = "artist", permaUrl = "https://www.jiosaavn.com/artist/arijit/xyz"),
            )
        val albums = mapMiniPaged(dto, "album", 1)
        assertEquals(1, albums.items.size)
        assertEquals("Animal", albums.items[0].title)
        val artists: List<MiniEntity> = mapMiniPaged(dto, "artist", 1).items
        assertEquals(listOf("Arijit"), artists.map { it.title })
        assertEquals("Animal", rankMinis("ani", albums.items + artists).first().title)
    }

    @Test
    fun `miniPaged falls back to page size total`() {
        val paged = mapMiniPaged(ResultsDto(results = emptyList()), "album", 2)
        assertEquals(0, paged.items.size)
        assertEquals(2, paged.page)
    }

    // ── one dedupe key, correct across namespaces ───────────────────────────────────────────

    /**
     * The origin repeats the same song across buckets (`autocomplete_ws_frame.json`: `8zyUtIEp`
     * appears in `data_0` and `data_2`) and ships titles with **trailing spaces**
     * (`top_searches.json`: `"Toh Phir Aao Tera Mera Rishta "`). The old key was
     * `title to (songKey?.songId ?: albumId.orEmpty())`: case- and whitespace-sensitive, and it put
     * three id namespaces in one slot, so a song and an album sharing a numeric id collided.
     */
    @Test
    fun dedupKeyIsNamespacedWhitespaceAndCaseInsensitive() {
        val asSong = mini("song", "songKey", "8zyUtIEp", "Awaara Bhanwara")
        val asSongTrailing = mini("song", "songKey", "8zyUtIEp", "Awaara Bhanwara ")
        assertEquals(dedupKey(asSong), dedupKey(asSongTrailing), "a trailing space must not fork an identity")
        assertNotEquals(
            dedupKey(asSong),
            dedupKey(mini("song", "songKey", "other", "Awaara Bhanwara")),
            "different songs are different rows",
        )

        val album = mini("album", "albumId", "79121261", "Awaara")
        assertNotEquals(
            dedupKey(album),
            dedupKey(mini("song", "songKey", "79121261", "Awaara")),
            "an album and a song sharing a numeric id must not collide",
        )
    }

    @Test
    fun aNonNavigableRowStillGetsAStableKey() {
        // A playlist card carries its id in permaToken only: mapMiniOf gives songKey to `song` and
        // nothing else, which is what makes the row un-openable but still keyable.
        val p1 = mini("playlist", "none", "429009648", "Awaaz")
        val p2 = mini("playlist", "none", "1181511835", "Awaaz")
        assertFalse(p1.navigable, "a playlist row is shown but not openable")
        assertEquals(
            MiniKind.OtherCard("playlist", "https://www.jiosaavn.com/playlist/Awaaz/429009648"),
            p1.kind,
        )
        assertNotEquals(p1.rowKey, p2.rowKey, "two same-titled playlists must not share a LazyColumn key")
        assertEquals(2, listOf(p1, p2).map { it.rowKey }.distinct().size)
    }

    @Test
    fun rankMinisDistinctDropsTheCrossBucketRepeat() {
        val duplicated = mini("song", "songKey", "8zyUtIEp", "Awaara Bhanwara")
        val spaced = mini("song", "songKey", "8zyUtIEp", "Awaara Bhanwara ")
        val other = mini("album", "albumId", "abc", "Awarapan 2")
        val out = rankMinisDistinct("awa", listOf(duplicated, spaced, other))
        assertEquals(listOf("Awaara Bhanwara", "Awarapan 2"), out.map { it.title })
    }

    @Test
    fun theProductionPageDecodeDropsOneCardAndKeepsTheRest() {
        val text =
            """{"total":"3","start":"1","results":[
              {"id":"1","title":"A","type":"album","perma_url":"https://www.jiosaavn.com/album/a/t1"},
              {"id":"2","title":"B","type":"album","perma_url":""},
              {"id":"3","title":"C","type":"album","perma_url":"https://www.jiosaavn.com/album/c/t3"}]}"""
        val rows = decodeMiniPage(text, "album", "search.getAlbumResults", 1)
        assertEquals(listOf("A", "C"), rows.items.items.map { it.title }, "one bad card, one dropped card")
        assertTrue(rows.drift.any { it.reason == dylan.provider.saavn.NO_PERMA_TOKEN }, "${rows.drift}")
    }

    private fun mini(
        type: String,
        idField: String,
        id: String,
        title: String,
    ): MiniEntity =
        MiniEntity(
            songKey = if (idField == "songKey") SongKey("saavn", id) else null,
            albumId = if (idField == "albumId") id else null,
            artistId = if (idField == "artistId") id else null,
            title = title,
            subtitle = "",
            type = type,
            image = "",
            permaToken = "https://www.jiosaavn.com/$type/$title/$id",
        )

    // ── measurement ─────────────────────────────────────────────────────────────────────────
    //
    // Not JMH: the module has no benchmark source set. Plain `measureNanoTime` over a fixed
    // workload after 3 warmup rounds, with a blackhole accumulator so the JIT cannot delete the
    // work, plus `getThreadAllocatedBytes` for the allocation claim. Same pattern as
    // `jvmTest/support/LogSinkPerfHarness.kt`.

    private val allocatedBytes: (Long) -> Long =
        run {
            val bean =
                java.lang.management.ManagementFactory
                    .getThreadMXBean()
            if (bean is com.sun.management.ThreadMXBean) {
                { id: Long -> bean.getThreadAllocatedBytes(id) }
            } else {
                { _: Long -> -1L }
            }
        }

    private var blackhole = 0L

    @Test
    fun rankingNormalisesEachTitleOnceNotOncePerComparison() {
        // A realistic submit page is 20 rows; the measured set is 20x that so the comparator runs a
        // realistic number of times against a fixed, seeded input.
        val items = List(BENCH_ROWS) { i -> song("Track $i ${"x".repeat(i % 7)}") }
        assertEquals(
            items.sortedBy { dylan.search.relevanceBand("track", it.title) }.map { it.title },
            rankSongs("track", items).map { it.title },
            "the precomputed band must order identically",
        )
        var ref = 0
        val oldNs = bench { ref += items.sortedBy { dylan.search.relevanceBand("track", it.title) }.size }
        var now = 0
        val newNs = bench { now += rankSongs("track", items).size }
        assertEquals(ref, now, "both forms must produce the same number of rows")
        println(
            "[bench] %-46s %8.3f ms  %6d ns/item".format(
                "OLD rankSongs: band re-normalised per compare",
                oldNs * PER_OP / 1_000_000.0,
                oldNs,
            ),
        )
        println(
            "[bench] %-46s %8.3f ms  %6d ns/item".format(
                "NEW rankSongs: band computed once per element",
                newNs * PER_OP / 1_000_000.0,
                newNs,
            ),
        )
        println(
            "[bench] %-46s %6.1f%%".format(
                "=> CPU removed by the pre-computed band",
                100.0 * (oldNs - newNs) / oldNs,
            ),
        )
        assertTrue(newNs <= oldNs, "the precomputed band must not be slower: new=$newNs old=$oldNs")
    }

    @Test
    fun dedupKeyCostsOneNormalisationPerRowAndOneHashLookup() {
        val rows = List(BENCH_ROWS) { i -> mini("song", "songKey", "id$i", "  Song $i  ") }
        var newRows = 0
        val newNs = bench { newRows += rows.map { dedupKey(it) }.size }
        var oldRows = 0
        val oldNs = bench { oldRows += rows.map { it.title to (it.songKey?.songId ?: it.albumId.orEmpty()) }.size }
        println(
            "[bench] %-46s %8.3f ms  %6d ns/row".format(
                "NEW dedupKey (namespaced, normalised)",
                newNs * PER_OP / 1_000_000.0,
                newNs,
            ),
        )
        println(
            "[bench] %-46s %8.3f ms  %6d ns/row".format(
                "OLD title-to-(id?:id?:empty) key",
                oldNs * PER_OP / 1_000_000.0,
                oldNs,
            ),
        )
        // bench() runs the block PER_OP times per round and WARMUP_ROUNDS rounds first, so both
        // accumulators count the same number of calls: comparing them is the blackhole check.
        assertEquals(oldRows, newRows, "both forms must key the same number of rows")
        assertTrue(newNs <= oldNs, "the typed key must not be slower: new=$newNs old=$oldNs")
    }

    /** Per-op nanoseconds, after [WARMUP_ROUNDS] warmup rounds, with a blackhole accumulator. */
    private fun bench(block: (Int) -> Unit): Long {
        repeat(WARMUP_ROUNDS) { for (i in 0 until PER_OP) block(i) }
        val id = Thread.currentThread().id
        val before = allocatedBytes(id)
        val ns = kotlin.system.measureNanoTime { for (i in 0 until PER_OP) block(i) }
        blackhole += ns
        val bytes = allocatedBytes(id) - before
        if (bytes >= 0) {
            println("[bench] %-46s %6d B/op".format("   (allocation)", bytes / PER_OP))
        }
        return ns / PER_OP
    }

    private companion object {
        const val PER_OP = 200
        const val WARMUP_ROUNDS = 3
        const val BENCH_ROWS = 400
    }
}
