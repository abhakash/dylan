package dylan.android.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-scoped mirror of the live engine's audio route. The engine is created inside
 * DylanMediaService, out of the UI's reach; the service binds it here on create and
 * clears on destroy so Now Playing can render "Playing on ⟨device⟩".
 */
class MediaHub {
    private val backing = MutableStateFlow<AudioRoute?>(null)
    val audioRoute: StateFlow<AudioRoute?> = backing

    internal fun publish(route: AudioRoute?) {
        backing.value = route
    }
}
