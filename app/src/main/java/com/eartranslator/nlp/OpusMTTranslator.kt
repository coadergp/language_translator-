package com.eartranslator.nlp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.eartranslator.config.Language
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Machine translation using Helsinki-NLP opus-mt (encoder + decoder ONNX) with a
 * SentencePiece tokenizer per direction.
 *
 * ENGLISH-PIVOT ROUTING
 * ---------------------
 * To avoid the N×(N−1) explosion of pairwise models, any pair X→Y is translated as
 * X→English→Y, unless one side is already English (then it's a single hop). So you only
 * ship the `en-<code>` and `<code>-en` model folders per language, never every
 * combination. See [com.eartranslator.config.Language].
 *
 * A single direction (one opus-mt model) is encapsulated in [Stage]; this class composes
 * one stage (when English is involved) or two stages (the pivot case). [load] picks the
 * route; [translate] runs it. Models are loaded from
 * assets/models/opus-mt/<src>-<tgt>/ and are swappable at runtime.
 */
class OpusMTTranslator(private val context: Context) {

    companion object {
        private const val TAG = "OpusMT"
    }

    private var stage1: Stage? = null   // src → (en | tgt)
    private var stage2: Stage? = null   // en → tgt   (pivot only)
    private var route: String? = null   // e.g. "fr->en->es" for logging / dedupe
    private var passthrough = false     // src == tgt

    /**
     * Loads the model(s) for a language pair, choosing single-hop or English-pivot.
     * No-op if the same route is already loaded.
     */
    fun load(src: String, tgt: String) {
        when {
            src == tgt -> {
                if (route == "passthrough") return
                close(); passthrough = true; route = "passthrough"
                Log.d(TAG, "Same language ($src) — passthrough")
            }
            src == Language.PIVOT || tgt == Language.PIVOT -> {
                val r = "$src->$tgt"
                if (r == route) return
                close()
                stage1 = Stage.load(context, src, tgt)
                route = r
                Log.d(TAG, "Loaded single-hop $r")
            }
            else -> {
                val r = "$src->${Language.PIVOT}->$tgt"
                if (r == route) return
                close()
                stage1 = Stage.load(context, src, Language.PIVOT)   // X → en
                stage2 = Stage.load(context, Language.PIVOT, tgt)   // en → Y
                route = r
                Log.d(TAG, "Loaded pivot $r")
            }
        }
    }

    fun translate(text: String): String {
        if (text.isBlank()) return ""
        if (passthrough) return text
        val first = stage1?.translate(text) ?: return ""
        val second = stage2 ?: return first
        return second.translate(first)
    }

    fun close() {
        stage1?.close(); stage1 = null
        stage2?.close(); stage2 = null
        route = null
        passthrough = false
    }

    /**
     * One opus-mt direction: encoder + decoder + SentencePiece + the model's `vocab.json`.
     *
     * Piece⇄id mapping: opus-mt/Marian tokenizers segment text into SentencePiece pieces
     * and then map those piece **strings** to integer ids via a shared `vocab.json`
     * (`{ "piece": id }`). The ONNX model consumes those vocab ids, so we map here rather
     * than using the .spm's internal ids. Special ids (pad/eos/unk) are read from the same
     * vocab; Marian uses pad as the decoder start token and `</s>` as eos.
     *
     * Expected ONNX I/O (MarianMT/opus-mt export):
     *   encoder: in "input_ids" int64 [1,S], "attention_mask" int64 [1,S]
     *            out "last_hidden_state" float32 [1,S,H]
     *   decoder: in "input_ids" int64 [1,T], "encoder_attention_mask" int64 [1,S],
     *               "encoder_hidden_states" float32 [1,S,H]
     *            out "logits" float32 [1,T,vocab]
     */
    private class Stage(
        private val encoder: OrtSession,
        private val decoder: OrtSession,
        private val srcSpm: SentencePieceProcessor,
        private val tgtSpm: SentencePieceProcessor,
        private val pieceToId: Map<String, Int>,
        private val idToPiece: Map<Int, String>,
        private val padId: Int,
        private val eosId: Int,
        private val unkId: Int
    ) {
        companion object {
            private const val MAX_DECODE_TOKENS = 128

            fun load(context: Context, src: String, tgt: String): Stage {
                val base = "models/opus-mt/$src-$tgt"
                val enc = OnnxEnv.sessionFromAsset(context, "$base/encoder_model.onnx")
                val dec = OnnxEnv.sessionFromAsset(context, "$base/decoder_model.onnx")
                val srcSpm = SentencePieceProcessor(OnnxEnv.readAsset(context, "$base/source.spm"), "SPM-$src")
                val tgtSpm = SentencePieceProcessor(OnnxEnv.readAsset(context, "$base/target.spm"), "SPM-$tgt")

                // vocab.json: { piece -> id }. Build both directions and resolve specials.
                val vocabJson = JSONObject(String(OnnxEnv.readAsset(context, "$base/vocab.json"), Charsets.UTF_8))
                val p2i = HashMap<String, Int>(vocabJson.length())
                val i2p = HashMap<Int, String>(vocabJson.length())
                val keys = vocabJson.keys()
                while (keys.hasNext()) {
                    val piece = keys.next()
                    val id = vocabJson.getInt(piece)
                    p2i[piece] = id
                    i2p[id] = piece
                }
                val pad = p2i["<pad>"] ?: 0
                val eos = p2i["</s>"] ?: 0
                val unk = p2i["<unk>"] ?: pad
                return Stage(enc, dec, srcSpm, tgtSpm, p2i, i2p, pad, eos, unk)
            }
        }

        private val env = OnnxEnv.ortEnv
        private var hiddenSize = 512

        fun translate(text: String): String {
            // Tokenize → pieces → vocab ids, append eos.
            val pieces = srcSpm.encode(text)
            if (pieces.isEmpty()) return ""
            val ids = ArrayList<Int>(pieces.size + 1)
            for (p in pieces) ids.add(pieceToId[p] ?: unkId)
            ids.add(eosId)

            val s = ids.size
            val inputIds = LongArray(s) { ids[it].toLong() }
            val attnMask = LongArray(s) { 1L }

            val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, s.toLong()))
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attnMask), longArrayOf(1, s.toLong()))

            val encHidden: Array<FloatArray> = try {
                encoder.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val out = r[0].value as Array<Array<FloatArray>>
                    hiddenSize = out[0][0].size
                    out[0]
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encoder failed", e)
                return ""
            } finally {
                idsTensor.close()
                maskTensor.close()
            }

            val outIds = greedyDecode(encHidden, attnMask)

            // ids → pieces (skip specials) → text.
            val outPieces = outIds.mapNotNull { id ->
                if (id == eosId || id == padId || id == unkId) null else idToPiece[id]
            }
            return tgtSpm.decode(outPieces)
        }

        private fun greedyDecode(encHidden: Array<FloatArray>, srcMask: LongArray): List<Int> {
            val s = encHidden.size
            val flatEnc = FloatArray(s * hiddenSize)
            var k = 0
            for (row in encHidden) for (v in row) flatEnc[k++] = v

            val generated = ArrayList<Long>()
            generated.add(padId.toLong())   // Marian: pad token id is the decoder start
            val result = ArrayList<Int>()

            for (step in 0 until MAX_DECODE_TOKENS) {
                val decIds = generated.toLongArray()
                val decTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decIds), longArrayOf(1, decIds.size.toLong()))
                val encMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(srcMask), longArrayOf(1, s.toLong()))
                val encTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(flatEnc), longArrayOf(1, s.toLong(), hiddenSize.toLong())
                )

                val next = try {
                    decoder.run(
                        mapOf(
                            "input_ids" to decTensor,
                            "encoder_attention_mask" to encMaskTensor,
                            "encoder_hidden_states" to encTensor
                        )
                    ).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = r[0].value as Array<Array<FloatArray>>
                        argmax(logits[0][generated.size - 1])
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decoder step failed", e)
                    eosId
                } finally {
                    decTensor.close()
                    encMaskTensor.close()
                    encTensor.close()
                }

                if (next == eosId && step > 0) break
                generated.add(next.toLong())
                result.add(next)
            }
            return result
        }

        private fun argmax(arr: FloatArray): Int {
            var best = 0
            var bestV = arr[0]
            for (i in 1 until arr.size) if (arr[i] > bestV) { bestV = arr[i]; best = i }
            return best
        }

        fun close() {
            encoder.close()
            decoder.close()
        }
    }
}
