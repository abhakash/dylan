@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("UnsafeOptInUsageError")

package dylan.android.media

import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dylan.android.DylanApp
import dylan.di.AppContainer
import dylan.diag.LogBuffer
import dylan.model.Phase
import dylan.model.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(UnstableApi::class)
class DylanMediaService : MediaSessionService() {
    private var session: MediaSession? = null
    private var engine: ExoPlayerEngine? = null
    private var routeJob: Job? = null
    private var stateJob: Job? = null
    private var resumeFuture: ListenableFuture<MediaSession.MediaItemsWithStartPosition>? = null

    /** Set once the user swipes the task away; arms [stopWhenPlaybackEnds]. */
    private var taskRemoved = false

    private val container: AppContainer get() = DylanApp.of(this).container

    private val log: LogBuffer get() = container.log

    companion object {
        // Custom-layout transport commands — the queue lives in the Orchestrator, not the
        // Exo timeline, so next/prev must route here regardless of timeline state.
        private const val CMD_NEXT = "dylan.NEXT"
        private const val CMD_PREV = "dylan.PREVIOUS"

        /** Resumption I/O budget: exceed it and Media3 gets empty (no resume), never a stall. */
        private const val RESUME_TIMEOUT_MS = 1_500L

        /** Mirrors `Orchestrator.RESTART_PREVIOUS_MS`, which is private to the shared module. */
        private const val RESTART_PREVIOUS_MS = 3_000L
    }

    /** Next/Previous availability for one state emission; see [transportOf]. */
    private data class Transport(
        val canNext: Boolean,
        val canPrev: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        val app = DylanApp.of(this)
        val container = app.container
        container.log.i("service", "onCreate")
        // Built before the placeholder: the session activity is the one content intent both it and
        // every later Media3 notification use, and a placeholder without one cannot be tapped away.
        val sessionActivity =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, dylan.android.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        promoteEarly(sessionActivity)

        val e = container.createEngine() as ExoPlayerEngine
        engine = e
        container.orchestrator.attachEngine(e)
        routeJob =
            container.scope.launch {
                e.audioRoute.collect { app.mediaHub.publish(it) }
            }
        // Pre-warm the resumption table off the session looper: by the time
        // Media3 asks, onPlaybackResumption serves an immediate future.
        container.scope.launch {
            resumeFuture = Futures.immediateFuture(computeResumptionItems())
        }
        // Build initial buttons — disabled by default until first state emission corrects them.
        val initialButtons = buildMediaButtons(Transport(canNext = false, canPrev = false))
        // Every controller command routes through the Orchestrator: the Exo timeline is only a
        // 2-item window, not the queue, and the phase the app UI renders is the Orchestrator's —
        // a transport that skips it moves audio while the app still shows the old phase.
        val sessionPlayer =
            object : ForwardingPlayer(e.player) {
                override fun play() {
                    routeTransport(playing = true)
                }

                override fun pause() {
                    routeTransport(playing = false)
                }

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    routeTransport(playing = playWhenReady)
                }

                override fun seekTo(positionMs: Long) {
                    container.orchestrator.submit(dylan.playback.Intent.Seek(positionMs))
                }

                override fun seekToNext() {
                    container.orchestrator.submit(dylan.playback.Intent.Next)
                }

                override fun seekToNextMediaItem() {
                    container.orchestrator.submit(dylan.playback.Intent.Next)
                }

                override fun seekToPrevious() {
                    container.orchestrator.submit(dylan.playback.Intent.Previous)
                }

                override fun seekToPreviousMediaItem() {
                    container.orchestrator.submit(dylan.playback.Intent.Previous)
                }
            }
        session =
            MediaSession
                .Builder(this, sessionPlayer)
                .setSessionActivity(sessionActivity)
                .setCustomLayout(initialButtons)
                .setMediaButtonPreferences(initialButtons)
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult {
                            val projection = transportOf(container.orchestrator.state.value)
                            val buttons = buildMediaButtons(projection)
                            return MediaSession.ConnectionResult
                                .AcceptedResultBuilder(session, controller)
                                .setAvailablePlayerCommands(playerCommands(projection))
                                .setAvailableSessionCommands(sessionCommands())
                                .setCustomLayout(buttons)
                                .setMediaButtonPreferences(buttons)
                                .build()
                        }

                        override fun onCustomCommand(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            command: SessionCommand,
                            args: android.os.Bundle,
                        ): ListenableFuture<SessionResult> {
                            val c = DylanApp.of(this@DylanMediaService).container
                            when (command.customAction) {
                                CMD_NEXT -> c.orchestrator.submit(dylan.playback.Intent.Next)
                                CMD_PREV -> c.orchestrator.submit(dylan.playback.Intent.Previous)
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }

                        override fun onPlaybackResumption(
                            mediaSession: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                            // Never block: serve the pre-warmed future, or resolve
                            // async and complete. Slow I/O degrades to empty (no
                            // resume) instead of stalling Media3's timeout.
                            resumeFuture?.let { return it }
                            val c = DylanApp.of(this@DylanMediaService).container
                            val f =
                                androidx.concurrent.futures.ResolvableFuture
                                    .create<MediaSession.MediaItemsWithStartPosition>()
                            c.scope.launch { f.set(computeResumptionItems()) }
                            resumeFuture = f
                            return f
                        }
                    },
                ).build()
        addSession(session!!)

        // Keep notification buttons + available commands in sync with queue state.
        // Collecting orchestrator.state ensures custom layout is refreshed on every
        // player state change (queue mutation, repeat mode, shuffle) — not just onCreate.
        // This also advertises native COMMAND_SEEK_TO_NEXT/PREV so Vivo/Custom ROMs that
        // suppress pure-custom layouts still show transport controls, and keeps
        // DefaultMediaNotificationProvider from hiding buttons when expanded.
        stateJob =
            container.scope.launch {
                container.orchestrator.state.collect { state ->
                    val s = session ?: return@collect
                    val projection = transportOf(state)
                    val buttons = buildMediaButtons(projection)
                    runCatching {
                        s.setCustomLayout(buttons)
                        s.setMediaButtonPreferences(buttons)
                    }.onFailure { log.d("service", "custom layout rejected: ${it.message}") }
                    // The per-controller command set is what SystemUI reads; the session-wide
                    // setAvailableCommands has no ControllerInfo-free form.
                    val commands = playerCommands(projection)
                    for (controller in s.connectedControllers) {
                        runCatching {
                            s.setAvailableCommands(controller, sessionCommands(), commands)
                        }.onFailure { log.d("service", "available commands rejected: ${it.message}") }
                    }
                    stopWhenPlaybackEnds()
                }
            }
    }

    /**
     * FGS contract: startForegroundService() must see startForeground within seconds or the process
     * is killed, and Media3 promotes only when playback starts — an uncached first play exceeds the
     * window. So promote NOW, under Media3's OWN notification id + channel id: when
     * DefaultMediaNotificationManager later promotes, its startForeground(same id) replaces this
     * placeholder in place. Under any other id Media3's gated update path skips posting entirely.
     */
    private fun promoteEarly(sessionActivity: android.app.PendingIntent) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val mediaChannelId = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        nm.createNotificationChannel(
            android.app.NotificationChannel(mediaChannelId, "Dylan", android.app.NotificationManager.IMPORTANCE_LOW),
        )
        val quiet =
            android.app.Notification
                .Builder(this, mediaChannelId)
                .setSmallIcon(dylan.android.R.drawable.ic_stat_note)
                .setContentTitle("Dylan")
                .setContentText("Preparing playback…")
                .setContentIntent(sessionActivity)
                .setOngoing(true)
                .build()
        startForeground(
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            quiet,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /**
     * A foregrounded service is stopped by this rule alone: nothing else can. Before the task was
     * removed the app is alive to drive it, so the service is left running. After, it lives exactly
     * as long as playback is live — a paused or finished session stops it, which is what lets the
     * notification go. Called from the state collector, so it re-decides on every phase change
     * rather than on the one the swipe happened to land on.
     */
    private fun stopWhenPlaybackEnds() {
        if (!taskRemoved) return
        val phase = container.orchestrator.state.value.phase
        val live =
            phase is Phase.Playing ||
                phase is Phase.Ready ||
                phase is Phase.Resolving ||
                phase is Phase.Downloading
        if (live) return
        log.i("service", "task gone and playback $phase — stopping")
        stopSelf()
    }

    /**
     * [Player.play] / [Player.pause] / [Player.setPlayWhenReady] promise to *set* the transport, but
     * the Orchestrator exposes one intent that toggles it. Submit only when the shared phase does
     * not already answer the request, or a lock-screen Pause reads as "already paused" and does
     * nothing.
     */
    private fun routeTransport(playing: Boolean) {
        if (container.orchestrator.state.value.phase is Phase.Playing == playing) return
        container.orchestrator.submit(dylan.playback.Intent.TogglePlayPause)
    }

    /**
     * Whether Next/Previous would change what the user hears, from the same algebra the
     * Orchestrator navigates with: the maintained forward successor, the one-track restart, and
     * Previous's restart-past-3s rule. A `queue.size > 1 || repeat != OFF` threshold is a third
     * answer, and it is the wrong one in both directions.
     *
     * Previous stays conservative about the 3 s restart: `PlayerState.posMs` is written by seeks and
     * pauses, not by the position ticker, so mid-track playback reads as "at the start" and the
     * button is disabled rather than wrong.
     */
    private fun transportOf(state: PlayerState): Transport {
        val queued = state.queue.isNotEmpty()
        val previousSlot =
            PlayerState.nextIndexIn(
                state.queue,
                state.index,
                state.shuffleOrder,
                state.shuffleOn,
                state.repeat,
                -1,
            )
        return Transport(
            canNext = queued && (state.nextIndex != null || state.queue.size == 1),
            canPrev = queued && (previousSlot != null || state.posMs > RESTART_PREVIOUS_MS),
        )
    }

    private fun playerCommands(t: Transport): Player.Commands =
        Player.Commands
            .Builder()
            .addAll(Player.Commands.EMPTY)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            // Routed to the Orchestrator like every other transport command, so the lock-screen
            // scrub bar and the Bluetooth seek both move the shared position.
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, t.canNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, t.canNext)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, t.canPrev)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, t.canPrev)
            .build()

    private fun sessionCommands(): androidx.media3.session.SessionCommands =
        androidx.media3.session.SessionCommands
            .Builder()
            .add(SessionCommand(CMD_NEXT, android.os.Bundle.EMPTY))
            .add(SessionCommand(CMD_PREV, android.os.Bundle.EMPTY))
            .build()

    private fun buildMediaButtons(t: Transport): ImmutableList<CommandButton> {
        // Use proper ICON constants so getDefaultSlot() maps to SLOT_BACK/FORWARD, not OVERFLOW.
        // Slot assignment is critical: DefaultMediaNotificationProvider.getMediaButtons hides
        // OVERFLOW buttons in compact view and some OEMs (Vivo) drop them entirely.
        // We keep custom white icons (ic_prev/next) via setCustomIconResId for contrast on
        // dark notification background (media_notification_small_icon bg) but also declare slots.
        val prev =
            CommandButton
                .Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName("Previous")
                .setSessionCommand(SessionCommand(CMD_PREV, android.os.Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_BACK)
                .setEnabled(t.canPrev)
                .setCustomIconResId(dylan.android.R.drawable.ic_prev)
                .build()
        val next =
            CommandButton
                .Builder(CommandButton.ICON_NEXT)
                .setDisplayName("Next")
                .setSessionCommand(SessionCommand(CMD_NEXT, android.os.Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_FORWARD)
                .setEnabled(t.canNext)
                .setCustomIconResId(dylan.android.R.drawable.ic_next)
                .build()
        return ImmutableList.of(prev, next)
    }

    /**
     * Suspend resume-table builder: one settings read + one batched DB pass,
     * bounded by [RESUME_TIMEOUT_MS]. Replaces the old nested-runBlocking
     * resumptionItems() that serialized slow I/O on the dylan-resume thread.
     */
    private suspend fun computeResumptionItems(): MediaSession.MediaItemsWithStartPosition {
        val container = DylanApp.of(this).container
        val empty = MediaSession.MediaItemsWithStartPosition(ImmutableList.of<MediaItem>(), 0, 0L)
        return kotlinx.coroutines.withTimeoutOrNull(RESUME_TIMEOUT_MS) {
            val json = runCatching { container.settings.get("resume") }.getOrNull() ?: return@withTimeoutOrNull empty
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@withTimeoutOrNull empty
            val itemsJson = root["items"]?.jsonArray ?: return@withTimeoutOrNull empty
            val index = (root["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            val posMs = (root["posMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
            val keys =
                itemsJson.mapNotNull { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val providerId = o["provider"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val songId = o["songId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    dylan.model.SongKey(providerId, songId)
                }
            // Single dbLane pass for every row (was: one runBlocking per item).
            val rows =
                kotlinx.coroutines.withContext(container.disp.dbLane) {
                    keys.mapNotNull { key ->
                        val row =
                            runCatching {
                                container.db.dylanQueries
                                    .selectCached(key.provider, key.songId)
                                    .executeAsOneOrNull()
                            }.getOrNull() ?: return@mapNotNull null
                        key to row
                    }
                }
            val mediaItems =
                rows.mapNotNull { (key, row) ->
                    val path = container.paths.final(key, row.bitrate.toInt(), row.ext).toString()
                    if (!java.io.File(path).exists()) return@mapNotNull null
                    MediaItem
                        .fromUri(path)
                        .buildUpon()
                        .setMediaId("${key.provider}:${key.songId}:${row.bitrate}")
                        .build()
                }
            if (mediaItems.isEmpty()) {
                empty
            } else {
                MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(mediaItems), index.coerceIn(0, mediaItems.size - 1), posMs)
            }
        } ?: empty
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        taskRemoved = true
        // One decision here is not a decision: playback can end, or the user can pause, long after
        // the swipe. Arm the rule and let the state collector re-apply it on every phase change.
        log.i("service", "onTaskRemoved — playback decides when the service stops")
        stopWhenPlaybackEnds()
    }

    override fun onDestroy() {
        log.i("service", "onDestroy")
        resumeFuture?.cancel(false)
        resumeFuture = null
        routeJob?.cancel()
        routeJob = null
        stateJob?.cancel()
        stateJob = null
        DylanApp.of(this).mediaHub.publish(null)
        container.orchestrator.detachEngine()
        val s = session
        session = null
        val e = engine
        engine = null
        s?.let { removeSession(it) }
        if (e != null) {
            // The engine owns the media HandlerThread, the artwork scope and the AudioManager
            // device registration; only release() drops all three, and the Orchestrator's
            // dispose() — the other caller — is a process-lifetime path this service must not
            // reach. MediaSession.release() deliberately leaves the app's player alone, so
            // releasing the session and the engine is the whole teardown, once.
            e.postToMedia {
                s?.release()
                e.release()
            }
        }
        super.onDestroy()
    }
}
