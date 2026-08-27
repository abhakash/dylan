package dylan.tools

import dylan.config.AppConfig
import dylan.net.apiClient
import dylan.provider.saavn.dto.AlbumDto
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import dylan.provider.saavn.mapAlbum
import dylan.provider.saavn.mapMini
import dylan.provider.saavn.mapPaged
import dylan.provider.saavn.permaAlbumToken
import dylan.provider.saavn.permaArtistToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.system.exitProcess

private const val MAX_DEPTH = 4
private const val PRESENCE_FLOOR = 0.6

private val json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private enum class Kind { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, NULL }

private data class Drift(
    val field: String,
    val kind: String,
    val sample: String,
)

private fun kindOf(el: JsonElement): Kind =
    when {
        el is JsonNull -> Kind.NULL
        el is JsonObject -> Kind.OBJECT
        el is JsonArray -> Kind.ARRAY
        el is JsonPrimitive && el.isString -> Kind.STRING
        el is JsonPrimitive && el.booleanOrNull != null -> Kind.BOOLEAN
        else -> Kind.NUMBER
    }

private fun sampleOf(el: JsonElement): String =
    when (el) {
        is JsonNull -> "null"
        is JsonArray -> "[${el.size} items]"
        is JsonObject -> "{${el.keys.size} keys}"
        is JsonPrimitive -> el.content.take(48)
    }

private class Shape(
    val family: String,
) {
    val kinds = LinkedHashMap<String, MutableSet<Kind>>()
    val presence = HashMap<String, Int>()
    val samples = LinkedHashMap<String, String>()
    var objects = 0

    fun absorb(obj: JsonObject) {
        objects += 1
        val seen = HashSet<String>()
        walkInto(obj, "", 0, seen)
        seen.forEach { path -> presence.merge(path, 1, Int::plus) }
    }

    private fun walkInto(
        el: JsonElement,
        prefix: String,
        depth: Int,
        seen: MutableSet<String>,
    ) {
        when (el) {
            is JsonObject ->
                if (depth < MAX_DEPTH) {
                    el.forEach { (key, value) ->
                        walkInto(value, if (prefix.isEmpty()) key else "$prefix.$key", depth + 1, seen)
                    }
                }
            is JsonArray ->
                if (depth < MAX_DEPTH) el.take(4).forEach { item -> walkInto(item, prefix, depth + 1, seen) }
            else ->
                if (prefix.isNotEmpty()) {
                    kinds.getOrPut(prefix) { LinkedHashSet() }.add(kindOf(el))
                    samples.putIfAbsent(prefix, sampleOf(el))
                    seen.add(prefix)
                }
        }
    }
}

private fun parse(text: String?): JsonElement? = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

private fun textOf(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull

private fun readFixture(name: String): String? = File("fixtures", name).takeIf { it.isFile }?.readText()

private fun expectedShapes(): Map<String, Shape> {
    val out = LinkedHashMap<String, Shape>()

    fun add(
        family: String,
        objs: List<JsonObject>,
    ) {
        if (objs.isEmpty()) return
        val shape = out.getOrPut(family) { Shape(family) }
        objs.forEach(shape::absorb)
    }

    parse(readFixture("search_getresults_p1.json"))?.let { root ->
        (root as? JsonObject)?.let { o ->
            add("SEARCH_ENVELOPE", listOf(o))
            add("FULL_SONG", (o["results"] as? JsonArray)?.mapNotNull { e -> e as? JsonObject }.orEmpty())
        }
    }
    parse(readFixture("album_detail_full.json"))?.let { root ->
        (root as? JsonObject)?.let { o ->
            add("ALBUM_ENVELOPE", listOf(o))
            add("FULL_SONG", (o["list"] as? JsonArray)?.mapNotNull { e -> e as? JsonObject }.orEmpty())
        }
    }
    val minis =
        listOf("top_searches.json", "trending.json").flatMap { name ->
            parse(readFixture(name))?.let { el -> el as? JsonArray }.orEmpty().mapNotNull { e -> e as? JsonObject }
        }
    add("MINI_CARD", minis)
    return out
}

private fun rank(kind: String): Int =
    when (kind) {
        "MISSING_FIELD" -> 0
        "TYPE_DRIFT" -> 1
        "NULLABILITY_DRIFT" -> 2
        "NEW_FIELD" -> 9
        else -> 5
    }

private fun compareShapes(
    expected: Shape?,
    live: Shape,
    rows: MutableList<Drift>,
) {
    if (expected == null || expected.objects == 0) return
    if (live.objects == 0) {
        rows += Drift(expected.family, "ENDPOINT_EMPTY", "no live objects sampled")
        return
    }
    expected.kinds.forEach { (path, expKinds) ->
        val present = expected.presence[path] ?: 0
        val liveKinds = live.kinds[path]
        when {
            liveKinds == null && present >= expected.objects * PRESENCE_FLOOR ->
                rows += Drift("${expected.family}.$path", "MISSING_FIELD", "fixture $present/${expected.objects}")
            liveKinds != null && liveKinds.intersect(expKinds).isEmpty() ->
                rows += Drift("${expected.family}.$path", "TYPE_DRIFT", "fixture=$expKinds live=$liveKinds e.g. ${live.samples[path]}")
            liveKinds == setOf(Kind.NULL) && Kind.NULL !in expKinds ->
                rows += Drift("${expected.family}.$path", "NULLABILITY_DRIFT", "e.g. ${live.samples[path]}")
            else -> Unit
        }
    }
    live.kinds.forEach { (path, _) ->
        if (!expected.kinds.containsKey(path)) {
            rows += Drift("${expected.family}.$path", "NEW_FIELD", "e.g. ${live.samples[path]}")
        }
    }
}

object ContractDrift {
    private lateinit var cfg: AppConfig
    private lateinit var api: HttpClient
    private val rows = mutableListOf<Drift>()

    private suspend fun fetch(
        label: String,
        vararg params: Pair<String, String>,
    ): Pair<Int, String?> {
        val t0 = System.nanoTime()
        val resp =
            api.get(cfg.apiBaseUrl) {
                params.forEach { (key, value) -> parameter(key, value) }
                cfg.commonParams.forEach { (key, value) -> parameter(key, value) }
                header(HttpHeaders.UserAgent, cfg.userAgent)
            }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val ok = resp.status.isSuccess()
        val text =
            runCatching { resp.bodyAsText() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
        println("[net] $label status=${resp.status.value} ${ms}ms chars=${text?.length ?: 0}")
        return resp.status.value to text.takeIf { ok }
    }

    private fun checkMinis(
        label: String,
        payload: Pair<Int, String?>,
        liveShape: Shape,
    ): List<SongDto> {
        val text = payload.second
        if (text == null) {
            println("[map] $label UNREACHABLE status=${payload.first}")
            return emptyList()
        }
        parse(text)?.let { root ->
            (root as? JsonArray)?.take(40)?.forEach { e -> (e as? JsonObject)?.let(liveShape::absorb) }
        }
        val dtos =
            runCatching { json.decodeFromString(ListSerializer(SongDto.serializer()), text) }.getOrElse { emptyList() }
        if (dtos.isEmpty()) {
            rows += Drift(label, "ENDPOINT_EMPTY", "no mini cards decoded head='${text.take(40)}'")
            return emptyList()
        }
        val minis = dtos.mapNotNull(::mapMini)
        println("[map] $label decoded=${dtos.size} mapped=${minis.size}")
        val pairs = dtos.map { d -> d.title to d.id }
        if (pairs.size > pairs.distinct().size) {
            rows += Drift("$label.minis", "DUPLICATE_CARDS", "${pairs.size} cards -> ${pairs.distinct().size} distinct")
        }
        if (minis.size < dtos.size) {
            rows += Drift("$label.minis", "MAPPING_LOSS", "${dtos.size - minis.size} of ${dtos.size} cards dropped by mapMini")
        }
        minis
            .filter { m -> m.type == "album" }
            .forEach { m ->
                val derived = permaAlbumToken(m.permaToken)
                when {
                    m.albumId == null -> rows += Drift("$label.albumId", "TOKEN_DERIVATION_FAILED", "perma_url=${m.permaToken ?: "?"}")
                    derived == null -> rows += Drift("$label.albumId", "FALLBACK_NUMERIC_ID", "id=${m.albumId} (numeric ids yield empty shells)")
                    derived != m.albumId -> rows += Drift("$label.albumId", "TOKEN_MISMATCH", "derived=$derived mapped=${m.albumId}")
                    else -> Unit
                }
            }
        minis
            .filter { m -> m.type == "artist" }
            .forEach { m ->
                val derived = permaArtistToken(m.permaToken)
                when {
                    m.artistId == null -> rows += Drift("$label.artistId", "TOKEN_DERIVATION_FAILED", "perma_url=${m.permaToken ?: "?"}")
                    derived == null -> rows += Drift("$label.artistId", "FALLBACK_NUMERIC_ID", "id=${m.artistId} (numeric ids yield empty shells)")
                    derived != m.artistId -> rows += Drift("$label.artistId", "TOKEN_MISMATCH", "derived=$derived mapped=${m.artistId}")
                    else -> Unit
                }
            }
        minis
            .filter { m -> m.type == "song" && m.songKey == null }
            .forEach { m -> rows += Drift("$label.songKey", "KEY_MISSING", m.title) }
        val types = minis.groupBy { m -> m.type }.mapValues { e -> e.value.size }
        println("[map] $label types=$types")
        return dtos
    }

    private fun checkPaged(
        label: String,
        text: String,
    ): ResultsDto? {
        val dto =
            runCatching { json.decodeFromString(ResultsDto.serializer(), text) }.getOrNull()
        if (dto == null) {
            println("[map] $label DECODE_FAIL head='${text.take(60)}'")
            return null
        }
        val paged = mapPaged(dto)
        val ids = dto.results.map { song -> song.id }.filter { id -> id.isNotBlank() }
        val distinct = ids.distinct()
        println(
            "[map] $label raw=${ids.size} distinct=${distinct.size} mapped=${paged.items.size}" +
                " total=${textOf(dto.total)} page=${textOf(dto.start)}",
        )
        if (paged.items.isEmpty()) rows += Drift("search.getResults.mapped", "EMPTY_MAPPING", text.take(60))
        if (ids.size > distinct.size) {
            rows += Drift("mapPaged.items", "DEDUPE_DROPPED", "${ids.size} raw ids -> ${distinct.size} distinct (D7 known dupes)")
        }
        val songs = paged.items
        partial("Song.resolveRef", songs.count { s -> s.resolveRef != null }, songs.size, "resolve_ref absent")
        partial("Song.permaToken", songs.count { s -> s.permaToken != null }, songs.size, "perma_url absent")
        partial("Song.durationS>0", songs.count { s -> s.durationS > 0 }, songs.size, "duration unparsed")
        partial(
            "Song.artUrl500Rewrite",
            songs.count { s -> s.artUrl500.contains("500x500") },
            songs.size,
            "rewrite fell back",
        )
        val raw320 = dto.results.mapNotNull { s -> s.moreInfo?.has320 }
        val bad320 = raw320.count { el -> el !is JsonPrimitive || el.booleanOrNull == null }
        if (bad320 > 0) rows += Drift("MoreInfo.has320", "PARSE_FAIL", "$bad320/${raw320.size} not boolean literal (mapper coerces to false)")
        return dto
    }

    private fun checkAlbum(
        token: String,
        text: String,
    ): Shape? {
        val root = parse(text)
        if (root == null) {
            rows += Drift("webapi.get album", "NOT_JSON", text.take(60))
            return null
        }
        val envelope = Shape("ALBUM_ENVELOPE")
        (root as? JsonObject)?.let(envelope::absorb)
        val album = runCatching { mapAlbum(json.decodeFromString(AlbumDto.serializer(), text)) }.getOrNull()
        if (album == null) {
            rows += Drift("webapi.get album", "MAPPED_NULL", "token=$token")
            return envelope
        }
        println("[map] webapi.get album '${album.title}' songs=${album.songs.size}")
        if (album.songs.isEmpty()) rows += Drift("mapAlbum.songs", "EMPTY_TRACKLIST", "token=$token")
        partial("Album.songs.resolveRef", album.songs.count { s -> s.resolveRef != null }, album.songs.size, "resolve_ref absent")
        partial(
            "Album.songs.artUrl500Rewrite",
            album.songs.count { s -> s.artUrl500.contains("500x500") },
            album.songs.size,
            "rewrite fell back",
        )
        return envelope
    }

    private fun partial(
        field: String,
        hits: Int,
        total: Int,
        why: String,
    ) {
        if (total > 0 && hits < total) rows += Drift(field, "PARTIAL_PRESENT", "$hits/$total ($why)")
    }

    suspend fun run(): Int {
        cfg = AppConfig()
        api = apiClient(CIO.create(), cfg)
        val expectations = expectedShapes()
        println("[exp] fixture-derived families: ${expectations.keys}")

        val liveEnvelope = Shape("SEARCH_ENVELOPE")
        val liveFullSong = Shape("FULL_SONG")
        val liveMiniCard = Shape("MINI_CARD")
        var albumToken: String? = null

        val search = fetch("search.getResults", "__call" to "search.getResults", "q" to "arijit singh", "p" to "1", "n" to cfg.submitPageSize.toString())
        val tops = fetch("content.getTopSearches", "__call" to "content.getTopSearches")
        val trending =
            fetch("content.getTrending", "__call" to "content.getTrending", "entity_type" to "album", "entity_language" to "hindi")

        val searchText = search.second
        if (searchText == null) {
            rows += Drift("search.getResults", "UNREACHABLE", "status=${search.first}")
        } else {
            parse(searchText)?.let { root ->
                (root as? JsonObject)?.let(liveEnvelope::absorb)
                ((root as? JsonObject)?.get("results") as? JsonArray)
                    ?.take(30)
                    ?.forEach { e -> (e as? JsonObject)?.let(liveFullSong::absorb) }
            }
            checkPaged("search.getResults", searchText)?.let { dto ->
                albumToken = dto.results.firstNotNullOfOrNull { s -> permaAlbumToken(s.permaUrl) }
            }
        }

        val topDtos = checkMinis("topSearches", tops, liveMiniCard)
        val trendDtos = checkMinis("trending", trending, liveMiniCard)

        if (albumToken == null) {
            albumToken =
                (topDtos + trendDtos)
                    .firstOrNull { d -> d.type == "album" }
                    ?.let { d -> permaAlbumToken(d.permaUrl) }
        }
        var liveAlbum: Shape? = null
        if (albumToken == null) {
            rows += Drift("webapi.get album", "SKIP_NO_TOKEN", "no derivable perma album token from live minis")
        } else {
            val album = fetch("webapi.get album", "__call" to "webapi.get", "token" to albumToken, "type" to "album", "includeMetaTags" to "0")
            val albumText = album.second
            if (albumText == null) {
                rows += Drift("webapi.get album", "UNREACHABLE", "token=$albumToken status=${album.first}")
            } else {
                parse(albumText)?.let { root ->
                    ((root as? JsonObject)?.get("list") as? JsonArray)
                        ?.take(20)
                        ?.forEach { e -> (e as? JsonObject)?.let(liveFullSong::absorb) }
                }
                liveAlbum = checkAlbum(albumToken, albumText)
            }
        }

        compareShapes(expectations["SEARCH_ENVELOPE"], liveEnvelope, rows)
        compareShapes(expectations["FULL_SONG"], liveFullSong, rows)
        compareShapes(expectations["MINI_CARD"], liveMiniCard, rows)
        liveAlbum?.let { s -> compareShapes(expectations["ALBUM_ENVELOPE"], s, rows) }

        val sorted = rows.sortedWith(compareBy({ rank(it.kind) }, { it.field }))
        val stamp = java.time.Instant.now()
        println()
        println("=== DYLAN contract-drift @ $stamp ===")
        if (sorted.isEmpty()) {
            println("NO DRIFT: live payloads match fixture-derived expectations")
        } else {
            println("--- drift findings: ${sorted.size} (field | kind | sample) ---")
            sorted.forEach { d -> println("${d.field} | ${d.kind} | ${d.sample}") }
            sorted.groupBy { it.kind }.forEach { (kind, list) -> println("summary $kind=${list.size}") }
        }
        val reachable = listOf(search, tops, trending).count { it.second != null }
        println("--- endpoints reachable: $reachable/3 ---")
        return if (reachable > 0) 0 else 1
    }
}

fun main(): Unit = exitProcess(runBlocking { ContractDrift.run() })
