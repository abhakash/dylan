package dylan.android.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dylan.model.SongKey
import dylan.playback.EngineEvent
import dylan.playback.LocalTrack
import dylan.playback.PlayerEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class ExoPlayerEngine(
    context: Context,
) : PlayerEngine {
    private val log = (context.applicationContext as dylan.android.DylanApp).container.log
    private val thread = HandlerThread("dylan-media").apply { start() }
    private val handler = Handler(thread.looper)

    /** All Media3 session/player calls must run on this looper (§9.9). */
    internal val mediaLooper: android.os.Looper = thread.looper

    internal fun postToMedia(block: () -> Unit) {
        handler.post(block)
    }

    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<EngineEvent> = mutableEvents

    private val mutablePosition = MutableStateFlow(0L)
    override val positionFlow: StateFlow<Long> = mutablePosition

    private val routes =
        AudioRouteMonitor(
            audioManager = context.getSystemService(android.media.AudioManager::class.java),
            handler = handler,
            onRouteLost = { emit(EngineEvent.RouteLost) },
        )
    val audioRoute: StateFlow<AudioRoute?> = routes.route

    val player: ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setLooper(thread.looper)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            ).setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

    init {
        handler.post {
            player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            log.i("exo", "prepared item=${player.currentMediaItem?.mediaId} durMs=${player.duration}")
                            player.currentMediaItem?.mediaId?.let { emit(EngineEvent.Prepared(it)) }
                        }
                        if (state == Player.STATE_ENDED) emit(EngineEvent.QueueExhausted)
                    }

                    override fun onMediaItemTransition(
                        item: MediaItem?,
                        reason: Int,
                    ) {
                        val id = item?.mediaId ?: return
                        val tr =
                            when (reason) {
                                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                                    player.currentMediaItemIndex.let { idx ->
                                        if (idx >
                                            0
                                        ) {
                                            player.getMediaItemAt(idx - 1).mediaId.let { prev -> emit(EngineEvent.ItemEnded(prev)) }
                                        }
                                    }
                                    dylan.playback.TransitionReason.AUTO
                                }
                                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> dylan.playback.TransitionReason.SEEK
                                else -> dylan.playback.TransitionReason.EXPLICIT
                            }
                        emit(EngineEvent.TrackChanged(id, tr))
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        log.e("exo", "playerError item=${player.currentMediaItem?.mediaId} code=${error.errorCode} ${error.message ?: ""}")
                        val kind = if (error.errorCode in 2000..2999) dylan.playback.EngineErr.SOURCE else dylan.playback.EngineErr.DECODE
                        emit(EngineEvent.Error(player.currentMediaItem?.mediaId, kind))
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        pollPosition()
                    }
                },
            )
            pollPosition()
        }
    }

    private fun pollPosition() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 100)
    }

    private val pollRunnable =
        object : Runnable {
            override fun run() {
                if (player.isPlaying) {
                    mutablePosition.value = player.currentPosition.coerceAtLeast(0L)
                    pollPosition()
                } else {
                    mutablePosition.value = player.currentPosition.coerceAtLeast(0L)
                }
            }
        }

    private fun emit(e: EngineEvent) {
        mutableEvents.tryEmit(e)
    }

    private fun buildMediaItem(t: LocalTrack): MediaItem {
        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setArtworkUri(t.artworkUri?.let(Uri::parse))
                .build()
        return MediaItem
            .fromUri(t.path)
            .buildUpon()
            .setMediaId(t.itemId)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun prepare(window: List<LocalTrack>) {
        handler.post {
            val items = window.map(::buildMediaItem)
            if (items.isEmpty()) player.clearMediaItems() else player.setMediaItems(items)
            player.prepare()
        }
    }

    override fun replaceUpNext(track: LocalTrack?) {
        handler.post {
            val item = track?.let(::buildMediaItem)
            val count = player.mediaItemCount
            when {
                item == null && count >= 2 -> player.removeMediaItems(1, count)
                item == null -> {}
                count >= 2 -> player.replaceMediaItem(1, item)
                else -> player.addMediaItem(1, item)
            }
        }
    }

    override fun play() {
        handler.post { player.play() }
    }

    override fun pause() {
        handler.post { player.pause() }
    }

    override fun seekTo(ms: Long) {
        handler.post { player.seekTo(ms) }
    }

    override fun release() {
        handler.post {
            routes.release()
            player.release()
            thread.quitSafely()
        }
    }

    companion object {
        fun keyOf(itemId: String): SongKey? = itemId.split(":").takeIf { it.size == 3 }?.let { SongKey(it[0], it[1]) }
    }
}
