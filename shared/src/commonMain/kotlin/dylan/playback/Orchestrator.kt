@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.playback

import dylan.cache.CacheManager
import dylan.config.AppConfig
import dylan.db.Cached_files
import dylan.db.Dylan
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.EnqueueResult
import dylan.download.JobState
import dylan.download.Priority
import dylan.model.DylanFailure
import dylan.model.ErrorCode
import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Quality
import dylan.model.QueueInvariantViolation
import dylan.model.Repeat
import dylan.model.Song
import dylan.model.SongKey
import dylan.model.message
import dylan.repo.SettingsStore
import dylan.repo.toSong
import dylan.util.AppDispatchers
import dylan.util.Lane
import dylan.util.LaneViolation
import dylan.util.NetClass
import dylan.util.NetMonitor
import dylan.util.logErrRedacted
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Routing, phases and engine-window bookkeeping.
 *
 * ## What actually makes the shared state safe
 *
 * The old KDoc here claimed *"no handler captures a snapshot and then suspends, so there is no
 * window in which a second message can interleave between a read and the write derived from it"*.
 * That is not the mechanism, and it was not checkable by anything: there is no portable hook that
 * can observe "a suspension happened between this read and that write", so a claim in that shape
 * is a comment asserting what the code does not enforce — which is the one kind of comment that
 * stops the next reader from looking. The real contract, all of which is structural:
 *
 *  * **One consumer.** The inbox is drained by a single `for (m in inbox)` coroutine on the state
 *    lane. A suspension inside a handler therefore cannot admit a *second message*; the next
 *    message waits for the handler to finish.
 *  * **The interleaving that does exist is with the side channels, not with messages.**
 *    `prepareJob` [playNow], `settleJob` [advanceOptimistic], the 10 Hz ticker, the
 *    `downloads.states` collector and [resyncFault]'s re-prepare are *different coroutines on the
 *    same single-permit lane*, and they write `_state`. What protects those writes is the
 *    generation: `playGeneration` is bumped by every transition that invalidates in-flight work,
 *    and each of those coroutines re-checks it before it publishes.
 *  * **Every `_state` write is one assignment from a value read at the point of use.** Multi-hop
 *    work (`prepareWindow`, `refreshUpNext`, `admitSongs`) is hoisted into one suspension that
 *    *returns* what the reducer then applies, and the generation re-check sits between the
 *    suspension and the write.
 *
 * A handler failure is handled by [guard], and the two kinds are handled differently on purpose:
 * a recoverable one is logged and the loop continues, while an **invariant violation stops the
 * line** — see [guard].
 *
 * ## Why `@Suppress("LargeClass", "TooManyFunctions")`
 *
 * This file is 1 617 lines against a 600-line `LargeClass` threshold and carries 82 functions
 * against a `TooManyFunctions` threshold of 25. Both findings are suppressed rather than fixed. It
 * is the last un-split class in `playback`, and the seams are known:
 *
 *  * `QueueEditor` — queue mutation (`removeAt`, `moveWithinQueue`, `commitQueue`, `slotIndexOf`)
 *  * `ReadinessGate` — ensure-ready and the download wait (`ensureReadyAndPlay`, `awaitPlayable`,
 *    `armEnsureReadyWatchdog`, `targetBits`, `awaitDownload`, `prepareWindow`)
 *  * `Navigator` — advance and skip (`navigate`, `advanceToIndex`, `skipToNextOrError`, `exhausted`)
 *  * `SessionLog` — history, play counts, prefetch (`recordHistory`, `watchSession`, `prefetchHook`)
 *  * `ResumeStore` — snapshot save and restore (`saveSnapshot`, `restore`, `loadSongs`)
 *
 * The difficulty is not the structure but the shared `_state`: every one of these functions reads
 * and writes the same `MutableStateFlow`, so each extraction has to settle what a collaborator is
 * *given* versus what it must *return*, and that boundary is where a split silently drops a state
 * update. It wants to be done with hardware attached, one seam per pass.
 *
 * This is a deferral, not a resolution. It is deliberately inline rather than a detekt baseline so
 * that it stays visible here and cannot regrow into a generated file that hides unrelated findings
 * in the same two rules. Remove it when the split lands.
 *
 * The two size rules are not suppressed anywhere else in production `commonMain`. They are used
 * twice more in the module, both on Swift-facing code where the threshold counts a generated
 * surface rather than logic: `IosGraph` (`TooManyFunctions`) and `SaavnProviderTest`
 * (`LargeClass`, a test).
 */
@Suppress("LargeClass", "TooManyFunctions")
class Orchestrator(
    private val scope: CoroutineScope,
    private val disp: AppDispatchers,
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: dylan.cache.Paths,
    private val cfg: AppConfig,
    private val downloads: DownloadEngine,
    private val cacheManager: CacheManager,
    private val settings: SettingsStore,
    private val net: NetMonitor,
    private val log: dylan.diag.LogBuffer,
) {
    private val clock = cfg.clock
    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(PlayerState())
    val state: kotlinx.coroutines.flow.StateFlow<PlayerState> = _state

    private val engineFlow = MutableStateFlow<PlayerEngine?>(null)
    val positionMs = engineFlow.flatMapLatest { it?.positionFlow ?: emptyFlow<Long>() }.conflate()

    /**
     * Platform toast sink, read from the state lane.
     *
     * An `AtomicReference` and not a `var`: `AppContainer.bootComponents` publishes this from the
     * **io** lane, and every handler here reads it on the state lane. A plain field is an
     * unsynchronised cross-lane publication of a non-null value, and the failure mode is a toast
     * that never arrives.
     */
    var toast: ((String) -> Unit)?
        get() = toastRef.load()
        set(value) {
            toastRef.store(value)
        }

    private val toastRef = AtomicReference<((String) -> Unit)?>(null)

    private var engine: PlayerEngine? = null
    private var inboxJob: Job? = null
    private var eventsJob: Job? = null
    private var positionJob: Job? = null
    private var sessionJob: Job? = null
    private var snapshotJob: Job? = null
    private var settleJob: Job? = null
    private var prepareJob: Job? = null
    private var statesJob: Job? = null
    private var lastHistoryKey: SongKey? = null
    private var lastHistoryAt = 0L
    private var consecutiveErrors = 0
    private var countedThisSession = false
    private var lastPosMs = 0L
    private var pushedUpNextId: String? = null
    private var doneJoinedKey: SongKey? = null
    private var playGeneration = 0L
    private var resyncStrikes = 0
    private var lastResyncItem: String? = null
    private var prefetchedForKey: SongKey? = null
    private var resumePendingMs: Long = 0L
    private var resumeAppliedGen: Long = -1L
    private var snapshotFingerprint: Long = Long.MIN_VALUE
    private val navMark = TimeSource.Monotonic.markNow()
    private var lastNavAt: Duration? = null
    private val transientFailCounts = mutableMapOf<SongKey, Int>()

    /** `key -> queue slot`, rebuilt with the queue. Makes an engine itemId a hash lookup. */
    private var keyToSlot: Map<SongKey, Int> = emptyMap()

    private val windowPreparer = WindowPreparer(db, fs, paths, disp)

    private companion object {
        const val MS_PER_SECOND = 1_000L
        val PERMANENT_SKIP_CODES =
            setOf(
                ErrorCode.NO_SOURCE,
                ErrorCode.NOT_FOUND,
                ErrorCode.UNSUPPORTED,
                ErrorCode.CORRUPT_SIZE,
                ErrorCode.CORRUPT_CONTAINER,
                ErrorCode.EXPIRED,
                ErrorCode.NOT_CACHEABLE,
            )
        val TRANSIENT_SKIP_CODES = setOf(ErrorCode.NETWORK_TIMEOUT, ErrorCode.RATE_LIMITED)
        const val MAX_TRANSIENT_RETRIES = 2

        /**
         * Consecutive engine errors after which the player reports exhaustion instead of skipping
         * forward (see `onEngineError`). Not 1: the first engine error of a session is routinely the
         * platform reporting a route change, an interruption or a focus loss rather than an
         * unplayable item, and skipping on that one trades a real track for a phantom fault.
         */
        const val MAX_CONSECUTIVE_ERRORS = 3

        /**
         * Times one unmappable `itemId` may be re-prepared before it is escalated to a skip. Bounded
         * because the counter is per itemId and resets on any mapped `TrackChanged`: without a
         * ceiling a transient event reorder re-prepares forever.
         */
        const val MAX_RESYNC_STRIKES = 3

        /** Denominator for [PREFETCH_AT_PERCENT], which is named as a percentage of the track. */
        const val PERCENT_SCALE = 100L

        const val WINDOW_SLOTS = 2
        const val RESTART_PREVIOUS_MS = 3_000L
        const val ITEM_ENDED_FALLBACK_MS = 2_000L
        const val LISTENED_MS = 30_000L
        const val HISTORY_WINDOW_MS = 30 * 60_000L
        const val PREFETCH_AT_PERCENT = 95

        /** `SQLITE_MAX_VARIABLE_NUMBER` is 999 on Android; stay well under it. */
        const val DB_IN_CHUNK = 400
        const val HASH_PRIME = 31
        const val HASH_OFFSET = 1_125_899_906_842_597L

        fun JobState?.isTerminal() = this is JobState.Done || this is JobState.Failed
    }

    init {
        inboxJob =
            scope.launch(disp.on(Lane.STATE)) {
                try {
                    for (m in inbox) guard("inbox message") { process(m) }
                } finally {
                    // The lane is finished — because it was disposed, or because [guard] let an
                    // invariant violation through. Either way there is no consumer left, and
                    // `inbox` is `UNLIMITED`, so closing it is what stops later intents from
                    // piling up behind a dead loop. [post] reports the drop instead of throwing.
                    inbox.close()
                }
            }
        // E1 prompt path: push nextUp into the engine window the moment its download lands,
        // else the natural end of the current item exhausts a one-item playlist and playback dies.
        statesJob =
            scope.launch(disp.on(Lane.STATE)) {
                downloads.states.collect { guard("states collector") { syncPendingNext() } }
            }
    }

    private sealed interface Msg {
        data class I(
            val intent: Intent,
        ) : Msg

        data class E(
            val event: EngineEvent,
        ) : Msg

        data object Attach : Msg

        data object Detach : Msg

        data object Background : Msg

        data object Restore : Msg
    }

    fun submit(intent: Intent) {
        scope.launch(disp.on(Lane.STATE)) { post(Msg.I(intent)) }
    }

    /**
     * The one way onto the state lane's inbox.
     *
     * `trySend`, not `send`: after the loop has ended for any reason the channel is closed, and a
     * `send` there would throw out of an unrelated coroutine — the engine's event collector, the
     * `attachEngine`/`detachEngine` launches — turning one dead lane into a stream of exceptions
     * nobody can act on. A closed channel means "the lane is gone", and saying so once per drop is
     * the whole diagnostic.
     */
    private suspend fun post(m: Msg) {
        val sent = inbox.trySend(m)
        if (sent.isFailure) log.c("play", "state lane is closed; dropped ${m::class.simpleName}")
    }

    fun attachEngine(e: PlayerEngine) {
        scope.launch(disp.on(Lane.STATE)) {
            eventsJob?.cancel()
            positionJob?.cancel()
            engine = e
            engineFlow.value = e
            eventsJob =
                scope.launch {
                    e.events.collect { post(Msg.E(it)) }
                }
            positionJob =
                scope.launch {
                    e.positionFlow.collect {
                        lastPosMs = it
                        maybePrefetchAtTail()
                    }
                }
            post(Msg.Attach)
        }
    }

    fun detachEngine() {
        scope.launch(disp.on(Lane.STATE)) { post(Msg.Detach) }
    }

    fun onBackground() {
        scope.launch(disp.on(Lane.STATE)) { post(Msg.Background) }
    }

    fun restoreFromSnapshot() {
        scope.launch(disp.on(Lane.STATE)) { post(Msg.Restore) }
    }

    /** Terminal teardown: no inbox, no collectors, no engine. */
    fun dispose() {
        scope.launch(disp.on(Lane.STATE)) { guard("dispose") { disposeOnState() } }
    }

    private fun disposeOnState() {
        inboxJob?.cancel()
        inboxJob = null
        inbox.close()
        statesJob?.cancel()
        statesJob = null
        detachCollectors()
        engine?.release()
        engine = null
        engineFlow.value = null
    }

    /** After a detach, a stale event advanced the queue with no engine (audit AN-2). */
    private fun detachCollectors() {
        eventsJob?.cancel()
        eventsJob = null
        positionJob?.cancel()
        positionJob = null
        sessionJob?.cancel()
        sessionJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        cancelPrepare()
        cancelSettle()
        windowPreparer.noteEngineCurrent(null)
    }

    /**
     * One message, one guard — and two classes of failure, handled differently on purpose.
     *
     * **Recoverable** (an I/O error, a platform callback that throws, anything else unexpected) is
     * logged and the loop continues. A throw used to end the `for` loop permanently, and `inbox`
     * is `UNLIMITED`, so every later intent accumulated behind a dead consumer.
     *
     * **An invariant violation stops the line.** [QueueInvariantViolation] (the queue algebra,
     * documented at its declaration as "a throw, not a log line: these are programmer errors") and
     * [LaneViolation] (a lane assert, documented as "a correctness bug that must stop the line that
     * caused it") are logged CRITICALLY and **rethrown**. They used to be swallowed here, which is
     * how a cold-start `PlayNext` against an empty queue threw and *"the button silently did
     * nothing"* — the violation was real, the class of bug was known, and the log line nobody
     * reads was the entire response. Rethrowing ends the inbox loop, whose `finally` closes
     * `inbox`, so there is no unbounded queue left behind a dead consumer; [post] then reports
     * every later intent as dropped. That is deliberately the worst *user-visible* outcome and the
     * best *diagnostic* one: a loud dead lane on a shipped build beats a player whose Next button
     * intermittently does nothing.
     */
    private suspend fun guard(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (queue: QueueInvariantViolation) {
            rethrowFatal(what, queue)
        } catch (lane: LaneViolation) {
            rethrowFatal(what, lane)
        } catch (swallowed: Exception) {
            // Deliberately `Exception`, not `Throwable`: an `Error` (OOM, `StackOverflow`) is not a
            // handler bug to be logged and shrugged off, and letting it out ends the lane the same
            // way an invariant violation does.
            logRecoverable(what, swallowed)
        }
    }

    /**
     * The one place an invariant violation is reported and rethrown, so "loud, and the lane stops"
     * has a single definition. Returns [Nothing] because it never returns — which is also what keeps
     * the `throw` out of [guard]'s own body and its clause count honest.
     */
    private fun rethrowFatal(
        what: String,
        t: Throwable,
    ): Nothing {
        log.c("play", "INVARIANT VIOLATION in $what: ${t.message} — the state lane is stopping")
        logErrRedacted("dylan-orchestrator: INVARIANT VIOLATION in $what: $t")
        throw t
    }

    /** The one place a recoverable handler failure is reported. */
    private fun logRecoverable(
        what: String,
        t: Throwable,
    ) {
        log.c("play", "$what failed: ${t.message}")
        logErrRedacted("dylan-orchestrator: $what failed: $t")
    }

    private suspend fun process(m: Msg) {
        disp.assertInContext(Lane.STATE)
        when (m) {
            is Msg.I -> handleIntent(m.intent)
            is Msg.E -> handleEvent(m.event)
            Msg.Attach -> onEngineAttached()
            Msg.Detach -> onEngineDetached()
            Msg.Background -> saveSnapshot(force = true)
            Msg.Restore -> restore()
        }
        // A late-assigned nextUp whose Done predates its assignment may never see a fresh states
        // emission (StateFlow emits only on map change) — re-check after every message so the
        // window join cannot be missed.
        syncPendingNext()
    }

    private suspend fun onEngineAttached() {
        log.i("play", "engine attached")
        // Re-prime the window for state built while detached (restore-to-PAUSED, or a first PlayNow
        // that raced service binding): Ready/Playing become audible, Paused gets its window so the
        // first user play has something to start.
        val s = _state.value
        when (s.phase) {
            is Phase.Ready, is Phase.Playing -> {
                prepareWindow(s.index)
                engine?.play()
            }
            is Phase.Paused -> prepareWindow(s.index)
            else -> {}
        }
    }

    private fun onEngineDetached() {
        val pos = enginePositionMs()
        log.i("play", "engine detached (phase=${_state.value.phase::class.simpleName})")
        detachCollectors()
        engine?.pause()
        engine = null
        engineFlow.value = null
        playGeneration++
        val s = _state.value
        val k = s.phase.keyOrNull()
        if (k != null) _state.value = s.copy(phase = Phase.Paused(k), posMs = pos)
        saveSnapshotAsync(force = true)
    }

    // ── intents ────────────────────────────────────────────────────────────────────────────

    private suspend fun handleIntent(i: Intent) {
        when (i) {
            is Intent.PlayNow -> playNow(i)
            is Intent.PlayNext -> addToQueue(i.song, afterCurrent = true)
            is Intent.AddLast -> addToQueue(i.song, afterCurrent = false)
            Intent.TogglePlayPause -> togglePlayPause()
            is Intent.Seek -> seek(i.ms)
            Intent.Next -> navigate(+1)
            Intent.Previous -> navigate(-1)
            Intent.ToggleShuffle -> {
                val on = !_state.value.shuffleOn
                commitQueue(shuffleOn = on, shuffleOrder = null)
                refreshUpNext()
            }
            Intent.CycleRepeat -> {
                val s = _state.value
                val next =
                    when (s.repeat) {
                        Repeat.OFF -> Repeat.ALL
                        Repeat.ALL -> Repeat.ONE
                        Repeat.ONE -> Repeat.OFF
                    }
                commitQueue(repeat = next)
                refreshUpNext()
            }
            is Intent.RemoveAt -> removeAt(i.queuePos)
            is Intent.MoveWithinQueue -> moveWithinQueue(i.from, i.to)
            Intent.ClearUpNext -> clearUpNext()
            is Intent.SetQuality -> setQuality(i.q)
        }
    }

    private suspend fun playNow(i: Intent.PlayNow) {
        if (i.songs.isEmpty()) return
        val idx = i.startIndex.coerceIn(0, i.songs.size - 1)
        log.i("play", "PlayNow size=${i.songs.size} idx=$idx start=${i.songs[idx].title}")
        // Every lane hop happens before the new state exists, so a 20-track album costs two
        // dbLane round-trips instead of twenty and Resolving lands in a single emission.
        admitSongs(i.songs)
        val gen = bumpGeneration()
        commitQueue(
            queue = i.songs.toPersistentList(),
            index = idx,
            current = i.songs[idx],
            phase = Phase.Resolving(i.songs[idx].key),
            posMs = 0L,
            shuffleOrder = null,
        )
        prepareJob = scope.launch(disp.on(Lane.STATE)) { guard("ensureReady") { ensureReadyAndPlay(idx, gen) } }
    }

    /**
     * The first row of an empty queue becomes the current one, because
     * `PlayerState.validateShape` requires `index == -1` **exactly** when the queue is empty. The
     * old `commitQueue(queue = …)` kept the inherited `index = -1`, so `PlayNext`/`AddLast` from a
     * cold start threw `QueueInvariantViolation`, and the button silently did nothing. The phase
     * stays `Idle`: queueing a track is not a request to play it, and claiming a transport here
     * would `play()` an engine that holds no window.
     *
     * The swallowing that hid this is gone — [guard] now stops the lane on an invariant violation —
     * so the *shape* has to hold for every queueing path, not just the one that was exercised.
     */
    private suspend fun addToQueue(
        song: Song,
        afterCurrent: Boolean,
    ) {
        admitSongs(listOf(song))
        val s = _state.value
        if (s.queue.isEmpty()) {
            commitQueue(queue = persistentListOf(song), index = 0, current = song)
            return
        }
        val q = s.queue.toMutableList()
        val at = if (afterCurrent && s.index in -1 until q.size) s.index + 1 else q.size
        q.add(at, song)
        commitQueue(queue = q.toPersistentList())
        refreshUpNext()
    }

    private fun removeAt(queuePos: Int) {
        val s = _state.value
        if (queuePos !in s.queue.indices) return
        val q = s.queue.toMutableList().apply { removeAt(queuePos) }
        val idx =
            when {
                queuePos < s.index -> s.index - 1
                // The playing row is the one being removed: the slot is reassigned, the audible
                // track is not. `current` is deliberately preserved, so `index` stops pointing at
                // it — that is the "removing the playing row keeps the audible song" contract, and
                // the algebra reads `current`, never the slot, so Next cannot skip a track.
                queuePos == s.index -> s.index.coerceAtMost(q.size - 1)
                else -> s.index
            }
        commitQueue(queue = q.toPersistentList(), index = idx, current = s.current)
        scope.launch(disp.on(Lane.STATE)) { guard("window refresh") { refreshUpNext() } }
    }

    private fun moveWithinQueue(
        from: Int,
        to: Int,
    ) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val q = s.queue.toMutableList()
        val item = q.removeAt(from)
        q.add(to, item)
        val idx =
            when {
                s.index == from -> to
                from < to && s.index in (from + 1)..to -> s.index - 1
                to < from && s.index in to until from -> s.index + 1
                else -> s.index
            }
        commitQueue(queue = q.toPersistentList(), index = idx)
        scope.launch(disp.on(Lane.STATE)) { guard("window refresh") { refreshUpNext() } }
    }

    private fun clearUpNext() {
        val s = _state.value
        if (s.index < 0 || s.index + 1 > s.queue.lastIndex) return
        commitQueue(queue = s.queue.take(s.index + 1).toPersistentList())
        scope.launch(disp.on(Lane.STATE)) { guard("window refresh") { refreshUpNext() } }
    }

    private suspend fun setQuality(q: Quality) {
        settings.setQualityPref(q)
        val s = _state.value
        val cur = s.current ?: return
        val bits = windowPreparer.cachedRow(cur.key)?.bitrate?.toInt() ?: 0
        if (s.phase is Phase.Playing && q.bits > bits) {
            downloads.enqueue(DownloadJob(cur.key, Priority.QUALITY_UPGRADE, q.bits, clock.nowMs()))
        }
    }

    private fun togglePlayPause() {
        val s = _state.value
        when (s.phase) {
            is Phase.Playing -> {
                val pos = enginePositionMs()
                engine?.pause()
                _state.value = s.copy(phase = Phase.Paused(s.phase.key), posMs = pos)
                snapshotJob?.cancel()
                saveSnapshotAsync(force = true)
            }
            is Phase.Paused -> resume(s.phase.key)
            is Phase.Ready -> resume(s.phase.key)
            else -> {}
        }
    }

    private fun resume(key: SongKey) {
        engine?.play()
        _state.value = _state.value.copy(phase = Phase.Playing(key))
        startTicking()
    }

    /**
     * A seek is authoritative whether or not the engine holds a window yet, so the clamped value
     * lands in `PlayerState.posMs` either way. Clamping uses the engine's own duration when it
     * answers and the window's hint otherwise; when both are unknown the request passes through,
     * because the catalog's documented fallback for an unparseable duration is 0 and clamping to
     * that would silently turn every seek on such a track into `seekTo(0)`.
     */
    private fun seek(ms: Long) {
        val s = _state.value
        if (s.index !in s.queue.indices) return
        val upper = upperBoundMs(s)
        val target = ms.coerceAtLeast(0L)
        val clamped = if (upper != null) target.coerceAtMost(upper) else target
        _state.value = s.copy(posMs = clamped)
        when (s.phase) {
            is Phase.Playing, is Phase.Paused, is Phase.Ready -> engine?.seekTo(clamped)
            is Phase.Resolving, is Phase.Downloading ->
                log.i("play", "seek deferred to ${clamped}ms: the track is not prepared yet")
            else -> {}
        }
    }

    /**
     * Only a transport the engine is actually holding can be clamped: while a track is still being
     * resolved the window describes the *previous* track, so its duration would clamp against the
     * wrong song.
     *
     * The fallback is read from `_state.value.current` **at the point of use**, not from a field
     * cached when a window was built. `onTrackChanged` does not rebuild the window — the engine
     * already had both items and rolled on its own — so a cached hint still described the track
     * that had just ended, and every seek on the new track was clamped to the *previous* one's
     * length (a 10-minute song reached from a 1-minute one could not be seeked past 1:00). Neither
     * real engine overrides `PlayerEngine.durationMs()`, so this fallback is the only clamp in
     * production. `durationS == 0` is the catalog's documented "unparseable" fallback, and
     * `takeIf { it > 0L }` reads it as unknown rather than as a zero-length track.
     */
    private fun upperBoundMs(s: PlayerState): Long? {
        if (!transportable(s.phase)) return null
        // `> 0L`, not `>= 0L`: the seam answers -1 for "unknown" and the catalog's documented
        // fallback for an unparseable duration is 0, so 0 means UNKNOWN here too. Admitting it
        // would make [seek] clamp every seek on such a track to seekTo(0) — the request swallowed
        // rather than clamped. This matches the `current` line below it.
        engine?.durationMs()?.takeIf { it > 0L }?.let { return it }
        return s.current
            ?.durationS
            ?.times(MS_PER_SECOND)
            ?.takeIf { it > 0L }
    }

    // ── navigation ─────────────────────────────────────────────────────────────────────────

    private suspend fun navigate(dir: Int) {
        if (debounceNav()) {
            log.d("play", "navigation dropped (debounce ${cfg.navDebounceMs}ms)")
            return
        }
        val s = _state.value
        if (transportable(s.phase)) {
            if (dir < 0 && engine != null && enginePositionMs() > RESTART_PREVIOUS_MS) {
                seek(0L)
                return
            }
            cancelPrepare()
            if (resolveAdvance(dir) != null) advanceOptimistic(dir) else exhausted(dir)
            return
        }
        cancelPrepare()
        cancelSettle()
        if (resolveAdvance(dir) != null) {
            advanceOptimistic(dir)
        } else {
            exhausted(dir)
        }
    }

    /**
     * Reached only when the algebra found no successor, so every slot in the playing order has been
     * played. Nothing here consults a slot: the old `s.index == s.queue.lastIndex` was a *slot*
     * comparison made after `resolveAdvance` had already decided exhaustion in the *order*, which is
     * how a shuffled queue with unplayed slots toasted "End of queue". A single-track queue restarts
     * instead, so Next is never a dead button.
     *
     * The message follows the *direction*. The transportable branch used to call the Next-only
     * version unconditionally, so Previous at the head of a playing queue told the user they had
     * reached the end.
     */
    private suspend fun exhausted(dir: Int) {
        val s = _state.value
        when {
            s.queue.isEmpty() -> {}
            dir < 0 -> toast?.invoke("Start of queue")
            s.index < 0 || s.queue.size == 1 -> advanceToIndex(0)
            else -> toast?.invoke("End of queue")
        }
    }

    private fun advanceOptimistic(dir: Int) {
        val target = resolveAdvance(dir) ?: return
        val s = _state.value
        cancelSettle()
        val gen = bumpGeneration()
        val song = s.queue[target]
        _state.value =
            s.withQueueMutation(index = target, current = song, phase = Phase.Resolving(song.key), posMs = 0L)
        settleJob =
            scope.launch(disp.on(Lane.STATE)) {
                delay(cfg.skipSettleMs.toLong())
                guard("settle advance") { if (gen == playGeneration) ensureReadyAndPlay(target, gen) }
            }
    }

    private suspend fun advanceToIndex(idx: Int) {
        val s = _state.value
        if (idx !in s.queue.indices) return
        cancelSettle()
        val gen = bumpGeneration()
        val song = s.queue[idx]
        _state.value = s.withQueueMutation(index = idx, current = song, phase = Phase.Resolving(song.key), posMs = 0L)
        ensureReadyAndPlay(idx, gen)
    }

    // ── readiness ──────────────────────────────────────────────────────────────────────────

    /**
     * Cached-and-playable, or downloaded-and-playable; then `Ready`, the window, and the
     * bookkeeping that says a track actually started.
     *
     * The generation is re-checked after every suspension this function owns ([awaitPlayable], and
     * the `fetchAndVerify` hop inside it). [prepareWindow] is the one suspension it does not
     * re-check after, and the audit read that as a live defect — but `prepareWindow`'s own
     * post-suspension generation check means the *only* consequence of being superseded is that this
     * caller skips the engine post; whether `onTrackStarted` then runs is settled one level up,
     * where the generation actually moves:
     *
     *  * Every generation bump goes through [bumpGeneration], whose first act is `cancelPrepare()`
     *    and `cancelSettle()` — so the tracked owners of an in-flight resolve (`prepareJob` from
     *    [playNow], `settleJob` from [advanceOptimistic]) are cancelled by the bump and never reach
     *    this line at all.
     *  * The one untracked caller is [advanceToIndex], reached from [exhausted]. It runs **inside
     *    the inbox handler**, and the state lane is single-permit: while its `prepareWindow` is
     *    suspended on the db or io lane it has released the permit, but the only other coroutines
     *    that can bump the generation from off-inbox are the two this paragraph just cancelled.
     *    So no in-tree interleaving reaches [onTrackStarted] with a stale generation — the finding
     *    is SUSPECTED, not proven, and is recorded as such rather than "fixed" behind an assertion
     *    that cannot be made to fail.
     */
    private suspend fun ensureReadyAndPlay(
        index: Int,
        gen: Long,
    ) {
        if (gen != playGeneration) return
        val song = _state.value.queue.getOrNull(index) ?: return
        countedThisSession = false
        _state.value = _state.value.copy(phase = Phase.Resolving(song.key))
        log.d("play", "ensureReady gen=$gen idx=$index key=${song.key.provider}:${song.key.songId}")
        armEnsureReadyWatchdog(index, gen, song.key)
        if (!awaitPlayable(index, gen, song)) return
        if (gen != playGeneration) return
        consecutiveErrors = 0
        _state.value = _state.value.copy(phase = Phase.Ready(song.key))
        prepareWindow(index)
        onTrackStarted(song)
    }

    /**
     * Cached-and-playable, or downloaded-and-playable. Every hop happens here, before the caller's
     * state write, so the phase transitions below are contiguous.
     */
    private suspend fun awaitPlayable(
        index: Int,
        gen: Long,
        song: Song,
    ): Boolean {
        val cached = windowPreparer.cachedRow(song.key)
        if (cached != null && windowPreparer.sniffOk(cached)) return gen == playGeneration
        return fetchAndVerify(index, gen, song)
    }

    /**
     * Cached-and-playable was false, so the track has to be fetched. Every hop happens here, before
     * the caller's state write, so the phase transitions are contiguous.
     */
    private suspend fun fetchAndVerify(
        index: Int,
        gen: Long,
        song: Song,
    ): Boolean {
        // Offline fast-path: never spin the 120s download wait without a network.
        if (!net.isOnline()) {
            failWith(DylanFailure(ErrorCode.OFFLINE, song.key), gen)
            return false
        }
        val bits = targetBits()
        val attempt = downloads.enqueue(DownloadJob(song.key, Priority.USER_NOW, bits, clock.nowMs()))
        if (gen != playGeneration) return false
        _state.value = _state.value.copy(phase = Phase.Downloading(song.key))
        log.d("play", "downloading gen=$gen idx=$index key=${song.key.provider}:${song.key.songId} bits=$bits")
        val outcome = awaitDownload(song.key, attempt)
        if (gen != playGeneration) return false
        if (outcome !is JobState.Done) {
            // null = the attempt never settled inside the ceiling; anything else is a reported failure.
            val timedOut = outcome == null
            val failure = (outcome as? JobState.Failed)?.err ?: DylanFailure(ErrorCode.NETWORK_TIMEOUT, song.key)
            if (!timedOut) log.w("play", "ensureReady failed gen=$gen idx=$index code=${failure.code}")
            onPrepareFailed(failure, song, index, gen)
            return false
        }
        transientFailCounts.remove(song.key)
        return playableAfterDownload(song, gen)
    }

    /**
     * A `Done` is a claim, not a fact: the row can be gone and the file can be unplayable, and both
     * used to leave the player in `Ready` on a track it could never start — no error, no skip.
     */
    private suspend fun playableAfterDownload(
        song: Song,
        gen: Long,
    ): Boolean {
        val row = windowPreparer.cachedRow(song.key)
        if (row == null) {
            // A Done whose row did not survive; the old shape blamed the file for a cache miss.
            failWith(DylanFailure(ErrorCode.CORRUPT_SIZE, song.key), gen)
            return false
        }
        if (!windowPreparer.sniffOk(row)) {
            failWith(DylanFailure(ErrorCode.CORRUPT_CONTAINER, song.key), gen)
            return false
        }
        return true
    }

    /** Log-only tripwire: never fail playback, [AppConfig.readyTimeoutMs] still owns failure. */
    private fun armEnsureReadyWatchdog(
        index: Int,
        gen: Long,
        key: SongKey,
    ) {
        scope.launch(disp.on(Lane.STATE)) {
            delay(cfg.ensureReadyWatchdogMs)
            if (gen != playGeneration) return@launch
            val ph = _state.value.phase
            if (ph is Phase.Resolving || ph is Phase.Downloading) {
                log.w("play", "ensureReady slow gen=$gen idx=$index key=${key.provider}:${key.songId}")
            }
        }
    }

    private suspend fun targetBits(): Int {
        val metered = net.current() == NetClass.METERED
        return if (metered) cfg.meteredQuality.bits else settings.qualityPref().bits
    }

    /**
     * Wait for the attempt this enqueue produced, not for "some terminal state for this key".
     *
     * The key-keyed wait needed a 2 s `FRESH_TERMINAL_MS` guard in front of it: `states` is a
     * `StateFlow`, so a subscriber that arrives after the download has already finished is handed
     * the *previous* attempt's `Failed` and reports it for a job that never ran. That guard was a
     * dead 2 s on every download that finished faster, charged to playback. Awaiting the
     * [EnqueueResult.Queued] attempt id removes the ambiguity outright, and it also makes a retry
     * after a preemption waitable — a displaced attempt is not terminal for the key, so the
     * key-keyed wait could only ever be satisfied by the *new* attempt's state.
     *
     * An enqueue that was dropped or superseded never produced an attempt to await, so that case
     * falls back to the key-keyed wait and reports the same NETWORK_TIMEOUT it always did.
     */
    private suspend fun awaitDownload(
        key: SongKey,
        admitted: EnqueueResult,
    ): JobState? {
        val id = (admitted as? EnqueueResult.Queued)?.id
        if (id != null) {
            val settled = downloads.awaitAttempt(id, cfg.readyTimeoutMs)
            // A ready-timeout is NOT a download failure: the attempt is still streaming, and an
            // attempt's own wall ceiling is max(stallWallFloorMs, expectedBytes/8) — up to ~30 min
            // for a 14.5 MB track. Nothing else cancels it: onPrepareFailed re-enqueues the same
            // key at the same USER_NOW rank, which the live attempt out-ranks, so the incumbent just
            // keeps going. Without this the user is told the download failed and is then billed for
            // the rest of the transfer on cellular.
            if (settled == null) downloads.cancelAttempt(id, keepPart = true)
            return settled
        }
        val settled = withTimeoutOrNull(cfg.readyTimeoutMs) { downloads.states.first { it[key].isTerminal() }[key] }
        // No id to cancel precisely, so cancel by key — same reasoning, coarser blast radius.
        if (settled == null) downloads.cancel(key, keepPart = true)
        return settled
    }

    private suspend fun onPrepareFailed(
        failure: DylanFailure,
        song: Song,
        index: Int,
        gen: Long,
    ) {
        if (gen != playGeneration) return
        if (failure.code in TRANSIENT_SKIP_CODES) {
            val n = (transientFailCounts[song.key] ?: 0) + 1
            transientFailCounts[song.key] = n
            if (n <= MAX_TRANSIENT_RETRIES) {
                log.w("play", "transient retry gen=$gen idx=$index attempt=$n code=${failure.code}")
                delay(cfg.dlBackoffBaseMs * n)
                if (gen != playGeneration) return
                ensureReadyAndPlay(index, gen)
                return
            }
        }
        transientFailCounts.remove(song.key)
        if (failure.code in PERMANENT_SKIP_CODES || failure.code in TRANSIENT_SKIP_CODES) {
            skipToNextOrError(failure, gen)
        } else {
            failWith(failure, gen)
        }
    }

    private fun failWith(
        failure: DylanFailure,
        gen: Long,
    ) {
        if (gen != playGeneration) return
        log.w("play", "failed key=${failure.songKey?.provider}:${failure.songKey?.songId} code=${failure.code}")
        _state.value = _state.value.copy(phase = Phase.Error(failure))
        toast?.invoke(failure.message())
    }

    /**
     * Guaranteed progress: an unplayable track never parks the player in a terminal Error when
     * there is somewhere to go — toast and step to the next item instead.
     */
    private fun skipToNextOrError(
        failure: DylanFailure,
        gen: Long,
    ) {
        if (gen != playGeneration) return
        if (resolveAdvance(+1) != null) {
            log.w("play", "skipping unplayable key=${failure.songKey?.provider}:${failure.songKey?.songId}")
            toast?.invoke("Skipping unplayable track")
            advanceOptimistic(+1)
        } else {
            _state.value = _state.value.copy(phase = Phase.Error(failure))
            toast?.invoke(failure.message())
        }
    }

    // ── engine window ──────────────────────────────────────────────────────────────────────

    /**
     * Builds and posts the window for [index]. The up-next comes from the pre-suspension snapshot,
     * the generation is re-checked immediately before the post, and [pushedUpNextId] /
     * [doneJoinedKey] are mutated **after** that check — so a superseded caller can neither post a
     * stale window nor record it as delivered.
     *
     * It reports nothing back: a superseded caller is indistinguishable from one that posted. See
     * [ensureReadyAndPlay] for why that is not, in this tree, reachable with a stale generation.
     */
    private suspend fun prepareWindow(index: Int) {
        val e = engine ?: return
        val s = _state.value
        if (index !in s.queue.indices) return
        val gen = playGeneration
        val head = s.queue[index]
        val keys = listOfNotNull(head.key, s.nextUp?.key)
        val rows = windowPreparer.cachedRows(keys)
        val first = trackFor(head, gen, rows) ?: return
        val window = ArrayList<LocalTrack>(WINDOW_SLOTS)
        window.add(first)
        val next = s.nextUp
        if (next != null && next.key != head.key) trackFor(next, gen, rows)?.let { window.add(it) }
        if (gen != playGeneration || engine !== e) return
        val slot1 = window.getOrNull(1)
        pushedUpNextId = slot1?.itemId
        doneJoinedKey = if (slot1 != null) next?.key else null
        val want = s.resumePosMsFor(index)
        val hint = first.durationHintMs
        resumePendingMs = if (hint != null && hint > 0L) want.coerceAtMost(hint) else want
        e.prepare(window)
    }

    /**
     * The up-next slot only. `want` and [doneJoinedKey] are read from the *same* state read, so the
     * pair deciding "is it already joined" and the pair deciding "what to join" can no longer
     * disagree across a suspension.
     */
    private suspend fun refreshUpNext() {
        val e = engine ?: return
        val s = _state.value
        val next = joinableUpNext(s)
        if (next == null) {
            // Nothing may occupy the up-next slot any more — `Intent.ClearUpNext` truncated the
            // queue, or the successor left it. Returning here (the old shape) left the previously
            // pushed track in the engine's window, so "clear up next" truncated the *state* while
            // the engine still rolled onto the track the user had just removed.
            //
            // Only ever a clear: never a *replace* with something, and never when there is nothing
            // recorded as pushed. Both guards matter for iOS, whose `replaceUpNext(nil)` removes the
            // currently-playing item.
            if (pushedUpNextId == null) return
            pushedUpNextId = null
            doneJoinedKey = null
            e.replaceUpNext(null)
            return
        }
        val gen = playGeneration
        val candidate = trackFor(next, gen, windowPreparer.cachedRows(listOf(next.key)))
        // Never ask the engine to replace a slot with the item it is already playing: on iOS
        // `replaceUpNext` removes the currently-playing item, and a window that is about to roll
        // into slot 1 would be overwritten by a write computed before the roll.
        val want = if (candidate?.itemId == windowPreparer.currentItemId) null else candidate
        if (want?.itemId == pushedUpNextId) return
        if (gen != playGeneration || engine !== e) return
        pushedUpNextId = want?.itemId
        doneJoinedKey = next.key
        e.replaceUpNext(want)
    }

    /**
     * The track the up-next slot may hold right now, or null when it may hold nothing.
     *
     * Repeat-ONE pins the transport, so `nextUp` *is* the current item. The engine must never be
     * asked to queue the track it is playing, and comparing [WindowPreparer.currentItemId] cannot see
     * that on its own: before the engine's first `TrackChanged` there is no current id to compare
     * against, so the guard silently did nothing for exactly the window in which the first window is
     * built. Key identity is the check that does not depend on engine bookkeeping.
     */
    private fun joinableUpNext(s: PlayerState): Song? {
        if (s.phase is Phase.Idle || s.phase is Phase.Error) return null
        val next = s.nextUp ?: return null
        return if (next.key == s.current?.key) null else next
    }

    private fun syncPendingNext() {
        val s = _state.value
        val next = s.nextUp ?: return
        if (s.phase is Phase.Idle || s.phase is Phase.Error) return
        if (next.key == doneJoinedKey) return
        if (downloads.states.value[next.key] !is JobState.Done) return
        scope.launch(disp.on(Lane.STATE)) { guard("window join") { refreshUpNext() } }
    }

    /**
     * Window contents. Repeat-ONE pins the transport, so a window listing the same track twice made
     * every track play twice per cycle; `next != head` is the whole guard.
     */
    private suspend fun trackFor(
        song: Song,
        gen: Long,
        rows: Map<SongKey, Cached_files>,
    ): LocalTrack? {
        val row = rows[song.key] ?: return null
        if (!windowPreparer.sniffOk(row)) return null
        return LocalTrack(
            itemId = song.key.itemId(gen, row.bitrate.toInt()),
            path = paths.final(song.key, row.bitrate.toInt(), row.ext).toString(),
            durationHintMs = song.durationS * 1000,
            title = song.title,
            artist = song.artistName ?: song.subtitle,
            artworkUri = song.artUrl500.takeIf { it.isNotBlank() } ?: song.artUrl150.takeIf { it.isNotBlank() },
        )
    }

    // ── queue identity ────────────────────────────────────────────────────────────────────

    /**
     * The one assignment of the queue's *shape*. [PlayerState.withQueueMutation] recomputes
     * `nextIndex` and asserts the invariants; this adds the `key -> slot` index the engine's
     * `TrackChanged` lookups read, so a track change is a hash lookup rather than a `startsWith`
     * per queue entry (which allocated one String per entry, per event, on the state lane).
     */
    private fun commit(next: PlayerState) {
        keyToSlot = slotIndexOf(next)
        _state.value = next
    }

    private fun commitQueue(
        queue: PersistentList<Song> = _state.value.queue,
        index: Int = _state.value.index,
        current: Song? = _state.value.current,
        phase: Phase = _state.value.phase,
        posMs: Long? = null,
        shuffleOn: Boolean = _state.value.shuffleOn,
        shuffleOrder: PersistentList<Int>? = _state.value.shuffleOrder,
        repeat: Repeat = _state.value.repeat,
    ) {
        commit(
            _state.value.withQueueMutation(
                queue = queue,
                index = index,
                current = current,
                phase = phase,
                posMs = posMs,
                shuffleOn = shuffleOn,
                shuffleOrder = shuffleOrder,
                repeat = repeat,
            ),
        )
    }

    private fun slotIndexOf(s: PlayerState): Map<SongKey, Int> {
        if (s.queue.isEmpty()) return emptyMap()
        val out = HashMap<SongKey, Int>()
        // Ascending, keeping the first sighting: a key queued twice maps to its earlier slot, which
        // is the one the engine can still be holding a window for.
        for (i in s.queue.indices) {
            val key = s.queue[i].key
            if (key !in out) out[key] = i
        }
        return out
    }

    // ── engine events ─────────────────────────────────────────────────────────────────────

    private suspend fun handleEvent(ev: EngineEvent) {
        when (ev) {
            is EngineEvent.Prepared -> onPrepared(ev)
            is EngineEvent.TrackChanged -> onTrackChanged(ev)
            EngineEvent.QueueExhausted -> advanceOnNaturalEnd()
            is EngineEvent.ItemEnded -> onItemEnded()
            is EngineEvent.Error -> onEngineError()
            EngineEvent.RouteLost -> holdPosition()
            is EngineEvent.Interrupted -> if (ev.shouldResume) engine?.play() else holdPosition()
        }
    }

    private fun holdPosition() {
        val pos = enginePositionMs()
        engine?.pause()
        val s = _state.value
        val k = s.phase.keyOrNull()
        if (k != null) _state.value = s.copy(phase = Phase.Paused(k), posMs = pos)
    }

    /**
     * `Prepared` is a *buffering* event, not a transport decision: calling `play()` from it made a
     * rebuffer override a user pause and left the notification disagreeing with the app. Autoplay
     * follows the state machine, the only place that knows the user's intent ([autoplayReady] for
     * the Media3 ordering argument), and the resume seek is applied exactly once per generation.
     */
    private fun onPrepared(ev: EngineEvent.Prepared) {
        autoplayReady()
        val pending = resumePendingMs
        resumePendingMs = 0L
        if (pending <= 0L) return
        if (resumeAppliedGen == playGeneration) return
        resumeAppliedGen = playGeneration
        log.d("play", "resumed at ${pending}ms gen=$playGeneration item=${ev.itemId}")
        engine?.seekTo(pending)
    }

    /**
     * `Ready → Playing` plus the `play()` that makes it audible, as one indivisible step.
     *
     * Both engine events that can mean "the window is ready" call this, and it is keyed on the
     * phase, so whichever arrives first performs the transition and the second is a no-op. That
     * ordering-independence is the point: Media3 announces the item transition for a freshly
     * prepared window *before* it reports `STATE_READY` (`onMediaItemTransition` follows the
     * playlist change; `onPlaybackStateChanged` follows buffering), while
     * `FakePlayerEngine` emits `Prepared` first. A guard that only `onPrepared` owned was
     * therefore satisfied by neither order in one of the two cases — and the real one lost:
     * `onTrackChanged` had already promoted the phase, so `engine.play()` was never called and
     * the very first `PlayNow` of a session left a `Playing` state over a stopped transport.
     */
    private fun autoplayReady() {
        val s = _state.value
        if (s.phase !is Phase.Ready) return
        val k = s.phase.key
        _state.value = s.copy(phase = Phase.Playing(k))
        engine?.play()
    }

    private suspend fun onTrackChanged(ev: EngineEvent.TrackChanged) {
        log.i("play", "track changed item=${ev.itemId} reason=${ev.reason}")
        windowPreparer.noteEngineCurrent(ev.itemId)
        val s = _state.value
        if (!isCurrentGeneration(ev.itemId)) {
            // The engine is on a window we have already superseded. That is a fault, not a track
            // change: re-prepare the window the state machine actually asked for.
            resyncFault(ev.itemId)
            return
        }
        if (queueKeyOf(ev.itemId) == s.current?.key) {
            // The engine re-announced the item that is already `current`: the audible track has
            // not changed, so nothing moves. Two situations land here and they need different
            // phases — `prepareWindow` re-announces the head it just asked for (phase `Ready`,
            // which is the autoplay trigger and must be left intact for [autoplayReady], and this
            // is the *first* event of that pair on Media3), and the user removed this row from
            // under the engine (phase already past `Ready`, so only the phase is refreshed).
            autoplayReady()
            val cur = _state.value
            val k = cur.current?.key
            if (k != null && cur.phase !is Phase.Ready) _state.value = cur.copy(phase = Phase.Playing(k))
            return
        }
        val idx = keyToSlot[queueKeyOf(ev.itemId)]
        val song = idx?.let { s.queue.getOrNull(it) }
        if (idx == null || song == null) {
            resyncFault(ev.itemId)
            return
        }
        _state.value = s.withQueueMutation(index = idx, current = song, phase = Phase.Playing(song.key), posMs = 0L)
        consecutiveErrors = 0
        resyncStrikes = 0
        lastResyncItem = null
        pushedUpNextId = null
        doneJoinedKey = null
        resumeAppliedGen = playGeneration
        onTrackStarted(song)
        refreshUpNext()
    }

    private fun onItemEnded() {
        val s = _state.value
        if (!countedThisSession && s.current != null) {
            val key = s.current.key
            scope.launch(disp.on(Lane.STATE)) { guard("bump play count") { bumpPlayCount(key) } }
        }
        // Fallback: some engines report ItemEnded with no TrackChanged or QueueExhausted follow-up.
        val genAtEnd = playGeneration
        val keyAtEnd = s.current?.key ?: return
        if (s.phase !is Phase.Playing) return
        scope.launch(disp.on(Lane.STATE)) {
            delay(ITEM_ENDED_FALLBACK_MS)
            if (genAtEnd != playGeneration) return@launch
            val cur = _state.value
            if (cur.current?.key != keyAtEnd || cur.phase !is Phase.Playing) return@launch
            val durMs = (cur.current?.durationS ?: 0) * MS_PER_SECOND
            if (durMs > 0 && enginePositionMs() >= durMs - ITEM_ENDED_FALLBACK_MS) {
                log.w("play", "ItemEnded fallback advancing gen=$genAtEnd")
                advanceOnNaturalEnd()
            }
        }
    }

    private fun onEngineError() {
        consecutiveErrors++
        log.w("play", "engine error (consecutive=$consecutiveErrors)")
        val exhausted = DylanFailure(ErrorCode.TOO_MANY_FAILURES)
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS || resolveAdvance(+1) == null) {
            _state.value = _state.value.copy(phase = Phase.Error(exhausted))
            return
        }
        toast?.invoke("Skipping unplayable track")
        advanceOptimistic(+1)
    }

    private fun advanceOnNaturalEnd() {
        val s = _state.value
        val target = resolveAdvance(+1)
        if (target == null) {
            snapshotJob?.cancel()
            _state.value = s.copy(phase = Phase.Idle)
            return
        }
        // Tail-prefetch may not have landed yet (95% policy); advance regardless, pulling the
        // uncached track down now so playback continues.
        val song = s.queue[target]
        val gen = bumpGeneration()
        _state.value =
            s.withQueueMutation(index = target, current = song, phase = Phase.Resolving(song.key), posMs = 0L)
        prepareJob = scope.launch(disp.on(Lane.STATE)) { guard("ensureReady") { ensureReadyAndPlay(target, gen) } }
    }

    /**
     * The engine reported an itemId the queue cannot map — either a stale generation or a key that
     * is not in the queue. Re-preparing the window the state machine asked for is correct once (a
     * transient event reorder), but unbounded repeats loop forever, so after 3 strikes for the same
     * itemId this escalates to skipping forward like any other unplayable track. The counter resets
     * on any mapped TrackChanged.
     */
    private fun resyncFault(itemId: String) {
        resyncStrikes = if (lastResyncItem == itemId) resyncStrikes + 1 else 1
        lastResyncItem = itemId
        if (resyncStrikes >= MAX_RESYNC_STRIKES) {
            log.w("play", "resyncFault escalating, skipping item=$itemId strikes=$resyncStrikes")
            resyncStrikes = 0
            lastResyncItem = null
            toast?.invoke("Skipping unplayable track")
            advanceOptimistic(+1)
            return
        }
        log.w("play", "resyncFault item=$itemId strike=$resyncStrikes")
        scope.launch(disp.on(Lane.STATE)) { guard("resync re-prepare") { prepareWindow(_state.value.index) } }
    }

    /**
     * The generation is part of the itemId, so a stale prefix is rejected by one comparison instead
     * of scanning the queue — and a stale itemId is a fault, never a track change.
     */
    private fun isCurrentGeneration(itemId: String): Boolean {
        val sep = itemId.indexOf(SongKey.GEN_SEP)
        if (sep <= SongKey.GEN_PREFIX.length) return false
        return itemId.substring(0, sep) == SongKey.GEN_PREFIX + playGeneration
    }

    private fun queueKeyOf(itemId: String): SongKey {
        val parts = itemId.split(SongKey.GEN_SEP)
        return SongKey(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    // ── session bookkeeping ───────────────────────────────────────────────────────────────

    private fun onTrackStarted(song: Song) {
        val now = clock.nowMs()
        if (!(lastHistoryKey == song.key && now - lastHistoryAt < HISTORY_WINDOW_MS)) {
            scope.launch(disp.on(Lane.STATE)) { guard("history") { recordHistory(song, now) } }
            lastHistoryKey = song.key
            lastHistoryAt = now
        }
        scope.launch(disp.on(Lane.STATE)) { guard("cache touch") { cacheManager.touch(song.key, now) } }
        startTicking()
        watchSession(song)
    }

    private suspend fun recordHistory(
        song: Song,
        now: Long,
    ) {
        withContext(disp.on(Lane.DB)) {
            db.transaction {
                db.dylanQueries.insertHistory(song.key.provider, song.key.songId, now)
                db.dylanQueries.trimHistory(cfg.historyLimit.toLong())
            }
        }
    }

    private suspend fun bumpPlayCount(key: SongKey) {
        withContext(disp.on(Lane.DB)) { db.dylanQueries.bumpPlayCount(key.provider, key.songId) }
    }

    private fun watchSession(song: Song) {
        sessionJob?.cancel()
        val e = engine ?: return
        sessionJob =
            scope.launch {
                var lastPos = 0L
                var lastElapsed = Duration.ZERO
                var listened = 0L
                val mark = TimeSource.Monotonic.markNow()
                e.positionFlow.collect { pos ->
                    val elapsed = mark.elapsedNow()
                    val s = _state.value
                    if (s.phase is Phase.Playing && s.current?.key == song.key && lastElapsed > Duration.ZERO) {
                        val dPos = pos - lastPos
                        val dElapsed = (elapsed - lastElapsed).inWholeMilliseconds
                        if (dPos > 0 && dElapsed > 0) listened += min(dPos, dElapsed)
                        if (listened >= LISTENED_MS && !countedThisSession) {
                            countedThisSession = true
                            bumpPlayCount(song.key)
                        }
                    }
                    lastPos = pos
                    lastElapsed = elapsed
                }
            }
    }

    // ── prefetch ───────────────────────────────────────────────────────────────────────────

    private fun prefetchHook() {
        if (!cfg.prefetchEnabled) return
        scope.launch(disp.on(Lane.STATE)) {
            guard("prefetch") {
                val s = _state.value
                val next = s.nextUp ?: return@guard
                if (windowPreparer.cachedRow(next.key) != null) return@guard
                if (net.current() == NetClass.METERED && cfg.prefetchCellularTracks == 0) return@guard
                downloads.enqueue(DownloadJob(next.key, Priority.PREFETCH_NEXT, targetBits(), clock.nowMs()))
            }
        }
    }

    /** Defer the next-track download until the current song is 95% played. */
    private fun maybePrefetchAtTail() {
        if (!cfg.prefetchEnabled) return
        val s = _state.value
        val cur = s.current ?: return
        if (prefetchedForKey == cur.key) return
        if (s.phase !is Phase.Playing || s.repeat == Repeat.ONE) return
        val durMs = cur.durationS * MS_PER_SECOND
        if (durMs <= 0L || lastPosMs < durMs * PREFETCH_AT_PERCENT / PERCENT_SCALE) return
        prefetchedForKey = cur.key
        prefetchHook()
    }

    // ── resume artifact ────────────────────────────────────────────────────────────────────

    private fun saveSnapshotAsync(force: Boolean = false) {
        scope.launch(disp.on(Lane.STATE)) { guard("snapshot write") { saveSnapshot(force) } }
    }

    private fun startTicking() {
        snapshotJob?.cancel()
        snapshotJob =
            scope.launch(disp.on(Lane.STATE)) {
                while (currentCoroutineContext().isActive) {
                    delay(cfg.snapshotIntervalMs)
                    if (!currentCoroutineContext().isActive) return@launch
                    val ph = _state.value.phase
                    if (ph is Phase.Error || ph is Phase.Idle) return@launch
                    guard("snapshot tick") { saveSnapshot() }
                }
            }
    }

    /**
     * Only write when something the artifact holds actually changed. A ticker that serialised the
     * whole queue and wrote it every 10s forever cost a `dbLane` write per tick and contended with
     * every download step's queries on the same single lane.
     */
    private suspend fun saveSnapshot(force: Boolean = false) {
        val s = _state.value
        if (s.queue.isEmpty() || s.index !in s.queue.indices) return
        val fingerprint = snapshotFingerprintOf(s)
        if (!force && fingerprint == snapshotFingerprint) return
        val snap =
            ResumeSnapshot(
                items = s.queue.map { ItemRef(it.key.provider, it.key.songId) },
                index = s.index,
                posMs = resumeCandidateMs(s),
                playedAtMs = clock.nowMs(),
                shuffleOn = s.shuffleOn,
                order = s.shuffleOrder?.toList() ?: emptyList(),
            )
        settings.put("resume", encodeSnapshot(snap))
        snapshotFingerprint = fingerprint
    }

    /**
     * While the engine is playing, the authoritative position is the one it publishes 10x a second,
     * not the one a seek last wrote — otherwise a snapshot taken mid-track always records 0.
     */
    private fun resumeCandidateMs(s: PlayerState): Long = if (s.phase is Phase.Playing) lastPosMs else s.posMs

    /** Cheap change detector over everything the artifact carries; a weak hash is enough here. */
    private fun snapshotFingerprintOf(s: PlayerState): Long {
        var h = HASH_OFFSET
        h = h * HASH_PRIME + s.queue.size
        h = h * HASH_PRIME + s.index
        h = h * HASH_PRIME + if (s.shuffleOn) 1 else 0
        h = h * HASH_PRIME + (s.shuffleOrder?.size ?: -1)
        return h * HASH_PRIME + (resumeCandidateMs(s) / cfg.snapshotPosStepMs)
    }

    /**
     * A restore replaces the whole queue, so it supersedes whatever was resolving — the same
     * relationship a [Intent.PlayNow] has to the pending prepare. It has to go through
     * [bumpGeneration] for that reason, not for tidiness: without it the abandoned `ensureReady`
     * stayed armed against a generation the restore never invalidated, and when its own download
     * finished (or failed) it wrote over the queue the user had just been handed — a successful
     * prepare re-prepared the restored head's window, and a *failed* one skipped the restored
     * track forward, so a boot-time restore could drop the user onto the wrong song.
     */
    private suspend fun restore() {
        val raw = settings.get("resume") ?: return
        val snap = decodeSnapshot(raw)
        if (snap == null) {
            log.w("restore", "snapshot unparsable (len=${raw.length})")
            return
        }
        val byKey = loadSongs(snap.items.map { SongKey(it.provider, it.songId) })
        val pos = if (freshPosition(snap)) snap.posMs else 0L
        val restored =
            restoreState(snap, byKey, pos) ?: run {
                log.w("restore", "snapshot not restorable (had ${snap.items.size} items)")
                return
            }
        log.i("restore", "restored items=${restored.queue.size} idx=${restored.index} posMs=$pos")
        bumpGeneration()
        commit(restored)
        // A restore lands `Paused`, and a `Paused` state with an engine attached is exactly what
        // `onEngineAttached` primes with a window. Doing it here too covers the ordering the
        // production boot does NOT guarantee: `restoreFromSnapshot` runs off the io lane while the
        // media service attaches from `onCreate`, so the restore is routinely the *later* of the
        // two and would otherwise leave a restored queue with no engine window at all.
        if (engine != null) prepareWindow(restored.index)
    }

    /**
     * A sticky service restart is a pause-then-resume, so a position older than
     * [AppConfig.resumeMaxAgeMs] describes a track the user had already paused, not one that was
     * playing when the process died.
     */
    private fun freshPosition(snap: ResumeSnapshot): Boolean {
        val age = clock.nowMs() - snap.playedAtMs
        return snap.playedAtMs <= 0L || age <= cfg.resumeMaxAgeMs
    }

    /**
     * One batched pass, one `HashSet`, one `HashMap`: `ceil(n / DB_IN_CHUNK)` `dbLane` round-trips
     * and O(n) work.
     *
     * The old shape was O(n·m) — one `dbLane` hop per item, and a `key in keys` linear scan of a
     * `List<SongKey>` for every returned row. The membership test is a set lookup here; nothing in
     * this function scales with the square of the queue any more.
     */
    private suspend fun loadSongs(keys: List<SongKey>): Map<SongKey, Song> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<SongKey, Song>(keys.size)
        val wanted = HashSet(keys)
        withContext(disp.on(Lane.DB)) {
            for (chunk in keys
                .asSequence()
                .map { it.songId }
                .distinct()
                .chunked(DB_IN_CHUNK)) {
                if (chunk.isEmpty()) continue
                for (row in db.dylanQueries.selectSongsByIds(chunk).executeAsList()) {
                    val key = SongKey(row.provider, row.song_id)
                    if (key in wanted) out[key] = row.toSong()
                }
            }
        }
        return out
    }

    // ── misc ───────────────────────────────────────────────────────────────────────────────

    private fun transportable(p: Phase) = QueueStateMachine.transportable(p)

    /**
     * Monotonic: a wall-clock correction must not permanently permit or permanently block navigation.
     *
     * The "no previous tap" state is `null`, not `Duration.INFINITE`: `now - INFINITE` is
     * `-INFINITE`, which is less than any debounce window, so the *first* tap after any process
     * start was always dropped — and because a dropped tap never updates the mark, every later tap
     * was dropped too. Next and Previous did nothing at all unless the caller set
     * `navDebounceMs = 0`.
     */
    private fun debounceNav(): Boolean {
        val now = navMark.elapsedNow()
        val since = lastNavAt?.let { now - it }
        if (since != null && since < cfg.navDebounceMs.milliseconds) return true
        lastNavAt = now
        return false
    }

    private fun resolveAdvance(dir: Int): Int? = QueueStateMachine.resolveAdvance(_state.value, dir)

    /**
     * Every generation change goes through here: the pending resolve/prepare is cancelled and the
     * abandoned `USER_NOW` download is dropped, because the engine preempts only on a *strictly*
     * higher priority, so an abandoned one otherwise costs a full transfer.
     */
    private fun bumpGeneration(): Long {
        cancelPrepare()
        cancelSettle()
        val s = _state.value
        val phase = s.phase
        if (phase is Phase.Downloading || phase is Phase.Resolving) {
            s.current?.key?.let { downloads.cancel(it, keepPart = true) }
        }
        return ++playGeneration
    }

    private fun cancelPrepare() {
        prepareJob?.cancel()
        prepareJob = null
    }

    private fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    /** Two `dbLane` round-trips for an album, in one transaction, instead of one per song. */
    private suspend fun admitSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val keys = songs.map { it.key }
        val known =
            withContext(disp.on(Lane.DB)) { knownSongKeys(keys) }
        val missing = songs.filter { it.key !in known }
        if (missing.isEmpty()) return
        val now = clock.nowMs()
        withContext(disp.on(Lane.DB)) {
            db.transaction {
                for (song in missing) {
                    db.dylanQueries.insertSong(
                        song.key.provider,
                        song.key.songId,
                        song.title,
                        song.subtitle,
                        song.albumId,
                        song.albumName,
                        song.artUrl150,
                        song.artUrl500,
                        song.durationS,
                        if (song.has320) 1L else 0L,
                        song.resolveRef,
                        song.permaToken,
                        now,
                    )
                }
            }
        }
    }

    private fun knownSongKeys(keys: List<SongKey>): Set<SongKey> {
        val out = HashSet<SongKey>(keys.size)
        for (chunk in keys
            .asSequence()
            .map { it.songId }
            .distinct()
            .chunked(DB_IN_CHUNK)) {
            if (chunk.isEmpty()) continue
            for (row in db.dylanQueries.selectSongsByIds(chunk).executeAsList()) {
                out.add(SongKey(row.provider, row.song_id))
            }
        }
        return out
    }

    private fun enginePositionMs(): Long {
        val sync = engine?.currentTimeMs() ?: -1L
        return if (sync >= 0) sync else lastPosMs
    }
}

fun Phase.keyOrNull(): SongKey? =
    when (this) {
        is Phase.Resolving -> key
        is Phase.Downloading -> key
        is Phase.Ready -> key
        is Phase.Playing -> key
        is Phase.Paused -> key
        is Phase.Error -> null
        Phase.Idle -> null
    }
