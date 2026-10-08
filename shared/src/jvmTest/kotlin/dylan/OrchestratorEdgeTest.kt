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
import kotlin.system.measureNanoTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class GatedProvider : MusicProvider {
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

abstract class OrchestratorEdgeFixture {
    internal lateinit var tmp: String
    internal lateinit var db: Dylan
    internal lateinit var orchestrator: Orchestrator
    internal lateinit var downloadEngine: DownloadEngine
    internal lateinit var fakePlayer: dylan.support.FakePlayerEngine
    internal lateinit var provider: GatedProvider
    internal lateinit var scope: CoroutineScope
    internal lateinit var paths: Paths
    internal lateinit var disp: dylan.util.AppDispatchers
    internal lateinit var bulk: HttpClient
    internal lateinit var settings: SettingsStore
    internal val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())

    internal var mockBody: ByteArray = ByteArray(0)
    internal var mockStatus: HttpStatusCode = HttpStatusCode.OK

    // See `DownloadEngineTest`: the retry ladder is policy, not a subject of any test here, and its
    // default 800 ms x n spacing is what made `readyTimeoutLandsInPhaseErrorWithUserCopy` (a
    // 400 ms ready timeout) take 3.6 s.
    internal var cfg = AppConfig(dlBackoffBaseMs = DL_BACKOFF_MS)
    internal val fakeNet = dylan.support.FakeNetMonitor(online = true, netClass = dylan.util.NetClass.UNMETERED)
    internal val testLog =
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

    internal fun buildGraph(newCfg: AppConfig) {
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
                breakers = Breakers(baseCooldownMs = DL_COOLDOWN_MS),
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
                log = testLog,
            )
    }

    internal fun rebuildGraph(newCfg: AppConfig) {
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

    /**
     * A *real* mp4 head: `ftyp` at 4 and a printable major brand at 8. The brand is not decoration —
     * `sniffContainer` requires it, so a fixture carrying `ftyp` alone is a body the engine rejects
     * as `CORRUPT_CONTAINER`, and every test that expected a track to play reported the sniff.
     */
    internal fun ftypBody(size: Int): ByteArray {
        val b = ByteArray(size)
        "ftyp".encodeToByteArray().copyInto(b, 4)
        "M4A ".encodeToByteArray().copyInto(b, 8)
        return b
    }

    internal fun song(
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

    internal fun seedCached(id: String) {
        val key = SongKey("saavn", id)
        val fs = FileSystem.SYSTEM
        val file = paths.final(key, 128, "m4a")
        fs.write(file) { write(ftypBody(1000)) }
        db.dylanQueries.insertSong("saavn", id, id, "", null, null, "", "", 100L, 1L, "enc-$id", null, 0L)
        db.dylanQueries.insertCached("saavn", id, 128L, "m4a", 1000L, 0L, null, 0L, 0L, null)
    }

    internal suspend fun awaitPhase(
        timeoutMs: Long = 15_000,
        predicate: (PlayerState) -> Boolean,
    ): PlayerState = withTimeout(timeoutMs) { orchestrator.state.first { predicate(it) } }

    /**
     * On a timeout, dump every thread before rethrowing. A 20 s `withTimeout` that fires with no
     * clue which of four lanes is wedged is the least actionable failure in the suite.
     */
    internal suspend fun <T> withDumpOnTimeout(
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
}

class OrchestratorEdgeTest : OrchestratorEdgeFixture() {
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
            withTimeout(30_000) { fakePlayer.upNext.first { t -> t?.itemId?.contains(":saavn:b:") == true } }
            fakePlayer.script(EngineEvent.TrackChanged(fakePlayer.upNext.value!!.itemId, TransitionReason.AUTO))
            val advanced = awaitPhase { it.current?.key?.songId == "b" }
            assertTrue(advanced.phase is dylan.model.Phase.Playing)
        }

    /**
     * `PlayNext` / `AddLast` from a cold start (nothing playing, empty queue) must queue the row.
     *
     * It used to throw `QueueInvariantViolation` — `PlayerState.validateShape` requires `index == -1`
     * exactly when the queue is empty, and the commit kept the inherited `-1` against a queue of
     * one — and `guard` caught, logged and discarded it, so the button silently did nothing. The
     * phase must stay `Idle`: queueing a track is not a request to play it, and the engine holds
     * no window at that point, so claiming a transport here would be a lie with no way to fulfil it.
     */
    @Test
    fun queueingTheFirstTrackFromAColdStartTakes() {
        runBlocking {
            val ops =
                listOf(
                    "z" to dylan.playback.Intent.PlayNext(song("z")),
                    "w" to dylan.playback.Intent.AddLast(song("w")),
                )
            for ((id, intent) in ops) {
                seedCached(id)
                orchestrator.submit(intent)
                val s = awaitPhase { it.queue.isNotEmpty() }
                assertEquals(
                    listOf(id),
                    s.queue.map { it.key.songId },
                    "${intent::class.simpleName} must queue the row",
                )
                assertEquals(0, s.index, "the first row of an empty queue is the current one")
                assertNotNull(s.current, "…and it is the state machine's current track")
                assertTrue(s.phase is dylan.model.Phase.Idle, "queueing a track must not start playback")
                // The throw used to be swallowed by `guard`, so prove the inbox is still live
                // afterwards: an unrelated intent must still take effect.
                orchestrator.submit(dylan.playback.Intent.CycleRepeat)
                awaitPhase { it.repeat == dylan.model.Repeat.ALL }
                // Return to a cold queue for the next intent under test.
                orchestrator.submit(dylan.playback.Intent.RemoveAt(0))
                awaitPhase { it.queue.isEmpty() }
            }
        }
    }

    /**
     * `ClearUpNext` must empty the **engine's** up-next slot, not just the state queue.
     *
     * `refreshUpNext` returned early when there was nothing to join, so it never issued the
     * `replaceUpNext(null)` that drops the slot. The state said "one track" while the engine still
     * held the removed one in slot 1, and rolled onto it when the current track ended — playing
     * exactly the track the user had just cleared.
     */
    @Test
    fun clearUpNextDropsTheTrackFromTheEngineWindowToo() =
        runBlocking {
            seedCached("a")
            seedCached("b")
            seedCached("c")
            orchestrator.submit(PlayNow(listOf(song("a"), song("b"), song("c")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            assertEquals(2, fakePlayer.preparedWindows.last().size, "precondition: a 2-slot window")
            orchestrator.submit(dylan.playback.Intent.ClearUpNext)
            val cleared = awaitPhase { it.queue.size == 1 }
            assertEquals(listOf("a"), cleared.queue.map { it.key.songId }, "the state queue is truncated")
            withTimeout(EMPTY_UP_NEXT_MS) { while (fakePlayer.windowSize() > 1) delay(20) }
            assertEquals(1, fakePlayer.windowSize(), "the engine's up-next slot must be emptied too")
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
            rebuildGraph(AppConfig(readyTimeoutMs = 30_000, dlBackoffBaseMs = DL_BACKOFF_MS))
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
            rebuildGraph(AppConfig(readyTimeoutMs = 400, dlBackoffBaseMs = DL_BACKOFF_MS))
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
                listOf("a", "b"),
                fakePlayer.preparedWindows
                    .last()
                    .map { it.itemId.split(':').getOrElse(2) { "" } },
                "attach onto a restored PAUSED state must hand the engine its window",
            )
            assertTrue(
                fakePlayer.preparedWindows.last().all { it.itemId.startsWith("g") },
                "itemIds are generation-stamped g<gen>:<provider>:<songId>:<bits>, so a stale prefix " +
                    "is one comparison rather than a scan",
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
}

class OrchestratorRecoveryTest : OrchestratorEdgeFixture() {
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

    /**
     * PB-1. The inbox consumer was `for (m in inbox) process(m)` with no `try`/`catch`, so one throw
     * ended playback for the life of the process while the `UNLIMITED` channel kept accepting.
     * A throwing platform toast sink is a realistic source of that throw, and it happens on the
     * "End of queue" path, which only fires once the queue has actually been walked to its end.
     */
    @Test
    fun oneFailedMessageDoesNotEndPlayback() =
        runBlocking {
            rebuildGraph(AppConfig(navDebounceMs = 0L, dlBackoffBaseMs = DL_BACKOFF_MS))
            seedCached("a")
            seedCached("b")
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            orchestrator.submit(dylan.playback.Intent.Next)
            awaitPhase { it.current?.key?.songId == "b" && it.phase is dylan.model.Phase.Playing }
            orchestrator.toast = { throw IllegalStateException("toast sink exploded") }
            // End of the queue: this is the path that reaches the platform sink, and it throws.
            orchestrator.submit(dylan.playback.Intent.Next)
            delay(FAILED_MESSAGE_SETTLE_MS)
            // Proof the loop is still consuming: a later message must still take effect.
            orchestrator.toast = null
            orchestrator.submit(dylan.playback.Intent.CycleRepeat)
            val after =
                withTimeout(5_000) {
                    orchestrator.state.first { it.repeat == dylan.model.Repeat.ALL }
                }
            assertEquals(dylan.model.Repeat.ALL, after.repeat, "the inbox must survive a failed message")
        }

    /** AN-2: `detachEngine` cancelled nothing, so a stale event advanced the queue with no engine. */
    @Test
    fun aDetachedEngineNoLongerDrivesTheQueue() =
        runBlocking {
            seedCached("a")
            seedCached("b")
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing }
            val head = fakePlayer.preparedWindows.last().first()
            val liveItemId = head.itemId
            orchestrator.detachEngine()
            withTimeout(5_000) { orchestrator.state.first { it.phase is dylan.model.Phase.Paused } }
            // A stale Error is the sharpest probe: it carries no generation to reject, it is just
            // three engine errors arriving at a queue that no longer has an engine.
            repeat(3) { fakePlayer.script(EngineEvent.Error(liveItemId, EngineErr.DECODE)) }
            delay(DETACHED_SETTLE_MS)
            val s = orchestrator.state.value
            assertEquals("a", s.current?.key?.songId, "a stale event must not advance the queue with no engine")
            assertTrue(
                s.phase is dylan.model.Phase.Paused,
                "three stale errors must not reach TOO_MANY_FAILURES with no engine attached, got ${s.phase}",
            )
        }

    /** PB-3: `posMs` was written but never consumed, so a restart began at 0 and autoplayed. */
    @Test
    fun aRestoredPositionIsAppliedExactlyOnceAndNeverAutoplays() =
        runBlocking {
            seedCached("a")
            seedCached("b")
            orchestrator.detachEngine()
            val snap =
                dylan.playback.ResumeSnapshot(
                    items = listOf(dylan.playback.ItemRef("saavn", "a"), dylan.playback.ItemRef("saavn", "b")),
                    index = 0,
                    posMs = 12_345L,
                    playedAtMs = cfg.clock.nowMs(),
                )
            db.dylanQueries.putSetting("resume", dylan.playback.encodeSnapshot(snap))
            orchestrator.restoreFromSnapshot()
            val restored = awaitPhase { it.phase is dylan.model.Phase.Paused && it.queue.size == 2 }
            assertEquals(12_345L, restored.posMs, "the state machine must own the restored position")
            assertTrue(restored.phase is dylan.model.Phase.Paused, "restore never autoplays")
            fakePlayer.preparedWindows.clear()
            fakePlayer.seeks.clear()
            orchestrator.attachEngine(fakePlayer)
            withTimeout(15_000) { while (fakePlayer.seeks.isEmpty()) delay(20) }
            delay(RESUME_SETTLE_MS)
            assertEquals(listOf(12_345L), fakePlayer.seeks, "exactly one clamped seek, not one per rebuffer")
        }

    /**
     * PB-2 tail: a committed row whose file cannot be sniffed used to become a permanent `Ready`
     * deadlock — the window came out empty, the engine had nothing, and there was no error and no
     * skip. Whichever site rejects the bytes (the download's own verify step, or the orchestrator's
     * re-check of a `Done`), the observable contract is the same: a failure, not a stall in `Ready`.
     */
    @Test
    fun aTrackThatCannotBeSniffedNeverSitsInReady() =
        runBlocking {
            mockBody = ByteArray(1_000).also { it[0] = 0x58 }
            orchestrator.submit(PlayNow(listOf(song("broken")), 0))
            val end =
                awaitPhase(timeoutMs = 30_000) {
                    it.phase is dylan.model.Phase.Error || it.phase is dylan.model.Phase.Playing
                }
            assertTrue(
                end.phase is dylan.model.Phase.Error,
                "an unplayable committed file must surface a failure, got ${end.phase}",
            )
        }

    /**
     * Task 4.10. The engine preempts only on a *strictly* higher priority, so an abandoned
     * `USER_NOW` could not be preempted by its replacement's `USER_NOW` and a 3-second skip cost a
     * full transfer.
     */
    @Test
    fun skippingAnUncachedTrackCancelsItsDownload(): Unit =
        runBlocking {
            provider.gate = CompletableDeferred()
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            val downloading = awaitPhase { it.phase is dylan.model.Phase.Downloading }
            val abandoned = downloading.current?.key
            orchestrator.submit(dylan.playback.Intent.Next)
            val cancelled =
                withTimeout(20_000) {
                    downloadEngine.states.first { abandoned != null && it[abandoned] is JobState.Cancelled }
                }
            assertTrue(
                cancelled[abandoned] is JobState.Cancelled,
                "the abandoned job must be dropped, not left to finish, got ${cancelled[abandoned]}",
            )
            provider.gate.complete(Unit)
        }

    /**
     * A restore supersedes whatever was resolving, exactly as a `PlayNow` does.
     *
     * `restore()` replaced the whole queue without going through `bumpGeneration()`, so the
     * abandoned `ensureReady` stayed armed against a generation the restore never invalidated. When
     * its own download then *failed*, the skip path walked the freshly restored queue and advanced
     * the user off the track they had just been handed. Here the restored head is deliberately the
     * long one and the successor short, so a skip is unmistakable.
     */
    @Test
    fun aRestoreDisarmsTheResolveItSupersedes() =
        runBlocking {
            seedCached("z")
            seedCached("y")
            val snap =
                dylan.playback.ResumeSnapshot(
                    items =
                        listOf(
                            dylan.playback.ItemRef("saavn", "z"),
                            dylan.playback.ItemRef("saavn", "y"),
                        ),
                    index = 0,
                    posMs = 0L,
                    playedAtMs = cfg.clock.nowMs(),
                )
            db.dylanQueries.putSetting("resume", dylan.playback.encodeSnapshot(snap))
            provider.gate = CompletableDeferred()
            orchestrator.submit(PlayNow(listOf(song("a")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Downloading || it.phase is dylan.model.Phase.Resolving }
            orchestrator.restoreFromSnapshot()
            val restored = awaitPhase { it.current?.key?.songId == "z" }
            assertEquals(listOf("z", "y"), restored.queue.map { it.key.songId }, "the restore must own the queue")
            // The abandoned resolve's own download now fails: a body the container sniff rejects.
            mockBody = ByteArray(1_000).also { it[0] = 0x58 }
            provider.gate.complete(Unit)
            delay(RESTORE_DISARM_SETTLE_MS)
            val after = orchestrator.state.value
            assertEquals(
                "z",
                after.current?.key?.songId,
                "a superseded resolve must not skip the restored track when its own download fails",
            )
            assertEquals(listOf("z", "y"), after.queue.map { it.key.songId }, "…nor edit the restored queue")
        }

    /** Task 4.5: `restore` used to keep `shuffleOn` after `sanitizeSnapshot` dropped a stale order. */
    @Test
    fun aRestoredShuffleWithoutAPermutationIsNavigableAgain() =
        runBlocking {
            seedCached("a")
            seedCached("b")
            val snap =
                dylan.playback.ResumeSnapshot(
                    items = listOf(dylan.playback.ItemRef("saavn", "a"), dylan.playback.ItemRef("saavn", "b")),
                    index = 0,
                    posMs = 0L,
                    shuffleOn = true,
                    order = emptyList(),
                )
            db.dylanQueries.putSetting("resume", dylan.playback.encodeSnapshot(snap))
            orchestrator.restoreFromSnapshot()
            val s = awaitPhase { it.queue.size == 2 }
            assertTrue(!s.shuffleOn, "a shuffle with no surviving permutation is not a shuffle")
            assertEquals("b", s.nextUp?.key?.songId, "the restored queue must be navigable")
        }

    /**
     * Not JMH (the module has no benchmark source set): `measureNanoTime` over a fixed workload
     * after warm-up, with a blackhole accumulator so the JIT cannot delete the work. Both shapes run
     * against the *same* 1000-row database, so the only difference is the algorithm.
     *
     * OLD: one `dbLane` round-trip per item, then a linear scan in `sanitizeSnapshot`'s predicate,
     * then another linear scan — two fresh `SongKey` allocations per comparison.
     * NEW: one batched pass, one `HashMap`, one lookup per item.
     */
    @Test
    fun restoreOfALargeQueueIsLinearNotQuadratic() =
        runBlocking {
            val n = 1_000
            repeat(n) {
                db.dylanQueries.insertSong("saavn", "s$it", "s$it", "", null, null, "", "", 100L, 1L, "e$it", null, 0L)
            }
            val refs = (0 until n).map { dylan.playback.ItemRef("saavn", "s$it") }
            val snap = dylan.playback.ResumeSnapshot(items = refs, index = n - 1, posMs = 1_000L)
            val encoded = dylan.playback.encodeSnapshot(snap)
            var blackhole = 0L

            val oldNs =
                measureNanoTime {
                    val songs = mutableListOf<Song>()
                    for (ref in refs) {
                        songs +=
                            db.dylanQueries.selectSong(ref.provider, ref.songId).executeAsOneOrNull()?.let { row ->
                                Song(
                                    SongKey(row.provider, row.song_id),
                                    row.title,
                                    row.subtitle,
                                    row.album_id,
                                    row.album_name,
                                    row.art_url_150,
                                    row.art_url_500,
                                    row.duration_s,
                                    row.has_320 == 1L,
                                    row.resolve_ref,
                                    row.perma_token,
                                )
                            } ?: continue
                    }
                    val kept =
                        refs.withIndex().filter { iv ->
                            songs.any { s -> s.key == SongKey(iv.value.provider, iv.value.songId) }
                        }
                    blackhole += kept.size.toLong()
                }

            db.dylanQueries.putSetting("resume", encoded)
            val t0 = System.nanoTime()
            orchestrator.restoreFromSnapshot()
            val restored = awaitPhase(timeoutMs = 60_000) { it.queue.size == n }
            val newNs = System.nanoTime() - t0
            blackhole += restored.index.toLong()
            println(
                "[bench] restore($n items): " +
                    "OLD ${"%.1f".format(oldNs / 1e6)} ms  NEW ${"%.1f".format(newNs / 1e6)} ms  " +
                    "(${"%.1f".format(oldNs.toDouble() / newNs)}x)  blackhole=$blackhole",
            )
            assertEquals(n, restored.queue.size)
            assertTrue(
                newNs * 3 < oldNs,
                "the batched restore must be at least 3x faster than one round-trip per item: " +
                    "old=${oldNs / 1_000_000}ms new=${newNs / 1_000_000}ms",
            )
        }

    /**
     * H6. `guard` used to `runCatching` every handler, so a `QueueInvariantViolation` — documented at
     * its declaration as "a throw, not a log line: these are programmer errors" — was logged and
     * discarded. The tree has already hit that once: a cold-start `PlayNext` threw and the button
     * silently did nothing.
     *
     * A queue violation must therefore stop the line. The violation is injected through the toast
     * sink because that is a *realistic* throw site inside a handler (the "End of queue" path
     * reaches the platform sink) and because it needs no production seam.
     *
     * Asserted as behaviour: after the violation, the state must be *unchanged* by a later intent,
     * and the log must name the violation. Either half alone would pass against broken code — a
     * swallowed violation logs nothing, and a lane that died for an unrelated reason would not log
     * this. `oneFailedMessageDoesNotEndPlayback` is the control: a plain `IllegalStateException` is
     * recoverable and the lane must survive it.
     */
    @Test
    fun aQueueInvariantViolationStopsTheStateLaneInsteadOfBeingSwallowed() =
        runBlocking {
            rebuildGraph(AppConfig(navDebounceMs = 0L, dlBackoffBaseMs = DL_BACKOFF_MS))
            seedCached("a")
            seedCached("b")
            orchestrator.submit(PlayNow(listOf(song("a"), song("b")), 0))
            awaitPhase { it.phase is dylan.model.Phase.Playing && it.current?.key?.songId == "a" }
            orchestrator.submit(dylan.playback.Intent.Next)
            awaitPhase { it.current?.key?.songId == "b" && it.phase is dylan.model.Phase.Playing }

            // End of the queue: the path that reaches the platform sink, which violates.
            orchestrator.toast = { throw dylan.model.QueueInvariantViolation("injected: sink is inside the handler") }
            orchestrator.submit(dylan.playback.Intent.Next)
            delay(FAILED_MESSAGE_SETTLE_MS)

            val stopped = orchestrator.state.value
            orchestrator.toast = null
            orchestrator.submit(dylan.playback.Intent.CycleRepeat)
            delay(FAILED_MESSAGE_SETTLE_MS)
            assertEquals(
                stopped,
                orchestrator.state.value,
                "a later intent still took effect — the violation was swallowed and the lane kept consuming",
            )
            assertTrue(
                testLog.dump().any { it.msg.contains("INVARIANT VIOLATION") },
                "the violation must be reported loudly, not logged as an ordinary handler failure",
            )
        }
}

private const val DL_BACKOFF_MS = 5L

/** See `DownloadEngineTest`: the breaker cooldown is policy; no test here asserts a duration. */
private const val DL_COOLDOWN_MS = 5L
private const val FAILED_MESSAGE_SETTLE_MS = 300L

/** Bound on `ClearUpNext`'s side-channel `refreshUpNext`, not a sleep: a pass returns at once. */
private const val EMPTY_UP_NEXT_MS = 5_000L

/** The abandoned resolve's failure path: a DB hop, a transfer, and the skip decision. */
private const val RESTORE_DISARM_SETTLE_MS = 2_000L
private const val DETACHED_SETTLE_MS = 400L
private const val RESUME_SETTLE_MS = 400L
private const val SETTLE_SETTLE_MS = 350L
private const val SETTLE_MARGIN_MS = 150L
private const val PREFETCH_ABSENCE_MS = 300L
