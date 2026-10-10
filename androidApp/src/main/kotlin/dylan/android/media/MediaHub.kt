package dylan.android.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-scoped mirror of the live engine's audio route. The engine is created inside
 * DylanMediaService, out of the UI's reach; the service binds it here on create and
 * clears on destroy so Now Playing can render "Playing on ⟨device⟩".
 *
 * Deliberately NOT a `dylan.model.PlayerState` field, which `docs/codebase-audit.md` §3.6 asks for.
 * Checked against the tree before declining it:
 *
 *  - **No shared consumer.** The only reader of the value is `NowPlayingSheet`'s "PLAYING ON ⟨⟩"
 *    chip. The route fact that *does* change behaviour — a playback device unplugged — already
 *    reaches shared logic as `EngineEvent.RouteLost` → `Orchestrator.holdPosition()`.
 *  - **`AudioRoute` is an `AudioDeviceInfo` projection** (`isBluetoothOutput` / `isWiredOutput` in
 *    `AudioRouteMonitor`), so a shared copy would be a platform-shaped enum with no shared producer.
 *  - **iOS has nothing to publish.** `NativeAudioOutputImpl` observes
 *    `AVAudioSession.routeChangeNotification` but only emits `routeLost()`; it never reads
 *    `currentRoute`. A shared field would therefore be permanently `null` on iOS — less honest,
 *    not more shared.
 *  - **The churn premise is false.** `PlayerState.posMs` is *not* written at 10 Hz (the live clock is
 *    a separate `positionMs` flow, and every `posMs` write is a seek/navigation/snapshot), so
 *    `PlayerState`'s generated `equals` already changes on every write. Adding a rarely-changing
 *    field costs one emission per real route change, not a stream of them.
 *
 * The real cost of moving it is the second writer: `_state` today has exactly one write discipline
 * (`Orchestrator.process` serialises on `Lane.STATE`, and the class forbids a read → suspend → write),
 * and `PlayerState.withQueueMutation` rebuilds through an explicit field list, so a new field silently
 * resets to `null` at six sites unless every one is threaded. A side channel has neither problem.
 */
class MediaHub {
    private val backing = MutableStateFlow<AudioRoute?>(null)
    val audioRoute: StateFlow<AudioRoute?> = backing

    internal fun publish(route: AudioRoute?) {
        backing.value = route
    }
}
