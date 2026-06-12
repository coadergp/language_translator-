package com.eartranslator.nlp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Automatic Speech Recognition using Whisper tiny exported to ONNX (encoder + decoder).
 *
 * The service accumulates PCM during a detected speech segment and calls [transcribe]
 * once VAD reports end-of-utterance. The pipeline is:
 *
 *   PCM (16 kHz mono) → log-mel spectrogram [1, 80, 3000]
 *        → encoder → last_hidden_state [1, 1500, 384]
 *        → greedy decoder loop (start tokens → next token … until EOT)
 *        → BPE detokenize → text
 *
 * Expected ONNX I/O:
 *   encoder:
 *     input  "input_features" : float32 [1, 80, 3000]
 *     output "last_hidden_state": float32 [1, 1500, 384]
 *   decoder (per step, no KV-cache variant for simplicity):
 *     input  "input_ids"            : int64 [1, seq]
 *     input  "encoder_hidden_states": float32 [1, 1500, 384]
 *     output "logits"               : float32 [1, seq, vocab]
 */
class WhisperASR(context: Context) {

    companion object {
        private const val TAG = "WhisperASR"
        private const val ENCODER = "models/whisper/encoder_model.onnx"
        private const val DECODER = "models/whisper/decoder_model.onnx"
        private const val VOCAB = "models/whisper/vocab.json"

        private const val N_MELS = 80
        private const val N_FRAMES = 3000          // 30 s context
        private const val ENC_HIDDEN = 384         // whisper-tiny d_model
        private const val MAX_DECODE_TOKENS = 128

        // Whisper special tokens (tiny multilingual). Adjust if using an .en model.
        private const val SOT = 50258               // <|startoftranscript|>
        private const val EOT = 50257               // <|endoftext|>
        private const val NO_TIMESTAMPS = 50363     // <|notimestamps|>
        private const val TRANSCRIBE = 50359        // <|transcribe|>
    }

    private val encoder: OrtSession = OnnxEnv.sessionFromAsset(context, ENCODER)
    private val decoder: OrtSession = OnnxEnv.sessionFromAsset(context, DECODER)
    private val env = OnnxEnv.ortEnv

    /** token-id -> string, loaded from vocab.json (used by [decodeTokens]). */
    private val idToToken: Map<Int, String> = loadVocab(context)

    /** Maps a Whisper language code (e.g. "en", "fr") to its language token id. */
    private val langTokens: Map<String, Int> = buildLanguageTokenMap()

    /** Real (non-stub) Whisper-compatible feature extractor. */
    private val melExtractor = MelSpectrogram(nMels = N_MELS, nFrames = N_FRAMES)

    /** Result of an auto-detecting transcription: which language was spoken + the text. */
    data class Result(val language: String, val text: String)

    /**
     * Transcribes a full utterance, forcing a known source language.
     * @param pcm accumulated PCM-16 samples at 16 kHz mono.
     * @param sourceLang Whisper language code to force-decode in (e.g. "en").
     */
    fun transcribe(pcm: ShortArray, sourceLang: String): String {
        if (pcm.isEmpty()) return ""
        val encHidden = runEncoder(computeLogMelSpectrogram(pcm)) ?: return ""
        val (flatEnc, seqLen) = flattenEncoder(encHidden)
        val langTok = langTokens[sourceLang] ?: langTokens["en"]!!
        return decodeTokens(greedyDecode(flatEnc, seqLen, langTok)).trim()
    }

    /**
     * Transcribes a full utterance and AUTO-DETECTS which language was spoken, restricted
     * to [candidates] (the two languages chosen in the UI). This is the core of the
     * turn-based design: the speaker is identified by the language Whisper hears, not by
     * which microphone was used.
     *
     * Whisper's decoder predicts a language token immediately after the start-of-transcript
     * token. We run a single decode step from [SOT], take the argmax over ONLY the
     * candidate language-token ids (more robust than open-set detection), then decode the
     * text forcing that language.
     *
     * @return a [Result] with the detected language code and the transcribed text, or null
     *         if detection/transcription failed.
     */
    fun transcribeAuto(pcm: ShortArray, candidates: List<String>): Result? {
        if (pcm.isEmpty() || candidates.isEmpty()) return null
        val encHidden = runEncoder(computeLogMelSpectrogram(pcm)) ?: return null
        val (flatEnc, seqLen) = flattenEncoder(encHidden)

        val detected = detectLanguage(flatEnc, seqLen, candidates) ?: candidates.first()
        val langTok = langTokens[detected] ?: langTokens["en"]!!
        val text = decodeTokens(greedyDecode(flatEnc, seqLen, langTok)).trim()
        return Result(detected, text)
    }

    // region Feature extraction --------------------------------------------------

    /**
     * Computes the 80-bin log-mel spectrogram Whisper expects, flattened to
     * [N_MELS * N_FRAMES] row-major. This is a real implementation (see [MelSpectrogram]),
     * matching whisper.audio's recipe (n_fft=400, hop=160, Slaney mel, log10 + normalize).
     */
    private fun computeLogMelSpectrogram(pcm: ShortArray): FloatArray = melExtractor.compute(pcm)

    // endregion

    private fun runEncoder(mel: FloatArray): Array<FloatArray>? {
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(mel), longArrayOf(1, N_MELS.toLong(), N_FRAMES.toLong())
        )
        return try {
            encoder.run(mapOf("input_features" to tensor)).use { result ->
                // [1, 1500, 384] -> keep as [1500][384]
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<FloatArray>>
                out[0]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder failed", e)
            null
        } finally {
            tensor.close()
        }
    }

    /** Flattens encoder output [1500][384] → FloatArray for reuse across decode steps. */
    private fun flattenEncoder(encHidden: Array<FloatArray>): Pair<FloatArray, Int> {
        val seqLen = encHidden.size            // 1500
        val flatEnc = FloatArray(seqLen * ENC_HIDDEN)
        var k = 0
        for (row in encHidden) for (v in row) flatEnc[k++] = v
        return flatEnc to seqLen
    }

    /**
     * Runs one decode step from [SOT] and returns the candidate language whose language
     * token has the highest logit. Restricting to [candidates] makes detection robust
     * when only two languages are in play.
     */
    private fun detectLanguage(flatEnc: FloatArray, seqLen: Int, candidates: List<String>): String? {
        val ids = longArrayOf(SOT.toLong())
        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, 1))
        val encTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flatEnc), longArrayOf(1, seqLen.toLong(), ENC_HIDDEN.toLong())
        )
        return try {
            decoder.run(mapOf("input_ids" to idsTensor, "encoder_hidden_states" to encTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = result[0].value as Array<Array<FloatArray>>
                val lastStep = logits[0][0]
                var best: String? = null
                var bestV = Float.NEGATIVE_INFINITY
                for (code in candidates) {
                    val tok = langTokens[code] ?: continue
                    if (tok < lastStep.size && lastStep[tok] > bestV) {
                        bestV = lastStep[tok]; best = code
                    }
                }
                best
            }
        } catch (e: Exception) {
            Log.e(TAG, "Language detection failed", e)
            null
        } finally {
            idsTensor.close()
            encTensor.close()
        }
    }

    private fun greedyDecode(flatEnc: FloatArray, seqLen: Int, langTok: Int): IntArray {
        val generated = ArrayList<Int>()
        generated.add(SOT)
        generated.add(langTok)
        generated.add(TRANSCRIBE)
        generated.add(NO_TIMESTAMPS)

        val startLen = generated.size

        for (step in 0 until MAX_DECODE_TOKENS) {
            val ids = LongArray(generated.size) { generated[it].toLong() }
            val idsTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())
            )
            val encTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flatEnc),
                longArrayOf(1, seqLen.toLong(), ENC_HIDDEN.toLong())
            )

            val next = try {
                decoder.run(
                    mapOf(
                        "input_ids" to idsTensor,
                        "encoder_hidden_states" to encTensor
                    )
                ).use { result ->
                    // logits [1, seq, vocab]; take argmax of last position.
                    @Suppress("UNCHECKED_CAST")
                    val logits = result[0].value as Array<Array<FloatArray>>
                    argmax(logits[0][generated.size - 1])
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decoder step failed", e)
                EOT
            } finally {
                idsTensor.close()
                encTensor.close()
            }

            if (next == EOT) break
            generated.add(next)
        }

        // Drop the forced prompt tokens; keep only the generated text tokens.
        return generated.drop(startLen).toIntArray()
    }

    private fun argmax(arr: FloatArray): Int {
        var best = 0
        var bestV = arr[0]
        for (i in 1 until arr.size) if (arr[i] > bestV) { bestV = arr[i]; best = i }
        return best
    }

    // region Detokenize ----------------------------------------------------------

    /**
     * Converts token IDs back to text using Whisper's GPT-2 **byte-level BPE**.
     *
     * Whisper's vocab.json keys are byte-level-encoded strings: each raw UTF-8 byte is
     * remapped to a printable Unicode char via GPT-2's `bytes_to_unicode` table (e.g. a
     * space byte 0x20 appears as 'Ġ'). To recover text we:
     *   1. look up each id's token string in vocab.json,
     *   2. concatenate them,
     *   3. reverse the byte-level mapping char→byte (see [byteDecoder]),
     *   4. UTF-8-decode the resulting bytes (correct for multibyte scripts too).
     * Special tokens (id >= [EOT]) are skipped.
     */
    private fun decodeTokens(tokens: IntArray): String =
        GptByteDecoder.decode(tokens, idToToken, specialFloor = EOT)

    // endregion

    private fun loadVocab(context: Context): Map<Int, String> {
        return try {
            val json = String(OnnxEnv.readAsset(context, VOCAB), Charsets.UTF_8)
            val obj = JSONObject(json)
            val map = HashMap<Int, String>(obj.length())
            // vocab.json is { "token": id }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                map[obj.getInt(token)] = token
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab.json", e)
            emptyMap()
        }
    }

    private fun buildLanguageTokenMap(): Map<String, Int> {
        // Whisper multilingual language tokens are contiguous starting at 50259 in the
        // canonical token order. This is the standard ordering used by openai/whisper.
        val codes = listOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca",
            "nl", "ar", "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms",
            "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la",
            "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn",
            "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw"
        )
        val map = HashMap<String, Int>()
        var id = 50259
        for (c in codes) map[c] = id++
        return map
    }

    fun close() {
        encoder.close()
        decoder.close()
    }
}
