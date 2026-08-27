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
import dylan.playback.LocalTrack
import dylan.playback.Orchestrator
import dylan.playback.PlayerEngine
import dylan.playback.TransitionReason
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.repo.SettingsStore
import dylan.util.AppDispatchers
import dylan.util.NetClass
import dylan.util.NetMonitor
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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

private class FakeEngine : PlayerEngine {
    val preparedWindows = mutableListOf<List<LocalTrack>>()
    val upNextHistory = mutableListOf<LocalTrack?>()

    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<EngineEvent> = mutableEvents
    val mutablePosition = MutableStateFlow(0L)
    override val positionFlow: StateFlow<Long> = mutablePosition

    override fun prepare(window: List<LocalTrack>) {
        preparedWindows += window
        mutableEvents.tryEmit(EngineEvent.Prepared(window.first().itemId))
        mutableEvents.tryEmit(EngineEvent.TrackChanged(window.first().itemId, TransitionReason.EXPLICIT))
    }

    override fun replaceUpNext(track: LocalTrack?) {
        upNextHistory += track
    }

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(ms: Long) = Unit

    override fun release() = Unit

    fun emit(e: EngineEvent) {
        mutableEvents.tryEmit(e)
    }
}

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
    private lateinit var fakePlayer: FakeEngine
    private lateinit var provider: GatedProvider
    private lateinit var scope: CoroutineScope
    private lateinit var paths: Paths
    private lateinit var disp: AppDispatchers
    private lateinit var bulk: HttpClient
    private lateinit var settings: SettingsStore
    private val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    private var mockBody: ByteArray = ByteArray(0)
    private var mockStatus: HttpStatusCode = HttpStatusCode.OK
    private var cfg = AppConfig()
    private val testLog =
        dylan.diag.LogBuffer(minLevel = dylan.diag.LogLevel.DEBUG).also { buf ->
            buf.bindSink { e -> println("[${e.level}] Dylan:${e.tag} ${e.msg}") }
        }

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-orch-${System.nanoTime()}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db").createDriver())
        disp = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)
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
                        headers = headersOf("Content-Length" to listOf(mockBody.size.toString()), "Content-Type" to listOf("audio/mp4")),
                    )
                },
            )
        buildGraph(AppConfig())
        fakePlayer = FakeEngine()
        orchestrator.attachEngine(fakePlayer)
        downloadEngine.start()
        mockBody = ftypBody(1000)
    }

    private fun buildGraph(newCfg: AppConfig) {
        cfg = newCfg
        val fs = FileSystem.SYSTEM
        val paths = Paths(tmp.toPath() / "audio", fs)
        this.paths = paths
        val cacheManager = CacheManager(db, fs, paths, protectedKeys, cfg, disp)
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
                netClass = { NetClass.UNMETERED },
                qualityPref = { Quality.BITRATE_128 },
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
                net = NetMonitor(),
                protectedKeys = protectedKeys,
            )
    }

    private fun rebuildGraph(newCfg: AppConfig) {
        runCatching { orchestrator.detachEngine() }
        runCatching { downloadEngine.stop() }
        buildGraph(newCfg)
        fakePlayer = FakeEngine()
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

    private fun song(id: String) =
        Song(
            key = SongKey("saavn", id),
            title = id,
            subtitle = "",
            albumId = null,
            albumName = null,
            artUrl150 = "",
            artUrl500 = "",
            durationS = 100,
            has320 = false,
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
            assertEquals(1, fakePlayer.preparedWindows.last().size, "uncached next means the window holds only the current track")
        }

    @Test
    fun intentsStayResponsiveWhileFirstTrackDownloads() =
        runBlocking {
            provider.gate = CompletableDeferred()
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            withTimeout(15_000) {
                orchestrator.state.first { it.phase is dylan.model.Phase.Resolving || it.phase is dylan.model.Phase.Downloading }
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
            fakePlayer.emit(EngineEvent.QueueExhausted)
            val wrapped = awaitPhase(timeoutMs = 30_000) { it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == expectedId }
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
            fakePlayer.emit(EngineEvent.ItemEnded(window[0].itemId))
            fakePlayer.emit(EngineEvent.TrackChanged(window[1].itemId, TransitionReason.AUTO))
            val advanced = awaitPhase { it.current?.key?.songId == "b" }
            assertTrue(advanced.phase is dylan.model.Phase.Playing, "auto-advance must land in Playing")
            assertEquals(1, advanced.index)
        }

    @Test
    fun prefetchDefersUntilCurrentIsNinetyFivePercentPlayed() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            fakePlayer.mutablePosition.value = 50_000L
            delay(300)
            assertTrue(
                downloadEngine.states.value.keys
                    .none { it.songId == "b" },
                "no prefetch may fire mid-track",
            )
            fakePlayer.mutablePosition.value = 96_000L
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
            fakePlayer.mutablePosition.value = 99_500L
            delay(400)
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
            fakePlayer.mutablePosition.value = 96_000L
            withTimeout(30_000) {
                while (fakePlayer.upNextHistory.none { it?.itemId?.startsWith("saavn:b:") == true }) delay(50)
            }
            fakePlayer.emit(EngineEvent.TrackChanged("saavn:b:128", TransitionReason.AUTO))
            val advanced = awaitPhase { it.current?.key?.songId == "b" }
            assertTrue(advanced.phase is dylan.model.Phase.Playing)
        }

    @Test
    fun exhaustedQueueAdvancesIntoUncachedNextByDownloadingIt() =
        runBlocking {
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            fakePlayer.emit(
                EngineEvent.ItemEnded(
                    fakePlayer.preparedWindows
                        .last()
                        .first()
                        .itemId,
                ),
            )
            fakePlayer.emit(EngineEvent.QueueExhausted)
            val advanced = awaitPhase { it.current?.key?.songId == "b" }
            assertTrue(
                advanced.phase is dylan.model.Phase.Playing || advanced.phase is dylan.model.Phase.Downloading || advanced.phase is dylan.model.Phase.Resolving,
                "natural end with uncached next must pull it down and continue",
            )
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
            orchestrator.submit(PlayNow(listOf(song("z"), song("y")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == "z" }
            delay(600)
            assertEquals(
                "z",
                orchestrator.state.value.current
                    ?.key
                    ?.songId,
                "stale settle timer must not stomp a newer PlayNow",
            )
        }

    @Test
    fun consecutiveErrorsResetPerSuccessfulTrackNotPerPhaseTransition() =
        runBlocking {
            repeat(5) { idx -> seedCached("abcdefghijklmnopqrstuvwxyz"[idx].toString()) }
            val list = listOf("a", "b", "c", "d", "e").map(::song)
            orchestrator.submit(PlayNow(list, 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            fakePlayer.emit(EngineEvent.Error("saavn:a:128", EngineErr.DECODE))
            fakePlayer.emit(EngineEvent.Error("saavn:a:128", EngineErr.DECODE))
            awaitPhase { it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == "c" }
            fakePlayer.emit(EngineEvent.Error("saavn:c:128", EngineErr.DECODE))
            fakePlayer.emit(EngineEvent.Error("saavn:c:128", EngineErr.DECODE))
            val end = awaitPhase(timeoutMs = 30_000) { it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == "e" }
            assertTrue(end.phase is dylan.model.Phase.Playing, "four transient errors across resets must never reach TOO_MANY_FAILURES")
        }
}
