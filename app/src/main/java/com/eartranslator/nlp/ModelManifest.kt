package com.eartranslator.nlp

import android.content.Context
import com.eartranslator.config.Language

/**
 * Computes which model files are required for a given set of languages, so the app can
 * check what's present and (optionally) download what's missing.
 *
 * Paths are the same relative paths [OnnxEnv] resolves (filesDir → assets). Translation
 * uses the English pivot, so only `en-X` and `X-en` opus-mt folders are needed.
 */
object ModelManifest {

    /** All model files needed to translate between the given languages. */
    fun requiredPaths(languages: List<Language>): List<String> {
        val out = LinkedHashSet<String>()

        // Shared across all languages.
        out.add("models/silero_vad.onnx")
        out.add("models/whisper/encoder_model.onnx")
        out.add("models/whisper/decoder_model.onnx")
        out.add("models/whisper/vocab.json")

        val codes = languages.map { it.code }.toSet()

        // opus-mt English-pivot pairs (en-X and X-en for every non-English language).
        for (c in codes) {
            if (c == Language.PIVOT) continue
            for (pair in listOf("${Language.PIVOT}-$c", "$c-${Language.PIVOT}")) {
                for (f in listOf("encoder_model.onnx", "decoder_model.onnx", "source.spm", "target.spm", "vocab.json")) {
                    out.add("models/opus-mt/$pair/$f")
                }
            }
        }

        // One Piper voice per language (+ optional permissive lexicon, not required).
        for (l in languages) {
            out.add("models/piper/${l.code}/${l.piperVoice}.onnx")
            out.add("models/piper/${l.code}/${l.piperVoice}.onnx.json")
        }

        return out.toList()
    }

    /** Subset of [requiredPaths] that is not yet present (neither downloaded nor bundled). */
    fun missing(context: Context, languages: List<Language>): List<String> =
        requiredPaths(languages).filterNot { OnnxEnv.modelExists(context, it) }
}
