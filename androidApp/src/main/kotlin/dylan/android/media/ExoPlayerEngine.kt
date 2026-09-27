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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ExoPlayerEngine(
    context: Context,
) : PlayerEngine {
    private val appContext = context.applicationContext
    private val log = (appContext as dylan.android.DylanApp).container.log
    private val thread = HandlerThread("dylan-media").apply { start() }

    /**
     * Notification/lock-screen artwork: Media3 renders `artworkData` bitmaps but
     * never fetches remote `artworkUri` itself. Each prepared item gets a Coil
     * load (software bitmap, 512px); on success the item's metadata is replaced
     * so the notification refreshes. Loads are per-itemId cancellable and die
     * with prepare()/release().
     */
    private val artScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
    private val artJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun loadArtwork(
        itemId: String,
        url: String?,
    ) {
        artJobs.remove(itemId)?.cancel()
        if (url.isNullOrBlank()) return
        artJobs[itemId] =
            artScope.launch {
                // MediaMetadata.artworkData is a byte[] — decode via Coil, ship JPEG
                // bytes and let Media3's notification manager decode the large icon.
                val bytes =
                    runCatching {
                        val loader = coil3.ImageLoader(appContext)
                        val req =
                            coil3.request.ImageRequest
                                .Builder(appContext)
                                .data(url)
                                .size(512)
                                .build()
                        val raw = (loader.execute(req).image as? coil3.BitmapImage)?.bitmap ?: return@launch
                        // compress() throws on HARDWARE-config bitmaps — copy out first.
                        val bitmap =
                            if (raw.config == android.graphics.Bitmap.Config.HARDWARE) {
                                raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return@launch
                            } else {
                                raw
                            }
                        val out = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }.getOrNull() ?: return@launch
                postToMedia {
                    val idx = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == itemId }
                    if (idx != null) {
                        val cur = player.getMediaItemAt(idx)
                        val meta =
                            cur.mediaMetadata
                                .buildUpon()
                                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                        player.replaceMediaItem(idx, cur.buildUpon().setMediaMetadata(meta).build())
                    }
                }
            }
    }

    private fun cancelArtwork() {
        artJobs.values.forEach { it.cancel() }
        artJobs.clear()
    }

    private val handler = Handler(thread.looper)

    /** All Media3 session/player calls must run on this looper (§9.9). */
    internal val mediaLooper: android.os.Looper = thread.looper

    internal fun postToMedia(block: () -> Unit) {
        handler.post(block)
    }

    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<EngineEvent> = mutableEvents

    private val mutablePosition = MutableStateFlow(0L)
    override val positionFlow: StateFlow<Long> = mutablePosition

    override fun currentTimeMs(): Long = mutablePosition.value

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
        if (!mutableEvents.tryEmit(e)) log.w("exo", "events buffer full, dropped $e")
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
            cancelArtwork()
            val items = window.map(::buildMediaItem)
            if (items.isEmpty()) player.clearMediaItems() else player.setMediaItems(items)
            player.prepare()
            window.forEach { loadArtwork(it.itemId, it.artworkUri) }
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
            track?.let { loadArtwork(it.itemId, it.artworkUri) }
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
            cancelArtwork()
            artScope.cancel()
            routes.release()
            player.release()
            thread.quitSafely()
        }
    }

    companion object {
        fun keyOf(itemId: String): SongKey? = itemId.split(":").takeIf { it.size == 3 }?.let { SongKey(it[0], it[1]) }
    }
}
