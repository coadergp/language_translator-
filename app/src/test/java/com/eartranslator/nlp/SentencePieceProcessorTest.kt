package com.eartranslator.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Builds a tiny synthetic SentencePiece `.spm` (the real protobuf wire format) and checks
 * that the parser, Viterbi encoder, and decoder behave.
 */
class SentencePieceProcessorTest {

    /** ModelProto.pieces is field 1; SentencePiece.piece is field 1, score is field 2. */
    private fun buildSpm(pieces: List<Pair<String, Float>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((piece, score) in pieces) {
            val inner = ByteArrayOutputStream()
            // field 1, wire type 2 (length-delimited string)
            inner.write(0x0A)
            val pb = piece.toByteArray(Charsets.UTF_8)
            writeVarint(inner, pb.size)
            inner.write(pb)
            // field 2, wire type 5 (fixed32 float, little-endian)
            inner.write(0x15)
            val bits = score.toRawBits()
            inner.write(bits and 0xFF)
            inner.write((bits ushr 8) and 0xFF)
            inner.write((bits ushr 16) and 0xFF)
            inner.write((bits ushr 24) and 0xFF)

            val innerBytes = inner.toByteArray()
            // outer field 1, wire type 2 (the SentencePiece message)
            out.write(0x0A)
            writeVarint(out, innerBytes.size)
            out.write(innerBytes)
        }
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) { out.write(b); break } else out.write(b or 0x80)
        }
    }

    private val sp = SentencePieceProcessor(
        buildSpm(
            listOf(
                "▁he" to -1f,   // "▁he"
                "llo" to -1f,
                "▁" to -3f,
                "he" to -2f,
                "l" to -5f,
                "o" to -5f
            )
        )
    )

    @Test
    fun parsesAllPieces() {
        assertEquals(6, sp.pieceCount)
    }

    @Test
    fun viterbiPicksHighestScoreSegmentation() {
        // "hello" → "▁hello"; best path is ▁he(-1)+llo(-1) = -2, beating ▁+he+... .
        assertEquals(listOf("▁he", "llo"), sp.encode("hello"))
    }

    @Test
    fun decodeRestoresSpacesFromMetaSymbol() {
        assertEquals("hello", sp.decode(listOf("▁he", "llo")))
    }

    @Test
    fun emptyAndBlankEncodeToNothing() {
        assertTrue(sp.encode("").isEmpty())
        assertTrue(sp.encode("   ").isEmpty())
    }

    @Test
    fun unknownCharactersFallBackToUnk() {
        // 'z' is in no piece → Viterbi uses the <unk> fallback rather than crashing.
        assertTrue(sp.encode("zzz").contains("<unk>"))
    }

    @Test
    fun multiWordRoundTrips() {
        val pieces = sp.encode("hello hello")
        assertEquals("hello hello", sp.decode(pieces))
    }

    @Test
    fun decodeEmptyIsEmptyString() {
        assertEquals("", sp.decode(emptyList()))
    }
}
