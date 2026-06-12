package com.eartranslator.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Plays translated speech to a specific earbud.
 *
 * Holds two [AudioTrack] instances — one per [com.eartranslator.bluetooth.BluetoothAudioManager.Slot].
 * Each track is pinned to its earbud's [AudioDeviceInfo] via [AudioTrack.setPreferredDevice]
 * so Person A's translated speech is heard only in Person B's ear and vice-versa.
 *
 * Piper TTS produces 22050 Hz mono float PCM; we convert to PCM-16 [ShortArray] before
 * writing. AudioTrack is created at 22050 Hz to match the TTS sample rate (no resampling).
 */
class AudioPlaybackManager {

    companion object {
        private const val TAG = "AudioPlayback"
        /** Piper VITS output sample rate. */
        const val TTS_SAMPLE_RATE = 22_050
    }

    private val tracks = HashMap<String, AudioTrack>()

    private fun keyOf(slot: com.eartranslator.bluetooth.BluetoothAudioManager.Slot) = slot.name

    /**
     * Creates (or recreates) an AudioTrack bound to [outputDevice] for the given [slot].
     * Call once playback routing is known (after SCO is up and devices enumerated).
     */
    fun prepareTrack(
        slot: com.eartranslator.bluetooth.BluetoothAudioManager.Slot,
        outputDevice: AudioDeviceInfo?
    ) {
        releaseTrack(slot)

        val minBuf = AudioTrack.getMinBufferSize(
            TTS_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // VOICE_COMMUNICATION usage keeps playback on the SCO link.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(TTS_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, TTS_SAMPLE_RATE)) // ~0.5s headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        outputDevice?.let {
            val ok = track.setPreferredDevice(it)
            Log.d(TAG, "[$slot] setPreferredDevice(out=${it.type}, addr=${it.address}) -> $ok")
        }

        tracks[keyOf(slot)] = track
    }

    /**
     * Writes a mono PCM-16 buffer to the track for [slot] and blocks until it has been
     * accepted by the audio system. Safe to call from an IO coroutine.
     */
    fun play(
        slot: com.eartranslator.bluetooth.BluetoothAudioManager.Slot,
        pcm: ShortArray
    ) {
        val track = tracks[keyOf(slot)] ?: run {
            Log.w(TAG, "No track prepared for $slot")
            return
        }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        var offset = 0
        while (offset < pcm.size) {
            val written = track.write(pcm, offset, pcm.size - offset)
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write error=$written")
                break
            }
            offset += written
        }
    }

    /** Converts Piper's float PCM (range ~[-1, 1]) to PCM-16. */
    fun floatToPcm16(floats: FloatArray): ShortArray {
        val out = ShortArray(floats.size)
        for (i in floats.indices) {
            val v = (floats[i] * 32767f).coerceIn(-32768f, 32767f)
            out[i] = v.toInt().toShort()
        }
        return out
    }

    fun releaseTrack(slot: com.eartranslator.bluetooth.BluetoothAudioManager.Slot) {
        tracks.remove(keyOf(slot))?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {}
            it.release()
        }
    }

    fun releaseAll() {
        tracks.values.forEach {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        tracks.clear()
    }
}
