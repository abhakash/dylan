@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("UnsafeOptInUsageError")

package dylan.android.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dylan.util.WorkOutcome
import dylan.util.launchSettled
import kotlinx.coroutines.CoroutineScope

/**
 * [BitmapLoader] for Media3's notification path, backed by the app's Coil singleton.
 *
 * This exists so `MediaMetadata.artworkUri` reaches the lock screen WITHOUT the player being
 * touched. The previous design fetched the cover with Coil, JPEG-compressed it, and pushed it in
 * with `player.replaceMediaItem(idx, item.copy(metadata = metadata + artworkData))` on the media
 * looper. That is a live-player mutation from inside an artwork callback, and it has two costs:
 *
 *  1. **It reports a track change that did not happen.** `MediaItem.equals` compares
 *     `mediaMetadata`, so a metadata-only replacement is a *different* item to ExoPlayer: it
 *     fires `onMediaItemTransition(MEDIA_ITEM_TRANSITION_REASON_CHANGED)` and re-creates the
 *     current `MediaPeriod`. This engine turns that into `EngineEvent.TrackChanged(sameId)`,
 *     which the Orchestrator answers by forcing `phase = Playing(current)` — so an artwork load
 *     landing *after* a user pause flips the shared phase back to Playing. The UI then shows a
 *     Pause glyph over silent audio, and the next Play is swallowed by the ForwardingPlayer's
 *     `routeTransport` (which sees "already playing" and submits nothing).
 *  2. **It rebuilds the playing period**, which is an audible discontinuity on a local file.
 *
 * Media3 has an API for exactly this since 1.1: give the session a `BitmapLoader` and it fetches
 * `artworkUri` itself, off the player, re-rendering the notification when the future completes
 * (`DefaultMediaNotificationProvider` wraps this loader in `SizeLimitedBitmapLoader` +
 * `CacheBitmapLoader`, so at most one scaled bitmap is alive at a time).
 *
 * Coil stays the single fetcher — the same singleton, memory cache and disk cache as the UI — so
 * a cover that the list already decoded is a memory-cache hit here.
 *
 * Lives on [scope] (the engine's IO scope): `loadBitmap` is called from Media3's notification
 * path and must not block it, and Coil's `execute` is blocking. Cancelled with the engine — and
 * **every future settles when the coroutine it belongs to settles**, including the case where
 * `ExoPlayerEngine.release()` already cancelled the scope before Media3's notification path asked.
 * `launchSettled` ties the report to the job's *completion* rather than to the body running,
 * because a `SettableFuture` nobody settles is retained by Media3's `CacheBitmapLoader` for the
 * life of the process and the notification's artwork update is then permanently pending.
 */
internal class ArtworkBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    /**
     * Only reached if some metadata still carries `artworkData`. Nothing in this app sets it after
     * the player-mutation path was removed, but the contract is total, so decode rather than throw.
     */
    override fun decodeBitmap(bitmapData: ByteArray): ListenableFuture<Bitmap> {
        val out = newFuture()
        scope.launchSettled({ settle(out, it) }) {
            BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.size)
                ?: throw IllegalArgumentException("artworkData is not a decodable image")
        }
        return out
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val out = newFuture()
        scope.launchSettled({ settle(out, it) }) { fetch(uri.toString()) }
        return out
    }

    private fun settle(
        out: SettableFuture<Bitmap>,
        outcome: WorkOutcome<Bitmap>,
    ) {
        when (outcome) {
            is WorkOutcome.Value -> out.set(outcome.value)
            is WorkOutcome.Failure -> out.setException(outcome.error)
            // `cancel`, not `setException(CancellationException)`: Guava's two are distinct states
            // and a future in the cancelled state is one Media3 drops rather than logs.
            WorkOutcome.Cancelled -> out.cancel(false)
        }
    }

    private suspend fun fetch(url: String): Bitmap {
        val loader = coil3.SingletonImageLoader.get(context)
        val request =
            coil3.request.ImageRequest
                .Builder(context)
                .data(url)
                .size(LOAD_PX)
                .build()
        val raw =
            (loader.execute(request).image as? coil3.BitmapImage)?.bitmap
                ?: error("no bitmap for $url")
        // NotificationCompat cannot parcel a HARDWARE-config bitmap into RemoteViews; copy it out.
        if (raw.config == Bitmap.Config.HARDWARE) {
            return raw.copy(Bitmap.Config.ARGB_8888, false) ?: error("hardware bitmap not copyable")
        }
        return raw
    }

    private fun newFuture(): SettableFuture<Bitmap> = SettableFuture.create()

    private companion object {
        /** Covers are decoded once at this size; Media3 scales down for the notification icon. */
        const val LOAD_PX = 512
    }
}
