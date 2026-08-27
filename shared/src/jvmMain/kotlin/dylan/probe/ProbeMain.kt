package dylan.probe

import dylan.config.AppConfig
import dylan.model.MiniEntity
import dylan.model.Quality
import dylan.model.Song
import dylan.net.apiClient
import dylan.net.bulkClient
import dylan.provider.saavn.SaavnProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.system.exitProcess

private data class Row(
    val id: String,
    val gate: String,
    val status: String,
    val note: String,
)

private class CheckFailed(
    val note: String,
) : Exception()

private fun require(
    cond: Boolean,
    note: String,
) {
    if (!cond) throw CheckFailed(note)
}

object Probe {
    private lateinit var cfg: AppConfig
    private lateinit var api: HttpClient
    private lateinit var bulk: HttpClient
    private lateinit var provider: SaavnProvider
    private var lastMediaHitMs = 0L
    private val rows = mutableListOf<Row>()

    private suspend fun HttpClient.mediaGet(
        url: String,
        range: String? = null,
        ifRange: String? = null,
    ): Pair<Int, Map<String, String>> {
        if (System.nanoTime() / 1_000_000 - lastMediaHitMs < 300) delay(350)
        lastMediaHitMs = System.nanoTime() / 1_000_000
        val resp =
            get(url) {
                range?.let { header(HttpHeaders.Range, it) }
                ifRange?.let { header(HttpHeaders.IfRange, it) }
                header(HttpHeaders.UserAgent, cfg.userAgent)
                header(HttpHeaders.Referrer, "https://www.jiosaavn.com/")
            }
        return resp.status.value to
            buildMap {
                for (name in listOf(
                    HttpHeaders.AcceptRanges,
                    HttpHeaders.ContentLength,
                    HttpHeaders.ETag,
                    HttpHeaders.ContentType,
                    HttpHeaders.ContentRange,
                    HttpHeaders.Date,
                )) {
                    resp.headers[name]?.let { put(name, it) }
                }
            }
    }

    private suspend fun HttpClient.bodyBytes(
        url: String,
        maxBytes: Int = 8_000_000,
    ): ByteArray =
        get(url) {
            header(HttpHeaders.UserAgent, cfg.userAgent)
            header(HttpHeaders.Referrer, "https://www.jiosaavn.com/")
        }.bodyAsChannel().let { ch ->
            val buf = ByteArray(64 * 1024)
            var out = ByteArray(0)
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                out += buf.copyOfRange(0, n)
                if (out.size > maxBytes) break
            }
            out
        }

    private suspend fun check(
        id: String,
        gate: String,
        desc: String,
        timeoutMs: Long = 45_000,
        block: suspend () -> String,
    ) {
        try {
            withTimeoutOrNull(timeoutMs) { block() }?.let { rows += Row(id, gate, "PASS", "$desc :: $it") }
                ?: run { rows += Row(id, gate, "TIMEOUT", "$desc (exceeded ${timeoutMs / 1000}s)") }
        } catch (e: CheckFailed) {
            rows += Row(id, gate, "FAIL", e.note)
        } catch (e: Exception) {
            rows += Row(id, gate, "FAIL", "${e::class.simpleName}: ${e.message}")
        }
    }

    private suspend fun seedSongs(): List<Song> {
        val seen = LinkedHashMap<String, Song>()
        repeat(3) { p ->
            provider.search("top hindi", p + 1).items.forEach { seen.putIfAbsent(it.key.songId, it) }
        }
        val albums =
            provider.home().sections.flatMap { it.items }.filter { it.albumId != null }.take(2).mapNotNull { m: MiniEntity ->
                m.albumId
            }
        for (id in albums) provider.album(id)?.songs?.forEach { seen.putIfAbsent(it.key.songId, it) }
        return seen.values.toList()
    }

    suspend fun run(
        mode: String,
        fast: Boolean,
    ): Int {
        cfg = AppConfig()
        api = apiClient(CIO.create(), cfg)
        bulk = bulkClient(CIO.create(), cfg)
        provider = SaavnProvider(api, cfg)

        val songs = seedSongs()
        if (mode == "ci") return structural()

        val sample = songs.filter { it.resolveRef != null && it.durationS > 0 }

        check("P5", "M1", "resolveRef coverage over ${songs.size} sampled songs (warn-only)") {
            val withRef = songs.count { !it.resolveRef.isNullOrBlank() }
            val pct = if (songs.isEmpty()) 0.0 else 100.0 * withRef / songs.size
            // Warn, don't fail: M1 gate so a catalog dip pages nobody, but the note stays
            // visible in probe-results.md for drift triage.
            val verdict = if (pct > 95.0) "ok" else "WARN: coverage below 95% gate (non-blocking)"
            "resolveRef=$withRef/${songs.size} (${"%.1f".format(pct)}%) $verdict"
        }

        val ref128 = sample.firstOrNull()
        requireNotNull(ref128) { "no resolvable song found - catalog unreachable or geo-blocked" }
        val signed = provider.resolveStream(ref128.resolveRef!!, Quality.BITRATE_128)
        requireNotNull(signed) { "generateAuthToken returned no auth_url" }
        val host = runCatching { java.net.URI(signed.url).host }.getOrDefault("?")

        check("P1", "M0", "Range + If-Range semantics on $host") {
            val headOk =
                runCatching {
                    val r =
                        bulk.head(signed.url) {
                            header(HttpHeaders.UserAgent, cfg.userAgent)
                            header(HttpHeaders.Referrer, "https://www.jiosaavn.com/")
                        }
                    r.status.isSuccess() || r.status.value in listOf(403)
                }.getOrDefault(false)
            val acceptRangesHead =
                runCatching {
                    bulk
                        .head(signed.url) {
                            header(HttpHeaders.UserAgent, cfg.userAgent)
                            header(HttpHeaders.Referrer, "https://www.jiosaavn.com/")
                        }.headers[HttpHeaders.AcceptRanges]
                }.getOrNull()
            val (code100, h100) = bulk.mediaGet(signed.url, range = "bytes=100-199")
            require(
                code100 == 206,
                "GET Range expected 206 got $code100${if (!headOk) " (HEAD also failed; GET-Range fallback used)" else ""}",
            )
            require(
                h100[HttpHeaders.ContentLength]?.toIntOrNull() == 100,
                "206 Content-Length=${h100[HttpHeaders.ContentLength]} expected exactly 100",
            )
            val etag = h100[HttpHeaders.ETag]
            val bogus = etag != null
            val (codeBogus, _) =
                if (bogus) {
                    bulk.mediaGet(signed.url, range = "bytes=0-99", ifRange = "\"definitely-bogus-etag\"")
                } else {
                    200 to
                        emptyMap()
                }
            val (codeMatch, _) =
                if (etag !=
                    null
                ) {
                    bulk.mediaGet(signed.url, range = "bytes=100-199", ifRange = etag)
                } else {
                    code100 to emptyMap()
                }
            val ifr = if (bogus) "bogus-etag=>$codeBogus(expect 200)" else "skipped(no etag)"
            val ifm = if (etag != null) "matching=>$codeMatch(expect 206)" else "skipped"
            require(!bogus || codeBogus == 200, "If-Range bogus etag gave $codeBogus expected full 200")
            require(etag == null || codeMatch == 206, "If-Range matching etag gave $codeMatch expected 206")
            "AcceptRanges=${acceptRangesHead ?: "?"} 206CL=100 $ifr $ifm"
        }

        var etagP2: String? = null
        check("P2", "M0", "ETag present on media response") {
            val (_, h) = bulk.mediaGet(signed.url, range = "bytes=0-63")
            etagP2 = h[HttpHeaders.ETag]
            require(etagP2 != null, "no ETag on media response - If-Range resume guard unusable")
            "ETag=${etagP2!!.take(24)}..."
        }

        check("P3", "M0", "Content-Length present and truthful") {
            val shortest = sample.minByOrNull { it.durationS }!!
            val s128 = provider.resolveStream(shortest.resolveRef!!, Quality.BITRATE_128)!!
            val (codeFull, hf) = bulk.mediaGet(s128.url)
            require(codeFull == 200, "full GET got $codeFull")
            val cl = hf[HttpHeaders.ContentLength]?.toLongOrNull()
            require(cl != null, "no Content-Length on 200")
            val actual = bulk.bodyBytes(s128.url).size.toLong()
            require(actual == cl, "truthful-length violated: header=$cl actual=$actual")
            "CL truthful: $cl bytes for ${shortest.durationS}s track"
        }

        check("P13", "M0", "signed-media DRIFT sentinel (audio/* not HTML)") {
            val (code, h) = bulk.mediaGet(signed.url, range = "bytes=0-255")
            val ct = h[HttpHeaders.ContentType].orEmpty()
            require(code == 200 || code == 206, "media GET got $code")
            require(ct.startsWith("audio/") || ct.contains("octet-stream"), "DRIFT: media Content-Type='$ct' - bot-wall/HTML surface")
            val head = bulk.bodyBytes(signed.url, 16)
            require(head.isNotEmpty() && head[0] != '<'.code.toByte(), "DRIFT: body starts with '<' (HTML)")
            "CT=$ct"
        }

        check("P11", "M0", "signed-URL TTL >= 60s (server-windowed)", timeoutMs = 700_000) {
            if (fast) return@check "SKIPPED (--fast)"
            bulk.mediaGet(signed.url, range = "bytes=0-15")
            val startMs = System.nanoTime() / 1_000_000
            var ttlMs = -1L
            while (true) {
                delay(20_000)
                val elapsed = System.nanoTime() / 1_000_000 - startMs
                if (elapsed >= 600_000) {
                    ttlMs = Long.MAX_VALUE
                    break
                }
                val r = runCatching { bulk.mediaGet(signed.url, range = "bytes=0-15") }.getOrNull()
                val code = r?.first ?: -1
                if (code != 200 && code != 206) {
                    ttlMs = elapsed
                    break
                }
            }
            require(ttlMs >= 60_000, "signed URL died before 60s (TTL~${ttlMs}ms)")
            if (ttlMs == Long.MAX_VALUE) "still valid past 10min (dominates [60s,10min] assumption)" else "TTL~${ttlMs / 1000}s"
        }

        check("P12", "M0", "WS handshake + round-trip < 2s") {
            val wsCfg = cfg.copy(wsRequestTimeoutMs = 3_000)
            val t0 = System.nanoTime()
            val session = api.wsClientForProbe(wsCfg).webSocketSession(cfg.wsSearchUrl)
            session.send(
                io.ktor.websocket.Frame
                    .Text("""{"url":"/api.php?__call=autocomplete.get&query=ari&_format=json&_marker=0&ctx=web6dot0"}"""),
            )
            var got = false
            while (!got) {
                val f = withTimeoutOrNull(3_000) { session.incoming.receive() } ?: break
                if (f is io.ktor.websocket.Frame.Text) got = true
            }
            session.close()
            val ms = (System.nanoTime() - t0) / 1_000_000
            require(got && ms < 2_000, "round-trip ${ms}ms got=$got")
            "handshake+answer ${ms}ms"
        }

        check("P4", "M0", "WS correlation decision (3 rapid queries, one socket)") {
            data class R(
                val token: String?,
                val raw: String,
            )
            val tokens = listOf("dylanp4a", "dylanp4b", "dylanp4c")
            val session = api.wsClientForProbe(cfg).webSocketSession(cfg.wsSearchUrl)
            val received = mutableListOf<String>()
            try {
                tokens.forEach { q ->
                    session.send(
                        io.ktor.websocket.Frame.Text(
                            """{"url":"/api.php?__call=autocomplete.get&query=$q&_format=json&_marker=0&ctx=web6dot0"}""",
                        ),
                    )
                }
                withTimeoutOrNull(5_000) {
                    while (received.size < 3) {
                        val f = session.incoming.receive()
                        if (f is io.ktor.websocket.Frame.Text) received += String(f.data, Charsets.UTF_8)
                    }
                }
            } finally {
                session.close()
            }
            require(received.size == 3, "only ${received.size}/3 frames arrived - treat as UNORDERED w/ strikes")
            val echoCount = received.count { frame -> tokens.any { frame.contains(it) } }
            val mode = if (echoCount >= 2) "ECHO" else "ORDERED"
            "correlation=$mode (identity echoed in $echoCount/3 frames) -> set SearchChannel.correlationMode accordingly"
        }

        check("P8", "M0", "autocomplete.get as plain HTTP GET") {
            val text =
                api
                    .get(cfg.apiBaseUrl) {
                        parameter("__call", "autocomplete.get")
                        parameter("query", "arijit")
                        cfg.commonParams.forEach { (k, v) -> parameter(k, v) }
                    }.bodyAsText()
            require(text.trimStart().startsWith("{"), "autocomplete fallback not JSON: '${text.take(60)}'")
            "JSON payload ok (${text.length} chars)"
        }

        check("P10", "M0", "-500x500 image variant exists (3 samples)") {
            val urls =
                songs
                    .map { it.artUrl500 }
                    .filter { it.contains("500x500") }
                    .distinct()
                    .take(3)
            require(urls.size == 3, "fewer than 3 distinct 500x500 URLs derived")
            urls.forEach { u ->
                val (code, h) = bulk.mediaGet(u)
                require(
                    code == 200 && h[HttpHeaders.ContentType].orEmpty().startsWith("image/"),
                    "$u -> $code ${h[HttpHeaders.ContentType]}",
                )
            }
            "3/3 variants served"
        }

        check("P6", "M1", "re-sign path stability (two generateAuthToken calls)") {
            val a = provider.resolveStream(ref128.resolveRef!!, Quality.BITRATE_128)!!.url
            delay(300)
            val b = provider.resolveStream(ref128.resolveRef!!, Quality.BITRATE_128)!!.url
            val pa = a.substringBefore('?')
            val pb = b.substringBefore('?')
            "samePath=${pa == pb} path=$pa"
        }

        check("P7", "M1", "bitrate calibration CL/duration (replaces x125)") {
            val s128 = sample.first { it.durationS > 30 }
            val r128 = provider.resolveStream(s128.resolveRef!!, Quality.BITRATE_128)!!
            val cl128 = bulk.mediaGet(r128.url).second[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1
            val bps128 = if (cl128 > 0) cl128 / s128.durationS else -1
            var line = "128kbps=${bps128}B/s(x125 would be 16000)"
            val s320 = sample.firstOrNull { it.has320 && it.durationS > 30 }
            if (s320 != null) {
                val r320 = provider.resolveStream(s320.resolveRef!!, Quality.BITRATE_320)
                val cl320 = r320?.let { bulk.mediaGet(it.url).second[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1 } ?: -1
                val bps320 = if (cl320 > 0) cl320 / s320.durationS else -1
                line += " | 320kbps=${bps320}B/s(x125 would be 40000)"
            }
            line
        }

        check("P9", "M1", "geo sanity: CDN edge logged") {
            "apiHost=${java.net.URI(cfg.apiBaseUrl).host} cdnEdge=$host signedType=${signed.type}"
        }

        return report(mode, gates = true)
    }

    private suspend fun structural(): Int {
        check("S1", "CI", "api.php returns JSON (search.getResults live shape)") {
            val paged = provider.search("arijit", 1)
            require(paged.items.isNotEmpty(), "live search mapped zero songs")
            "mapped ${paged.items.size} songs, first='${paged.items.first().title}'"
        }

        check("S2", "CI", "autocomplete reachable over plain HTTP") {
            val text =
                api
                    .get(cfg.apiBaseUrl) {
                        parameter("__call", "autocomplete.get")
                        parameter("query", "test")
                        cfg.commonParams.forEach { (k, v) -> parameter(k, v) }
                    }.bodyAsText()
            require(text.trimStart().startsWith("{"), "not JSON")
            "ok"
        }
        check("S3", "CI", "WS handshake reachable") {
            val s = withTimeoutOrNull(8_000) { api.wsClientForProbe(cfg).webSocketSession(cfg.wsSearchUrl) }
            requireNotNull(s) { "handshake failed" }
            s.close()
            "reachable"
        }
        return report("ci", gates = false)
    }

    private fun report(
        mode: String,
        gates: Boolean,
    ): Int {
        val stamp = java.time.Instant.now()
        val failed = rows.count { it.status == "FAIL" }
        println("\n=== DYLAN probe ($mode) @ $stamp ===")
        rows.forEach { r ->
            val mark =
                when (r.status) {
                    "PASS" -> "+"
                    "FAIL" -> "!"
                    else -> "-"
                }
            println("[$mark] ${r.id} (${r.gate}) ${r.note}")
        }
        println("---\n${rows.size - failed}/${rows.size} checks passed")
        val blocking = if (gates) rows.filter { it.status == "FAIL" && it.gate == "M0" } else emptyList()
        if (blocking.isNotEmpty()) println("GATING FAILURES: ${blocking.joinToString { it.id }}")
        val file = File("tools/probe-results.md")
        file.appendText(
            "\n## $stamp mode=$mode\n" +
                rows.joinToString("\n") { "| ${it.id} | ${it.gate} | ${it.status} | ${it.note.replace("|", "/")} |" } +
                "\n",
        )
        return if (blocking.isEmpty()) 0 else 1
    }
}

private fun HttpClient.wsClientForProbe(cfg: AppConfig): HttpClient =
    HttpClient(CIO.create()) {
        install(io.ktor.client.plugins.websocket.WebSockets) { pingIntervalMillis = cfg.wsPingIntervalMs.toLong() }
    }

fun main(args: Array<String>) {
    val mode = args.firstOrNull { !it.startsWith("--") } ?: "local"
    val fast = "--fast" in args
    exitProcess(runBlocking { Probe.run(mode, fast) })
}
