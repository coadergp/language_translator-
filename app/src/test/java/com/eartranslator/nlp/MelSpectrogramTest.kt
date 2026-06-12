package com.eartranslator.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MelSpectrogramTest {

    private val mel = MelSpectrogram()  // defaults: 80 mels, 3000 frames

    @Test
    fun outputHasWhisperShape() {
        val out = mel.compute(ShortArray(16_000)) // 1 s of silence
        assertEquals(80 * 3000, out.size)
    }

    @Test
    fun silenceNormalizesToConstantFloor() {
        val out = mel.compute(ShortArray(16_000))
        // For all-silence, every bin hits the log floor (-10) and normalizes to (-10+4)/4.
        val expected = (-10f + 4f) / 4f
        for (v in out) assertEquals(expected, v, 1e-3f)
    }

    @Test
    fun toneProducesFiniteVariedOutput() {
        // 440 Hz tone for ~0.5 s.
        val n = 8000
        val pcm = ShortArray(n) { (sin(2.0 * PI * 440.0 * it / 16_000.0) * 12000).toInt().toShort() }
        val out = mel.compute(pcm)

        assertEquals(80 * 3000, out.size)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in out) {
            assertTrue("value must be finite", v.isFinite())
            if (v < min) min = v
            if (v > max) max = v
        }
        // A real tone must create spectral variation (not the flat silence floor).
        assertTrue("expected variation across mel bins", max - min > 0.5f)
        // Whisper normalization caps the max at (globalMax+4)/4; with the (max-8) clamp
        // the spread is bounded to ~2.0.
        assertTrue("normalized range should be bounded", max - min <= 2.01f)
    }

    @Test
    fun emptyInputIsAllFiniteSilence() {
        val out = mel.compute(ShortArray(0))
        assertEquals(80 * 3000, out.size)
        val expected = (-10f + 4f) / 4f
        for (v in out) assertEquals(expected, v, 1e-3f)
    }

    @Test
    fun inputLongerThan30sIsTruncatedNotCrash() {
        // 40 s of audio must be trimmed to the 30 s context; output stays fixed-size/finite.
        val pcm = ShortArray(16_000 * 40) { ((it % 200) - 100).toShort() }
        val out = mel.compute(pcm)
        assertEquals(80 * 3000, out.size)
        for (v in out) assertTrue(v.isFinite())
    }

    @Test
    fun maxAmplitudeSquareWaveStaysFinite() {
        // Full-scale square wave (worst case for clipping/overflow) must not produce NaN/Inf.
        val pcm = ShortArray(8000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val out = mel.compute(pcm)
        for (v in out) assertTrue("non-finite at full scale", v.isFinite())
    }

    @Test
    fun singleSampleDoesNotCrash() {
        val out = mel.compute(shortArrayOf(1234))
        assertEquals(80 * 3000, out.size)
        for (v in out) assertTrue(v.isFinite())
    }
}
