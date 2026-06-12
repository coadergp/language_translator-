package com.eartranslator.nlp

/**
 * Converts text into a phoneme string (IPA) for Piper TTS.
 *
 * Implementations are pluggable so the phonemizer backend can be chosen by license and
 * capability:
 *   - [LexiconG2P]   — permissive, pure-Kotlin dictionary lookup (no GPL, no native code),
 *   - [EspeakPhonemizer] — eSpeak-ng via JNI (GPLv3; highest coverage),
 *   - (future) an ONNX neural G2P could implement this same interface.
 *
 * IMPORTANT: the returned phonemes must use the SAME phoneme inventory the loaded Piper
 * voice was trained on (the keys of its `phoneme_id_map`). Mixing inventories yields
 * unmappable symbols.
 *
 * @return the phoneme string, or null if this backend can't phonemize the input (so the
 *         caller can try the next backend or a fallback).
 */
interface Phonemizer {
    fun phonemize(text: String): String?
}
