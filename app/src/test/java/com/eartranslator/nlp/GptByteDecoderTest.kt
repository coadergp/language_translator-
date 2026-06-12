package com.eartranslator.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GptByteDecoderTest {

    @Test
    fun decodesAsciiWithSpaceMarker() {
        // 'Ġ' is the byte-level encoding of a leading space (byte 0x20).
        val vocab = mapOf(0 to "Ġthe", 1 to "Ġworld")
        val text = GptByteDecoder.decode(intArrayOf(0, 1), vocab, specialFloor = 50257)
        assertEquals(" the world", text)
    }

    @Test
    fun decodesMultibyteUtf8() {
        // "é" (U+00E9) is UTF-8 bytes 0xC3 0xA9, which byte-level BPE renders as "Ã©".
        val vocab = mapOf(0 to "caf", 1 to "Ã©")
        val text = GptByteDecoder.decode(intArrayOf(0, 1), vocab, specialFloor = 50257)
        assertEquals("café", text)
    }

    @Test
    fun skipsSpecialTokens() {
        val vocab = mapOf(0 to "Ġhi", 50257 to "<|endoftext|>")
        val text = GptByteDecoder.decode(intArrayOf(0, 50257), vocab, specialFloor = 50257)
        assertEquals(" hi", text)
    }

    @Test
    fun byteDecoderCoversAll256Bytes() {
        // The reverse table must map exactly 256 distinct chars back to 0..255.
        val values = GptByteDecoder.byteDecoder.values.toSortedSet()
        assertEquals(256, GptByteDecoder.byteDecoder.size)
        assertEquals(0, values.first())
        assertEquals(255, values.last())
    }

    @Test
    fun emptyTokensGiveEmptyString() {
        assertEquals("", GptByteDecoder.decode(intArrayOf(), emptyMap(), 50257))
    }

    @Test
    fun unknownIdsAreSkipped() {
        // ids not present in the vocab are skipped, not crashed on.
        assertEquals("", GptByteDecoder.decode(intArrayOf(7, 8, 9), emptyMap(), 50257))
    }

    @Test
    fun allSpecialTokensGiveEmptyString() {
        val vocab = mapOf(50257 to "<|endoftext|>", 50258 to "<|startoftranscript|>")
        assertEquals("", GptByteDecoder.decode(intArrayOf(50258, 50257), vocab, 50257))
    }

    @Test
    fun incompleteMultibyteDoesNotCrash() {
        // A lone 0xC3 ("Ã") is half a UTF-8 sequence; must decode to a replacement char,
        // not throw.
        val vocab = mapOf(0 to "Ã")
        val text = GptByteDecoder.decode(intArrayOf(0), vocab, 50257)
        assertTrue(text.isNotEmpty()) // U+FFFD replacement, no exception
    }
}
