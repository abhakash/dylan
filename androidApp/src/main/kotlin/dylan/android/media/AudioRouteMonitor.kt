package dylan.android.media

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RouteKind { BLUETOOTH, WIRED, SPEAKER }

data class AudioRoute(
    val kind: RouteKind,
    val productName: String?,
)

/**
 * Tracks the active output route and reports unplug of a playback device so the engine can
 * emit EngineEvent.RouteLost (§9.10). Callbacks arrive on the handler's looper.
 */
internal class AudioRouteMonitor(
    private val audioManager: AudioManager,
    handler: Handler,
    private val onRouteLost: () -> Unit,
) {
    private val backing = MutableStateFlow<AudioRoute?>(null)
    val route: StateFlow<AudioRoute?> = backing

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.isPlaybackOutput }) onRouteLost()
                refresh()
            }
        }

    init {
        // Registration replays current outputs through onAudioDevicesAdded, seeding the flow.
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(callback)
        backing.value = null
    }

    private fun refresh() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        backing.value =
            outputs.firstOrNull { it.isBluetoothOutput }?.let { AudioRoute(RouteKind.BLUETOOTH, it.label()) }
                ?: outputs.firstOrNull { it.isWiredOutput }?.let { AudioRoute(RouteKind.WIRED, it.label()) }
                ?: AudioRoute(RouteKind.SPEAKER, null)
    }

    private val AudioDeviceInfo.isPlaybackOutput: Boolean
        get() = isBluetoothOutput || isWiredOutput

    private val AudioDeviceInfo.isBluetoothOutput: Boolean
        get() =
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

    private val AudioDeviceInfo.isWiredOutput: Boolean
        get() =
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun AudioDeviceInfo.label(): String? = productName.toString().trim().takeIf { it.isNotEmpty() }
}
