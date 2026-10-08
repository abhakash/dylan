// Media3's `ExoPlayer.Builder.setLooper` and the session-side types this file names are
// @UnstableApi. `@file:OptIn` tells the Kotlin compiler; lint reads `@file:Suppress`, which is
// what keeps `UnsafeOptInUsageError` off this file's lint gate (the same pair
// DylanMediaService.kt uses) instead of a baseline entry pinned to a line number.
@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("UnsafeOptInUsageError")

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
import dylan.playback.clampPlaybackRate
import dylan.playback.clampSeekTargetMs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class ExoPlayerEngine(
    context: Context,
) : PlayerEngine {
    private val appContext = context.applicationContext
    private val log = (appContext as dylan.android.DylanApp).container.log
    private val thread = HandlerThread("dylan-media").apply { start() }

    /** Lives for the engine; [release] cancels it, which cancels every in-flight artwork load. */
    private val artScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )

    /**
     * Notification/lock-screen artwork: Media3 renders `artworkData` but never fetches remote
     * `artworkUri` itself, so the session is handed this loader and does the fetch itself, off the
     * player. This engine deliberately does NOT push artwork in through `replaceMediaItem`: a
     * metadata-only replacement is a different `MediaItem` to ExoPlayer (its `equals` compares
     * `mediaMetadata`), so it fires `onMediaItemTransition(REASON_CHANGED)` and re-creates the
     * playing `MediaPeriod` — an audible discontinuity plus a `TrackChanged` event for a track
     * that did not change.
     *
     * The loader goes through the singleton configured in [dylan.android.DylanApp]; a per-load
     * `ImageLoader` would own its own (empty) memory cache, disk cache and HTTP client, so every
     * prepare re-fetched and re-decoded the same cover.
     */
    val artworkLoader: androidx.media3.common.util.BitmapLoader = ArtworkBitmapLoader(appContext, artScope)

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

    /**
     * OS output telemetry, mirrored to the UI by [MediaHub] rather than folded into the shared
     * `PlayerState`; `MediaHub`'s KDoc carries the reasoning.
     */
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
                                    emitItemEndedForPredecessor()
                                    dylan.playback.TransitionReason.AUTO
                                }
                                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> dylan.playback.TransitionReason.SEEK
                                else -> dylan.playback.TransitionReason.EXPLICIT
                            }
                        emit(EngineEvent.TrackChanged(id, tr))
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val itemId = player.currentMediaItem?.mediaId
                        log.e("exo", "playerError item=$itemId code=${error.errorCode} ${error.message ?: ""}")
                        val kind =
                            if (error.errorCode in 2000..2999) {
                                dylan.playback.EngineErr.SOURCE
                            } else {
                                dylan.playback.EngineErr.DECODE
                            }
                        emit(EngineEvent.Error(itemId, kind))
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        pollPosition()
                    }

                    /**
                     * Media3 pauses for one reason the Orchestrator did not ask for and never resumes
                     * on its own: audio became noisy (headphones/BT dropped). Without this event the
                     * shared phase keeps claiming `Playing`, so a lock-screen Play — routed through
                     * the Orchestrator, so the app UI can follow it — reads as "already playing" and
                     * pauses instead of resuming. Focus loss is deliberately NOT reported: Media3
                     * auto-resumes on `AUDIOFOCUS_GAIN`, and the Orchestrator has no event that
                     * moves the phase forward with it.
                     */
                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int,
                    ) {
                        if (playWhenReady) return
                        if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) return
                        emit(EngineEvent.Interrupted(shouldResume = false))
                    }
                },
            )
            pollPosition()
        }
    }

    private fun pollPosition() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POSITION_POLL_INTERVAL_MS)
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

    /**
     * An auto-advance transition does not name the item Media3 left behind, so it is read back off
     * the index the player is now on. Index 0 has no predecessor, so nothing is emitted there.
     */
    private fun emitItemEndedForPredecessor() {
        val index = player.currentMediaItemIndex
        if (index <= 0) return
        emit(EngineEvent.ItemEnded(player.getMediaItemAt(index - 1).mediaId))
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

    /**
     * Speed only — `PlaybackParameters` carries `playWhenReady` unchanged, and Media3's setter
     * has no AVPlayer-style "a positive rate starts playback" behaviour, so this cannot resume a
     * paused player. On the media thread like every other player call (§9.9), and NaN/±Inf are
     * dropped by [clampPlaybackRate] before they can reach `withSpeed` (which validates but does
     * not reject NaN).
     */
    override fun setRate(rate: Float) {
        val speed = clampPlaybackRate(rate) ?: return
        handler.post {
            val p = player.playbackParameters
            if (p.speed == speed) return@post
            player.playbackParameters = p.withSpeed(speed)
        }
    }

    /**
     * Overridden rather than taking `PlayerEngine.skipBy`'s default, which would read
     * [currentTimeMs] — the 10 Hz [pollRunnable] sample, i.e. up to 100 ms stale — from the
     * caller's thread. `player.currentPosition` is read where it is authoritative, on the media
     * looper.
     *
     * `player.duration` is [C.TIME_UNSET] until the item is ready, which `clampSeekTargetMs`
     * reads as unknown, so a skip on a not-yet-ready item still seeks instead of collapsing to 0.
     */
    override fun skipBy(deltaMs: Long) {
        handler.post {
            val base = player.currentPosition
            if (base < 0L) return@post
            player.seekTo(clampSeekTargetMs(base + deltaMs, player.duration))
        }
    }

    override fun release() {
        handler.post {
            artScope.cancel()
            routes.release()
            player.release()
            thread.quitSafely()
        }
    }

    companion object {
        /** The 10 Hz [pollRunnable] reschedules itself at, and the staleness ceiling it imposes. */
        private const val POSITION_POLL_INTERVAL_MS = 100L

        /** A media item id is `provider:trackId:<variant>`; anything else is not a [SongKey]. */
        private const val SONG_KEY_SEGMENT_COUNT = 3

        fun keyOf(itemId: String): SongKey? {
            val parts = itemId.split(":")
            return parts.takeIf { it.size == SONG_KEY_SEGMENT_COUNT }?.let { SongKey(it[0], it[1]) }
        }
    }
}
