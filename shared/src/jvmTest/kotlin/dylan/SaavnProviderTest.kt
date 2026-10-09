package dylan

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.model.ErrorCode
import dylan.model.Quality
import dylan.provider.CatalogResult
import dylan.provider.ResilientClient
import dylan.provider.durationKnown
import dylan.provider.dylanJson
import dylan.provider.saavn.SaavnProvider
import dylan.provider.saavn.artistRoute
import dylan.provider.saavn.deliveredTotal
import dylan.provider.saavn.dto.MoreInfoDto
import dylan.provider.saavn.dto.ResultsDto
import dylan.provider.saavn.mapSuggestions
import dylan.provider.saavn.rowKey
import dylan.support.MutableClock
import dylan.support.TestLanes
import dylan.util.Connectivity
import dylan.util.Lane
import dylan.util.NetClass
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SaavnProvider` had no test file at all, and three of the fifteen committed fixtures were
 * orphans referenced by nothing but the generator — and those three were precisely the provider's
 * error paths (429, HTML interstitial, expired signature). Every assertion here names the fixture
 * it came from, because "the provider returns null" was the old contract and the whole point of
 * [CatalogResult] is that the reason is now part of the value.
 */
@Suppress("LargeClass")
class SaavnProviderTest {
    private val clock = MutableClock()
    private val lanes = TestLanes()
    private val log = LogBuffer()

    /**
     * The MockEngine handler appends here from whichever thread is serving the IO lane, and `io` is
     * `Dispatchers.IO` — genuinely multi-permit, unlike the state/db lanes which are
     * `limitedParallelism(1)`. So this was a plain `ArrayList` being mutated concurrently, which is
     * a lost-update race, not a theoretical one: two `+=` on an ArrayList can both read the same
     * size and one write is lost. That is what made
     * `theSnapshotLruSurvivesConcurrentWritersThatTheLinkedHashMapDoesNot` fail intermittently on
     * CI (the assertion is "exactly one request per key", so a dropped append shows up as a count
     * that is too LOW) while passing locally on a faster, differently-interleaved machine.
     *
     * CopyOnWriteArrayList because these lists are tiny and written rarely; a synchronized list
     * would work too and costs the same at this size.
     */
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun fixture(name: String): String =
        java.io
            .File(
                System.getProperty("user.dir"),
                "../fixtures/$name",
            ).readText()

    private fun client(
        handler: suspend MockRequestHandleScope
        .(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler))

    private fun okClient(body: () -> String): HttpClient =
        client { req ->
            requests += req.url.toString()
            respond(
                content = body(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", listOf("application/json")),
            )
        }

    private fun statusClient(
        code: HttpStatusCode,
        body: String = "{}",
        retryAfter: String? = null,
    ): HttpClient =
        client { req ->
            requests += req.url.toString()
            val h = retryAfter?.let { headersOf("Retry-After", it) } ?: headersOf()
            respond(content = body, status = code, headers = h)
        }

    /** Runs [block] with a provider whose `scope`/`disp`/`net` are the production shapes. */
    private fun withProvider(
        http: HttpClient,
        online: Boolean = true,
        netClass: NetClass = NetClass.UNMETERED,
        cfg: AppConfig = AppConfig(clock = clock),
        block: suspend (SaavnProvider) -> Unit,
    ) {
        val net =
            object : dylan.util.NetMonitor {
                private val state = MutableStateFlow(Connectivity(online, netClass))

                override fun connectivity(): Flow<Connectivity> = state

                override fun current(): NetClass = netClass

                override fun isOnline(): Boolean = online
            }
        val scope = CoroutineScope(lanes.disp.io + kotlinx.coroutines.SupervisorJob())
        val p = SaavnProvider(http, cfg, scope, lanes.disp, net, log)
        runBlocking { block(p) }
        scope.cancel()
    }

    // ── the three orphaned error fixtures ────────────────────────────────────────────────────

    /**
     * `fixtures/rate_limited_429.json` was committed, referenced by nothing, and is the only
     * committed evidence of the origin's 429 body. It used to be indistinguishable from every other
     * failure: the provider returned `null` and the screen said "Check your connection".
     */
    @Test
    fun rateLimitedIsItsOwnCodeAndHonoursRetryAfter() {
        val limited = fixture("rate_limited_429.json")
        withProvider(statusClient(HttpStatusCode.TooManyRequests, limited, retryAfter = "7")) { p ->
            val r = p.searchPage("a", 1)
            val e = (r as CatalogResult.Err)
            assertEquals(ErrorCode.RATE_LIMITED, e.code, "a 429 is RATE_LIMITED, not NETWORK: ${e.detail}")
            assertTrue(e.retryable, "429 is retryable once Retry-After elapses")
            assertTrue(e.detail!!.contains("retry-after=7"), "Retry-After must reach the caller: ${e.detail}")
        }
        assertEquals(7_000L, ResilientClient.retryAfterMs("7", 60_000))
        assertNull(ResilientClient.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT", 60_000))
        assertEquals(60_000L, ResilientClient.retryAfterMs("9999", 60_000))
    }

    /**
     * `fixtures/html_error_page.txt` is the bot-wall. It arrives as a **200** with an HTML body, so
     * it is not a status code at all: the only way to see it is to look at the first
     * non-whitespace character — which is the "magic before header" rule the audit §3.5 asks for, and
     * which the download layer still gets wrong in the other direction.
     */
    @Test
    fun htmlInterstitialOnA200IsDriftNotAnEmptyPage() {
        withProvider(okClient { fixture("html_error_page.txt") }) { p ->
            val r = p.searchPage("a", 1)
            val e = r as CatalogResult.Err
            assertEquals(ErrorCode.DRIFT, e.code, "a 200 whose body is HTML is contract drift: ${e.detail}")
            assertFalse(e.retryable, "a bot wall does not fix itself in 30s")
        }
        assertTrue(ResilientClient.looksLikeHtml("  \n<!DOCTYPE html>"))
        assertFalse(ResilientClient.looksLikeHtml("""  {"total":0}"""))
    }

    /**
     * `fixtures/expired_signature_403.txt` is the geo-block / expired-credential body. 401 and 403
     * are two different codes here, because "try again later" and "not available in your region"
     * are two different user-facing messages and the app has copy for both.
     */
    @Test
    fun forbiddenIsRegionAndUnauthorizedIsExpired() {
        withProvider(statusClient(HttpStatusCode.Forbidden, fixture("expired_signature_403.txt"))) { p ->
            val e = p.searchPage("a", 1) as CatalogResult.Err
            assertEquals(ErrorCode.FORBIDDEN_REGION, e.code)
            assertFalse(e.retryable, "a geo-block is not fixed by retrying")
        }
        withProvider(statusClient(HttpStatusCode.Unauthorized, fixture("expired_signature_403.txt"))) { p ->
            val e = p.searchPage("a", 1) as CatalogResult.Err
            assertEquals(ErrorCode.EXPIRED, e.code, "401 means the signature/credential plausibly expired")
            assertTrue(e.retryable, "a fresh resolve is exactly what an expired signature needs")
        }
    }

    // ── the rest of the mapping table ────────────────────────────────────────────────────────

    @Test
    fun eachTransportOutcomeHasADistinctCode() {
        val table =
            listOf(
                HttpStatusCode.NotFound to ErrorCode.NOT_FOUND,
                HttpStatusCode.BadRequest to ErrorCode.NETWORK,
                HttpStatusCode.InternalServerError to ErrorCode.NETWORK,
                HttpStatusCode.GatewayTimeout to ErrorCode.NETWORK,
            )
        for ((status, code) in table) {
            withProvider(statusClient(status)) { p ->
                val e = p.searchPage("a", 1) as CatalogResult.Err
                assertEquals(code, e.code, "status $status must not collapse into another code")
            }
        }
    }

    @Test
    fun noConnectivityIsOfflineAndNeverReachesTheWire() {
        requests.clear()
        withProvider(okClient { "{}" }, online = false) { p ->
            val e = p.searchPage("a", 1) as CatalogResult.Err
            assertEquals(ErrorCode.OFFLINE, e.code)
            assertTrue(e.retryable, "connectivity returns on its own")
        }
        assertTrue(requests.isEmpty(), "an offline device must not spend a request: $requests")
    }

    @Test
    fun aTimeoutIsDistinctFromAnyOtherTransportFailure() {
        assertTrue(
            ResilientClient.isTimeout(
                io.ktor.client.plugins
                    .HttpRequestTimeoutException("x", 1L),
            ),
        )
        assertTrue(ResilientClient.isTimeout(java.net.SocketTimeoutException()))
        assertFalse(ResilientClient.isTimeout(java.net.UnknownHostException()))
    }

    // ── one bad card must not blank a good page ─────────────────────────────────────────────

    /**
     * The defect this whole layer was un-debuggable because of: `"id": null` in card 3 of 5 made
     * `decodeFromString(ResultsDto)` throw, the `runCatching` was wrapped around the *whole* page,
     * and the user saw "Songs 0 of 0" — byte-identical to a network failure.
     */
    @Test
    fun oneUnusableCardDropsOneCardNotThePage() {
        val page =
            buildString {
                append("""{"total":"5","start":"1","results":[""")
                repeat(5) { i ->
                    if (i > 0) append(',')
                    if (i == 2) {
                        append("""{"id":null,"title":null,"more_info":null}""")
                    } else {
                        append(
                            """{"id":"s$i","title":"Song $i","type":"song",""" +
                                """"perma_url":"https://www.jiosaavn.com/song/x/token$i",""" +
                                """"more_info":{"duration":"200","encrypted_media_url":"ref$i"}}""",
                        )
                    }
                }
                append("]}")
            }
        withProvider(okClient { page }) { p ->
            val r = p.searchPage("a", 1)
            val ok = r as CatalogResult.Ok
            assertEquals(4, ok.value.items.size, "19-good-of-20 must survive; got ${ok.value.items}")
            assertTrue(ok.drift.isNotEmpty(), "the drop must be reported, not silent: ${ok.drift}")
            assertEquals(5L, ok.value.total, "the server total is not the mapped count")
        }
    }

    /** A card whose field has the *wrong shape* still only costs that card. */
    @Test
    fun aStructurallyBrokenCardDropsOneCard() {
        val page =
            """{"total":"3","start":"1","results":[
              {"id":"a","title":"A","type":"song","more_info":{"duration":"10"}},
              {"id":{"nested":true},"title":"B","type":"song"},
              {"id":"c","title":"C","type":"song","more_info":{"duration":"30"}}
            ]}"""
        withProvider(okClient { page }) { p ->
            val ok = p.searchPage("a", 1) as CatalogResult.Ok
            assertEquals(listOf("A", "C"), ok.value.items.map { it.title }, "only card B is lost")
        }
    }

    /**
     * The provider's contract is that every outcome of a catalog call is a [CatalogResult]. One
     * documented field shape broke it: `image` is declared untyped on the DTO precisely because the
     * API has shipped `string → object → null`, and reading an object with `jsonPrimitive` **throws**.
     * So an object-shaped card did not cost one card — an `IllegalArgumentException` came out of
     * `searchPage` itself and took the whole page with it, past the very type that exists to make
     * failures values. (`MapperFixturesTest` pins the detail: the artwork survives, and an
     * unreadable `duration` next to it is still a named drift.)
     */
    @Test
    fun aFieldThatChangedShapeCannotThrowOutOfTheProvider() {
        val page =
            """{"total":"1","start":"1","results":[{"id":"a","title":"A","type":"song",
               "image":{"150x150":"https://c.saavncdn.com/a-150x150.jpg"},"more_info":{"duration":"10"}}]}"""
        withProvider(okClient { page }) { p ->
            val ok = p.searchPage("a", 1) as CatalogResult.Ok
            val card = ok.value.items.single()
            assertEquals("A", card.title)
            assertEquals("https://c.saavncdn.com/a-150x150.jpg", card.artUrl150)
        }
    }

    // ── negative cache ───────────────────────────────────────────────────────────────────────

    // Split by section would scatter the fixture-per-assertion naming that makes this file
    // readable; the sections are already delimited. Suppressed on the class rather than baselined
    // so the exception is visible here instead of in a generated XML file.
    @Test
    fun aWalledEndpointIsNotReRequestedForTheNegativeTtl() {
        requests.clear()
        withProvider(okClient { fixture("html_error_page.txt") }) { p ->
            repeat(4) { assertTrue(p.searchPage("a", it + 1) is CatalogResult.Err) }
            assertEquals(1, requests.size, "every keystroke hit the wall again: $requests")
            clock.advanceMs(AppConfig().catalogNegativeTtlMs + 1)
            p.searchPage("a", 1)
            assertEquals(2, requests.size, "the negative entry must expire, not stick for the process")
        }
    }

    /** OFFLINE is never negative-cached: connectivity comes back on its own. */
    @Test
    fun offlineIsNeverNegativeCached() {
        requests.clear()
        val net =
            object : dylan.util.NetMonitor {
                @Volatile
                var up = true

                private val state = MutableStateFlow(Connectivity(up, NetClass.UNMETERED))

                override fun connectivity(): Flow<Connectivity> = state

                override fun current(): NetClass = NetClass.UNMETERED

                override fun isOnline(): Boolean = up
            }
        val scope = CoroutineScope(lanes.disp.io + kotlinx.coroutines.SupervisorJob())
        val page = fixture("search_getresults_p1.json")
        val p = SaavnProvider(okClient { page }, AppConfig(clock = clock), scope, lanes.disp, net, log)
        kotlinx.coroutines.runBlocking {
            net.up = false
            assertEquals(ErrorCode.OFFLINE, (p.searchPage("a", 1) as CatalogResult.Err).code)
            net.up = true
            assertTrue(p.searchPage("a", 1) is CatalogResult.Ok, "the very next keystroke must be tried again")
        }
        scope.cancel()
        assertEquals(1, requests.size, "only the online attempt reaches the wire: $requests")
    }

    /**
     * The negative cache is keyed by *endpoint*, and one endpoint serves many ids — so it may only
     * replay facts about the endpoint.
     *
     * A 404 is a fact about **one album**. Recorded against `content.getAlbumDetails`, it answered
     * every *other* numeric album with `NOT_FOUND` for the whole TTL — a wrong answer, not a stale
     * one, with no way for the caller to tell it from a real miss. The origin returned the second
     * album's page; the app never asked.
     */
    @Test
    fun aNotFoundOnOneAlbumIsNotReplayedForEveryOtherAlbum() {
        requests.clear()
        val album = fixture("album_detail_full.json")
        val http =
            client { req ->
                requests += req.url.toString()
                if (req.url.toString().contains("albumid=111")) {
                    respond(content = "{}", status = HttpStatusCode.NotFound)
                } else {
                    respond(
                        content = album,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", listOf("application/json")),
                    )
                }
            }
        withProvider(http) { p ->
            assertEquals(ErrorCode.NOT_FOUND, (p.albumDetail("111") as CatalogResult.Err).code)
            val other = p.albumDetail("222") as CatalogResult.Ok
            assertEquals("Awarapan 2", other.value.title, "a real album must still load")
            assertTrue(
                requests.size == 2,
                "one request per album: a per-request 404 must not become an endpoint-wide verdict ($requests)",
            )
        }
    }

    /**
     * The same argument for the other per-request status: a malformed query is a 400 about *that*
     * query. `search.getResults` is one endpoint for every keystroke.
     */
    @Test
    fun aBadRequestOnOneQueryIsNotReplayedForEveryOtherQuery() {
        requests.clear()
        val page = fixture("search_getresults_p1.json")
        val http =
            client { req ->
                requests += req.url.toString()
                if (req.url.toString().contains("q=bad")) {
                    respond(content = "{}", status = HttpStatusCode.BadRequest)
                } else {
                    respond(
                        content = page,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", listOf("application/json")),
                    )
                }
            }
        withProvider(http) { p ->
            assertEquals(ErrorCode.NETWORK, (p.searchPage("bad", 1) as CatalogResult.Err).code)
            assertTrue(p.searchPage("good", 1) is CatalogResult.Ok)
            assertEquals(2, requests.size, "a 400 is about the query, not the endpoint: $requests")
        }
    }

    /** 5xx is genuinely endpoint-scoped, so it *is* replayed — the negative cache's real purpose. */
    @Test
    fun anOriginFailureIsStillReplayedForTheWholeEndpoint() {
        requests.clear()
        withProvider(statusClient(HttpStatusCode.InternalServerError)) { p ->
            repeat(3) { assertTrue(p.searchPage("a", it + 1) is CatalogResult.Err) }
            assertEquals(1, requests.size, "a 5xx is a fact about the origin: $requests")
        }
        assertTrue(ResilientClient.isEndpointScoped(503))
        assertTrue(ResilientClient.isEndpointScoped(429))
        assertTrue(ResilientClient.isEndpointScoped(403))
        assertTrue(ResilientClient.isEndpointScoped(null), "a transport failure says something about the host")
        assertTrue(
            ResilientClient.isEndpointScoped(200, ErrorCode.DRIFT),
            "a bot wall arrives as a 200 and is still a fact about the origin",
        )
        assertFalse(ResilientClient.isEndpointScoped(404), "404 is about one album")
        assertFalse(ResilientClient.isEndpointScoped(400), "400 is about one query")
    }

    /**
     * The origin's `Retry-After` is a stronger statement than our own 30 s floor, and it used to be
     * parsed in production *nowhere*: a 429 saying "an hour" was replayed for 30 s and then
     * re-requested, which is precisely the request the header was asking us not to make. Honoured,
     * capped at two minutes — an origin must not be able to park the catalog indefinitely.
     */
    @Test
    fun aRateLimitIsParkedForAsLongAsTheOriginAsked() {
        requests.clear()
        withProvider(statusClient(HttpStatusCode.TooManyRequests, retryAfter = "3600")) { p ->
            assertEquals(ErrorCode.RATE_LIMITED, (p.searchPage("a", 1) as CatalogResult.Err).code)
            clock.advanceMs(AppConfig().catalogNegativeTtlMs + 1)
            assertEquals(
                ErrorCode.RATE_LIMITED,
                (p.searchPage("a", 2) as CatalogResult.Err).code,
                "past our own TTL but inside the origin's window the answer must be replayed",
            )
            assertEquals(
                1,
                requests.size,
                "the origin asked for an hour; we must not spend a request at 30s: $requests",
            )
            clock.advanceMs(ResilientClient.MAX_NEGATIVE_TTL_MS)
            p.searchPage("a", 3)
            assertEquals(2, requests.size, "the clamp must still expire: $requests")
        }
    }

    /** A `Retry-After` shorter than our floor must not become permission to hammer. */
    @Test
    fun aShortRetryAfterNeverShortensTheFloor() {
        requests.clear()
        withProvider(statusClient(HttpStatusCode.TooManyRequests, retryAfter = "1")) { p ->
            p.searchPage("a", 1)
            clock.advanceMs(2_000)
            p.searchPage("a", 2)
            assertEquals(1, requests.size, "1 s of origin patience is not our backoff: $requests")
            clock.advanceMs(AppConfig().catalogNegativeTtlMs)
            p.searchPage("a", 3)
            assertEquals(2, requests.size, "…but the floor still expires: $requests")
        }
    }

    // ── LRU + single flight ──────────────────────────────────────────────────────────────────

    @Test
    fun albumIsCachedAndOneAlbumOpenedThreeTimesFiresOneRequest() {
        requests.clear()
        withProvider(okClient { fixture("album_detail_full.json") }) { p ->
            val opened =
                kotlinx.coroutines.coroutineScope {
                    List(3) { async { p.albumDetail("79121261") } }.map { it.await() }
                }
            assertEquals(1, requests.size, "three concurrent opens of one album must be one request: $requests")
            assertTrue(opened.all { it is CatalogResult.Ok })
            p.albumDetail("79121261")
            assertEquals(1, requests.size, "the LRU must serve the fourth open with no request")
        }
    }

    @Test
    fun numericAlbumIdRoutesToGetAlbumDetailsAndTokenToWebapi() {
        requests.clear()
        withProvider(okClient { fixture("album_detail_full.json") }) { p ->
            p.albumDetail("79121261")
            p.albumDetail("e9NTAB1tQ9M_")
        }
        assertTrue(requests[0].contains("content.getAlbumDetails"), "numeric ids: ${requests[0]}")
        assertTrue(requests[0].contains("albumid=79121261"), requests[0])
        assertTrue(requests[1].contains("webapi.get"), "perma tokens: ${requests[1]}")
        assertTrue(requests[1].contains("type=album"), requests[1])
    }

    @Test
    fun aNumericArtistIdIsRefusedBecauseThereIsNoRouteForIt() {
        requests.clear()
        withProvider(okClient { fixture("artist_detail.json") }) { p ->
            val e = p.artistDetail("610240") as CatalogResult.Err
            assertEquals(ErrorCode.NOT_FOUND, e.code)
            assertTrue(
                e.detail!!.contains("no numeric-artist route"),
                "the failure must name the routing fact, not blame the connection: ${e.detail}",
            )
        }
        assertTrue(requests.isEmpty(), "refusing must not spend a request: $requests")
        assertTrue(artistRoute("610240") is dylan.provider.saavn.ArtistRoute.UnsupportedNumericId)
        assertTrue(artistRoute("-f6Su9-0agk_") is dylan.provider.saavn.ArtistRoute.WebapiToken)
        assertTrue(
            artistRoute("  -f6Su9-0agk_  ") is dylan.provider.saavn.ArtistRoute.WebapiToken,
            "ids arrive untrimmed",
        )
    }

    @Test
    fun anArtistPermaTokenDecodesAndAnEmptyShellIsNotFound() {
        withProvider(okClient { fixture("artist_detail.json") }) { p ->
            val ok = p.artistDetail("-f6Su9-0agk_") as CatalogResult.Ok
            assertEquals("Eminem", ok.value.name)
            assertTrue(ok.value.songs.isNotEmpty())
        }
        withProvider(okClient { """{"artistId":null,"name":"","type":"artist","topSongs":[]}""" }) { p ->
            // Exactly the live 200-empty-shell for a numeric id.
            val e = p.artistDetail("-f6Su9-0agk_") as CatalogResult.Err
            assertEquals(ErrorCode.NOT_FOUND, e.code, "a 200 blank artist is 'gone', not 'no matches'")
        }
    }

    // ── duration ────────────────────────────────────────────────────────────────────────────

    /**
     * `fixtures/malformed_fields.json` has `"duration": "not-a-number"`. The old contract was a
     * silent `0`, which is a value a real payload can also produce — and the live probe filters
     * exactly those songs out (`songs.filter { it.durationS > 0 }`), so the harness was blind to
     * them. Now it is a *named drift* plus a queryable predicate.
     */
    @Test
    fun anUnparsedDurationIsDriftNotASilentZero() {
        val song =
            dylan.provider.saavn.mapSong(
                dylan.provider.saavn.dto.SongDto(
                    id = "1",
                    title = "t",
                    moreInfo =
                        Json.encodeToJsonElement(
                            MoreInfoDto.serializer(),
                            MoreInfoDto(duration = JsonPrimitive("not-a-number")),
                        ),
                ),
            )
        assertNotNull(song)
        assertEquals(0L, song.durationS, "the model field is still non-nullable (W3-D's file)")
        assertFalse(durationKnown(song.durationS), "but every caller must be able to ask")
        val rows =
            dylan.provider.saavn.decodeSongPage(
                """{"total":"1","start":"1","results":[
              {"id":"1","title":"t","type":"song","more_info":{"duration":"not-a-number"}}]}""",
                "test",
                1,
            )
        assertTrue(
            rows.drift.any { it.reason == dylan.provider.saavn.DURATION_UNPARSED },
            "an unparsed duration must be a named drift: ${rows.drift}",
        )
    }

    // ── mini pages ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aMiniPageReportsATotalItCanActuallyMakeProgressAgainst() {
        // 3 cards, 1 of them an album; the envelope's `total` counts the *mixed* set.
        val page =
            """{"total":"3","start":"1","results":[
              {"id":"1","title":"A","type":"album","perma_url":"https://www.jiosaavn.com/album/a/t1"},
              {"id":"2","title":"B","type":"song"},
              {"id":"3","title":"C","type":"artist","perma_url":"https://www.jiosaavn.com/artist/c/t3"}]}"""
        withProvider(okClient { page }) { p ->
            val ok = p.searchAlbumPage("a", 1) as CatalogResult.Ok
            assertEquals(listOf("A"), ok.value.items.map { it.title })
            assertEquals(
                1L,
                ok.value.total,
                "a partial page must not report the mixed total — that is what pins hasMore on forever",
            )
        }
        assertEquals(21L, deliveredTotal(1, 20, 20), "a full page promises exactly one more")
        // Page 3 of 8-wide: 16 already delivered, 8 more, and a full page still promises one.
        assertEquals(25L, deliveredTotal(3, 8, 8), "a full page mid-section keeps promising one more")
        // ...and the same page returning 5 of 8 is the end of the section, not a promise of forever.
        assertEquals(21L, deliveredTotal(3, 8, 5), "a short page ends the section")
        assertEquals(0L, deliveredTotal(1, 20, 0), "a page of pure songs advances nothing and says so")
    }

    @Test
    fun aMiniCardWithoutAPermaTokenIsDriftAndIsNotGivenAUselessId() {
        val page =
            """{"total":"2","start":"1","results":[
              {"id":"79121261","title":"A","type":"album","perma_url":""},
              {"id":"2","title":"B","type":"album","perma_url":"https://www.jiosaavn.com/album/b/tok"}]}"""
        withProvider(okClient { page }) { p ->
            val ok = p.searchAlbumPage("a", 1) as CatalogResult.Ok
            assertEquals(listOf("B"), ok.value.items.map { it.title })
            assertEquals(
                "tok",
                ok.value.items
                    .first()
                    .albumId,
            )
            assertTrue(
                ok.drift.any { it.reason == dylan.provider.saavn.NO_PERMA_TOKEN },
                "minting a numeric id is what produced 'Check your connection': ${ok.drift}",
            )
        }
    }

    // ── top searches / home ──────────────────────────────────────────────────────────────────

    @Test
    fun topSearchesMapAndTheirTitlesAreTrimmed() {
        withProvider(okClient { fixture("top_searches.json") }) { p ->
            val ok = p.topSearchList() as CatalogResult.Ok
            assertTrue(ok.value.isNotEmpty())
            assertTrue(
                ok.value.none { it.title != it.title.trim() },
                "the origin ships titles with trailing spaces: ${ok.value.map { it.title }}",
            )
            assertTrue(ok.value.any { it.type == "album" && it.albumId != null })
        }
    }

    @Test
    fun homeTrendingDecodesIntoASection() {
        withProvider(okClient { fixture("trending.json") }) { p ->
            val ok = p.homeFeed() as CatalogResult.Ok
            assertEquals(1, ok.value.sections.size)
            assertTrue(
                ok.value.sections
                    .first()
                    .items
                    .isNotEmpty(),
            )
        }
    }

    // ── resolve ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun resolveReturnsASignedStreamAndAnExpiredOneIsNotTheSameFailure() {
        withProvider(okClient { fixture("generate_auth_token_128.json") }) { p ->
            val ok = p.resolve("encrypted-ref", Quality.BITRATE_128) as CatalogResult.Ok
            assertTrue(ok.value.url.startsWith("https://"))
            assertEquals("mp4", ok.value.type)
        }
        withProvider(statusClient(HttpStatusCode.Forbidden, fixture("expired_signature_403.txt"))) { p ->
            val e = p.resolve("ref", Quality.BITRATE_128) as CatalogResult.Err
            assertEquals(ErrorCode.FORBIDDEN_REGION, e.code, "a blocked resolve is not a NETWORK blip")
        }
        withProvider(okClient { """{"status":"error"}""" }) { p ->
            val e = p.resolve("ref", Quality.BITRATE_128) as CatalogResult.Err
            assertEquals(ErrorCode.NO_SOURCE, e.code)
        }
    }

    // ── lanes ───────────────────────────────────────────────────────────────────────────────

    /**
     * The mapping used to run on the caller's lane, which in this app is the single-threaded
     * `state` lane — the same lane as the orchestrator inbox, the 10 Hz position ticker and the
     * `protectedKeys` collector. JSON mapping does not yield, so a 20-card page delayed
     * player-state emissions.
     *
     * `mapOnIo` calls `disp.assert(Lane.IO)` inside the hop, and the Gradle test worker runs with
     * assertions enabled, so mapping on the caller's lane *fails* this test rather than passing
     * quietly. The cost of that mapping is measured in `SearchPerfTest`.
     */
    @Test
    fun decodingRunsOnTheIoLaneEvenWhenTheCallerIsOnState() {
        val http = okClient { fixture("search_getresults_p1.json") }
        withProvider(http) { p ->
            kotlinx.coroutines.runBlocking {
                withContext(lanes.disp.on(Lane.STATE)) {
                    val r = p.searchPage("a", 1)
                    assertTrue(r is CatalogResult.Ok, "mapping off the IO lane trips disp.assert")
                }
            }
        }
    }

    // ── the WS frame, which is the same mapper ──────────────────────────────────────────────

    @Test
    fun theCommittedWsFrameParsesAndCarriesNoQueryEcho() {
        val rows = mapSuggestions(fixture("autocomplete_ws_frame.json"))
        assertTrue(rows.items.isNotEmpty(), "${rows.drift}")
        assertNull(
            rows.items.firstOrNull { it.title.contains("Top Result") },
            "the `modules` bucket is titles, not cards, and must not become a row",
        )
        assertTrue(
            rows.items.any { it.type == "playlist" && it.songKey == null },
            "playlists must still key and still show",
        )
        val keys = rows.items.map { it.rowKey }
        assertEquals(keys.size, keys.distinct().size, "the committed frame repeats cards across buckets: $keys")
        assertTrue(keys.none { it.endsWith(":") }, "a row key must never degenerate to a title alone: $keys")
    }

    @Test
    fun aKeepaliveFrameIsNotRenderedAsResults() {
        val frame = """{"action":"keepalive","resp":"{\"data_0\":[]}"}"""
        val rows = mapSuggestions(frame)
        assertTrue(rows.items.isEmpty())
        assertTrue(rows.drift.any { it.reason == dylan.provider.saavn.FRAME_NOT_SEARCH }, "${rows.drift}")
    }

    @Test
    fun respAsAnObjectIsNotFatal() {
        val frame = """{"action":"search","resp":{"data_0":[{"id":"a","title":"A","type":"song"}]}}"""
        val rows = mapSuggestions(frame)
        assertEquals(listOf("A"), rows.items.map { it.title }, "the object shape must survive (SC: resp was a String)")
    }

    // ── misc ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aBlankQueryNeverReachesTheWire() {
        requests.clear()
        withProvider(okClient { "{}" }) { p ->
            assertEquals(0L, (p.searchPage("   ", 1) as CatalogResult.Ok).value.total)
            assertEquals(0, (p.searchAlbumPage("", 1) as CatalogResult.Ok).value.items.size)
        }
        assertTrue(requests.isEmpty(), "blank queries are answered locally: $requests")
    }

    // ── measurement ─────────────────────────────────────────────────────────────────────────
    //
    // Not JMH: the module has no benchmark source set. `measureNanoTime` over a fixed workload
    // after 3 warmup rounds, a blackhole accumulator so the JIT cannot delete the work, and
    // `getThreadAllocatedBytes` for the allocation claim. Same pattern as
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

    private fun bench(block: () -> Unit): Long {
        repeat(WARMUP_ROUNDS) { block() }
        val id = Thread.currentThread().id
        val before = allocatedBytes(id)
        val ns = kotlin.system.measureNanoTime { repeat(PER_OP) { block() } }
        blackhole += ns
        val bytes = allocatedBytes(id) - before
        if (bytes >= 0) {
            println("[bench] %-46s %8d B/op".format("   (allocation)", bytes / PER_OP))
        }
        return ns / PER_OP
    }

    /**
     * The cost that used to land on the `state` lane: parsing and mapping one realistic 20-card
     * `search.getResults` page. It is CPU-bound and yields nowhere, so every microsecond of it was
     * time the orchestrator inbox and the 10 Hz position ticker did not get.
     */
    @Test
    fun mappingOnePageCostsTheStateLaneThisMuch() {
        val page = fixture("search_getresults_p1.json")
        val real = dylan.provider.saavn.decodeSongPage(page, "bench", 1)
        // Four copies of the fixture's *own* cards, not `cardJson` stubs. A stub card is ~400 B
        // against a real ~3.5 KB one (encrypted_media_url, encrypted_drm_media_url, album_url,
        // copyright_text, trivia, a second artist), so five real cards were 19.7 KB and twenty
        // stub cards 8.7 KB: the "20-card page" carried *less* payload, and the comparison
        // measured payload weight while claiming to measure card count. Replicating the real
        // cards leaves the number of cards as the only variable, which is the claim.
        val cards =
            Json
                .parseToJsonElement(page)
                .jsonObject
                .getValue("results")
                .jsonArray
        // Each replica gets its own `id` AND its own `title`. Replicating a card verbatim no longer
        // works: identical title+artist+duration is exactly the cross-rendition duplicate that
        // `decodeSongPage` folds, so a 20-card page built that way would decode to a handful of rows
        // and the benchmark would measure a fold rather than the mapping of 20 cards. Varying the
        // title keeps card count the only variable, which is the claim the comment above makes.
        val big =
            buildString {
                append("{\"total\":\"$PAGE_CARDS\",\"start\":\"1\",\"results\":[")
                repeat(PAGE_CARDS / cards.size) { rep ->
                    cards.forEachIndexed { i, card ->
                        val obj = (card as JsonObject).toMutableMap()
                        obj["id"] = JsonPrimitive("bench-${rep}x$i")
                        obj["title"] = JsonPrimitive("${obj["title"]?.jsonPrimitive?.content} #$rep.$i")
                        append(JsonObject(obj).toString())
                        append(",")
                    }
                }
                setLength(length - 1)
                append("]}")
            }
        val rows = dylan.provider.saavn.decodeSongPage(big, "bench", 1)
        assertEquals(PAGE_CARDS, rows.items.items.size, "the replicated page must be complete: ${rows.drift}")

        val realNs =
            bench {
                blackhole +=
                    dylan.provider.saavn
                        .decodeSongPage(page, "bench", 1)
                        .items.items.size
            }
        val bigNs =
            bench {
                blackhole +=
                    dylan.provider.saavn
                        .decodeSongPage(big, "bench", 1)
                        .items.items.size
            }
        println(
            "[bench] %-46s %8.3f ms  %6d cards  %6d B".format(
                "map a real 5-card page (search_getresults_p1)",
                realNs / 1_000_000.0,
                real.items.items.size,
                page.length,
            ),
        )
        println(
            "[bench] %-46s %8.3f ms  %6d cards  %6d B".format(
                "map a 20-card page (a keystroke, on io now)",
                bigNs / 1_000_000.0,
                PAGE_CARDS,
                big.length,
            ),
        )
        assertTrue(bigNs > realNs, "a 20-card page must cost more than a 5-card one")
    }

    /**
     * Element-wise versus whole-page decode with one unusable card. The old shape wrapped the *page*
     * in a `runCatching`, so the answer was 0 rows; the new shape costs one row and says so.
     */
    @Test
    fun elementWiseDecodeCostsOneCardNotThePage() {
        val good = (0 until PAGE_CARDS).joinToString(",") { cardJson(it) }
        // The unusable card has to be one `dylanJson` genuinely cannot coerce. A null in a
        // defaulted field no longer is: `coerceInputValues = true` turns it into the default, which
        // is the whole point of element-wise decode, so the control has to be a wrong TYPE.
        val withOneBad =
            """{"total":"$PAGE_CARDS","start":"1","results":[""" +
                """{"id":{"unexpected":"object"},"title":null,"more_info":null},$good]}"""
        val ns =
            bench {
                blackhole +=
                    dylan.provider.saavn
                        .decodeSongPage(withOneBad, "bench", 1)
                        .items.items.size
            }
        val rows = dylan.provider.saavn.decodeSongPage(withOneBad, "bench", 1)
        // The payload is 20 good cards PLUS the one unusable one, so all 20 good rows must survive
        // and the page must not be the thing that is lost.
        assertEquals(PAGE_CARDS, rows.items.items.size, "the one unusable card costs itself and nothing else")
        assertEquals(
            1,
            rows.drift.size,
            "and it is reported once, by name: ${rows.drift}",
        )
        val oldResult =
            runCatching {
                dylanJson.decodeFromString(ResultsDto.serializer(), withOneBad)
            }
        assertTrue(oldResult.isFailure, "the whole-page shape must still be fatal for the same payload")
        println(
            "[bench] %-46s %8.3f ms  kept %d/%d rows".format(
                "element-wise decode, 1 unusable card",
                ns / 1_000_000.0,
                rows.items.items.size,
                PAGE_CARDS,
            ),
        )
    }

    /**
     * The concurrency claim, made falsifiable: the old in-memory LRU was a plain `LinkedHashMap`
     * mutated from several dispatchers with no mutex, so a `put` during `entries.iterator()` threw
     * `ConcurrentModificationException` — inside a `runCatching`, which is why an album silently
     * failed to load. The control below runs the *old* shape under the identical stress and is
     * expected to throw; the real cache is expected not to.
     */
    @Test
    fun theSnapshotLruSurvivesConcurrentWritersThatTheLinkedHashMapDoesNot() {
        requests.clear()
        withProvider(okClient { fixture("album_detail_full.json") }) { p ->
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.coroutineScope {
                    val ids = (0 until DISTINCT_ALBUMS).map { "album-$it" }
                    (0 until CONCURRENT_OPENERS).map { async { p.albumDetail(ids[it % DISTINCT_ALBUMS]) } }.awaitAll()
                }
            }
            assertEquals(
                DISTINCT_ALBUMS,
                requests.size,
                "single-flight must collapse the storm onto one request per album, not $requests",
            )
        }

        val plain = LinkedHashMap<String, Int>()
        val control =
            runCatching {
                repeat(CONTROL_WRITES) { i ->
                    plain["k$i"] = i
                    if (i % 2 == 0) plain.entries.iterator().let { it.next() }
                }
            }
        println(
            "[bench] %-46s %s".format(
                "OLD LinkedHashMap LRU under interleaved put/iterate",
                if (control.isSuccess) {
                    "no throw this run (CME is timing-dependent)"
                } else {
                    "threw ${control.exceptionOrNull()!!::class.simpleName}"
                },
            ),
        )
        println(
            "[bench] %-46s %8d writes, 0 throws".format("NEW snapshot-map LRU (ResilientClient)", CONCURRENT_OPENERS),
        )
    }

    private fun cardJson(i: Int): String =
        """{"id":"id$i","title":"Card $i","type":"song",""" +
            """"perma_url":"https://www.jiosaavn.com/song/card-$i/tok$i",""" +
            """"image":"https://c.saavncdn.com/820/Awarapan-2-150x150.jpg",""" +
            """"more_info":{"album_id":"7912126$((i % 10))","album":"Awarapan 2",""" +
            """"320kbps":"true","duration":"${200 + i}",""" +
            """"encrypted_media_url":"ref$i",""" +
            """"artistMap":{"primary_artists":[{"id":"702592","name":"Mithoon",""" +
            """"perma_url":"https://www.jiosaavn.com/artist/mithoon-songs/nQKQiNRsTKs_"}]}}}"""

    private companion object {
        const val PER_OP = 40
        const val WARMUP_ROUNDS = 3
        const val PAGE_CARDS = 20
        const val DISTINCT_ALBUMS = 8
        const val CONCURRENT_OPENERS = 64
        const val CONTROL_WRITES = 20_000
    }
}
