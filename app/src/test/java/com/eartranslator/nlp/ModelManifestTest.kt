package com.eartranslator.nlp

import com.eartranslator.config.Language
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestTest {

    @Test
    fun includesSharedAndPairAndVoiceForEnEs() {
        val paths = ModelManifest.requiredPaths(listOf(Language.ENGLISH, Language.SPANISH))
        // Shared
        assertTrue(paths.contains("models/silero_vad.onnx"))
        assertTrue(paths.contains("models/whisper/encoder_model.onnx"))
        assertTrue(paths.contains("models/whisper/decoder_model.onnx"))
        assertTrue(paths.contains("models/whisper/vocab.json"))
        // opus-mt en<->es (both directions), with all four files for en-es
        assertTrue(paths.contains("models/opus-mt/en-es/encoder_model.onnx"))
        assertTrue(paths.contains("models/opus-mt/en-es/vocab.json"))
        assertTrue(paths.contains("models/opus-mt/es-en/decoder_model.onnx"))
        // Piper voices
        assertTrue(paths.any { it.startsWith("models/piper/en/") })
        assertTrue(paths.any { it.startsWith("models/piper/es/") })
    }

    @Test
    fun neverRequestsEnglishToEnglishPair() {
        val paths = ModelManifest.requiredPaths(listOf(Language.ENGLISH, Language.SPANISH))
        assertFalse(paths.any { it.contains("opus-mt/en-en/") })
    }

    @Test
    fun nonEnglishPairStillPivotsThroughEnglish() {
        // Spanish <-> French: both directions must route via English.
        val paths = ModelManifest.requiredPaths(listOf(Language.SPANISH, Language.FRENCH))
        assertTrue(paths.contains("models/opus-mt/en-es/encoder_model.onnx"))
        assertTrue(paths.contains("models/opus-mt/es-en/encoder_model.onnx"))
        assertTrue(paths.contains("models/opus-mt/en-fr/encoder_model.onnx"))
        assertTrue(paths.contains("models/opus-mt/fr-en/encoder_model.onnx"))
        // TTS only for the two spoken languages, not English.
        assertTrue(paths.any { it.startsWith("models/piper/es/") })
        assertTrue(paths.any { it.startsWith("models/piper/fr/") })
        assertFalse(paths.any { it.startsWith("models/piper/en/") })
    }

    @Test
    fun pathsAreDeduplicated() {
        val paths = ModelManifest.requiredPaths(listOf(Language.ENGLISH, Language.SPANISH))
        assertTrue(paths.size == paths.toSet().size)
    }

    @Test
    fun sameLanguageBothSidesHasNoPairs() {
        val paths = ModelManifest.requiredPaths(listOf(Language.ENGLISH, Language.ENGLISH))
        assertFalse(paths.any { it.contains("opus-mt/") })
        assertTrue(paths.contains("models/silero_vad.onnx"))
    }
}
