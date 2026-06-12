package com.eartranslator.nlp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Voice Activity Detection using Silero VAD (silero_vad.onnx).
 *
 * The model is STATEFUL: it carries hidden/cell tensors (h, c) between successive
 * frames. We persist them here and feed them back on each [process] call. Call [reset]
 * at the end of an utterance (or when starting a fresh stream) to clear the state.
 *
 * Expected ONNX I/O (Silero v4 h/c variant):
 *   inputs:
 *     "input" : float32 [1, 512]     — one 32 ms frame at 16 kHz, samples in [-1, 1]
 *     "sr"    : int64   scalar       — sample rate (16000)
 *     "h"     : float32 [2, 1, 64]   — hidden state
 *     "c"     : float32 [2, 1, 64]   — cell state
 *   outputs:
 *     "output": float32 [1, 1]       — speech probability in [0, 1]
 *     "hn"    : float32 [2, 1, 64]   — next hidden state
 *     "cn"    : float32 [2, 1, 64]   — next cell state
 *
 * Note: newer single-file silero exports use a combined "state" [2,1,128] tensor and a
 * "state" output instead of separate h/c. This class targets the h/c variant per spec;
 * if you export the combined variant, swap the two state tensors for one.
 */
class SileroVAD(context: Context) {

    companion object {
        private const val TAG = "SileroVAD"
        private const val MODEL = "models/silero_vad.onnx"
        const val FRAME_SAMPLES = 512
        private const val SR = 16_000L
        private const val STATE_DIM = 64
        /** Probabilities above this are treated as speech. */
        const val SPEECH_THRESHOLD = 0.5f
    }

    private val session: OrtSession = OnnxEnv.sessionFromAsset(context, MODEL)
    private val env = OnnxEnv.ortEnv

    // Persisted recurrent state.
    private var h = FloatArray(2 * 1 * STATE_DIM)
    private var c = FloatArray(2 * 1 * STATE_DIM)

    /** Clears recurrent state — call at end of utterance / start of stream. */
    fun reset() {
        h = FloatArray(2 * 1 * STATE_DIM)
        c = FloatArray(2 * 1 * STATE_DIM)
    }

    /**
     * Runs one frame through the model and returns the speech probability.
     * [frame] must be exactly [FRAME_SAMPLES] PCM-16 samples; they are normalized to
     * float [-1, 1] internally.
     */
    fun process(frame: ShortArray): Float {
        require(frame.size == FRAME_SAMPLES) {
            "Silero expects $FRAME_SAMPLES samples, got ${frame.size}"
        }

        val input = FloatArray(FRAME_SAMPLES) { frame[it] / 32768f }

        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, FRAME_SAMPLES.toLong())
        )
        val srTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(SR)), longArrayOf(1)
        )
        val hTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(h), longArrayOf(2, 1, STATE_DIM.toLong())
        )
        val cTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(c), longArrayOf(2, 1, STATE_DIM.toLong())
        )

        return try {
            val inputs = mapOf(
                "input" to inputTensor,
                "sr" to srTensor,
                "h" to hTensor,
                "c" to cTensor
            )
            session.run(inputs).use { result ->
                val prob = (result[0].value as Array<FloatArray>)[0][0]
                // Persist the recurrent state for the next frame.
                @Suppress("UNCHECKED_CAST")
                h = flatten3d(result[1].value)
                @Suppress("UNCHECKED_CAST")
                c = flatten3d(result[2].value)
                prob
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference failed", e)
            0f
        } finally {
            inputTensor.close()
            srTensor.close()
            hTensor.close()
            cTensor.close()
        }
    }

    fun isSpeech(frame: ShortArray): Boolean = process(frame) >= SPEECH_THRESHOLD

    private fun flatten3d(value: Any?): FloatArray {
        // value is float[2][1][64]
        @Suppress("UNCHECKED_CAST")
        val arr = value as Array<Array<FloatArray>>
        val out = FloatArray(2 * 1 * STATE_DIM)
        var i = 0
        for (a in arr) for (b in a) for (v in b) out[i++] = v
        return out
    }

    fun close() = session.close()
}
