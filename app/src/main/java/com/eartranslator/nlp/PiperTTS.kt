package com.eartranslator.nlp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Text-to-speech using Piper (VITS) exported to ONNX.
 *
 * Each voice is a <voice>.onnx model plus a <voice>.onnx.json config that contains, among
 * other things, the phoneme→id map and the inference scales. Models live under
 * assets/models/piper/<lang>/.
 *
 * Pipeline: text → phoneme ids → ONNX (VITS) → float PCM @ 22050 Hz → ShortArray.
 *
 * Expected ONNX I/O (Piper VITS):
 *   inputs:
 *     "input"        : int64 [1, L]   — phoneme id sequence
 *     "input_lengths": int64 [1]      — = L
 *     "scales"       : float32 [3]    — [noise_scale, length_scale, noise_w]
 *   output:
 *     "output"       : float32 [1, 1, T] — mono waveform in [-1, 1]
 */
class PiperTTS(private val context: Context) {

    companion object {
        private const val TAG = "PiperTTS"
        const val SAMPLE_RATE = 22_050
    }

    private var session: OrtSession? = null
    private var phonemeIdMap: Map<String, IntArray> = emptyMap()
    private var noiseScale = 0.667f
    private var lengthScale = 1.0f
    private var noiseW = 0.8f
    private var currentVoice: String? = null

    /** eSpeak voice/language used for phonemization, from the Piper config's `espeak.voice`. */
    private var espeakVoice = "en-us"
    private var espeakReady = false

    /** Permissive dictionary phonemizer (preferred when present; no GPL). */
    private var lexicon: LexiconG2P? = null

    private val env = OnnxEnv.ortEnv

    /**
     * Loads a Piper voice, e.g. load("fr", "fr_FR-siwis-medium") reads
     * assets/models/piper/fr/fr_FR-siwis-medium.onnx(.json).
     */
    fun load(lang: String, voice: String) {
        val key = "$lang/$voice"
        if (key == currentVoice) return
        close()

        val base = "models/piper/$lang/$voice"
        try {
            session = OnnxEnv.sessionFromAsset(context, "$base.onnx")
            parseConfig(OnnxEnv.readAsset(context, "$base.onnx.json"))
            // Phonemizer backends, in priority order:
            //  1) permissive lexicon (no GPL) if a dictionary is bundled for this language,
            //  2) eSpeak-ng via JNI (GPLv3) if its native lib is bundled,
            //  3) char-level fallback (placeholder).
            lexicon = LexiconG2P.loadOrNull(context, lang)
            espeakReady = if (lexicon == null) EspeakPhonemizer.ensureInit(context) else false
            currentVoice = key
            Log.d(TAG, "Loaded piper voice $key (phonemes=${phonemeIdMap.size}, lexicon=${lexicon != null}, espeak=$espeakReady)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load piper voice $key", e)
        }
    }

    fun synthesize(text: String): FloatArray {
        val s = session ?: return FloatArray(0)
        if (text.isBlank()) return FloatArray(0)

        val ids = textToPhonemeIds(text)
        if (ids.isEmpty()) return FloatArray(0)

        val idArr = LongArray(ids.size) { ids[it].toLong() }
        val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(idArr), longArrayOf(1, idArr.size.toLong()))
        val lenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(idArr.size.toLong())), longArrayOf(1))
        val scalesTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(floatArrayOf(noiseScale, lengthScale, noiseW)), longArrayOf(3)
        )

        return try {
            s.run(
                mapOf(
                    "input" to inputTensor,
                    "input_lengths" to lenTensor,
                    "scales" to scalesTensor
                )
            ).use { r ->
                // output [1, 1, T]
                @Suppress("UNCHECKED_CAST")
                val out = r[0].value as Array<Array<FloatArray>>
                out[0][0]
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS inference failed", e)
            FloatArray(0)
        } finally {
            inputTensor.close()
            lenTensor.close()
            scalesTensor.close()
        }
    }

    // region Phonemization -------------------------------------------------------

    /**
     * Converts text to Piper phoneme ids.
     *
     * Preferred path: eSpeak-ng turns text into IPA phonemes, then each IPA symbol is
     * mapped through the config's `phoneme_id_map`, following Piper's convention:
     * BOS '^', then for each phoneme its id(s) followed by the pad id '_', then EOS '$'.
     *
     * Fallback (when eSpeak-ng isn't bundled): a crude per-character mapping that only
     * works for trivial cases — NOT real phonemization. See [EspeakPhonemizer] and the
     * README "Phonemizer (eSpeak-ng)" section.
     */
    private fun textToPhonemeIds(text: String): IntArray {
        val out = ArrayList<Int>()
        val padId = phonemeIdMap["_"]?.get(0) ?: 0
        phonemeIdMap["^"]?.let { out.add(it[0]) }   // BOS
        out.add(padId)

        val ipa = lexicon?.phonemize(text)
            ?: if (espeakReady) EspeakPhonemizer.phonemize(text, espeakVoice) else null
        val symbols: CharSequence = ipa ?: run {
            Log.w(TAG, "textToPhonemeIds: no real phonemizer — using char fallback")
            text.lowercase()
        }

        // Iterate by Unicode code point so multi-unit IPA (combining marks) maps per symbol,
        // matching Piper's phonemize loop.
        var i = 0
        while (i < symbols.length) {
            val cp = Character.codePointAt(symbols, i)
            val s = String(Character.toChars(cp))
            i += Character.charCount(cp)
            val mapped = phonemeIdMap[s] ?: continue
            for (id in mapped) out.add(id)
            out.add(padId)
        }

        phonemeIdMap["$"]?.let { out.add(it[0]) }   // EOS
        return out.toIntArray()
    }

    // endregion

    private fun parseConfig(bytes: ByteArray) {
        val json = JSONObject(String(bytes, Charsets.UTF_8))

        json.optJSONObject("inference")?.let { inf ->
            noiseScale = inf.optDouble("noise_scale", noiseScale.toDouble()).toFloat()
            lengthScale = inf.optDouble("length_scale", lengthScale.toDouble()).toFloat()
            noiseW = inf.optDouble("noise_w", noiseW.toDouble()).toFloat()
        }

        // Piper configs carry the eSpeak voice under "espeak": { "voice": "en-us" }.
        json.optJSONObject("espeak")?.let { espeakVoice = it.optString("voice", espeakVoice) }

        val map = HashMap<String, IntArray>()
        json.optJSONObject("phoneme_id_map")?.let { pim ->
            val keys = pim.keys()
            while (keys.hasNext()) {
                val phoneme = keys.next()
                val arr = pim.getJSONArray(phoneme)
                map[phoneme] = IntArray(arr.length()) { arr.getInt(it) }
            }
        }
        phonemeIdMap = map
    }

    fun close() {
        session?.close(); session = null
        phonemeIdMap = emptyMap()
        lexicon = null
        currentVoice = null
    }
}
