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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val resumptionExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "dylan-resume").apply { isDaemon = true }
        }

    companion object {
        // Custom-layout transport commands — the queue lives in the Orchestrator, not the
        // Exo timeline, so next/prev must route here regardless of timeline state.
        private const val CMD_NEXT = "dylan.NEXT"
        private const val CMD_PREV = "dylan.PREVIOUS"
    }

    // (Deleted the no-op DylanNotificationProvider — it only forwarded to super.)

    override fun onCreate() {
        super.onCreate()
        val appLog = DylanApp.of(this).container.log
        appLog.i("service", "onCreate")
        // FGS contract: startForegroundService() must see startForeground within seconds.
        // Media3 promotes only when playback starts — an uncached download exceeds the window
        // and kills the process (ForegroundServiceDidNotStartInTimeException).
        //
        // We therefore promote NOW, but under Media3's OWN notification id + channel id:
        // when DefaultMediaNotificationManager later promotes, its startForeground(1001, …)
        // replaces this placeholder in place. Promoting under any other id makes Media3's
        // gated update path skip posting entirely (no MediaStyle, no lock-screen controls).
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
                .setOngoing(true)
                .build()
        startForeground(
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            quiet,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        val container = DylanApp.of(this).container
        val app = DylanApp.of(this)
        val e = container.createEngine() as ExoPlayerEngine
        engine = e
        container.orchestrator.attachEngine(e)
        routeJob =
            container.scope.launch {
                e.audioRoute.collect { app.mediaHub.publish(it) }
            }
        // DefaultMediaNotificationProvider uses the session activity as the notification's
        // content intent — without it, tapping the media notification does nothing.
        val sessionActivity =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, dylan.android.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        // Build initial buttons — disabled by default until first state emission corrects them.
        val initialButtons = buildMediaButtons(canNext = false, canPrev = false)
        // Native seek/next/prev (notification buttons, BT, lock-screen) must route through
        // the Orchestrator — the Exo timeline is only a 2-item window, not the queue.
        val sessionPlayer =
            object : ForwardingPlayer(e.player) {
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
                            val state = DylanApp.of(this@DylanMediaService).container.orchestrator.state.value
                            val canNext = state.queue.size > 1 || state.repeat != dylan.model.Repeat.OFF
                            val canPrev = state.queue.size > 1 || state.repeat != dylan.model.Repeat.OFF
                            val buttons = buildMediaButtons(canNext, canPrev)
                            val playerCommands =
                                Player.Commands.Builder()
                                    .addAll(Player.Commands.EMPTY)
                                    .add(Player.COMMAND_PLAY_PAUSE)
                                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                                    .add(Player.COMMAND_GET_TIMELINE)
                                    .add(Player.COMMAND_GET_METADATA)
                                    .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, canNext)
                                    .addIf(Player.COMMAND_SEEK_TO_NEXT, canNext)
                                    .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, canPrev)
                                    .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, canPrev)
                                    .build()
                            val sessionCommands =
                                androidx.media3.session.SessionCommands.Builder()
                                    .add(SessionCommand(CMD_NEXT, android.os.Bundle.EMPTY))
                                    .add(SessionCommand(CMD_PREV, android.os.Bundle.EMPTY))
                                    .build()
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                                .setAvailablePlayerCommands(playerCommands)
                                .setAvailableSessionCommands(sessionCommands)
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
                        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                            // DB reads must never block the session looper — resolve on a worker.
                            // Explicit Callable SAM: the Runnable overload would infer Void and
                            // discard the MediaItemsWithStartPosition.
                            Futures.submit(
                                java.util.concurrent.Callable<MediaSession.MediaItemsWithStartPosition> { resumptionItems() },
                                resumptionExecutor,
                            )
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
                    // Heuristic from spec: available if queue >1 or repeat != OFF.
                    // Using nextUp/prev logic would also work but spec demands this threshold.
                    val canNext = state.queue.size > 1 || state.repeat != dylan.model.Repeat.OFF
                    val canPrev = state.queue.size > 1 || state.repeat != dylan.model.Repeat.OFF
                    val buttons = buildMediaButtons(canNext, canPrev)
                    try {
                        s.setCustomLayout(buttons)
                        s.setMediaButtonPreferences(buttons)
                    } catch (_: Exception) {
                    }
                    // Advertise native commands so SystemUI / Vivo shows next/prev.
                    // Handle onSeekToNext via onMediaItemTransition (engine already emits TrackChanged)
                    // but also update MediaSession available commands per controller — global
                    // setAvailableCommands requires a ControllerInfo, so iterate connected ones.
                    try {
                        val playerCommands =
                            Player.Commands.Builder()
                                .addAll(Player.Commands.EMPTY)
                                .add(Player.COMMAND_PLAY_PAUSE)
                                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                                .add(Player.COMMAND_GET_TIMELINE)
                                .add(Player.COMMAND_GET_METADATA)
                                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, canNext)
                                .addIf(Player.COMMAND_SEEK_TO_NEXT, canNext)
                                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, canPrev)
                                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, canPrev)
                                .build()
                        val sessionCommands =
                            androidx.media3.session.SessionCommands.Builder()
                                .add(SessionCommand(CMD_NEXT, android.os.Bundle.EMPTY))
                                .add(SessionCommand(CMD_PREV, android.os.Bundle.EMPTY))
                                .build()
                        for (controller in s.connectedControllers) {
                            try {
                                s.setAvailableCommands(controller, sessionCommands, playerCommands)
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
    }

    private fun buildMediaButtons(canNext: Boolean, canPrev: Boolean): ImmutableList<CommandButton> {
        // Use proper ICON constants so getDefaultSlot() maps to SLOT_BACK/FORWARD, not OVERFLOW.
        // Slot assignment is critical: DefaultMediaNotificationProvider.getMediaButtons hides
        // OVERFLOW buttons in compact view and some OEMs (Vivo) drop them entirely.
        // We keep custom white icons (ic_prev/next) via setCustomIconResId for contrast on
        // dark notification background (media_notification_small_icon bg) but also declare slots.
        val prev =
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName("Previous")
                .setSessionCommand(SessionCommand(CMD_PREV, android.os.Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_BACK)
                .setEnabled(canPrev)
                .setCustomIconResId(dylan.android.R.drawable.ic_prev)
                .build()
        val next =
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName("Next")
                .setSessionCommand(SessionCommand(CMD_NEXT, android.os.Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_FORWARD)
                .setEnabled(canNext)
                .setCustomIconResId(dylan.android.R.drawable.ic_next)
                .build()
        return ImmutableList.of(prev, next)
    }

    private fun resumptionItems(): MediaSession.MediaItemsWithStartPosition {
        val container = DylanApp.of(this).container
        val empty = MediaSession.MediaItemsWithStartPosition(ImmutableList.of<MediaItem>(), 0, 0L)
        val json = runBlocking { runCatching { container.settings.get("resume") }.getOrNull() } ?: return empty
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val itemsJson = root["items"]?.jsonArray ?: return empty
            val index = (root["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            val posMs = (root["posMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
            val mediaItems =
                itemsJson.mapNotNull { el ->
                    val o = el.jsonObject
                    val providerId = o["provider"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val songId = o["songId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val key = dylan.model.SongKey(providerId, songId)
                    val row =
                        runBlocking {
                            runCatching {
                                container.db.dylanQueries
                                    .selectCached(key.provider, key.songId)
                                    .executeAsOneOrNull()
                            }.getOrNull()
                        } ?: return@mapNotNull null
                    val path = container.paths.final(key, row.bitrate.toInt(), row.ext).toString()
                    if (!java.io.File(path).exists()) return@mapNotNull null
                    MediaItem
                        .fromUri(path)
                        .buildUpon()
                        .setMediaId("$providerId:$songId:${row.bitrate}")
                        .build()
                }
            if (mediaItems.isEmpty()) {
                empty
            } else {
                MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(mediaItems), index.coerceIn(0, mediaItems.size - 1), posMs)
            }
        } catch (_: Exception) {
            empty
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Media3 player/session access must run on the media looper (§9.9) — never the binder/main thread.
        val e = engine ?: return super.onTaskRemoved(rootIntent)
        e.postToMedia {
            val p = session?.player
            val stop = p == null || !p.playWhenReady || p.mediaItemCount == 0
            DylanApp
                .of(this@DylanMediaService)
                .container.log
                .i("service", "onTaskRemoved stop=$stop")
            if (stop) stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DylanApp
            .of(this)
            .container.log
            .i("service", "onDestroy")
        resumptionExecutor.shutdownNow()
        val c = DylanApp.of(this).container
        routeJob?.cancel()
        routeJob = null
        stateJob?.cancel()
        stateJob = null
        DylanApp.of(this).mediaHub.publish(null)
        c.orchestrator.detachEngine()
        val s = session
        session = null
        s?.let { removeSession(it) }
        engine?.postToMedia {
            s?.run {
                player.release()
                release()
            }
        }
        super.onDestroy()
    }
}
