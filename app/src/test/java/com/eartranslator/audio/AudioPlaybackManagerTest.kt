package com.eartranslator.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure float→PCM16 conversion (no Android calls). The AudioPlaybackManager
 * constructor only allocates a HashMap, so it's safe to instantiate under plain JVM.
 */
class AudioPlaybackManagerTest {

    private val pb = AudioPlaybackManager()

    @Test
    fun convertsAndClipsRange() {
        val out = pb.floatToPcm16(floatArrayOf(0f, 1f, -1f, 2f, -2f, 0.5f))
        assertEquals(0.toShort(), out[0])
        assertEquals(32767.toShort(), out[1])   // 1.0 * 32767
        assertEquals((-32767).toShort(), out[2]) // -1.0 * 32767
        assertEquals(32767.toShort(), out[3])    // 2.0 clipped to max
        assertEquals((-32768).toShort(), out[4]) // -2.0 clipped to min
        assertEquals(16383.toShort(), out[5])    // 0.5 * 32767 = 16383.5 -> 16383
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(0, pb.floatToPcm16(FloatArray(0)).size)
    }

    @Test
    fun preservesLength() {
        assertEquals(1000, pb.floatToPcm16(FloatArray(1000)).size)
    }

    @Test
    fun nanIsHandledAsZeroNotCrash() {
        // NaN.toInt() == 0 per JLS; must not throw.
        val out = pb.floatToPcm16(floatArrayOf(Float.NaN))
        assertEquals(0.toShort(), out[0])
    }
}
