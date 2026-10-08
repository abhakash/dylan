package dylan.probe

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.model.MiniEntity
import dylan.model.Quality
import dylan.model.Song
import dylan.net.apiClient
import dylan.net.bulkClient
import dylan.provider.SignedStream
import dylan.provider.saavn.SaavnProvider
import dylan.util.AppDispatchers
import dylan.util.NetClass
import dylan.util.NetMonitor
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** `bodyBytes` stops here. At 128 kbps that is ~8.4 minutes of audio — see [truthfulLengthFailure]. */
private const val BODY_READ_CAP_BYTES = 8_000_000

/**
 * A body read, with the cap that stopped it recorded in the answer.
 *
 * The cap used to be a silent exit, so a body longer than it came back indistinguishable from a body
 * the origin had finished sending. That is the whole defect: a caller could hold a short count and a
 * full `Content-Length` and compare them, and the comparison would be about the cap, not the origin.
 */
private data class BodyRead(
    val bytes: ByteArray,
    val truncated: Boolean,
)

/**
 * P3's whole decision, as a pure function of the header, the byte count, and whether the read hit
 * the cap. Returns the note that should fail the check, or `null` when the body is complete and
 * matches.
 *
 * A capped read is *neither* a drift finding nor a pass, because neither is supported by what was
 * read: reporting `actual != cl` is a fabricated "truthful-length violated" for every track longer
 * than [BODY_READ_CAP_BYTES], and reporting nothing claims a truthfulness nobody checked. The only
 * sentence this check is allowed to make about a capped body is that it could not measure it.
 *
 * Separate from `bodyBytes` on purpose — it is the one piece of probe judgement that can be wrong
 * without a network, so it is the one piece a test can hold still.
 */
internal fun truthfulLengthFailure(
    contentLength: Long,
    actual: Long,
    truncated: Boolean,
): String? =
    when {
        truncated ->
            "length not measurable: read stopped at the $BODY_READ_CAP_BYTES byte cap " +
                "(header=$contentLength, bytes read=$actual) - not a drift finding"
        actual != contentLength -> "truthful-length violated: header=$contentLength actual=$actual"
        else -> null
    }

object Probe {
    private lateinit var cfg: AppConfig
    private lateinit var api: HttpClient
    private lateinit var bulk: HttpClient
    private lateinit var provider: SaavnProvider
    private var lastMediaHitMs = 0L
    private val rows = mutableListOf<Row>()

    /** Per-check window. Overridable so the TIMEOUT arm of the gate can be demonstrated offline. */
    private var checkTimeoutMs = DEFAULT_CHECK_TIMEOUT_MS

    private const val DEFAULT_CHECK_TIMEOUT_MS = 45_000L

    /**
     * The parameters this gate is calibrated against. They are not style: every one of them is a
     * knob the recorded `probe-results.md` rows were measured with, so a silent edit invalidates
     * the history they are compared against. Named, never re-valued.
     */
    private const val NANOS_PER_MILLI = 1_000_000
    private const val MILLIS_PER_SECOND = 1_000

    /** Media GETs are spaced this far apart so the CDN does not throttle a full sweep. */
    private const val MEDIA_MIN_INTERVAL_MS = 300L
    private const val MEDIA_THROTTLE_DELAY_MS = 350L
    private const val READ_CHUNK_BYTES = 64 * 1024

    /** HTTP statuses the media checks assert on. */
    private const val HTTP_OK = 200
    private const val HTTP_PARTIAL_CONTENT = 206
    private const val HTTP_FORBIDDEN = 403

    /** `Range: bytes=100-199` is a 100-byte body; a truthful 206 echoes exactly that length. */
    private const val RANGE_PROBE_BYTES = 100
    private const val ETAG_PREVIEW_CHARS = 24
    private const val DRIFT_PROBE_BYTES = 16
    private const val JSON_SNIPPET_CHARS = 60
    private const val IMAGE_VARIANT_SAMPLES = 3

    /** Catalog seeding: how many search pages, and the resolveRef coverage M1 warns below. */
    private const val SEED_SEARCH_PAGES = 3
    private const val COVERAGE_PERCENT = 100.0
    private const val COVERAGE_WARN_PCT = 95.0

    /** Signed-URL TTL sweep: poll cadence, the window the sweep searches, and the M0 floor. */
    private const val SIGNED_URL_POLL_MS = 20_000L
    private const val SIGNED_URL_WINDOW_MS = 600_000L
    private const val SIGNED_URL_MIN_TTL_MS = 60_000L

    /** Websocket budget, correlation, and handshake windows. */
    private const val WS_ANSWER_TIMEOUT_MS = 3_000L
    private const val WS_ROUND_TRIP_BUDGET_MS = 2_000L
    private const val CORRELATION_FRAMES = 3
    private const val CORRELATION_TIMEOUT_MS = 5_000L
    private const val HANDSHAKE_TIMEOUT_MS = 8_000L

    /** Bitrate calibration only counts tracks long enough for CL/duration to mean anything. */
    private const val LONG_TRACK_SECONDS = 30

    /** Settle time between the two re-sign calls of the P6 stability check. */
    private const val RESIGN_SETTLE_MS = 300L

    /** Verbatim trailing advice of the P4 note, split out only so the line fits the style limit. */
    private const val CORRELATION_ADVICE = "-> set SearchChannel.correlationMode accordingly"

    /**
     * A hang is a failure, not an absence of one.
     *
     * Gating only on `FAIL` meant a blackholed origin — captive portal, firewall DROP, a dead CDN
     * edge, the single most common way a live gate breaks — produced three `TIMEOUT` rows and exit
     * 0. `withTimeoutOrNull` in `check` turns "never answered" into a `TIMEOUT` status precisely so
     * it can be counted here; counting only `FAIL` threw that distinction away.
     */
    private val GATING_STATUSES = setOf("FAIL", "TIMEOUT")

    private suspend fun HttpClient.mediaGet(
        url: String,
        range: String? = null,
        ifRange: String? = null,
    ): Pair<Int, Map<String, String>> {
        if (System.nanoTime() / NANOS_PER_MILLI - lastMediaHitMs < MEDIA_MIN_INTERVAL_MS) delay(MEDIA_THROTTLE_DELAY_MS)
        lastMediaHitMs = System.nanoTime() / NANOS_PER_MILLI
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

    /**
     * Reads at most [maxBytes] and SAYS when it stopped there.
     *
     * The read itself is unchanged — same `bodyAsChannel()`, same 64 KiB chunks, same overshoot past
     * the cap, same bytes returned. Only the answer changed: the cap is a returned fact, not a hidden
     * exit, so no caller can mistake a short read for a short body. [truthfulLengthFailure] is what
     * refuses to compare a capped read against a `Content-Length`.
     */
    private suspend fun HttpClient.bodyBytes(
        url: String,
        maxBytes: Int = BODY_READ_CAP_BYTES,
    ): BodyRead =
        get(url) {
            header(HttpHeaders.UserAgent, cfg.userAgent)
            header(HttpHeaders.Referrer, "https://www.jiosaavn.com/")
        }.bodyAsChannel().let { ch ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            var out = ByteArray(0)
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                // `-1` is the only end-of-body signal; `0` means "nothing yet", so only one of the
                // two exits below is a truncation decision.
                if (n == -1) break
                out += buf.copyOfRange(0, n)
                if (out.size > maxBytes) return@let BodyRead(out, truncated = true)
            }
            BodyRead(out, truncated = false)
        }

    private suspend fun check(
        id: String,
        gate: String,
        desc: String,
        timeoutMs: Long = checkTimeoutMs,
        block: suspend () -> String,
    ) {
        try {
            withTimeoutOrNull(timeoutMs) { block() }?.let { rows += Row(id, gate, "PASS", "$desc :: $it") }
                ?: run { rows += Row(id, gate, "TIMEOUT", "$desc (exceeded ${timeoutMs / MILLIS_PER_SECOND}s)") }
        } catch (e: CheckFailed) {
            rows += Row(id, gate, "FAIL", e.note)
        } catch (catchAll: Exception) {
            // Deliberately catch-all: an unexpected exception is a FAIL *row* carrying its type and
            // message, never a lost stack trace, so one exploding check cannot abort the sweep.
            rows += Row(id, gate, "FAIL", "${catchAll::class.simpleName}: ${catchAll.message}")
        }
    }

    private suspend fun seedSongs(): List<Song> {
        val seen = LinkedHashMap<String, Song>()
        repeat(SEED_SEARCH_PAGES) { p ->
            provider.search("top hindi", p + 1).items.forEach { seen.putIfAbsent(it.key.songId, it) }
        }
        val albums =
            provider
                .home()
                .sections
                .flatMap { it.items }
                .filter { it.albumId != null }
                .take(2)
                .mapNotNull { m: MiniEntity ->
                    m.albumId
                }
        for (id in albums) provider.album(id)?.songs?.forEach { seen.putIfAbsent(it.key.songId, it) }
        return seen.values.toList()
    }

    suspend fun run(
        mode: String,
        fast: Boolean,
    ): Int {
        // DYLAN_PROBE_API_BASE / DYLAN_PROBE_WS_URL let the gate be pointed at a
        // dead endpoint on purpose — that is the only way to prove probeCi is
        // capable of returning non-zero. DYLAN_PROBE_TIMEOUT_MS shortens the
        // per-check window so that proof does not take 45 s per check.
        val defaults = AppConfig()
        cfg =
            defaults.copy(
                apiBaseUrl = System.getenv("DYLAN_PROBE_API_BASE") ?: defaults.apiBaseUrl,
                wsSearchUrl = System.getenv("DYLAN_PROBE_WS_URL") ?: defaults.wsSearchUrl,
            )
        checkTimeoutMs =
            System.getenv("DYLAN_PROBE_TIMEOUT_MS")?.toLongOrNull()?.coerceAtLeast(1L)
                ?: DEFAULT_CHECK_TIMEOUT_MS
        api = apiClient(CIO.create(), cfg)
        bulk = bulkClient(CIO.create(), cfg)
        provider =
            SaavnProvider(
                api,
                cfg,
                CoroutineScope(Dispatchers.IO),
                TestProbeLanes.disp,
                TestProbeLanes.net,
                LogBuffer(),
            )
        // S1-S3 need no catalog seed; skipping it also keeps the nightly off the
        // three extra search+home round trips the local mode uses.
        if (mode == "ci") return structural()

        val songs =
            runCatching { seedSongs() }.getOrElse {
                rows += Row("P0", "M0", "FAIL", "catalog seed failed: ${it::class.simpleName}: ${it.message}")
                return report(mode, gates = true)
            }
        val sample = songs.filter { it.resolveRef != null && it.durationS > 0 }

        // The check order below is load-bearing: `report` lists rows in execution order and
        // probe-results.md is read as a sequence, so each helper holds the checks that used to be
        // written here, in the order they used to run in.
        coverageGate(songs)

        val ref128 = sample.firstOrNull()
        requireNotNull(ref128) { "no resolvable song found - catalog unreachable or geo-blocked" }
        val signed = provider.resolveStream(ref128.resolveRef!!, Quality.BITRATE_128)
        requireNotNull(signed) { "generateAuthToken returned no auth_url" }
        val host = runCatching { java.net.URI(signed.url).host }.getOrDefault("?")

        rangeAndEtagGates(signed, host)
        mediaIntegrityGates(signed, sample, fast)
        channelGates()
        imageVariantGate(songs)
        qualityGates(ref128, sample, host, signed)

        return report(mode, gates = true)
    }

    /** P5 — resolveRef coverage over the sampled catalog. Warn-only, so a catalog dip pages nobody. */
    private suspend fun coverageGate(songs: List<Song>) {
        check("P5", "M1", "resolveRef coverage over ${songs.size} sampled songs (warn-only)") {
            val withRef = songs.count { !it.resolveRef.isNullOrBlank() }
            val pct = if (songs.isEmpty()) 0.0 else COVERAGE_PERCENT * withRef / songs.size
            // Warn, don't fail: M1 gate so a catalog dip pages nobody, but the note stays
            // visible in probe-results.md for drift triage.
            val verdict = if (pct > COVERAGE_WARN_PCT) "ok" else "WARN: coverage below 95% gate (non-blocking)"
            "resolveRef=$withRef/${songs.size} (${"%.1f".format(pct)}%) $verdict"
        }
    }

    /** P1 + P2 — Range/If-Range semantics on a signed URL, then the ETag the resume guard needs. */
    private suspend fun rangeAndEtagGates(
        signed: SignedStream,
        host: String,
    ) {
        check("P1", "M0", "Range + If-Range semantics on $host") {
            val headOk =
                runCatching {
                    val r =
                        bulk.head(signed.url) {
                            header(HttpHeaders.UserAgent, cfg.userAgent)
                            header(HttpHeaders.Referrer, "https://www.jiosaavn.com/")
                        }
                    r.status.isSuccess() || r.status.value in listOf(HTTP_FORBIDDEN)
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
            val fallbackNote = if (!headOk) " (HEAD also failed; GET-Range fallback used)" else ""
            require(
                code100 == HTTP_PARTIAL_CONTENT,
                "GET Range expected 206 got $code100$fallbackNote",
            )
            require(
                h100[HttpHeaders.ContentLength]?.toIntOrNull() == RANGE_PROBE_BYTES,
                "206 Content-Length=${h100[HttpHeaders.ContentLength]} expected exactly 100",
            )
            val etag = h100[HttpHeaders.ETag]
            val bogus = etag != null
            val (codeBogus, _) =
                if (bogus) {
                    bulk.mediaGet(signed.url, range = "bytes=0-99", ifRange = "\"definitely-bogus-etag\"")
                } else {
                    HTTP_OK to
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
            require(!bogus || codeBogus == HTTP_OK, "If-Range bogus etag gave $codeBogus expected full 200")
            require(
                etag == null || codeMatch == HTTP_PARTIAL_CONTENT,
                "If-Range matching etag gave $codeMatch expected 206",
            )
            "AcceptRanges=${acceptRangesHead ?: "?"} 206CL=100 $ifr $ifm"
        }

        check("P2", "M0", "ETag present on media response") {
            val (_, h) = bulk.mediaGet(signed.url, range = "bytes=0-63")
            val etagP2 = h[HttpHeaders.ETag]
            require(etagP2 != null, "no ETag on media response - If-Range resume guard unusable")
            "ETag=${etagP2!!.take(ETAG_PREVIEW_CHARS)}..."
        }
    }

    /** P3 + P13 + P11 — truthful length, no bot-wall HTML, and how long the signed URL really lives. */
    private suspend fun mediaIntegrityGates(
        signed: SignedStream,
        sample: List<Song>,
        fast: Boolean,
    ) {
        check("P3", "M0", "Content-Length present and truthful") {
            val shortest = sample.minByOrNull { it.durationS }!!
            val s128 = provider.resolveStream(shortest.resolveRef!!, Quality.BITRATE_128)!!
            val (codeFull, hf) = bulk.mediaGet(s128.url)
            require(codeFull == HTTP_OK, "full GET got $codeFull")
            // Same note and same FAIL row as the `require` this replaces; only the binding changed, so
            // the header can be handed on as a non-null `Long` without a `!!`.
            val cl = hf[HttpHeaders.ContentLength]?.toLongOrNull() ?: throw CheckFailed("no Content-Length on 200")
            // The one place in this file that judges a byte count, and it refuses to compare a read
            // the cap cut short against a full header — see [truthfulLengthFailure].
            val read = bulk.bodyBytes(s128.url)
            val failure = truthfulLengthFailure(cl, read.bytes.size.toLong(), read.truncated)
            if (failure != null) throw CheckFailed(failure)
            "CL truthful: $cl bytes for ${shortest.durationS}s track"
        }

        check("P13", "M0", "signed-media DRIFT sentinel (audio/* not HTML)") {
            val (code, h) = bulk.mediaGet(signed.url, range = "bytes=0-255")
            val ct = h[HttpHeaders.ContentType].orEmpty()
            require(code == HTTP_OK || code == HTTP_PARTIAL_CONTENT, "media GET got $code")
            require(
                ct.startsWith("audio/") || ct.contains("octet-stream"),
                "DRIFT: media Content-Type='$ct' - bot-wall/HTML surface",
            )
            // Truncation is the point here: this check WANTS the first bytes, so `truncated` carries
            // no meaning for it and is deliberately not asserted on.
            val head = bulk.bodyBytes(signed.url, DRIFT_PROBE_BYTES).bytes
            require(head.isNotEmpty() && head[0] != '<'.code.toByte(), "DRIFT: body starts with '<' (HTML)")
            "CT=$ct"
        }

        check("P11", "M0", "signed-URL TTL >= 60s (server-windowed)", timeoutMs = 700_000) {
            if (fast) return@check "SKIPPED (--fast)"
            bulk.mediaGet(signed.url, range = "bytes=0-15")
            val startMs = System.nanoTime() / NANOS_PER_MILLI
            var ttlMs = -1L
            // The same sweep `Transfer` cannot do: keep probing until the URL stops answering, and
            // call the first refusal the TTL. `done` is the single exit, so the loop reads as the
            // measurement it is rather than as two breaks.
            var done = false
            while (!done) {
                delay(SIGNED_URL_POLL_MS)
                val elapsed = System.nanoTime() / NANOS_PER_MILLI - startMs
                if (elapsed >= SIGNED_URL_WINDOW_MS) {
                    ttlMs = Long.MAX_VALUE
                    done = true
                } else {
                    val r = runCatching { bulk.mediaGet(signed.url, range = "bytes=0-15") }.getOrNull()
                    val code = r?.first ?: -1
                    if (code != HTTP_OK && code != HTTP_PARTIAL_CONTENT) {
                        ttlMs = elapsed
                        done = true
                    }
                }
            }
            require(ttlMs >= SIGNED_URL_MIN_TTL_MS, "signed URL died before 60s (TTL~${ttlMs}ms)")
            if (ttlMs == Long.MAX_VALUE) {
                "still valid past 10min (dominates [60s,10min] assumption)"
            } else {
                "TTL~${ttlMs / MILLIS_PER_SECOND}s"
            }
        }
    }

    /** P12 + P4 + P8 — websocket handshake, query correlation, and the plain-HTTP fallback. */
    private suspend fun channelGates() {
        check("P12", "M0", "WS handshake + round-trip < 2s") {
            val wsCfg = cfg.copy(wsAnswerTimeoutMs = WS_ANSWER_TIMEOUT_MS)
            val t0 = System.nanoTime()
            val session = api.wsClientForProbe(wsCfg).webSocketSession(cfg.wsSearchUrl)
            session.send(
                io.ktor.websocket.Frame.Text(
                    """{"url":"/api.php?__call=autocomplete.get&query=ari&_format=json&_marker=0&ctx=web6dot0"}""",
                ),
            )
            var got = false
            while (!got) {
                val f = withTimeoutOrNull(WS_ANSWER_TIMEOUT_MS) { session.incoming.receive() } ?: break
                if (f is io.ktor.websocket.Frame.Text) got = true
            }
            session.close()
            val ms = (System.nanoTime() - t0) / NANOS_PER_MILLI
            require(got && ms < WS_ROUND_TRIP_BUDGET_MS, "round-trip ${ms}ms got=$got")
            "handshake+answer ${ms}ms"
        }

        check("P4", "M0", "WS correlation decision (3 rapid queries, one socket)") {
            val tokens = listOf("dylanp4a", "dylanp4b", "dylanp4c")
            val session = api.wsClientForProbe(cfg).webSocketSession(cfg.wsSearchUrl)
            val received = mutableListOf<String>()
            try {
                tokens.forEach { q ->
                    val payload =
                        """{"url":"/api.php?__call=autocomplete.get&query=$q&_format=json&_marker=0&ctx=web6dot0"}"""
                    session.send(
                        io.ktor.websocket.Frame
                            .Text(payload),
                    )
                }
                withTimeoutOrNull(CORRELATION_TIMEOUT_MS) {
                    while (received.size < CORRELATION_FRAMES) {
                        val f = session.incoming.receive()
                        if (f is io.ktor.websocket.Frame.Text) received += String(f.data, Charsets.UTF_8)
                    }
                }
            } finally {
                session.close()
            }
            require(
                received.size == CORRELATION_FRAMES,
                "only ${received.size}/3 frames arrived - treat as UNORDERED w/ strikes",
            )
            val echoCount = received.count { frame -> tokens.any { frame.contains(it) } }
            val mode = if (echoCount >= 2) "ECHO" else "ORDERED"
            val measured = "correlation=$mode (identity echoed in $echoCount/3 frames)"
            "$measured $CORRELATION_ADVICE"
        }

        check("P8", "M0", "autocomplete.get as plain HTTP GET") {
            val text =
                api
                    .get(cfg.apiBaseUrl) {
                        parameter("__call", "autocomplete.get")
                        parameter("query", "arijit")
                        cfg.commonParams.forEach { (k, v) -> parameter(k, v) }
                    }.bodyAsText()
            require(
                text.trimStart().startsWith("{"),
                "autocomplete fallback not JSON: '${text.take(JSON_SNIPPET_CHARS)}'",
            )
            "JSON payload ok (${text.length} chars)"
        }
    }

    /** P10 — the image variant the search screen asks for, served as an image and not as a shell. */
    private suspend fun imageVariantGate(songs: List<Song>) {
        check("P10", "M0", "-500x500 image variant exists (3 samples)") {
            val urls =
                songs
                    .map { it.artUrl500 }
                    .filter { it.contains("500x500") }
                    .distinct()
                    .take(IMAGE_VARIANT_SAMPLES)
            require(urls.size == IMAGE_VARIANT_SAMPLES, "fewer than 3 distinct 500x500 URLs derived")
            urls.forEach { u ->
                val (code, h) = bulk.mediaGet(u)
                require(
                    code == HTTP_OK && h[HttpHeaders.ContentType].orEmpty().startsWith("image/"),
                    "$u -> $code ${h[HttpHeaders.ContentType]}",
                )
            }
            "3/3 variants served"
        }
    }

    /** P6 + P7 + P9 — re-sign stability, bitrate calibration, and which edge answered. */
    private suspend fun qualityGates(
        ref128: Song,
        sample: List<Song>,
        host: String,
        signed: SignedStream,
    ) {
        check("P6", "M1", "re-sign path stability (two generateAuthToken calls)") {
            val a = provider.resolveStream(ref128.resolveRef!!, Quality.BITRATE_128)!!.url
            delay(RESIGN_SETTLE_MS)
            val b = provider.resolveStream(ref128.resolveRef!!, Quality.BITRATE_128)!!.url
            val pa = a.substringBefore('?')
            val pb = b.substringBefore('?')
            "samePath=${pa == pb} path=$pa"
        }

        check("P7", "M1", "bitrate calibration CL/duration (replaces x125)") {
            val s128 = sample.first { it.durationS > LONG_TRACK_SECONDS }
            val r128 = provider.resolveStream(s128.resolveRef!!, Quality.BITRATE_128)!!
            val cl128 = bulk.mediaGet(r128.url).second[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1
            val bps128 = if (cl128 > 0) cl128 / s128.durationS else -1
            var line = "128kbps=${bps128}B/s(x125 would be 16000)"
            val s320 = sample.firstOrNull { it.has320 && it.durationS > LONG_TRACK_SECONDS }
            if (s320 != null) {
                val r320 = provider.resolveStream(s320.resolveRef!!, Quality.BITRATE_320)
                val cl320 =
                    r320?.let {
                        bulk.mediaGet(it.url).second[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1
                    } ?: -1
                val bps320 = if (cl320 > 0) cl320 / s320.durationS else -1
                line += " | 320kbps=${bps320}B/s(x125 would be 40000)"
            }
            line
        }

        check("P9", "M1", "geo sanity: CDN edge logged") {
            "apiHost=${java.net.URI(cfg.apiBaseUrl).host} cdnEdge=$host signedType=${signed.type}"
        }
    }

    private suspend fun structural(): Int {
        check("S1", "M0", "api.php returns JSON (search.getResults live shape)") {
            val paged = provider.search("arijit", 1)
            require(paged.items.isNotEmpty(), "live search mapped zero songs")
            "mapped ${paged.items.size} songs, first='${paged.items.first().title}'"
        }

        check("S2", "M0", "autocomplete reachable over plain HTTP") {
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
        check("S3", "M0", "WS handshake reachable") {
            val s =
                withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                    api.wsClientForProbe(cfg).webSocketSession(cfg.wsSearchUrl)
                }
            requireNotNull(s) { "handshake failed" }
            s.close()
            "reachable"
        }
        return report("ci", gates = true)
    }

    private fun report(
        mode: String,
        gates: Boolean,
    ): Int {
        val stamp = java.time.Instant.now()
        val bad = rows.count { it.status in GATING_STATUSES }
        println("\n=== DYLAN probe ($mode) @ $stamp ===")
        rows.forEach { r ->
            val mark =
                when (r.status) {
                    "PASS" -> "+"
                    in GATING_STATUSES -> "!"
                    else -> "-"
                }
            println("[$mark] ${r.id} (${r.gate}) ${r.note}")
        }
        println("---\n${rows.size - bad}/${rows.size} checks passed")
        val blocking = if (gates) rows.filter { it.status in GATING_STATUSES && it.gate == "M0" } else emptyList()
        if (blocking.isNotEmpty()) {
            println(
                "GATING FAILURES: ${blocking.joinToString { "${it.id}(${it.status})" }}" +
                    " — see build/reports/probe/probe-results.md",
            )
        }
        // Notes carry live API response bodies — never write them to a tracked file.
        val dir = File("build/reports/probe").also { it.mkdirs() }
        File(dir, "probe-results.md").appendText(
            "\n## $stamp mode=$mode gates=$gates\n" +
                rows.joinToString("\n") { "| ${it.id} | ${it.gate} | ${it.status} | ${it.note.replace("|", "/")} |" } +
                "\n",
        )
        return if (blocking.isEmpty()) 0 else 1
    }
}

/**
 * The lanes and connectivity the live probe runs on. The probe is a `main()` with no graph, so it
 * supplies them itself; `AlwaysOnline` matches the `NetMonitor` contract default of a platform
 * monitor that actually knows the answer (the probe requires the network to be up by construction).
 */
private object TestProbeLanes {
    val disp = AppDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default)
    val net =
        object : NetMonitor {
            override fun current(): NetClass = NetClass.UNMETERED

            override fun isOnline(): Boolean = true

            override fun changes(): Flow<NetClass> = MutableStateFlow(NetClass.UNMETERED)
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
