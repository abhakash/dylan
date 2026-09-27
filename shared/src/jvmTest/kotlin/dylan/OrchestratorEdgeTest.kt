package dylan

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.JobState
import dylan.download.Priority
import dylan.model.Album
import dylan.model.HomeFeed
import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.PlayerState
import dylan.model.Quality
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.EngineErr
import dylan.playback.EngineEvent
import dylan.playback.Intent.PlayNow
import dylan.playback.Orchestrator
import dylan.playback.TransitionReason
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.repo.SettingsStore
import dylan.support.FakePlayerEngine
import dylan.support.TestLanes
import dylan.util.NetClass
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class GatedProvider : MusicProvider {
    var gate = CompletableDeferred<Unit>()
    var resolveCalls = 0

    override suspend fun search(
        query: String,
        page: Int,
    ) = Paged<Song>(emptyList(), 0, page)

    override suspend fun album(id: String): Album? = null

    override suspend fun artist(id: String): dylan.model.Artist? = null

    override suspend fun home() = HomeFeed(emptyList())

    override suspend fun topSearches() = emptyList<MiniEntity>()

    override suspend fun resolveStream(
        resolveRef: String,
        q: Quality,
    ): SignedStream? {
        resolveCalls++
        gate.await()
        return SignedStream("http://mock/audio", "mp4")
    }
}

class OrchestratorEdgeTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var orchestrator: Orchestrator
    private lateinit var downloadEngine: DownloadEngine
    private lateinit var fakePlayer: dylan.support.FakePlayerEngine
    private lateinit var provider: GatedProvider
    private lateinit var scope: CoroutineScope
    private lateinit var paths: Paths
    private lateinit var disp: dylan.util.AppDispatchers
    private lateinit var bulk: HttpClient
    private lateinit var settings: SettingsStore
    private val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    private var mockBody: ByteArray = ByteArray(0)
    private var mockStatus: HttpStatusCode = HttpStatusCode.OK
    private var cfg = AppConfig()
    private val fakeNet = dylan.support.FakeNetMonitor(online = true, netClass = dylan.util.NetClass.UNMETERED)
    private val testLog =
        dylan.diag.LogBuffer(minLevel = dylan.diag.LogLevel.DEBUG).also { buf ->
            buf.bindSink { e -> println("[${e.level}] Dylan:${e.tag} ${e.msg}") }
        }

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-orch-${System.nanoTime()}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db", testLog).createDriver())
        disp = TestLanes().disp
        scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
            )
        provider = GatedProvider()
        provider.gate.complete(Unit)
        bulk =
            HttpClient(
                MockEngine {
                    respond(
                        content = mockBody,
                        status = mockStatus,
                        headers =
                            headersOf(
                                "Content-Length" to listOf(mockBody.size.toString()),
                                "Content-Type" to listOf("audio/mp4"),
                            ),
                    )
                },
            )
        buildGraph(AppConfig())
        fakePlayer = dylan.support.FakePlayerEngine.onRealLooper(scope)
        orchestrator.attachEngine(fakePlayer)
        downloadEngine.start()
        mockBody = ftypBody(1000)
    }

    private fun buildGraph(newCfg: AppConfig) {
        cfg = newCfg
        val fs = FileSystem.SYSTEM
        val paths = Paths(tmp.toPath() / "audio", fs)
        this.paths = paths
        val cacheManager = CacheManager(db, fs, paths, protectedKeys, cfg, disp, testLog)
        downloadEngine =
            DownloadEngine(
                db = db,
                fs = fs,
                paths = paths,
                cfg = cfg,
                disp = disp,
                provider = provider,
                bulk = bulk,
                breakers = Breakers(),
                cacheManager = cacheManager,
                netClass = { fakeNet.current() },
                qualityPref = { cfg.defaultQuality },
                log = testLog,
            )
        settings = SettingsStore(db, disp, cfg)
        orchestrator =
            Orchestrator(
                scope = scope,
                disp = disp,
                db = db,
                fs = fs,
                paths = paths,
                cfg = cfg,
                downloads = downloadEngine,
                cacheManager = cacheManager,
                settings = settings,
                net = fakeNet,
                protectedKeys = protectedKeys,
                log = testLog,
            )
    }

    private fun rebuildGraph(newCfg: AppConfig) {
        runCatching { orchestrator.detachEngine() }
        runCatching { downloadEngine.stop() }
        buildGraph(newCfg)
        fakePlayer = dylan.support.FakePlayerEngine.onRealLooper(scope)
        orchestrator.attachEngine(fakePlayer)
        downloadEngine.start()
    }

    @AfterTest
    fun teardown() {
        orchestrator.detachEngine()
        downloadEngine.stop()
        scope.cancel()
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    private fun ftypBody(size: Int): ByteArray {
        val b = ByteArray(size)
        "ftyp".encodeToByteArray().copyInto(b, 4)
        return b
    }

    private fun song(
        id: String,
        has320: Boolean = false,
    ) = Song(
        key = SongKey("saavn", id),
        title = id,
        subtitle = "",
        albumId = null,
        albumName = null,
        artUrl150 = "",
        artUrl500 = "",
        durationS = 100,
        has320 = has320,
        resolveRef = "enc-$id",
        permaToken = null,
    )

    private fun seedCached(id: String) {
        val key = SongKey("saavn", id)
        val fs = FileSystem.SYSTEM
        val file = paths.final(key, 128, "m4a")
        fs.write(file) { write(ftypBody(1000)) }
        db.dylanQueries.insertSong("saavn", id, id, "", null, null, "", "", 100L, 1L, "enc-$id", null, 0L)
        db.dylanQueries.insertCached("saavn", id, 128L, "m4a", 1000L, 0L, null, 0L, 0L, null)
    }

    private suspend fun awaitPhase(
        timeoutMs: Long = 15_000,
        predicate: (PlayerState) -> Boolean,
    ): PlayerState = withTimeout(timeoutMs) { orchestrator.state.first { predicate(it) } }

    @Test
    fun playNowResolvesThroughRealPipelineAndPlays() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a")), 0))
            val s = awaitPhase { it.phase is dylan.model.Phase.Playing }
            assertEquals("a", s.current?.key?.songId)
            assertEquals(
                1,
                fakePlayer.preparedWindows.last().size,
                "uncached next means the window holds only the current track",
            )
        }

    @Test
    fun intentsStayResponsiveWhileFirstTrackDownloads() =
        runBlocking {
            provider.gate = CompletableDeferred()
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            withTimeout(15_000) {
                orchestrator.state.first {
                    it.phase is dylan.model.Phase.Resolving || it.phase is dylan.model.Phase.Downloading
                }
            }
            orchestrator.submit(dylan.playback.Intent.AddLast(song("z")))
            withTimeout(5_000) { orchestrator.state.first { it.queue.any { q -> q.key.songId == "z" } } }
            provider.gate.complete(Unit)
            awaitPhase(timeoutMs = 30_000) { it.phase is dylan.model.Phase.Playing }
            assertEquals(3, orchestrator.state.value.queue.size)
        }

    @Test
    fun removeCurrentRowKeepsAudibleTrackLabeled() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            orchestrator.submit(dylan.playback.Intent.RemoveAt(0))
            withTimeout(5_000) { orchestrator.state.first { it.queue.size == 1 } }
            val s = orchestrator.state.value
            assertEquals("a", s.current?.key?.songId, "removing the playing row must keep the audible song labeled")
            assertEquals(0, s.index)
        }

    @Test
    fun shuffleRepeatAllWrapFollowsShuffleOrderNotQueueZero() =
        runBlocking {
            orchestrator.submit(dylan.playback.Intent.CycleRepeat)
            orchestrator.submit(dylan.playback.Intent.ToggleShuffle)
            val list = listOf(song("a"), song("b"), song("c"))
            orchestrator.submit(PlayNow(list, 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            val order =
                orchestrator.state.value.shuffleOrder!!
                    .toList()
            assertEquals(0, order.first(), "current-first permutation expected")
            val expectedId = list[order[1]].key.songId
            fakePlayer.script(EngineEvent.QueueExhausted)
            val wrapped =
                awaitPhase(timeoutMs = 30_000) {
                    it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == expectedId
                }
            assertEquals(expectedId, wrapped.current?.key?.songId)
        }

    @Test
    fun staleFailedStateDoesNotBlockRetryAfterTransientFailure() =
        runBlocking {
            val key = SongKey("saavn", "retry")
            db.dylanQueries.insertSong("saavn", "retry", "retry", "", null, null, "", "", 100L, 1L, "enc", null, 0L)
            mockStatus = HttpStatusCode.ServiceUnavailable
            downloadEngine.start()
            downloadEngine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
            withTimeout(20_000) { downloadEngine.states.first { it[key] is JobState.Failed } }
            mockStatus = HttpStatusCode.OK
            downloadEngine.enqueue(DownloadJob(key, Priority.USER_NOW, 128, 0L))
            // StateFlow replays the previous terminal value to a late subscriber — wait out the
            // re-enqueue's eviction (or the fresh Queued) before demanding a terminal state,
            // otherwise this test can pass/fail on job #1's stale Failed.
            withTimeout(20_000) {
                downloadEngine.states.first {
                    val st = it[key]
                    st == null || st is JobState.Queued
                }
            }
            val done =
                withDumpOnTimeout(20_000) {
                    downloadEngine.states.first { s -> s[key] is JobState.Done || s[key] is JobState.Failed }
                }
            assertTrue(done[key] is JobState.Done, "retry after transient failure must reach Done, got ${done[key]}")
        }

    private suspend fun <T> withDumpOnTimeout(
        ms: Long,
        block: suspend () -> T,
    ): T =
        try {
            withTimeout(ms) { block() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            println("=== THREAD DUMP (timeout after ${ms}ms) ===")
            Thread
                .getAllStackTraces()
                .forEach { (t, st) ->
                    println("--- ${t.name} state=${t.state}")
                    st.take(15).forEach { println("    at $it") }
                }
            throw e
        }

    @Test
    fun cachedNextAdvancesIntoPreparedWindowOnNaturalEnd() =
        runBlocking {
            seedCached("b")
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            val window = fakePlayer.preparedWindows.last()
            assertEquals(2, window.size, "a cached next track must ride the initial engine window")
            fakePlayer.script(EngineEvent.ItemEnded(window[0].itemId))
            fakePlayer.script(EngineEvent.TrackChanged(window[1].itemId, TransitionReason.AUTO))
            val advanced = awaitPhase { it.current?.key?.songId == "b" }
            assertTrue(advanced.phase is dylan.model.Phase.Playing, "auto-advance must land in Playing")
            assertEquals(1, advanced.index)
        }

    @Test
    fun prefetchDefersUntilCurrentIsNinetyFivePercentPlayed() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            // Absence assertion: proving "no prefetch fired" costs a bounded real wait, because the
            // only evidence is the absence of an event. 300 ms is ~30 poll ticks.
            fakePlayer.setPositionMs(50_000L)
            delay(PREFETCH_ABSENCE_MS)
            assertTrue(
                downloadEngine.states.value.keys
                    .none { it.songId == "b" },
                "no prefetch may fire mid-track",
            )
            fakePlayer.setPositionMs(96_000L)
            withTimeout(30_000) {
                downloadEngine.states.first { st -> st.keys.any { it.songId == "b" } }
            }
            Unit
        }

    @Test
    fun repeatOneSuppressesTailPrefetch() =
        runBlocking {
            orchestrator.submit(dylan.playback.Intent.CycleRepeat)
            orchestrator.submit(dylan.playback.Intent.CycleRepeat)
            val s = awaitPhase { it.repeat == dylan.model.Repeat.ONE }
            assertEquals(dylan.model.Repeat.ONE, s.repeat)
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            fakePlayer.setPositionMs(99_500L)
            delay(PREFETCH_ABSENCE_MS)
            assertTrue(
                downloadEngine.states.value.keys
                    .none { it.songId == "b" },
                "repeat-one must never tail-prefetch the next track",
            )
        }

    @Test
    fun uncachedNextJoinsWindowWhenItsDownloadCompletes() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            assertEquals(1, fakePlayer.preparedWindows.last().size, "uncached next starts as a single-item window")
            fakePlayer.setPositionMs(96_000L)
            withTimeout(30_000) { fakePlayer.upNext.first { t -> t?.itemId?.startsWith("saavn:b:") == true } }
            fakePlayer.script(EngineEvent.TrackChanged("saavn:b:128", TransitionReason.AUTO))
            val advanced = awaitPhase { it.current?.key?.songId == "b" }
            assertTrue(advanced.phase is dylan.model.Phase.Playing)
        }

    @Test
    fun exhaustedQueueAdvancesIntoUncachedNextByDownloadingIt() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            fakePlayer.script(
                EngineEvent.ItemEnded(
                    fakePlayer.preparedWindows
                        .last()
                        .first()
                        .itemId,
                ),
            )
            fakePlayer.script(EngineEvent.QueueExhausted)
            val advanced = awaitPhase { it.current?.key?.songId == "b" }
            assertTrue(
                advanced.phase is dylan.model.Phase.Playing ||
                    advanced.phase is dylan.model.Phase.Downloading ||
                    advanced.phase is dylan.model.Phase.Resolving,
                "natural end with uncached next must pull it down and continue",
            )
        }

    @Test
    fun offlineNetworkSurfacesOfflineFastPathWithoutWaitingForTheDownload() =
        runBlocking {
            rebuildGraph(AppConfig(readyTimeoutMs = 30_000))
            fakeNet.pushOnline(false)
            val t0 = System.nanoTime()
            orchestrator.submit(PlayNow(listOf(song("a")), 0))
            val errored = awaitPhase { it.phase is dylan.model.Phase.Error }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(
                dylan.model.ErrorCode.OFFLINE,
                (errored.phase as dylan.model.Phase.Error).failure.code,
                "an uncached track with no network must surface OFFLINE, not spin the ready timeout",
            )
            assertTrue(elapsedMs < 5_000, "OFFLINE must be immediate, took ${elapsedMs}ms")
            assertTrue(
                downloadEngine.states.value.isEmpty(),
                "the offline fast path must not enqueue a download: ${downloadEngine.states.value}",
            )
        }

    @Test
    fun meteredNetworkPinsTheDownloadToTheMeteredQuality() =
        runBlocking {
            fakeNet.pushMetered(true)
            orchestrator.submit(PlayNow(listOf(song("hi", has320 = true)), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            val row = awaitCached("hi")
            assertEquals(128L, row.bitrate, "metered must never spend cellular on a 320 kbps fetch")
        }

    @Test
    fun unmeteredNetworkUsesThePreferredQuality() =
        runBlocking {
            fakeNet.pushMetered(false)
            orchestrator.submit(PlayNow(listOf(song("hi", has320 = true)), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            val row = awaitCached("hi")
            assertEquals(320L, row.bitrate, "unmetered must honour the 320 kbps preference")
        }

    private suspend fun awaitCached(id: String): dylan.db.Cached_files =
        withTimeout(20_000) {
            while (true) {
                val row = db.dylanQueries.selectCached("saavn", id).executeAsOneOrNull()
                if (row != null) return@withTimeout row
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test
    fun readyTimeoutLandsInPhaseErrorWithUserCopy() =
        runBlocking {
            rebuildGraph(AppConfig(readyTimeoutMs = 400))
            provider.gate = CompletableDeferred() // resolve never returns ⇒ job never Done/Failed
            var toast: String? = null
            orchestrator.toast = { toast = it }
            orchestrator.submit(PlayNow(listOf(song("z")), 0))
            val errored = awaitPhase { it.phase is dylan.model.Phase.Error }
            assertEquals(
                dylan.model.ErrorCode.NETWORK_TIMEOUT,
                (errored.phase as dylan.model.Phase.Error).failure.code,
                "ready timeout must surface NETWORK_TIMEOUT, not sit in Resolving",
            )
            // Toast fires on the line after the state lands — poll briefly instead of
            // asserting in the same instant the phase flip becomes visible.
            kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                while (toast == null) delay(20)
            }
            assertEquals("Check your connection and try again.", toast, "toast must carry user copy, not the enum name")
        }

    @Test
    fun toggleShuffleWhilePlayingAnchorsCurrentAndNeverRestarts() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a"), song("b"), song("c"), song("d")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            val windowsBefore = fakePlayer.preparedWindows.size
            orchestrator.submit(dylan.playback.Intent.ToggleShuffle)
            val s = withTimeout(5_000) { orchestrator.state.first { it.shuffleOn } }
            val order = s.shuffleOrder
            assertTrue(order != null, "shuffle on must carry a permutation")
            assertEquals("a", s.current?.key?.songId, "shuffle must anchor the playing item")
            assertEquals(0, s.index)
            assertEquals(0, order.first(), "current-first permutation expected")
            val nextId = s.queue[order[1]].key.songId
            assertEquals(nextId, s.nextUp?.key?.songId, "upcoming items reshuffle around the anchor")
            assertEquals(windowsBefore, fakePlayer.preparedWindows.size, "shuffle must not re-prepare the window")
        }

    @Test
    fun restoredPausedStateReceivesWindowOnAttach() =
        runBlocking {
            orchestrator.detachEngine()
            seedCached("a")
            seedCached("b")
            val snap =
                dylan.playback.ResumeSnapshot(
                    items = listOf(dylan.playback.ItemRef("saavn", "a"), dylan.playback.ItemRef("saavn", "b")),
                    index = 0,
                    posMs = 1234,
                )
            db.dylanQueries.putSetting("resume", dylan.playback.encodeSnapshot(snap))
            orchestrator.restoreFromSnapshot()
            awaitPhase { it.phase is dylan.model.Phase.Paused && it.queue.size == 2 }
            fakePlayer.preparedWindows.clear()
            orchestrator.attachEngine(fakePlayer)
            withTimeout(15_000) {
                while (fakePlayer.preparedWindows.isEmpty()) delay(50)
            }
            assertEquals(
                listOf("saavn:a:128", "saavn:b:128"),
                fakePlayer.preparedWindows.last().map { it.itemId },
                "attach onto a restored PAUSED state must hand the engine its window",
            )
        }

    /**
     * Rewritten. The previous version passed with the `Next` line deleted: nothing was pending, so
     * nothing could stomp anything. It now proves the pending advance *existed* before the newer
     * `PlayNow` superseded it, which is what makes the second half meaningful.
     */
    @Test
    fun playNowSupersedesPendingSettleAdvance() =
        runBlocking {
            seedCached("a")
            seedCached("b")
            seedCached("z")
            seedCached("y")
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            orchestrator.submit(dylan.playback.Intent.Next)
            // StateFlow.first evaluates the predicate against the current value first, so this
            // returns as soon as the optimistic advance is visible.
            val pending = withTimeout(5_000L) { orchestrator.state.first { it.current?.key?.songId == "b" } }
            assertEquals(
                "b",
                pending.current?.key?.songId,
                "Next must actually arm a pending advance, or the rest of this test proves nothing",
            )
            orchestrator.submit(PlayNow(listOf(song("z"), song("y")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == "z" }
            // The only way to observe that a timer did *not* fire is to outlive it, so this is the
            // one place in the file where a real wait is not avoidable. skipSettleMs is 350 ms.
            delay(SETTLE_SETTLE_MS + SETTLE_MARGIN_MS)
            assertEquals(
                "z",
                orchestrator.state.value.current
                    ?.key
                    ?.songId,
                "stale settle timer must not stomp a newer PlayNow",
            )
            assertEquals(
                "y",
                orchestrator.state.value.nextUp
                    ?.key
                    ?.songId,
                "and the newer PlayNow's own queue must be intact, not the superseded one",
            )
        }

    @Test
    fun consecutiveErrorsResetPerSuccessfulTrackNotPerPhaseTransition() =
        runBlocking {
            repeat(5) { idx -> seedCached("abcdefghijklmnopqrstuvwxyz"[idx].toString()) }
            val list = listOf("a", "b", "c", "d", "e").map(::song)
            orchestrator.submit(PlayNow(list, 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            fakePlayer.script(EngineEvent.Error("saavn:a:128", EngineErr.DECODE))
            fakePlayer.script(EngineEvent.Error("saavn:a:128", EngineErr.DECODE))
            awaitPhase { it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == "c" }
            fakePlayer.script(EngineEvent.Error("saavn:c:128", EngineErr.DECODE))
            fakePlayer.script(EngineEvent.Error("saavn:c:128", EngineErr.DECODE))
            val end =
                awaitPhase(timeoutMs = 30_000) {
                    it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == "e"
                }
            assertTrue(
                end.phase is dylan.model.Phase.Playing,
                "four transient errors across resets must never reach TOO_MANY_FAILURES",
            )
        }
}

private const val SETTLE_SETTLE_MS = 350L
private const val SETTLE_MARGIN_MS = 150L
private const val PREFETCH_ABSENCE_MS = 300L
