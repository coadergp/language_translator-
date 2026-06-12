package com.eartranslator.nlp

import android.util.Log
import kotlin.math.max

/**
 * Pure-Kotlin SentencePiece **Unigram** tokenizer for Helsinki-NLP opus-mt models.
 *
 * This is a real implementation (no native dependency): it parses the `.spm` model file —
 * which is a serialized `sentencepiece_model.proto` — to recover every subword piece and
 * its log-probability score, then:
 *   - [encode]: normalizes text (whitespace → `▁`, dummy prefix) and runs **Viterbi**
 *     segmentation to find the maximum-score split into known pieces (the Unigram model),
 *   - [decode]: concatenates pieces and restores spaces from the `▁` meta symbol.
 *
 * NOTE on IDs: this class works in terms of piece **strings**. opus-mt/Marian maps pieces
 * to the model's integer ids via a separate `vocab.json`, so the piece⇄id mapping lives in
 * [OpusMTTranslator.Stage], not here.
 *
 * Protobuf parsing is done with a tiny hand-rolled wire-format reader (only the two fields
 * we need: `ModelProto.pieces[].piece` and `.score`), avoiding a protobuf runtime dep.
 */
class SentencePieceProcessor(spmModel: ByteArray, private val tag: String = "SPM") {

    companion object {
        /** SentencePiece whitespace meta symbol (U+2581 "▁"). */
        const val SPACE = '▁'
        private const val UNK = "<unk>"
    }

    private val pieceToScore = HashMap<String, Float>()
    private var maxPieceLen = 1
    private var minScore = 0f
    private val unkPenalty: Float
    val pieceCount: Int

    init {
        parse(spmModel)
        pieceCount = pieceToScore.size
        unkPenalty = minScore - 10f
        Log.d(tag, "Parsed SentencePiece model: $pieceCount pieces, maxLen=$maxPieceLen")
    }

    // region Public API ----------------------------------------------------------

    /** Tokenizes text into SentencePiece pieces (strings, including the `▁` prefix). */
    fun encode(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // Default SP normalization: collapse whitespace to a single `▁` and add a dummy
        // prefix `▁` at the start.
        val norm = SPACE + text.trim().replace(Regex("\\s+"), SPACE.toString())
        return viterbi(norm)
    }

    /** Joins pieces back into text, restoring spaces from `▁`. */
    fun decode(pieces: List<String>): String =
        pieces.joinToString("").replace(SPACE, ' ').trim()

    // endregion

    // region Unigram Viterbi -----------------------------------------------------

    private fun viterbi(s: String): List<String> {
        val n = s.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backStart = IntArray(n + 1) { -1 }
        val backPiece = arrayOfNulls<String>(n + 1)
        best[0] = 0f

        for (i in 1..n) {
            val lo = max(0, i - maxPieceLen)
            for (j in lo until i) {
                if (best[j] == Float.NEGATIVE_INFINITY) continue
                val sub = s.substring(j, i)
                val sc = pieceToScore[sub] ?: continue
                val cand = best[j] + sc
                if (cand > best[i]) {
                    best[i] = cand; backStart[i] = j; backPiece[i] = sub
                }
            }
            // Unknown fallback: if no known piece ends here, consume a single char as <unk>.
            if (best[i] == Float.NEGATIVE_INFINITY && best[i - 1] != Float.NEGATIVE_INFINITY) {
                best[i] = best[i - 1] + unkPenalty
                backStart[i] = i - 1
                backPiece[i] = UNK
            }
        }

        // Backtrack.
        if (best[n] == Float.NEGATIVE_INFINITY) return emptyList()
        val out = ArrayList<String>()
        var i = n
        while (i > 0) {
            out.add(backPiece[i] ?: UNK)
            val j = backStart[i]
            if (j < 0) break
            i = j
        }
        out.reverse()
        return out
    }

    // endregion

    // region .spm protobuf parsing ----------------------------------------------

    private fun parse(buf: ByteArray) {
        val r = Reader(buf)
        while (r.hasMore()) {
            val tagByte = r.readVarint().toInt()
            val field = tagByte ushr 3
            val wire = tagByte and 0x7
            if (field == 1 && wire == 2) {
                // ModelProto.pieces[i] : SentencePiece message
                parsePiece(r.readLengthDelimited())
            } else {
                r.skip(wire)
            }
        }
        if (pieceToScore.isEmpty()) Log.e(tag, "No pieces parsed from .spm — wrong format?")
    }

    private fun parsePiece(msg: ByteArray) {
        val r = Reader(msg)
        var piece: String? = null
        var score = 0f
        while (r.hasMore()) {
            val tagByte = r.readVarint().toInt()
            val field = tagByte ushr 3
            val wire = tagByte and 0x7
            when {
                field == 1 && wire == 2 -> piece = String(r.readLengthDelimited(), Charsets.UTF_8)
                field == 2 && wire == 5 -> score = r.readFloat()
                else -> r.skip(wire)
            }
        }
        piece?.let {
            pieceToScore[it] = score
            if (it.length > maxPieceLen) maxPieceLen = it.length
            if (score < minScore) minScore = score
        }
    }

    /** Minimal protobuf wire-format reader (varint, length-delimited, fixed32/64). */
    private class Reader(private val buf: ByteArray) {
        private var pos = 0
        fun hasMore() = pos < buf.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        fun readLengthDelimited(): ByteArray {
            val len = readVarint().toInt()
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun readFloat(): Float {
            val bits = (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8           // fixed64
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> pos += 4           // fixed32
                else -> throw IllegalStateException("Unknown wire type $wire")
            }
        }
    }

    // endregion
}
