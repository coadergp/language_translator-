package com.eartranslator.nlp

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Kotlin wrapper around the eSpeak-ng JNI bridge ([espeak_jni.cpp]).
 *
 * Converts text → IPA phonemes, which Piper then maps to phoneme ids. This is OPTIONAL:
 * if the native library `libeartranslator_espeak` (and the eSpeak-ng it links) is not
 * present, [ensureInit] returns false and callers fall back to a placeholder phonemizer.
 *
 * Setup (see README "Phonemizer (eSpeak-ng)"):
 *   - prebuilt `libespeak-ng.so` per ABI under app/src/main/jniLibs/<abi>/,
 *   - eSpeak-ng headers under app/src/main/cpp/include/espeak-ng/,
 *   - the `espeak-ng-data` folder under app/src/main/assets/espeak-ng-data/.
 *
 * ⚠️ eSpeak-ng is GPLv3 — bundling it imposes GPLv3 obligations on the published app.
 */
object EspeakPhonemizer {

    private const val TAG = "EspeakPhonemizer"
    private const val ASSET_DATA_DIR = "espeak-ng-data"

    @Volatile private var initialized = false
    @Volatile private var available = true   // flips false if the native lib can't load

    /** Loads the native lib (once) and initializes eSpeak with the bundled data. */
    @Synchronized
    fun ensureInit(context: Context): Boolean {
        if (initialized) return true
        if (!available) return false
        return try {
            System.loadLibrary("eartranslator_espeak")
            val dataParent = copyEspeakData(context)
            val sampleRate = nativeInit(dataParent)
            initialized = sampleRate > 0
            if (!initialized) Log.e(TAG, "eSpeak init returned $sampleRate")
            initialized
        } catch (t: Throwable) {
            // UnsatisfiedLinkError when the .so isn't bundled, etc. — fall back silently.
            Log.w(TAG, "eSpeak-ng not available; using fallback phonemizer (${t.message})")
            available = false
            false
        }
    }

    /**
     * Phonemizes [text] for the given eSpeak [voice] (e.g. "en-us", "fr", "de").
     * Returns the IPA string, or null if eSpeak is unavailable.
     */
    @Synchronized
    fun phonemize(text: String, voice: String): String? {
        if (!initialized) return null
        return try {
            nativeSetVoice(voice)
            nativeTextToPhonemes(text)
        } catch (t: Throwable) {
            Log.e(TAG, "phonemize failed", t)
            null
        }
    }

    /**
     * Copies the assets espeak-ng-data tree to internal storage (once) and returns the
     * parent directory path that eSpeak expects (the folder containing espeak-ng-data).
     */
    private fun copyEspeakData(context: Context): String {
        val dest = File(context.filesDir, ASSET_DATA_DIR)
        if (!dest.exists() || (dest.list()?.isEmpty() != false)) {
            copyAssetDir(context, ASSET_DATA_DIR, dest)
        }
        return context.filesDir.absolutePath
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // It's a file: copy it.
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir(context, "$assetPath/$child", File(dest, child))
        }
    }

    private external fun nativeInit(dataParentPath: String): Int
    private external fun nativeSetVoice(voice: String): Int
    private external fun nativeTextToPhonemes(text: String): String
}
