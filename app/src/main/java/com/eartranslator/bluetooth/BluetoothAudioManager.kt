package com.eartranslator.bluetooth

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Owns everything Bluetooth-audio related:
 *  - opens the [BluetoothHeadset] (HFP/SCO) and [BluetoothA2dp] profile proxies to
 *    enumerate connected earbuds,
 *  - manages the SCO link lifecycle (SCO is required to get a usable mic input from a
 *    BT headset; A2DP is output-only and gives no mic),
 *  - holds the Person A / Person B device assignment, and
 *  - resolves an assigned [BluetoothDevice] to the concrete [AudioDeviceInfo] used by
 *    AudioRecord.setPreferredDevice() / AudioTrack.setPreferredDevice() for routing.
 *
 * IMPORTANT — SCO vs A2DP:
 *  SCO (Synchronous Connection-Oriented) is a low-bandwidth, bidirectional voice link
 *  (8/16 kHz). It is the ONLY way to capture mic audio from a BT headset. We turn it on
 *  with [AudioManager.startBluetoothSco]. A2DP is a high-quality, output-only stereo
 *  profile — great for music playback but it carries no microphone channel. For a
 *  real-time two-way translator we must run on SCO so both the mic and the routed
 *  playback share the voice link.
 */
class BluetoothAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "BtAudioManager"
    }

    enum class Slot { PERSON_A, PERSON_B }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val btManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? = btManager?.adapter

    @Volatile private var headsetProxy: BluetoothHeadset? = null
    @Volatile private var a2dpProxy: BluetoothA2dp? = null

    /** Device assigned to each conversation slot (chosen in the UI). */
    private val slotAssignment = HashMap<Slot, BluetoothDevice>()

    @Volatile private var scoStarted = false

    // region Permissions ---------------------------------------------------------

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT is only enforced on API 31+. Below that the legacy
        // BLUETOOTH permission is install-time and always granted if declared.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // endregion

    // region Profile proxies -----------------------------------------------------

    /**
     * Opens the HFP and A2DP profile proxies. Proxy connection is async, so [onReady]
     * fires once the headset proxy (the one we actually need for enumeration) is up.
     */
    fun openProfiles(onReady: () -> Unit) {
        val a = adapter
        if (a == null) {
            Log.w(TAG, "No Bluetooth adapter on this device")
            return
        }
        if (!hasConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }

        a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.HEADSET -> {
                        headsetProxy = proxy as BluetoothHeadset
                        Log.d(TAG, "HEADSET proxy connected")
                        onReady()
                    }
                    BluetoothProfile.A2DP -> {
                        a2dpProxy = proxy as BluetoothA2dp
                        Log.d(TAG, "A2DP proxy connected")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    BluetoothProfile.HEADSET -> headsetProxy = null
                    BluetoothProfile.A2DP -> a2dpProxy = null
                }
            }
        }, BluetoothProfile.HEADSET)

        a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy as BluetoothA2dp
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) a2dpProxy = null
            }
        }, BluetoothProfile.A2DP)
    }

    fun closeProfiles() {
        val a = adapter ?: return
        headsetProxy?.let { a.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dpProxy?.let { a.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headsetProxy = null
        a2dpProxy = null
    }

    /**
     * Union of devices connected over HFP and A2DP. Earbuds typically appear on both;
     * we de-dupe by address.
     */
    fun connectedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        val byAddress = LinkedHashMap<String, BluetoothDevice>()
        headsetProxy?.connectedDevices?.forEach { byAddress[it.address] = it }
        a2dpProxy?.connectedDevices?.forEach { byAddress.putIfAbsent(it.address, it) }
        return byAddress.values.toList()
    }

    fun deviceLabel(device: BluetoothDevice): String {
        return if (hasConnectPermission()) {
            (device.name ?: device.address)
        } else device.address
    }

    // endregion

    // region Slot assignment -----------------------------------------------------

    fun assignSlot(slot: Slot, device: BluetoothDevice) {
        slotAssignment[slot] = device
        Log.d(TAG, "Assigned ${deviceLabel(device)} -> $slot")
    }

    fun deviceForSlot(slot: Slot): BluetoothDevice? = slotAssignment[slot]

    // endregion

    // region SCO lifecycle -------------------------------------------------------

    /**
     * Turns on the SCO voice link so AudioRecord can read the BT mic. On API 31+ the
     * platform prefers communication-device APIs, but startBluetoothSco() remains the
     * reliable cross-version path for forcing SCO up for capture.
     */
    @Suppress("DEPRECATION")
    fun startSco() {
        if (scoStarted) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        scoStarted = true
        Log.d(TAG, "Bluetooth SCO requested")
    }

    @Suppress("DEPRECATION")
    fun stopSco() {
        if (!scoStarted) return
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
        scoStarted = false
        Log.d(TAG, "Bluetooth SCO stopped")
    }

    // endregion

    // region AudioDeviceInfo routing lookup --------------------------------------

    /**
     * Resolves the [AudioDeviceInfo] for the earbud assigned to [slot], for use as the
     * preferred OUTPUT device of an AudioTrack. SCO output advertises as
     * TYPE_BLUETOOTH_SCO. Matching is by device address so the right earbud is picked
     * when two are connected.
     */
    fun outputDeviceForSlot(slot: Slot): AudioDeviceInfo? {
        val device = slotAssignment[slot] ?: return null
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return matchByAddress(outputs, device.address, output = true)
    }

    /**
     * Resolves the [AudioDeviceInfo] for the earbud assigned to [slot], for use as the
     * preferred INPUT (mic) device of an AudioRecord.
     */
    fun inputDeviceForSlot(slot: Slot): AudioDeviceInfo? {
        val device = slotAssignment[slot] ?: return null
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return matchByAddress(inputs, device.address, output = false)
    }

    private fun matchByAddress(
        devices: Array<AudioDeviceInfo>,
        address: String,
        output: Boolean
    ): AudioDeviceInfo? {
        // 1) Best case: exact address match (API 28+ exposes a stable address).
        devices.firstOrNull { isScoOrA2dp(it, output) && addressMatches(it, address) }
            ?.let { return it }
        // 2) Fallback: first SCO device of the right direction. With a single earbud
        //    pair connected this is unambiguous; with two pairs, prefer the address
        //    match above.
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun isScoOrA2dp(info: AudioDeviceInfo, output: Boolean): Boolean {
        return info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (output && info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    }

    private fun addressMatches(info: AudioDeviceInfo, address: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.address.equals(address, ignoreCase = true)
        } else false
    }

    // endregion
}
