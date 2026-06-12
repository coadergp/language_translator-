package com.eartranslator.nlp

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

/**
 * Shared ONNX Runtime environment and model-loading helpers.
 *
 * MODEL RESOLUTION: every model is referenced by a relative path like
 * "models/whisper/encoder_model.onnx". Files are resolved **internal storage first, then
 * bundled assets**:
 *   1. `filesDir/<relPath>` — used for models delivered after install (first-run
 *      downloader or Play Asset Delivery copied here),
 *   2. `assets/<relPath>` — used for models bundled in the APK.
 * This lets you ship a tiny APK and fetch large models later without changing callers.
 * (See [ModelManifest] / [ModelDownloader].)
 */
object OnnxEnv {

    val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun defaultSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            // BASIC_OPT keeps session creation fast on-device. ALL_OPT runs many extra
            // graph fusions at load time, which is very slow for large quantized models
            // (loading 6 of them can take minutes). Inference is marginally slower but the
            // app becomes usable much faster.
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
    }

    /** The downloaded-models location for [relPath], whether or not it exists yet. */
    fun fileFor(context: Context, relPath: String): File = File(context.filesDir, relPath)

    /** True if the model exists either as a downloaded file or a bundled asset. */
    fun modelExists(context: Context, relPath: String): Boolean {
        val f = fileFor(context, relPath)
        if (f.exists() && f.length() > 0L) return true
        return try {
            context.assets.open(relPath).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /** Reads a model fully into memory, resolving filesDir → assets. */
    fun readAsset(context: Context, assetPath: String): ByteArray {
        val f = fileFor(context, assetPath)
        if (f.exists() && f.length() > 0L) return f.readBytes()
        context.assets.open(assetPath).use { return it.readBytes() }
    }

    /**
     * Creates a session, resolving filesDir → assets. Downloaded files are loaded by path
     * (mmap-friendly, lower peak memory); bundled assets are loaded from bytes.
     */
    fun sessionFromAsset(context: Context, assetPath: String): OrtSession {
        val f = fileFor(context, assetPath)
        if (f.exists() && f.length() > 0L) {
            return ortEnv.createSession(f.absolutePath, defaultSessionOptions())
        }
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return ortEnv.createSession(bytes, defaultSessionOptions())
    }

    /**
     * Copies an asset to internal storage if not already present, returning the file
     * path. Useful for models loaded by path.
     */
    fun copyAssetToFiles(context: Context, assetPath: String): String {
        val outFile = File(context.filesDir, assetPath.replace('/', '_'))
        if (!outFile.exists() || outFile.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile.absolutePath
    }
}
