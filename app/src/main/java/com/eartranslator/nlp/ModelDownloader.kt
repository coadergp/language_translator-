package com.eartranslator.nlp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional first-run downloader: fetches missing model files from [baseUrl] into internal
 * storage (`filesDir/<relPath>`), where [OnnxEnv] then finds them. Lets you ship a small
 * APK and deliver large models after install.
 *
 * ⚠️ PRIVACY / PERMISSION: this uses the network, so to use it you must add
 *     <uses-permission android:name="android.permission.INTERNET" />
 * to the manifest — which changes the app's "no internet" privacy posture. Update
 * `PRIVACY_POLICY.md`, the Data Safety form, and the disclosure copy accordingly, and
 * note that **only model files** are downloaded — user audio still never leaves the device.
 *
 * RECOMMENDED ALTERNATIVE: **Play Asset Delivery** delivers large assets through the Play
 * Store without any INTERNET permission, preserving the offline-privacy story. Use this
 * HTTP downloader only if you can't use PAD. Either way, copy delivered files to
 * `filesDir/models/...` (or load PAD packs and point [OnnxEnv] at them).
 */
class ModelDownloader(
    private val context: Context,
    private val baseUrl: String
) {
    companion object {
        private const val TAG = "ModelDownloader"
    }

    data class Progress(val completed: Int, val total: Int, val current: String)

    /**
     * True if a usable base URL is configured. We REQUIRE https:// — model files are
     * executable-adjacent inputs to ONNX Runtime, so fetching them over cleartext would
     * let a network attacker swap in malicious models (MITM). Reject anything non-https.
     */
    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && baseUrl.startsWith("https://", ignoreCase = true)

    /**
     * Downloads every path in [paths] that isn't already present. Reports progress on the
     * calling-supplied [onProgress]. Returns the list of paths that FAILED (empty = success).
     * Runs on IO.
     */
    suspend fun download(paths: List<String>, onProgress: (Progress) -> Unit): List<String> =
        withContext(Dispatchers.IO) {
            val missing = paths.filterNot { OnnxEnv.modelExists(context, it) }
            val failed = ArrayList<String>()
            missing.forEachIndexed { i, rel ->
                onProgress(Progress(i, missing.size, rel))
                if (!fetch(rel)) failed.add(rel)
            }
            onProgress(Progress(missing.size, missing.size, "done"))
            failed
        }

    private fun fetch(relPath: String): Boolean {
        // Path-traversal guard: the resolved file must stay inside filesDir, so a crafted
        // relPath (e.g. with "..") can't write elsewhere on the device.
        val dest = OnnxEnv.fileFor(context, relPath)
        val baseDir = context.filesDir.canonicalFile
        if (!dest.canonicalFile.toPath().startsWith(baseDir.toPath())) {
            Log.e(TAG, "Rejected path outside filesDir: $relPath")
            return false
        }
        val tmp = File(dest.parentFile, "${dest.name}.part")
        dest.parentFile?.mkdirs()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${baseUrl.trimEnd('/')}/$relPath").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ${conn.responseCode} for $relPath")
                return false
            }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() == 0L) return false
            tmp.renameTo(dest)   // atomic-ish: only the completed file becomes visible
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $relPath", e)
            tmp.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }
}
