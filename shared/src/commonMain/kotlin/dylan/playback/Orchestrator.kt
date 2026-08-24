package dylan.playback

import dylan.cache.CacheManager
import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.db.Songs
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.JobState
import dylan.download.Priority
import dylan.model.DylanFailure
import dylan.model.ErrorCode
import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.model.Song
import dylan.model.SongKey
import dylan.model.message
import dylan.repo.SettingsStore
import dylan.util.AppDispatchers
import dylan.util.nowMs
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import kotlin.math.min

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
    private val net: dylan.util.NetMonitor,
    private val protectedKeys: kotlinx.coroutines.flow.MutableStateFlow<Set<SongKey>>,
    private val log: dylan.diag.LogBuffer = dylan.diag.LogBuffer.SILENT,
) {
    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(PlayerState())
    val state: kotlinx.coroutines.flow.StateFlow<PlayerState> = _state

    private val engineFlow = MutableStateFlow<PlayerEngine?>(null)
    val positionMs = engineFlow.flatMapLatest { it?.positionFlow ?: emptyFlow<Long>() }.conflate()

    var toast: ((String) -> Unit)? = null
    private var engine: PlayerEngine? = null
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
    private var bufferedPlayNow: Intent.PlayNow? = null
    private var bufferedToggle: Intent.TogglePlayPause? = null
    private var countedThisSession = false
    private var lastPosMs = 0L
    private var pushedUpNextId: String? = null
    private var doneJoinedKey: SongKey? = null
    private var playGeneration = 0L

    init {
        scope.launch(disp.state) {
            for (m in inbox) process(m)
        }
        // E1 prompt path: push nextUp into the engine window the moment its download lands,
        // else the natural end of the current item exhausts a one-item playlist and playback dies.
        statesJob =
            scope.launch(disp.state) {
                downloads.states.collect { syncPendingNext() }
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
        scope.launch(disp.state) { inbox.send(Msg.I(intent)) }
    }

    fun attachEngine(e: PlayerEngine) {
        scope.launch(disp.state) {
            engine = e
            engineFlow.value = e
            eventsJob?.cancel()
            positionJob?.cancel()
            eventsJob =
                scope.launch {
                    e.events.collect { inbox.send(Msg.E(it)) }
                }
            positionJob =
                scope.launch {
                    e.positionFlow.collect {
                        lastPosMs = it
                        maybePrefetchAtTail()
                    }
                }
            inbox.send(Msg.Attach)
        }
    }

    fun detachEngine() {
        scope.launch(disp.state) { inbox.send(Msg.Detach) }
    }

    fun onBackground() {
        scope.launch(disp.state) { inbox.send(Msg.Background) }
    }

    fun restoreFromSnapshot() {
        scope.launch(disp.state) { inbox.send(Msg.Restore) }
    }

    private suspend fun process(m: Msg) {
        when (m) {
            is Msg.I -> handleIntent(m.intent)
            is Msg.E -> handleEvent(m.event)
            Msg.Attach -> {
                log.i("play", "engine attached")
                val bp = bufferedPlayNow
                if (bp != null) {
                    bufferedPlayNow = null
                    log.i("play", "draining buffered PlayNow size=${bp.songs.size} idx=${bp.startIndex}")
                    handleIntent(bp)
                    return
                }
                val bt = bufferedToggle
                if (bt != null) {
                    bufferedToggle = null
                    log.i("play", "draining buffered TogglePlayPause")
                    handleIntent(bt)
                    return
                }
                // Re-prime the window for state built while detached (restore-to-PAUSED, or a
                // first PlayNow that raced service binding): Ready/Playing become audible,
                // Paused gets its window so the first user play has something to start.
                val s2 = _state.value
                when (s2.phase) {
                    is Phase.Ready, is Phase.Playing -> {
                        prepareWindow(s2.index)
                        engine?.play()
                    }
                    is Phase.Paused -> prepareWindow(s2.index)
                    else -> {}
                }
            }
            Msg.Detach -> {
                log.i("play", "engine detached (phase=${_state.value.phase::class.simpleName})")
                settleJob?.cancel()
                settleJob = null
                prepareJob?.cancel()
                prepareJob = null
                sessionJob?.cancel()
                snapshotJob?.cancel()
                engine?.pause()
                engine = null
                engineFlow.value = null
                val k = _state.value.phase.keyOrNull()
                if (k != null) _state.value = _state.value.copy(phase = Phase.Paused(k))
                saveSnapshotAsync()
            }
            Msg.Background -> saveSnapshot()
            Msg.Restore -> restore()
        }
        // Late-assigned nextUp whose Done predates its assignment may never see a fresh
        // states emission (StateFlow emits only on map change) — re-check the latest
        // snapshot after every processed message so the window join cannot be missed.
        syncPendingNext()
    }

    private suspend fun handleIntent(i: Intent) {
        log.d("play", "intent=${i::class.simpleName} phase=${_state.value.phase::class.simpleName}")
        val s = _state.value
        when (i) {
            is Intent.PlayNow -> {
                if (i.songs.isEmpty()) return
                log.i("play", "PlayNow size=${i.songs.size} idx=${i.startIndex} start=${i.songs[i.startIndex.coerceIn(0, i.songs.size - 1)].title}")
                admitSongs(i.songs)
                val idx = i.startIndex.coerceIn(0, i.songs.size - 1)
                _state.value =
                    PlayerState(
                        phase = Phase.Resolving(i.songs[idx].key),
                        current = i.songs[idx],
                        queue = i.songs.toPersistentList(),
                        index = idx,
                        shuffleOn = s.shuffleOn,
                        shuffleOrder = if (s.shuffleOn) buildShuffleOrder(i.songs.size, idx) else null,
                        repeat = s.repeat,
                    )
                playGeneration++
                val gen = playGeneration
                prepareJob?.cancel()
                prepareJob = scope.launch(disp.state) { ensureReadyAndPlay(idx, gen) }
            }
            is Intent.PlayNext -> {
                admitSong(i.song)
                val q = s.queue.toMutableList().apply { add(min(s.index + 1, s.queue.size), i.song) }
                setStateQueue(s, q)
            }
            is Intent.AddLast -> {
                admitSong(i.song)
                setStateQueue(s, s.queue.toMutableList().apply { add(i.song) })
            }
            Intent.TogglePlayPause ->
                when (s.phase) {
                    is Phase.Playing -> {
                        val k = s.phase.key
                        engine?.pause()
                        _state.value = s.copy(phase = Phase.Paused(k))
                        snapshotJob?.cancel()
                        saveSnapshotAsync()
                    }
                    is Phase.Paused -> {
                        val k = s.phase.key
                        engine?.play()
                        _state.value = s.copy(phase = Phase.Playing(k))
                        startTicking()
                    }
                    is Phase.Ready -> {
                        val k = s.phase.key
                        engine?.play()
                        _state.value = s.copy(phase = Phase.Playing(k))
                        startTicking()
                    }
                    else -> {}
                }
            is Intent.Seek -> {
                if (s.phase is Phase.Playing || s.phase is Phase.Paused || s.phase is Phase.Ready) {
                    val dur = (s.current?.durationS ?: 0) * 1000
                    engine?.seekTo(i.ms.coerceIn(0, dur))
                }
            }
            Intent.Next -> {
                if (transportable(s.phase)) advanceOptimistic(+1)
            }
            Intent.Previous -> {
                if (!transportable(s.phase)) return
                if (engine != null && enginePositionMs() > 3_000) {
                    engine?.seekTo(0)
                } else {
                    advanceOptimistic(-1)
                }
            }
            Intent.ToggleShuffle -> {
                val on = !s.shuffleOn
                _state.value =
                    s.copy(
                        shuffleOn = on,
                        shuffleOrder = if (on && s.queue.isNotEmpty()) buildShuffleOrder(s.queue.size, s.index) else null,
                    )
                refreshUpNext()
            }
            Intent.CycleRepeat -> {
                _state.value =
                    s.copy(
                        repeat =
                            when (s.repeat) {
                                Repeat.OFF -> Repeat.ALL
                                Repeat.ALL -> Repeat.ONE
                                Repeat.ONE -> Repeat.OFF
                            },
                    )
                refreshUpNext()
            }
            is Intent.RemoveAt -> {
                if (i.queuePos !in s.queue.indices) return
                val q = s.queue.toMutableList().apply { removeAt(i.queuePos) }
                var idx = s.index
                var keepCurrent = false
                when {
                    i.queuePos < s.index -> idx--
                    i.queuePos == s.index -> {
                        idx = idx.coerceAtMost(q.size - 1)
                        keepCurrent = true
                    }
                }
                applyQueue(q.toPersistentList(), idx, keepCurrent)
            }
            is Intent.MoveWithinQueue -> {
                if (i.from !in s.queue.indices || i.to !in s.queue.indices || i.from == i.to) return
                val q = s.queue.toMutableList()
                val item = q.removeAt(i.from)
                q.add(i.to, item)
                val idx =
                    when {
                        s.index == i.from -> i.to
                        i.from < i.to && s.index in (i.from + 1)..i.to -> s.index - 1
                        i.to < i.from && s.index in i.to until i.from -> s.index + 1
                        else -> s.index
                    }
                applyQueue(q.toPersistentList(), idx)
            }
            Intent.ClearUpNext -> {
                if (s.index + 1 <= s.queue.lastIndex) {
                    applyQueue(s.queue.take(s.index + 1).toPersistentList(), s.index)
                }
            }
            is Intent.SetQuality -> {
                settings.setQualityPref(i.q)
                val cur = s.current
                val cachedBits = cur?.let { c -> cachedRow(c.key)?.bitrate?.toInt() } ?: 0
                if (cur != null && s.phase is Phase.Playing && i.q.bits > cachedBits) {
                    downloads.enqueue(
                        DownloadJob(cur.key, Priority.QUALITY_UPGRADE, i.q.bits, nowMs()),
                    )
                }
            }
        }
    }

    private fun transportable(p: Phase) = p is Phase.Playing || p is Phase.Paused || p is Phase.Ready

    private fun setStateQueue(
        s: PlayerState,
        q: MutableList<Song>,
    ) {
        applyQueue(q.toPersistentList(), s.index)
    }

    private fun applyQueue(
        q: PersistentList<Song>,
        newIndex: Int,
        keepCurrent: Boolean = false,
    ) {
        val s = _state.value
        _state.value =
            s.copy(
                queue = q,
                index = newIndex,
                current = if (keepCurrent) s.current else q.getOrNull(newIndex),
                shuffleOrder = if (s.shuffleOn && q.isNotEmpty()) buildShuffleOrder(q.size, newIndex) else null,
            )
        refreshUpNext()
    }

    private fun advanceOptimistic(dir: Int) {
        val s = _state.value
        val target = resolveAdvance(dir) ?: return
        settleJob?.cancel()
        playGeneration++
        val gen = playGeneration
        _state.value = s.copy(index = target, current = s.queue[target], phase = Phase.Resolving(s.queue[target].key))
        settleJob =
            scope.launch(disp.state) {
                delay(cfg.skipSettleMs.toLong())
                if (gen == playGeneration) ensureReadyAndPlay(target, gen)
            }
    }

    private fun resolveAdvance(dir: Int): Int? {
        val s = _state.value
        if (s.queue.isEmpty()) return null
        return if (s.shuffleOn) {
            val order = s.shuffleOrder ?: return null
            val pos = order.indexOf(s.index)
            if (pos < 0) return null
            val nextPos = pos + dir
            when {
                nextPos in order.indices -> order[nextPos]
                dir > 0 && s.repeat == Repeat.ALL -> order.first()
                dir < 0 && s.repeat == Repeat.ALL -> order.last()
                else -> null
            }
        } else {
            val n = s.index + dir
            when {
                n in s.queue.indices -> n
                dir > 0 && s.repeat == Repeat.ALL -> 0
                dir < 0 && s.repeat == Repeat.ALL -> s.queue.lastIndex
                else -> null
            }
        }
    }

    private suspend fun ensureReadyAndPlay(
        index: Int,
        gen: Long,
    ) {
        if (gen != playGeneration) return
        val s = _state.value
        val song = s.queue.getOrNull(index) ?: return
        countedThisSession = false
        _state.value = s.copy(phase = Phase.Resolving(song.key), current = song, index = index)

        var row = cachedRow(song.key)
        if (row == null || !sniffOk(row)) {
            val metered = net.current() == dylan.util.NetClass.METERED
            val bits = if (metered) cfg.meteredQuality.bits else settings.qualityPref().bits
            downloads.enqueue(DownloadJob(song.key, Priority.USER_NOW, bits, nowMs()))
            val outcome =
                withTimeoutOrNull(cfg.readyTimeoutMs) {
                    downloads.states.first { st ->
                        (st[song.key] as? JobState.Done)?.let { true }
                            ?: (st[song.key] as? JobState.Failed)?.let { true }
                            ?: false
                    }
                }
            if (gen != playGeneration) return
            val js = outcome?.get(song.key)
            if (js !is JobState.Done) {
                val failure = (js as? JobState.Failed)?.err ?: DylanFailure(ErrorCode.NETWORK_TIMEOUT, song.key)
                log.w("play", "ensureReady failed ${song.key.provider}:${song.key.songId} code=${failure.code} timeout=${outcome == null}")
                _state.value = _state.value.copy(phase = Phase.Error(failure))
                toast?.invoke(failureText(failure))
                return
            }
            row = cachedRow(song.key) ?: run {
                _state.value = _state.value.copy(phase = Phase.Error(DylanFailure(ErrorCode.CORRUPT_SIZE, song.key)))
                return
            }
        }

        consecutiveErrors = 0
        _state.value = _state.value.copy(phase = Phase.Ready(song.key))
        prepareWindow(index)
        onTrackStarted(song)
    }

    private suspend fun prepareWindow(index: Int) {
        val e = engine ?: return
        val s = _state.value
        val tracks =
            buildList {
                s.queue.getOrNull(index)?.let { song -> localTrackFor(song)?.let { add(it) } }
                if (size == 1) {
                    s.nextUp?.let { next -> localTrackFor(next)?.let { add(it) } }
                }
            }
        if (tracks.isEmpty()) return
        pushedUpNextId = tracks.getOrNull(1)?.itemId
        doneJoinedKey = if (tracks.size == 2) s.nextUp?.key else null
        e.prepare(tracks)
    }

    private fun refreshUpNext() {
        val e = engine ?: return
        if (!transportable(_state.value.phase)) return
        scope.launch(disp.state) {
            if (!transportable(_state.value.phase)) return@launch
            val want = _state.value.nextUp?.let { localTrackFor(it) }
            val wantId = want?.itemId
            if (wantId == pushedUpNextId) return@launch
            pushedUpNextId = wantId
            doneJoinedKey = _state.value.nextUp?.key
            e.replaceUpNext(want)
        }
    }

    private fun syncPendingNext() {
        val s = _state.value
        val next = s.nextUp ?: return
        if (!transportable(s.phase)) return
        if (next.key == doneJoinedKey) return
        if (downloads.states.value[next.key] !is JobState.Done) return
        refreshUpNext()
    }

    private fun prefetchHook() {
        if (!cfg.prefetchEnabled) return
        scope.launch(disp.state) {
            val s = _state.value
            val next = s.nextUp ?: return@launch
            if (cachedRow(next.key) != null) return@launch
            val metered = net.current() == dylan.util.NetClass.METERED
            if (metered && cfg.prefetchCellularTracks == 0) return@launch
            val bits = if (metered) cfg.meteredQuality.bits else settings.qualityPref().bits
            downloads.enqueue(DownloadJob(next.key, Priority.PREFETCH_NEXT, bits, nowMs()))
        }
    }

    private var prefetchedForKey: SongKey? = null

    // Prefetch policy: defer the next-track download until the current song is 95% played —
    // never on repeat-one, and never when the catalog gave us no usable duration.
    private fun maybePrefetchAtTail() {
        if (!cfg.prefetchEnabled) return
        val cur = _state.value.current ?: return
        if (prefetchedForKey == cur.key) return
        val s = _state.value
        if (s.phase !is Phase.Playing) return
        if (s.repeat == Repeat.ONE) return
        val durMs = cur.durationS * 1000
        if (durMs <= 0L || lastPosMs < durMs * 95 / 100) return
        prefetchedForKey = cur.key
        prefetchHook()
    }

    private suspend fun handleEvent(ev: EngineEvent) {
        val s = _state.value
        when (ev) {
            is EngineEvent.Prepared -> {
                engine?.play()
            }
            is EngineEvent.TrackChanged -> {
                log.i("play", "track changed item=${ev.itemId} reason=${ev.reason}")
                val idx = indexOfItemId(ev.itemId)
                if (idx == null) {
                    resyncFault(ev.itemId)
                    return
                }
                val song = s.queue[idx]
                _state.value = s.copy(index = idx, current = song, phase = Phase.Playing(song.key))
                consecutiveErrors = 0
                pushedUpNextId = null
                doneJoinedKey = null
                onTrackStarted(song)
                refreshUpNext()
            }
            EngineEvent.QueueExhausted -> {
                val cur = _state.value
                val target =
                    if (cur.queue.isEmpty()) {
                        null
                    } else {
                        resolveAdvance(+1)
                    }
                if (target != null) {
                    // Tail-prefetch may not have landed yet (95% policy); advance regardless,
                    // pulling the uncached track down now so playback continues.
                    _state.value = cur.copy(index = target, current = cur.queue[target], phase = Phase.Resolving(cur.queue[target].key))
                    playGeneration++
                    val gen = playGeneration
                    prepareJob?.cancel()
                    prepareJob = scope.launch(disp.state) { ensureReadyAndPlay(target, gen) }
                } else {
                    snapshotJob?.cancel()
                    _state.value = cur.copy(phase = Phase.Idle)
                }
            }
            is EngineEvent.ItemEnded -> {
                if (!countedThisSession) {
                    _state.value.current?.let { bumpPlayCount(it.key) }
                }
            }
            is EngineEvent.Error -> {
                consecutiveErrors++
                log.w("play", "engine error (consecutive=$consecutiveErrors)")
                if (consecutiveErrors >= 3) {
                    _state.value = _state.value.copy(phase = Phase.Error(DylanFailure(ErrorCode.TOO_MANY_FAILURES)))
                } else {
                    val target = resolveAdvance(+1)
                    if (target != null) {
                        toast?.invoke("Skipping unplayable track")
                        advanceOptimistic(+1)
                    } else {
                        _state.value = _state.value.copy(phase = Phase.Error(DylanFailure(ErrorCode.TOO_MANY_FAILURES)))
                    }
                }
            }
            EngineEvent.RouteLost -> {
                engine?.pause()
                val k = _state.value.phase.keyOrNull()
                if (k != null) _state.value = _state.value.copy(phase = Phase.Paused(k))
            }
            is EngineEvent.Interrupted -> {
                if (ev.shouldResume) {
                    engine?.play()
                } else {
                    engine?.pause()
                }
            }
        }
    }

    private fun publishProtected() {
        val s = _state.value
        val keys =
            buildSet {
                s.current?.let { add(it.key) }
                s.nextUp?.let { add(it.key) }
            }
        protectedKeys.value = keys
    }

    private suspend fun onTrackStarted(song: Song) {
        val now = nowMs()
        publishProtected()
        if (!(lastHistoryKey == song.key && now - lastHistoryAt < 30 * 60_000L)) {
            withContext(disp.dbLane) {
                db.transaction {
                    db.dylanQueries.insertHistory(song.key.provider, song.key.songId, now)
                    db.dylanQueries.trimHistory(cfg.historyLimit.toLong())
                }
            }
            lastHistoryKey = song.key
            lastHistoryAt = now
        }
        cacheManager.touch(song.key, now)
        startTicking()
        watchSession(song)
    }

    private fun saveSnapshotAsync() {
        scope.launch(disp.state) { saveSnapshot() }
    }

    private fun startTicking() {
        snapshotJob?.cancel()
        snapshotJob =
            scope.launch(disp.state) {
                while (true) {
                    delay(10_000)
                    saveSnapshot()
                }
            }
    }

    private fun watchSession(song: Song) {
        sessionJob?.cancel()
        val e = engine ?: return
        sessionJob =
            scope.launch {
                var lastPos = 0L
                var lastWall = 0L
                var listened = 0L
                e.positionFlow.collect { pos ->
                    val wall = nowMs()
                    if (_state.value.phase is Phase.Playing && _state.value.current?.key == song.key && lastWall > 0) {
                        val dPos = pos - lastPos
                        val dWall = wall - lastWall
                        if (dPos > 0 && dWall > 0) listened += minOf(dPos, dWall)
                        if (listened >= 30_000 && !countedThisSession) {
                            countedThisSession = true
                            bumpPlayCount(song.key)
                        }
                    }
                    lastPos = pos
                    lastWall = wall
                }
            }
    }

    private suspend fun bumpPlayCount(key: SongKey) {
        withContext(disp.dbLane) { db.dylanQueries.bumpPlayCount(key.provider, key.songId) }
    }

    suspend fun saveSnapshot() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val snap =
            ResumeSnapshot(
                items = s.queue.map { ItemRef(it.key.provider, it.key.songId) },
                index = s.index,
                posMs = enginePositionMs(),
                shuffleOn = s.shuffleOn,
                order = s.shuffleOrder?.toList() ?: emptyList(),
            )
        settings.put("resume", encodeSnapshot(snap))
    }

    private suspend fun restore() {
        val raw = settings.get("resume") ?: return
        val snap = decodeSnapshot(raw)
        if (snap == null) {
            log.w("restore", "snapshot unparsable (len=${raw.length})")
            return
        }
        val songs = mutableListOf<Song>()
        for (ref in snap.items) {
            val row =
                withContext(disp.dbLane) {
                    db.dylanQueries.selectSong(ref.provider, ref.songId).executeAsOneOrNull()
                } ?: continue
            songs.add(toSong(row))
        }
        val restored = sanitizeSnapshot(snap) { ref -> songs.any { it.key == SongKey(ref.provider, ref.songId) } }
        if (restored == null) {
            log.w("restore", "snapshot sanitized away (had ${snap.items.size} items)")
            return
        }
        val ordered = restored.refs.mapNotNull { ref -> songs.firstOrNull { it.key == SongKey(ref.provider, ref.songId) } }
        if (ordered.isEmpty()) {
            log.w("restore", "snapshot refs had no live songs")
            return
        }
        log.i("restore", "restored items=${ordered.size} idx=${restored.index} posMs=${snap.posMs}")
        _state.value =
            PlayerState(
                phase = Phase.Paused(ordered[restored.index].key),
                current = ordered[restored.index],
                queue = ordered.toPersistentList(),
                index = restored.index,
                shuffleOn = snap.shuffleOn,
                shuffleOrder = restored.order?.toPersistentList(),
                repeat = Repeat.OFF,
            )
    }

    private fun resyncFault(itemId: String) {
        scope.launch(disp.state) { prepareWindow(_state.value.index) }
    }

    private fun indexOfItemId(itemId: String): Int? {
        val s = _state.value
        s.queue.forEachIndexed { i, song ->
            if (itemId.startsWith("${song.key.provider}:${song.key.songId}:")) return i
        }
        return null
    }

    private suspend fun admitSongs(songs: List<Song>) = songs.forEach { admitSong(it) }

    private suspend fun admitSong(song: Song) {
        withContext(disp.dbLane) {
            val exists = db.dylanQueries.selectSong(song.key.provider, song.key.songId).executeAsOneOrNull()
            if (exists == null) {
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
                    if (song.cacheable) 1L else 0L,
                    song.resolveRef,
                    song.permaToken,
                    nowMs(),
                )
            }
        }
    }

    private suspend fun cachedRow(key: SongKey) =
        withContext(disp.dbLane) {
            db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
        }

    private fun sniffOk(row: dylan.db.Cached_files): Boolean {
        val key = SongKey(row.provider, row.song_id)
        val path = paths.final(key, row.bitrate.toInt(), row.ext)
        val meta = runCatching { fs.metadataOrNull(path) }.getOrNull() ?: return false
        if (meta.size ?: -1L != row.bytes) return false
        return runCatching {
            val h = fs.openReadOnly(path)
            try {
                val buf = ByteArray(12)
                var read = 0
                while (read < 12) {
                    val n = h.read(read.toLong(), buf, read, 12 - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 12) return@runCatching false
                val head = buf.decodeToString(4, 8)
                head == "ftyp" || buf[0] == 'I'.code.toByte() && buf[1] == 'D'.code.toByte() && buf[2] == '3'.code.toByte()
            } finally {
                runCatching { h.close() }
            }
        }.getOrDefault(false)
    }

    private suspend fun localTrackFor(song: Song): dylan.playback.LocalTrack? {
        val row = cachedRow(song.key) ?: return null
        if (!sniffOk(row)) return null
        val path = paths.final(song.key, row.bitrate.toInt(), row.ext)
        return LocalTrack(
            itemId = song.key.itemId(row.bitrate.toInt()),
            path = path.toString(),
            durationHintMs = song.durationS * 1000,
            title = song.title,
            artist = song.artistName ?: song.subtitle,
            artworkUri = song.artUrl500.takeIf { it.isNotBlank() } ?: song.artUrl150.takeIf { it.isNotBlank() },
        )
    }

    private fun enginePositionMs(): Long = lastPosMs

    private fun failureText(f: DylanFailure): String = f.message()

    private fun toSong(r: Songs): Song =
        Song(
            key = SongKey(r.provider, r.song_id),
            title = r.title,
            subtitle = r.subtitle,
            albumId = r.album_id,
            albumName = r.album_name,
            artUrl150 = r.art_url_150,
            artUrl500 = r.art_url_500,
            durationS = r.duration_s,
            has320 = r.has_320 == 1L,
            cacheable = r.cacheable == 1L,
            resolveRef = r.resolve_ref,
            permaToken = r.perma_token,
        )
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
