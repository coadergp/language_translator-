package com.eartranslator.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Captures 16 kHz / mono / PCM-16 audio from a specific Bluetooth earbud mic.
 *
 * The capture loop is blocking, so the caller is expected to run [captureLoop] on a
 * dedicated IO/coroutine dispatcher. Captured PCM is emitted as [ShortArray] chunks of
 * [CHUNK_SAMPLES] (512 samples = 32 ms at 16 kHz, matching the Silero VAD frame size)
 * through a [SharedFlow].
 *
 * The earbud mic is selected with [AudioRecord.setPreferredDevice]; combined with the
 * SCO link being up (see BluetoothAudioManager), this pins capture to the chosen ear.
 */
class AudioCaptureManager {

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        /** 512 samples @ 16 kHz = 32 ms — the Silero VAD frame size. */
        const val CHUNK_SAMPLES = 512
    }

    private val _pcmFlow = MutableSharedFlow<ShortArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val pcmFlow: SharedFlow<ShortArray> = _pcmFlow.asSharedFlow()

    @Volatile private var record: AudioRecord? = null
    @Volatile private var running = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    fun start(preferredMic: AudioDeviceInfo?) {
        if (running) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Use a generous buffer (≥ a few chunks) to absorb scheduling jitter on the BT link.
        val bufferBytes = maxOf(minBuf, CHUNK_SAMPLES * 2 * 8)

        val ar = AudioRecord(
            // VOICE_COMMUNICATION engages the platform's voice processing path that
            // cooperates with SCO routing.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            ar.release()
            return
        }

        // Pin capture to the assigned BT earbud mic.
        preferredMic?.let {
            val ok = ar.setPreferredDevice(it)
            Log.d(TAG, "setPreferredDevice(mic=${it.type}) -> $ok")
        }

        record = ar
        running = true
    }

    /**
     * Blocking read loop. Reads PCM in [CHUNK_SAMPLES] units and emits each chunk on
     * [pcmFlow]. Returns when [stop] is called or a read error occurs. Run on an IO
     * dispatcher inside a coroutine.
     */
    suspend fun captureLoop() {
        val ar = record ?: return
        ar.startRecording()
        Log.d(TAG, "Capture started")
        val buf = ShortArray(CHUNK_SAMPLES)
        try {
            while (running) {
                val read = ar.read(buf, 0, CHUNK_SAMPLES)
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "AudioRecord.read error=$read")
                    continue
                }
                // Emit a copy so downstream consumers can hold onto it safely.
                val chunk = if (read == CHUNK_SAMPLES) buf.copyOf()
                            else buf.copyOf(read)
                _pcmFlow.emit(chunk)
            }
        } finally {
            try { ar.stop() } catch (_: IllegalStateException) {}
        }
    }

    fun stop() {
        running = false
        record?.release()
        record = null
        Log.d(TAG, "Capture stopped")
    }
}
