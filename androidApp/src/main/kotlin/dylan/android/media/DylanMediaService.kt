package dylan.android.media

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dylan.android.DylanApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DylanMediaService : MediaSessionService() {
    private var session: MediaSession? = null
    private var engine: ExoPlayerEngine? = null
    private var routeJob: kotlinx.coroutines.Job? = null
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
        // Explicit provider ensures channel/id match our early startForeground placeholder.
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
        // DefaultMediaNotificationProvider uses the session activity as the notification's
        // content intent — without it, tapping the media notification does nothing.
        val sessionActivity =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, dylan.android.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        session =
            MediaSession
                .Builder(this, e.player)
                .setSessionActivity(sessionActivity)
                .setCustomLayout(
                    ImmutableList.of(
                        androidx.media3.session.CommandButton
                            .Builder()
                            .setDisplayName("Previous")
                            .setSessionCommand(androidx.media3.session.SessionCommand(CMD_PREV, android.os.Bundle.EMPTY))
                            .setIconResId(dylan.android.R.drawable.ic_prev)
                            .build(),
                        androidx.media3.session.CommandButton
                            .Builder()
                            .setDisplayName("Next")
                            .setSessionCommand(androidx.media3.session.SessionCommand(CMD_NEXT, android.os.Bundle.EMPTY))
                            .setIconResId(dylan.android.R.drawable.ic_next)
                            .build(),
                    ),
                ).setCallback(
                    object : MediaSession.Callback {
                        override fun onCustomCommand(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            command: androidx.media3.session.SessionCommand,
                            args: android.os.Bundle,
                        ): ListenableFuture<androidx.media3.session.SessionResult> {
                            val container = DylanApp.of(this@DylanMediaService).container
                            when (command.customAction) {
                                CMD_NEXT -> container.orchestrator.submit(dylan.playback.Intent.Next)
                                CMD_PREV -> container.orchestrator.submit(dylan.playback.Intent.Previous)
                            }
                            return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
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
