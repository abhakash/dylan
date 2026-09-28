package dylan.tools

import dylan.config.AppConfig
import dylan.net.apiClient
import dylan.provider.saavn.dto.AlbumDto
import dylan.provider.saavn.dto.ArtistDto
import dylan.provider.saavn.dto.MoreInfoDto
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.dto.SongDto
import dylan.provider.saavn.mapAlbum
import dylan.provider.saavn.mapMini
import dylan.provider.saavn.mapMiniPaged
import dylan.provider.saavn.mapPaged
import dylan.provider.saavn.permaAlbumToken
import dylan.provider.saavn.permaArtistToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import kotlin.system.exitProcess

private const val MAX_DEPTH = 4
private const val PRESENCE_FLOOR = 0.6
private const val WS_FRAME_TIMEOUT_MS = 8_000L

private val json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private enum class Kind { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, NULL }

/**
 * A finding is [FAIL] only when the live payload contradicts the committed
 * fixture in a way that changes what Dylan can map. Everything else is [WARN]
 * and is reported without failing the run.
 */
private enum class Severity { FAIL, WARN }

private data class Drift(
    val field: String,
    val kind: String,
    val sample: String,
    val severity: Severity,
)

private fun fail(
    field: String,
    kind: String,
    sample: String,
) = Drift(field, kind, sample, Severity.FAIL)

private fun warn(
    field: String,
    kind: String,
    sample: String,
) = Drift(field, kind, sample, Severity.WARN)

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

/**
 * Locates `fixtures/` independently of the process working directory.
 *
 * Resolution order: -Ddylan.fixturesDir, then the nearest ancestor of the CWD,
 * then the nearest ancestor of the loaded class (build/classes/kotlin/jvm/test).
 * A missing fixture is a hard error — a silently skipped expectation turns the
 * whole tool into a no-op that always reports "no drift".
 */
private fun fixturesDir(): File {
    System.getProperty("dylan.fixturesDir")?.let { return File(it) }
    val codeSource = ContractDrift::class.java.protectionDomain.codeSource
    val starts =
        listOfNotNull(
            File(".").absoluteFile,
            runCatching { File(codeSource.location.toURI()) }.getOrNull(),
        )
    starts.forEach { start ->
        generateSequence(start) { it.parentFile }.forEach { dir ->
            val candidate = File(dir, "fixtures")
            if (candidate.isDirectory) return candidate
        }
    }
    error("fixtures/ not found from ${starts.joinToString { it.path }} — pass -Ddylan.fixturesDir=<repo>/fixtures")
}

private fun readFixture(name: String): String {
    val file = File(fixturesDir(), name)
    check(file.isFile) { "missing fixture '$name' at ${file.path} — contract-drift cannot run without it" }
    return file.readText()
}

private fun parseFixture(name: String): JsonElement {
    val text = readFixture(name)
    return parse(text) ?: error("fixture '$name' is not valid JSON — contract-drift cannot run without it")
}

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

    (parseFixture("search_getresults_p1.json") as? JsonObject)?.let { o ->
        add("SEARCH_ENVELOPE", listOf(o))
        add("FULL_SONG", (o["results"] as? JsonArray)?.mapNotNull { e -> e as? JsonObject }.orEmpty())
    }
    (parseFixture("album_detail_full.json") as? JsonObject)?.let { o ->
        add("ALBUM_ENVELOPE", listOf(o))
        add("FULL_SONG", (o["list"] as? JsonArray)?.mapNotNull { e -> e as? JsonObject }.orEmpty())
    }
    (parseFixture("artist_detail.json") as? JsonObject)?.let { add("ARTIST_DETAIL", listOf(it)) }
    (parseFixture("generate_auth_token_128.json") as? JsonObject)?.let { add("AUTH_TOKEN", listOf(it)) }
    (parseFixture("autocomplete_ws_frame.json") as? JsonObject)?.let { add("WS_FRAME", listOf(it)) }
    val minis =
        listOf("top_searches.json", "trending.json").flatMap { name ->
            (parseFixture(name) as? JsonArray).orEmpty().mapNotNull { e -> e as? JsonObject }
        }
    add("MINI_CARD", minis)
    check(out.isNotEmpty()) { "no fixture families could be derived — contract-drift has nothing to compare" }
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
        rows += fail(expected.family, "ENDPOINT_EMPTY", "no live objects sampled")
        return
    }
    expected.kinds.forEach { (path, expKinds) ->
        val present = expected.presence[path] ?: 0
        val liveKinds = live.kinds[path]
        when {
            liveKinds == null && present >= expected.objects * PRESENCE_FLOOR ->
                rows += fail("${expected.family}.$path", "MISSING_FIELD", "fixture $present/${expected.objects}")
            liveKinds != null && liveKinds.intersect(expKinds).isEmpty() ->
                rows += fail("${expected.family}.$path", "TYPE_DRIFT", "fixture=$expKinds live=$liveKinds e.g. ${live.samples[path]}")
            liveKinds == setOf(Kind.NULL) && Kind.NULL !in expKinds ->
                rows += warn("${expected.family}.$path", "NULLABILITY_DRIFT", "e.g. ${live.samples[path]}")
            else -> Unit
        }
    }
    live.kinds.forEach { (path, _) ->
        if (!expected.kinds.containsKey(path)) {
            rows += fail("${expected.family}.$path", "NEW_FIELD", "e.g. ${live.samples[path]}")
        }
    }
}

private class LiveShapes {
    val envelope = Shape("SEARCH_ENVELOPE")
    val fullSong = Shape("FULL_SONG")
    val miniCard = Shape("MINI_CARD")
    val artistCard = Shape("ARTIST_SEARCH_CARD")
}

private fun wsProbeClient(cfg: AppConfig): HttpClient =
    HttpClient(CIO.create()) {
        install(WebSockets) { pingIntervalMillis = cfg.wsPingIntervalMs.toLong() }
    }

object ContractDrift {
    private lateinit var cfg: AppConfig
    private lateinit var api: HttpClient
    private val rows = mutableListOf<Drift>()
    private val reachable = mutableListOf<String>()
    private val unreachable = mutableListOf<String>()
    private val liveShapes = LiveShapes()

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
        if (ok && text != null) reachable += label else unreachable += "$label(status=${resp.status.value})"
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
            rows += fail(label, "UNREACHABLE", "status=${payload.first}")
            return emptyList()
        }
        parse(text)?.let { root ->
            (root as? JsonArray)?.take(40)?.forEach { e -> (e as? JsonObject)?.let(liveShape::absorb) }
        }
        val dtos =
            runCatching { json.decodeFromString(ListSerializer(SongDto.serializer()), text) }.getOrElse { emptyList() }
        if (dtos.isEmpty()) {
            rows += fail(label, "ENDPOINT_EMPTY", "no mini cards decoded head='${text.take(40)}'")
            return emptyList()
        }
        val minis = dtos.mapNotNull(::mapMini)
        println("[map] $label decoded=${dtos.size} mapped=${minis.size}")
        val pairs = dtos.map { d -> d.title to d.id }
        if (pairs.size > pairs.distinct().size) {
            rows += warn("$label.minis", "DUPLICATE_CARDS", "${pairs.size} cards -> ${pairs.distinct().size} distinct")
        }
        if (minis.size < dtos.size) {
            rows += fail("$label.minis", "MAPPING_LOSS", "${dtos.size - minis.size} of ${dtos.size} cards dropped by mapMini")
        }
        minis
            .filter { m -> m.type == "album" }
            .forEach { m ->
                val derived = permaAlbumToken(m.permaToken)
                when {
                    m.albumId == null -> rows += fail("$label.albumId", "TOKEN_DERIVATION_FAILED", "perma_url=${m.permaToken ?: "?"}")
                    derived == null -> rows += warn("$label.albumId", "FALLBACK_NUMERIC_ID", "id=${m.albumId} (numeric ids yield empty shells)")
                    derived != m.albumId -> rows += fail("$label.albumId", "TOKEN_MISMATCH", "derived=$derived mapped=${m.albumId}")
                    else -> Unit
                }
            }
        minis
            .filter { m -> m.type == "artist" }
            .forEach { m ->
                val derived = permaArtistToken(m.permaToken)
                when {
                    m.artistId == null -> rows += fail("$label.artistId", "TOKEN_DERIVATION_FAILED", "perma_url=${m.permaToken ?: "?"}")
                    derived == null -> rows += warn("$label.artistId", "FALLBACK_NUMERIC_ID", "id=${m.artistId} (numeric ids yield empty shells)")
                    derived != m.artistId -> rows += fail("$label.artistId", "TOKEN_MISMATCH", "derived=$derived mapped=${m.artistId}")
                    else -> Unit
                }
            }
        minis
            .filter { m -> m.type == "song" && m.songKey == null }
            .forEach { m -> rows += warn("$label.songKey", "KEY_MISSING", m.title) }
        val types = minis.groupBy { m -> m.type }.mapValues { e -> e.value.size }
        println("[map] $label types=$types")
        return dtos
    }

    /**
     * search.getAlbumResults / search.getArtistResults share the {total,start,results}
     * envelope and the same card DTO as topSearches/trending, so they are folded
     * into the same MINI_CARD family and mapped through the same mapMiniPaged the
     * search screen uses.
     */
    private fun checkMiniSearch(
        label: String,
        wantType: String,
        payload: Pair<Int, String?>,
        liveShape: Shape,
    ) {
        val text = payload.second
        if (text == null) {
            println("[map] $label UNREACHABLE status=${payload.first}")
            rows += fail(label, "UNREACHABLE", "status=${payload.first}")
            return
        }
        val dto = runCatching { json.decodeFromString(ResultsDto.serializer(), text) }.getOrNull()
        if (dto == null) {
            rows += fail(label, "DECODE_FAIL", text.take(60))
            return
        }
        (dto.results.take(30)).forEach { e ->
            (json.encodeToJsonElement(SongDto.serializer(), e) as? JsonObject)?.let(liveShape::absorb)
        }
        val paged = mapMiniPaged(dto, wantType, 1)
        println("[map] $label raw=${dto.results.size} want=$wantType mapped=${paged.items.size} total=${textOf(dto.total)}")
        if (paged.items.isEmpty()) rows += fail(label, "EMPTY_MAPPING", "no '$wantType' cards mapped from ${dto.results.size} results")
        val ids = dto.results.map { it.id }
        if (dto.results.size > ids.distinct().size) {
            rows += warn("$label.minis", "DUPLICATE_CARDS", "mapMiniPaged distinctBy dropped repeats")
        }
        paged.items
            .filter { it.songKey == null && it.albumId == null && it.artistId == null }
            .forEach { rows += fail("$label.minis", "MAPPING_LOSS", "card '${it.title}' mapped to no id at all") }
    }

    private fun checkPaged(
        label: String,
        text: String,
    ): ResultsDto? {
        val dto =
            runCatching { json.decodeFromString(ResultsDto.serializer(), text) }.getOrNull()
        if (dto == null) {
            println("[map] $label DECODE_FAIL head='${text.take(60)}'")
            rows += fail(label, "DECODE_FAIL", text.take(60))
            return null
        }
        val paged = mapPaged(dto)
        val ids = dto.results.map { song -> song.id }.filter { id -> id.isNotBlank() }
        val distinct = ids.distinct()
        println(
            "[map] $label raw=${ids.size} distinct=${distinct.size} mapped=${paged.items.size}" +
                " total=${textOf(dto.total)} page=${textOf(dto.start)}",
        )
        if (paged.items.isEmpty()) rows += fail("search.getResults.mapped", "EMPTY_MAPPING", text.take(60))
        if (ids.size > distinct.size) {
            rows += warn("mapPaged.items", "DEDUPE_DROPPED", "${ids.size} raw ids -> ${distinct.size} distinct (D7 known dupes)")
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
        val raw320 = dto.results.mapNotNull { s -> moreInfoOf(s)?.has320 }
        val bad320 = raw320.count { el -> el !is JsonPrimitive || el.booleanOrNull == null }
        if (bad320 > 0) rows += warn("MoreInfo.has320", "PARSE_FAIL", "$bad320/${raw320.size} not boolean literal (mapper coerces to false)")
        return dto
    }

    private fun checkAlbum(
        token: String,
        text: String,
    ): Shape? {
        val root = parse(text)
        if (root == null) {
            rows += fail("webapi.get album", "NOT_JSON", text.take(60))
            return null
        }
        val envelope = Shape("ALBUM_ENVELOPE")
        (root as? JsonObject)?.let(envelope::absorb)
        val album = runCatching { mapAlbum(json.decodeFromString(AlbumDto.serializer(), text)) }.getOrNull()
        if (album == null) {
            rows += fail("webapi.get album", "MAPPED_NULL", "token=$token")
            return envelope
        }
        println("[map] webapi.get album '${album.title}' songs=${album.songs.size}")
        if (album.songs.isEmpty()) rows += fail("mapAlbum.songs", "EMPTY_TRACKLIST", "token=$token")
        partial("Album.songs.resolveRef", album.songs.count { s -> s.resolveRef != null }, album.songs.size, "resolve_ref absent")
        partial(
            "Album.songs.artUrl500Rewrite",
            album.songs.count { s -> s.artUrl500.contains("500x500") },
            album.songs.size,
            "rewrite fell back",
        )
        return envelope
    }

    private fun checkArtist(
        token: String,
        text: String,
    ): Shape? {
        val root = parse(text)
        if (root == null) {
            rows += fail("webapi.get artist", "NOT_JSON", text.take(60))
            return null
        }
        val detail = Shape("ARTIST_DETAIL")
        (root as? JsonObject)?.let(detail::absorb)
        val artist = runCatching { json.decodeFromString(ArtistDto.serializer(), text) }.getOrNull()
        if (artist == null) {
            rows += fail("webapi.get artist", "MAPPED_NULL", "token=$token")
            return detail
        }
        println("[map] webapi.get artist '${artist.name}' topSongs=${artist.topSongs.size}")
        if (artist.name.isBlank()) rows += fail("mapArtist.name", "EMPTY_TRACKLIST", "token=$token")
        if (artist.topSongs.isEmpty()) rows += fail("mapArtist.topSongs", "EMPTY_TRACKLIST", "token=$token")
        partial(
            "Artist.topSongs.resolveRef",
            artist.topSongs.count { s -> moreInfoOfCard(s)?.encryptedMediaUrl != null },
            artist.topSongs.size,
            "encrypted_media_url absent",
        )
        return detail
    }

    private fun checkAuthToken(
        label: String,
        expected: Shape?,
        payload: Pair<Int, String?>,
    ) {
        val text = payload.second
        if (text == null) {
            rows += fail(label, "UNREACHABLE", "status=${payload.first}")
            return
        }
        val root = parse(text) as? JsonObject
        if (root == null) {
            rows += fail(label, "NOT_JSON", text.take(60))
            return
        }
        val shape = Shape("AUTH_TOKEN")
        shape.absorb(root)
        compareShapes(expected, shape, rows)
        val authUrl = textOf(root["auth_url"])
        if (authUrl.isNullOrBlank()) {
            rows += fail("$label.auth_url", "SIGNING_FAILED", "auth_url=${authUrl ?: "null"} status=${textOf(root["status"])}")
        } else {
            println("[map] $label signed ${authUrl.substringBefore('?').takeLast(48)} type=${textOf(root["type"])}")
        }
    }

    private suspend fun firstTextFrame(): String? {
        val client = wsProbeClient(cfg)
        return try {
            val session = client.webSocketSession(cfg.wsSearchUrl)
            try {
                session.send(
                    Frame.Text(
                        """{"url":"/api.php?__call=autocomplete.get&query=ari&_format=json&_marker=0&ctx=web6dot0"}""",
                    ),
                )
                withTimeoutOrNull(WS_FRAME_TIMEOUT_MS) {
                    var accepted: String? = null
                    while (accepted == null) {
                        val f = session.incoming.receive() as? Frame.Text ?: continue
                        accepted = f.readText()
                    }
                    accepted
                }
            } finally {
                session.close()
            }
        } finally {
            client.close()
        }
    }

    private suspend fun checkWsFrame(expected: Shape?) {
        val label = "ws.autocomplete.get"
        val frame = firstTextFrame()
        if (frame == null) {
            println("[net] $label UNREACHABLE (no frame in ${WS_FRAME_TIMEOUT_MS}ms)")
            unreachable += "$label(no-frame)"
            rows += fail(label, "UNREACHABLE", "no text frame within ${WS_FRAME_TIMEOUT_MS}ms")
            return
        }
        reachable += label
        val root = parse(frame) as? JsonObject
        if (root == null) {
            rows += fail(label, "NOT_JSON", frame.take(60))
            return
        }
        println("[net] $label frame chars=${frame.length} keys=${root.keys}")
        val shape = Shape("WS_FRAME")
        shape.absorb(root)
        compareShapes(expected, shape, rows)
        val resp = textOf(root["resp"])
        if (resp.isNullOrBlank()) {
            rows += fail("$label.resp", "MAPPING_LOSS", "resp is not a JSON string (mapSuggestions expects String)")
        } else {
            val groups = runCatching { json.parseToJsonElement(resp) }.getOrNull() as? JsonObject
            val cards = groups?.values?.filterIsInstance<JsonArray>()?.sumOf { it.size } ?: 0
            println("[map] $label groups=${groups?.size ?: 0} cards=$cards")
            if (cards == 0) rows += fail("$label.resp", "EMPTY_MAPPING", "no suggestion arrays in resp")
        }
    }

    private fun partial(
        field: String,
        hits: Int,
        total: Int,
        why: String,
    ) {
        if (total > 0 && hits < total) rows += warn(field, "PARTIAL_PRESENT", "$hits/$total ($why)")
    }

    /**
     * `more_info` is an untyped [JsonElement] on the DTO now, and so is every element of an artist's
     * `topSongs`. These are the two decodes the mapper itself does, so the probe reads the same
     * fields the app does instead of a re-typed view of them.
     */
    private fun moreInfoOf(dto: SongDto): MoreInfoDto? = dto.moreInfo?.let { decode(MoreInfoDto.serializer(), it) }

    private fun moreInfoOfCard(card: JsonElement): MoreInfoDto? = decode(SongDto.serializer(), card)?.let(::moreInfoOf)

    private fun <T> decode(
        strategy: DeserializationStrategy<T>,
        el: JsonElement,
    ): T? = runCatching { json.decodeFromJsonElement(strategy, el) }.getOrNull()

    /** Everything the album/artist/auth probes need to derive their request tokens. */
    private class Tokens(
        val album: String?,
        val artist: String?,
        val resolveRef: String?,
    )

    private suspend fun probeCatalog(minis: List<SongDto>): Tokens {
        val search =
            fetch(
                "search.getResults",
                "__call" to "search.getResults",
                "q" to "arijit singh",
                "p" to "1",
                "n" to cfg.submitPageSize.toString(),
            )
        val text = search.second
        if (text == null) {
            rows += fail("search.getResults", "UNREACHABLE", "status=${search.first}")
        } else {
            parse(text)?.let { root ->
                (root as? JsonObject)?.let(liveShapes.envelope::absorb)
                ((root as? JsonObject)?.get("results") as? JsonArray)
                    ?.take(30)
                    ?.forEach { e -> (e as? JsonObject)?.let(liveShapes.fullSong::absorb) }
            }
            checkPaged("search.getResults", text)?.let { dto ->
                return Tokens(
                    dto.results.firstNotNullOfOrNull { permaAlbumToken(it.permaUrl) }
                        ?: minis.firstOrNull { it.type == "album" }?.let { permaAlbumToken(it.permaUrl) },
                    dto.results.firstNotNullOfOrNull { permaArtistToken(it.permaUrl) }
                        ?: minis.firstOrNull { it.type == "artist" }?.let { permaArtistToken(it.permaUrl) },
                    dto.results.firstNotNullOfOrNull { moreInfoOf(it)?.encryptedMediaUrl },
                )
            }
        }
        return Tokens(null, null, null)
    }

    private suspend fun probeAlbum(token: String?): Shape? {
        if (token == null) {
            rows += warn("webapi.get album", "SKIP_NO_TOKEN", "no derivable perma album token from live minis")
            return null
        }
        val album = fetch("webapi.get album", "__call" to "webapi.get", "token" to token, "type" to "album", "includeMetaTags" to "0")
        val text = album.second
        if (text == null) {
            rows += fail("webapi.get album", "UNREACHABLE", "token=$token status=${album.first}")
            return null
        }
        parse(text)?.let { root ->
            ((root as? JsonObject)?.get("list") as? JsonArray)
                ?.take(20)
                ?.forEach { e -> (e as? JsonObject)?.let(liveShapes.fullSong::absorb) }
        }
        return checkAlbum(token, text)
    }

    private suspend fun probeArtist(token: String?) {
        if (token == null) {
            rows += warn("webapi.get artist", "SKIP_NO_TOKEN", "no derivable perma artist token from live minis")
            return
        }
        val artist = fetch("webapi.get artist", "__call" to "webapi.get", "token" to token, "type" to "artist", "includeMetaTags" to "0")
        val text = artist.second
        if (text == null) {
            rows += fail("webapi.get artist", "UNREACHABLE", "token=$token status=${artist.first}")
        } else {
            checkArtist(token, text)
        }
    }

    private suspend fun probeAuthToken(
        resolveRef: String?,
        expected: Shape?,
    ) {
        if (resolveRef == null) {
            rows += warn("song.generateAuthToken", "SKIP_NO_TOKEN", "no resolvable song in the live search page")
            return
        }
        val auth = fetch("song.generateAuthToken", "__call" to "song.generateAuthToken", "url" to resolveRef, "bitrate" to "128")
        checkAuthToken("song.generateAuthToken", expected, auth)
    }

    private fun compareAll(
        expectations: Map<String, Shape>,
        liveAlbum: Shape?,
    ) {
        compareShapes(expectations["SEARCH_ENVELOPE"], liveShapes.envelope, rows)
        compareShapes(expectations["FULL_SONG"], liveShapes.fullSong, rows)
        compareShapes(expectations["MINI_CARD"], liveShapes.miniCard, rows)
        liveAlbum?.let { compareShapes(expectations["ALBUM_ENVELOPE"], it, rows) }
    }

    private fun emit(sorted: List<Drift>): Int {
        val stamp = java.time.Instant.now()
        println()
        println("=== DYLAN contract-drift @ $stamp ===")
        if (sorted.isEmpty()) {
            println("NO DRIFT: live payloads match fixture-derived expectations")
        } else {
            sorted.groupBy { it.severity }.forEach { (severity, list) ->
                println("--- ${severity.name} findings: ${list.size} (field | kind | sample) ---")
                list.forEach { d -> println("${d.field} | ${d.kind} | ${d.sample}") }
            }
            sorted.groupBy { it.kind }.forEach { (kind, list) -> println("summary $kind=${list.size}") }
        }
        val total = reachable.size + unreachable.size
        println("--- endpoints reachable: ${reachable.size}/$total ---")
        unreachable.forEach { println("unreachable $it") }

        val fails = sorted.filter { it.severity == Severity.FAIL }
        val dir = File("build/reports/probe").also { it.mkdirs() }
        File(dir, "contract-drift.md").writeText(
            buildString {
                appendLine("# contract-drift @ $stamp")
                appendLine("reachable ${reachable.size}/$total fails=${fails.size}")
                sorted.forEach { appendLine("- ${it.severity} ${it.field} | ${it.kind} | ${it.sample.replace("|", "/")}") }
            },
        )
        if (System.getenv("DYLAN_DRIFT_ADVISORY") == "1") {
            println("ADVISORY MODE (DYLAN_DRIFT_ADVISORY=1): ${fails.size} FAIL findings reported, exit 0")
            return 0
        }
        if (fails.isNotEmpty() || unreachable.isNotEmpty()) {
            println("DRIFT GATE FAILED: ${fails.size} fail-severity findings, ${unreachable.size} unreachable endpoints")
            println("  NEW_FIELD/TYPE_DRIFT/MISSING_FIELD -> re-capture the matching file in fixtures/ (tools/extract_fixtures.py)")
            println("  MAPPING_LOSS/EMPTY_MAPPING -> the DTO no longer covers the live payload (dylan.provider.saavn.dto)")
            println("  triage only: DYLAN_DRIFT_ADVISORY=1 reports everything and exits 0")
            return 1
        }
        println("DRIFT GATE PASSED")
        return 0
    }

    suspend fun run(): Int {
        cfg = AppConfig()
        api = apiClient(CIO.create(), cfg)
        val expectations = expectedShapes()
        println("[exp] fixture-derived families: ${expectations.keys}")

        val tops = checkMinis("topSearches", fetch("content.getTopSearches", "__call" to "content.getTopSearches"), liveShapes.miniCard)
        val trends =
            checkMinis(
                "trending",
                fetch("content.getTrending", "__call" to "content.getTrending", "entity_type" to "album", "entity_language" to "hindi"),
                liveShapes.miniCard,
            )
        checkMiniSearch(
            "search.getAlbumResults",
            "album",
            fetch("search.getAlbumResults", "__call" to "search.getAlbumResults", "q" to "arijit", "p" to "1", "n" to "20"),
            liveShapes.miniCard,
        )
        checkMiniSearch(
            "search.getArtistResults",
            "artist",
            fetch("search.getArtistResults", "__call" to "search.getArtistResults", "q" to "arijit", "p" to "1", "n" to "20"),
            liveShapes.artistCard,
        )

        val tokens = probeCatalog(tops + trends)
        val liveAlbum = probeAlbum(tokens.album)
        probeArtist(tokens.artist)
        probeAuthToken(tokens.resolveRef, expectations["AUTH_TOKEN"])
        checkWsFrame(expectations["WS_FRAME"])

        compareAll(expectations, liveAlbum)
        return emit(rows.sortedWith(compareBy({ rank(it.kind) }, { it.field })))
    }
}

fun main(): Unit = exitProcess(runBlocking { ContractDrift.run() })
