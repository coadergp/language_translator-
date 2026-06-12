package com.eartranslator.nlp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper-compatible log-mel spectrogram, implemented in pure Kotlin (no native deps).
 *
 * Reproduces `whisper.audio.log_mel_spectrogram`:
 *   - pad/trim PCM to 30 s (480000 samples @ 16 kHz),
 *   - reflect-pad by n_fft/2, STFT with n_fft=400, hop=160, periodic Hann window,
 *   - power spectrum, then an 80-bin **Slaney** mel filterbank (librosa default,
 *     `htk=False, norm='slaney'`, the bank Whisper was trained with),
 *   - log10 with a 1e-10 floor,
 *   - dynamic-range clamp to (max - 8) and normalize via (x + 4) / 4.
 *
 * Output is a FloatArray of size nMels*nFrames laid out row-major [nMels][nFrames], i.e.
 * element (m, t) at index m*nFrames + t — matching the ONNX `input_features` tensor
 * shape [1, 80, 3000].
 *
 * Performance: silent/padding frames are short-circuited (only frames overlapping real
 * audio run the DFT), so a typical 1–3 s utterance is cheap. A general FFT lib (e.g.
 * JTransforms/TarsosDSP) would speed up worst-case 30 s inputs but is not required.
 */
class MelSpectrogram(
    private val sampleRate: Int = 16_000,
    private val nFft: Int = 400,
    private val hop: Int = 160,
    private val nMels: Int = 80,
    private val nFrames: Int = 3000
) {
    private val nFreq = nFft / 2 + 1          // 201
    private val nSamples = nFrames * hop      // 480000 (30 s)
    private val pad = nFft / 2                 // 200

    // Slaney mel-scale constants. MUST be declared before the init block, because
    // init -> buildMelFilterbank() uses them; if declared later they'd still be 0.0 when
    // init runs (Kotlin initializes properties top-to-bottom), producing an all-NaN bank.
    private val fSp = 200.0 / 3.0
    private val minLogHz = 1000.0
    private val minLogMel = minLogHz / fSp                 // 15.0
    private val logStep = ln(6.4) / 27.0

    // Periodic Hann window (denominator nFft, matching torch.hann_window default).
    private val window = FloatArray(nFft) { (0.5 - 0.5 * cos(2.0 * PI * it / nFft)).toFloat() }

    // Precomputed DFT basis (only the nFreq one-sided bins).
    private val cosT = FloatArray(nFreq * nFft)
    private val sinT = FloatArray(nFreq * nFft)

    // Mel filterbank [nMels][nFreq].
    private val melFb = Array(nMels) { FloatArray(nFreq) }

    init {
        for (k in 0 until nFreq) {
            val base = k * nFft
            for (n in 0 until nFft) {
                val a = 2.0 * PI * k * n / nFft
                cosT[base + n] = cos(a).toFloat()
                sinT[base + n] = sin(a).toFloat()
            }
        }
        buildMelFilterbank()
    }

    /** Computes the normalized log-mel features for [pcm] (PCM-16 @ 16 kHz). */
    fun compute(pcm: ShortArray): FloatArray {
        // 1) Normalize to float and trim/zero-pad to exactly nSamples.
        val signal = FloatArray(nSamples)
        val l = min(pcm.size, nSamples)
        for (i in 0 until l) signal[i] = pcm[i] / 32768f

        // 2) Reflect-pad by `pad` on both ends (np.pad mode='reflect').
        val padded = FloatArray(nSamples + 2 * pad)
        System.arraycopy(signal, 0, padded, pad, nSamples)
        for (i in 0 until pad) {
            padded[pad - 1 - i] = signal[i + 1]
            padded[pad + nSamples + i] = signal[nSamples - 2 - i]
        }

        val out = FloatArray(nMels * nFrames)
        val frame = FloatArray(nFft)
        val power = FloatArray(nFreq)
        val logFloor = log10(1e-10f) // = -10, the value for an all-zero (silent) frame
        // Frames whose window starts past the real audio are pure padding → constant floor.
        val validLen = l + pad
        var globalMax = Float.NEGATIVE_INFINITY

        for (t in 0 until nFrames) {
            val start = t * hop
            if (start >= validLen) {
                for (m in 0 until nMels) out[m * nFrames + t] = logFloor
                continue
            }

            for (n in 0 until nFft) frame[n] = padded[start + n] * window[n]

            // One-sided power spectrum via direct DFT.
            for (k in 0 until nFreq) {
                var re = 0f
                var im = 0f
                val b = k * nFft
                for (n in 0 until nFft) {
                    val s = frame[n]
                    re += s * cosT[b + n]
                    im -= s * sinT[b + n]
                }
                power[k] = re * re + im * im
            }

            // Apply mel filterbank, log10 with floor.
            for (m in 0 until nMels) {
                val fb = melFb[m]
                var sum = 0f
                for (k in 0 until nFreq) sum += fb[k] * power[k]
                val v = log10(max(sum, 1e-10f))
                out[m * nFrames + t] = v
                if (v > globalMax) globalMax = v
            }
        }

        // 3) Dynamic range clamp + normalize (Whisper).
        if (globalMax == Float.NEGATIVE_INFINITY) globalMax = logFloor
        val floor = globalMax - 8f
        for (i in out.indices) {
            val v = if (out[i] < floor) floor else out[i]
            out[i] = (v + 4f) / 4f
        }
        return out
    }

    // region Slaney mel scale ----------------------------------------------------

    private fun hzToMel(f: Double): Double =
        if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logStep else f / fSp

    private fun melToHz(m: Double): Double =
        if (m >= minLogMel) minLogHz * exp(logStep * (m - minLogMel)) else fSp * m

    private fun buildMelFilterbank() {
        val melMin = hzToMel(0.0)
        val melMax = hzToMel(sampleRate / 2.0)
        val melPts = DoubleArray(nMels + 2) { melMin + (melMax - melMin) * it / (nMels + 1) }
        val hzPts = DoubleArray(nMels + 2) { melToHz(melPts[it]) }
        val fftFreqs = DoubleArray(nFreq) { it.toDouble() * sampleRate / nFft }
        val fdiff = DoubleArray(nMels + 1) { hzPts[it + 1] - hzPts[it] }

        for (m in 0 until nMels) {
            val enorm = 2.0 / (hzPts[m + 2] - hzPts[m])   // Slaney normalization
            for (k in 0 until nFreq) {
                val lower = (fftFreqs[k] - hzPts[m]) / fdiff[m]
                val upper = (hzPts[m + 2] - fftFreqs[k]) / fdiff[m + 1]
                val w = max(0.0, min(lower, upper)) * enorm
                melFb[m][k] = w.toFloat()
            }
        }
    }

    // endregion
}
