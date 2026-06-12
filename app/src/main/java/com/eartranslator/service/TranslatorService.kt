package com.eartranslator.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.eartranslator.BuildConfig
import com.eartranslator.MainActivity
import com.eartranslator.R
import com.eartranslator.audio.AudioCaptureManager
import com.eartranslator.audio.AudioPlaybackManager
import com.eartranslator.bluetooth.BluetoothAudioManager
import com.eartranslator.bluetooth.BluetoothAudioManager.Slot
import com.eartranslator.config.Language
import com.eartranslator.nlp.OpusMTTranslator
import com.eartranslator.nlp.PiperTTS
import com.eartranslator.nlp.SileroVAD
import com.eartranslator.nlp.WhisperASR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the full real-time translation loop.
 *
 * Two conversation directions run as two parallel coroutines:
 *   - Person A (lang A) speaks → captured on A's earbud mic → translated to lang B →
 *     played into Person B's earbud.
 *   - Person B → A, symmetrically.
 *
 * NOTE on a single shared mic: a phone has ONE SCO uplink at a time, so both earbud mics
 * cannot be captured simultaneously through independent AudioRecords on most hardware.
 * This service captures from one shared 16 kHz mic stream and routes each direction's
 * OUTPUT to the correct opposite earbud (which is the part the hardware reliably
 * supports). The per-direction structure is preserved so that, on devices/stacks that do
 * expose two SCO mics, you can pin each capture with setPreferredDevice and run them
 * truly in parallel.
 *
 * VAD-gated pipeline per direction:
 *   accumulate PCM while VAD says speech
 *     → on SILENCE_FRAMES_THRESHOLD consecutive silent frames, flush:
 *         ASR (Whisper) → MT (opus-mt) → TTS (Piper) → playback to opposite earbud
 *     → reset VAD state.
 */
class TranslatorService : Service() {

    companion object {
        private const val TAG = "TranslatorService"
        private const val CHANNEL_ID = "eartranslator_fg"
        private const val NOTIF_ID = 42

        const val EXTRA_LANG_A = "lang_a"   // Language.code for Person A
        const val EXTRA_LANG_B = "lang_b"   // Language.code for Person B

        /** Live pipeline status broadcast to the UI (no logcat needed to debug on-device). */
        const val ACTION_STATUS = "com.eartranslator.STATUS"
        const val EXTRA_MSG = "msg"
        const val EXTRA_PROGRESS = "progress"   // 0..100 determinate; -1 busy/indeterminate; -2 hide
        const val EXTRA_SPEAKER = "speaker"     // "A", "B", or "" — which person is active
        const val PROGRESS_BUSY = -1
        const val PROGRESS_HIDE = -2

        /** ~480 ms of silence (15 × 32 ms frames) ends an utterance. */
        const val SILENCE_FRAMES_THRESHOLD = 15

        /** Cap accumulation at 30 s (Whisper context) to bound memory. */
        private const val MAX_UTTERANCE_SAMPLES = 16_000 * 30
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var btManager: BluetoothAudioManager
    private lateinit var capture: AudioCaptureManager
    private lateinit var playback: AudioPlaybackManager

    // Turn-based design: ONE shared mic + ONE VAD. The speaker is identified per utterance
    // by the language Whisper detects, which then selects the MT direction, TTS voice, and
    // target earbud. One MT/TTS per direction so each is loaded once.
    private lateinit var vad: SileroVAD
    private lateinit var asr: WhisperASR
    private lateinit var mtAtoB: OpusMTTranslator
    private lateinit var mtBtoA: OpusMTTranslator
    private lateinit var ttsB: PiperTTS   // speaks lang B into B's ear (A→B output)
    private lateinit var ttsA: PiperTTS   // speaks lang A into A's ear (B→A output)

    private lateinit var langA: Language
    private lateinit var langB: Language

    private var captureJob: Job? = null
    private var loopJob: Job? = null

    /**
     * Feedback guard: while we are playing translated speech (and for a short tail after),
     * captured audio is ignored so the app never transcribes its own TTS output. This is
     * what makes the single-mic turn-based design stable. System.currentTimeMillis is used
     * only as a monotonic-enough wall clock for this short window.
     */
    @Volatile private var muteCaptureUntilMs = 0L
    private val playbackTailMs = 300L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val codeA = intent?.getStringExtra(EXTRA_LANG_A) ?: Language.ENGLISH.code
        val codeB = intent?.getStringExtra(EXTRA_LANG_B) ?: Language.SPANISH.code
        langA = Language.byCode(codeA)
        langB = Language.byCode(codeB)

        startForegroundCompat()
        startPipeline()
        return START_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startPipeline() {
        btManager = BluetoothAudioManager(this)
        capture = AudioCaptureManager()
        playback = AudioPlaybackManager()

        // Models. (Heavy — created on a background dispatcher.)
        scope.launch {
            try {
                initModels()
            } catch (e: Throwable) {
                // Surface the real error to the UI instead of freezing on the last step.
                Log.e(TAG, "Model init failed", e)
                status("⚠ Model load failed: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
                return@launch
            }

            // Bring up SCO and routing.
            btManager.openProfiles {
                btManager.startSco()
                // Pin each direction's playback to the OPPOSITE earbud.
                playback.prepareTrack(Slot.PERSON_B, btManager.outputDeviceForSlot(Slot.PERSON_B))
                playback.prepareTrack(Slot.PERSON_A, btManager.outputDeviceForSlot(Slot.PERSON_A))
            }

            // Single shared mic stream feeding one VAD-gated loop.
            capture.start(btManager.inputDeviceForSlot(Slot.PERSON_A))
            captureJob = scope.launch(Dispatchers.IO) { capture.captureLoop() }
            loopJob = scope.launch { runTurnLoop() }
            status("Listening — speak now")
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Broadcasts a one-line pipeline status + progress (+ active speaker) to the UI. */
    private fun status(msg: String, progress: Int = PROGRESS_HIDE, speaker: String = "") {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_MSG, msg)
                .putExtra(EXTRA_PROGRESS, progress)
                .putExtra(EXTRA_SPEAKER, speaker)
        )
    }

    private suspend fun initModels() {
        status("Loading voice detector…", 5)
        vad = SileroVAD(this)
        status("Loading speech recognizer…", 20)
        asr = WhisperASR(this)

        status("Loading translator ${langA.code}→${langB.code}…", 40)
        mtAtoB = OpusMTTranslator(this).also { it.load(langA.code, langB.code) }
        status("Loading translator ${langB.code}→${langA.code}…", 60)
        mtBtoA = OpusMTTranslator(this).also { it.load(langB.code, langA.code) }

        status("Loading ${langB.display} voice…", 80)
        ttsB = PiperTTS(this).also { it.load(langB.code, langB.piperVoice) }
        status("Loading ${langA.display} voice…", 92)
        ttsA = PiperTTS(this).also { it.load(langA.code, langA.piperVoice) }
        status("Models ready", 100)
    }

    /**
     * The single turn-based loop: accumulate an utterance, and on end-of-speech detect
     * which language was spoken, then route the translation to the opposite ear.
     */
    private suspend fun runTurnLoop() {
        val buffer = ArrayList<Short>(MAX_UTTERANCE_SAMPLES)
        var silenceFrames = 0
        var inSpeech = false

        capture.pcmFlow.collect { chunk ->
            // Feedback guard: drop everything captured while/just-after we are speaking
            // translated output, so we never transcribe our own TTS.
            if (System.currentTimeMillis() < muteCaptureUntilMs) {
                if (inSpeech) { buffer.clear(); vad.reset(); inSpeech = false; silenceFrames = 0 }
                return@collect
            }

            val speech = if (chunk.size == SileroVAD.FRAME_SAMPLES) vad.isSpeech(chunk) else false

            if (speech) {
                if (!inSpeech) status("Hearing speech…", PROGRESS_BUSY, "?")
                inSpeech = true
                silenceFrames = 0
                for (s in chunk) buffer.add(s)
                if (buffer.size >= MAX_UTTERANCE_SAMPLES) {
                    flush(buffer); vad.reset(); inSpeech = false
                }
            } else if (inSpeech) {
                silenceFrames++
                for (s in chunk) buffer.add(s)   // keep trailing audio so tails aren't clipped
                if (silenceFrames >= SILENCE_FRAMES_THRESHOLD) {
                    flush(buffer); vad.reset(); inSpeech = false; silenceFrames = 0
                }
            }
        }
    }

    /**
     * One full utterance → ASR (auto-detect language) → route → MT → TTS → play to the
     * correct opposite earbud.
     */
    private suspend fun flush(buffer: ArrayList<Short>) {
        if (buffer.isEmpty()) return
        val pcm = ShortArray(buffer.size) { buffer[it] }
        buffer.clear()
        status("Recognizing…", PROGRESS_BUSY)

        // Auto-detect which of the two configured languages was spoken.
        val result = asr.transcribeAuto(pcm, listOf(langA.code, langB.code))
        if (result == null) { status("ASR failed (model error)"); return }
        val text = result.text
        if (text.isBlank()) { status("No words recognized"); return }

        // Route based on detected language: A spoke → translate to B, play in B's ear.
        // speakerTag: which person card to light up (the one who spoke).
        val (mt, tts, outSlot, speakerTag) = when (result.language) {
            langA.code -> Quad(mtAtoB, ttsB, Slot.PERSON_B, "A")
            langB.code -> Quad(mtBtoA, ttsA, Slot.PERSON_A, "B")
            else -> { status("Heard '${result.language}' (not a chosen language)"); return }
        }
        val speaker = if (speakerTag == "A") "Person A (${langA.display})" else "Person B (${langB.display})"
        status("🗣 $speaker: $text", PROGRESS_BUSY, speakerTag)
        if (BuildConfig.DEBUG) Log.d(TAG, "[lang=${result.language} → $outSlot] ASR: $text")

        val listener = if (outSlot == Slot.PERSON_B) "Person B (${langB.display})" else "Person A (${langA.display})"
        val translated = mt.translate(text)
        if (translated.isBlank()) { status("Translation empty"); return }
        status("→ $listener: $translated", PROGRESS_BUSY, speakerTag)
        if (BuildConfig.DEBUG) Log.d(TAG, "[$outSlot] MT: $translated")

        val floatPcm = tts.synthesize(translated)
        if (floatPcm.isEmpty()) { status("⚠ No voice: ${tts.lastDiag}"); return }

        val out = playback.floatToPcm16(floatPcm)

        // Mute capture for the duration of playback (+ tail) so we don't transcribe our
        // own output. Playback duration ≈ samples / sample-rate.
        val durationMs = (out.size.toLong() * 1000L) / AudioPlaybackManager.TTS_SAMPLE_RATE
        muteCaptureUntilMs = System.currentTimeMillis() + durationMs + playbackTailMs
        status("🔊 Speaking to $listener…", PROGRESS_BUSY, speakerTag)
        playback.play(outSlot, out)
        status("Listening — speak now")
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        loopJob?.cancel()

        try { capture.stop() } catch (_: Exception) {}
        try { playback.releaseAll() } catch (_: Exception) {}
        try { btManager.stopSco(); btManager.closeProfiles() } catch (_: Exception) {}

        if (::vad.isInitialized) vad.close()
        if (::asr.isInitialized) asr.close()
        if (::mtAtoB.isInitialized) mtAtoB.close()
        if (::mtBtoA.isInitialized) mtBtoA.close()
        if (::ttsA.isInitialized) ttsA.close()
        if (::ttsB.isInitialized) ttsB.close()

        scope.cancel()
    }

    // region Foreground notification --------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EarTranslator",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EarTranslator running")
            .setContentText("Translating ${langA.display} ⇄ ${langB.display}")
            .setSmallIcon(R.drawable.ic_translate)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // endregion
}
