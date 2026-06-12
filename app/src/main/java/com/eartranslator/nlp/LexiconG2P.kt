package com.eartranslator.nlp

import android.content.Context
import android.util.Log

/**
 * Permissive, pure-Kotlin grapheme-to-phoneme via a **pronunciation dictionary** — no
 * GPL, no native code, so it keeps the app closed-source-friendly.
 *
 * Loads a lexicon from `assets/models/g2p/<lang>/lexicon.txt`, one entry per line:
 *
 *     word<TAB>IPA phoneme string
 *
 * e.g.  `hello␉həˈloʊ`. At runtime it splits text into words, looks each up, and joins the
 * per-word IPA with a space (a word boundary). Out-of-vocabulary words are skipped.
 *
 * The IPA in the lexicon MUST use the phoneme inventory the target Piper voice expects
 * (the keys of its `phoneme_id_map` — which for standard voices is eSpeak's IPA set). The
 * easiest way to guarantee that match is to pre-generate the lexicon for your app's
 * vocabulary on your dev machine (e.g. with eSpeak-ng or DeepPhonemizer) — the *data* is
 * what ships, not the GPL tool. See README "Permissive phonemizer (no GPL)".
 */
class LexiconG2P private constructor(
    private val lexicon: Map<String, String>,
    private val lang: String
) : Phonemizer {

    companion object {
        private const val TAG = "LexiconG2P"

        /** Loads the lexicon for [lang], or returns null if there's no usable file. */
        fun loadOrNull(context: Context, lang: String): LexiconG2P? {
            val path = "models/g2p/$lang/lexicon.txt"
            return try {
                context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    fromLines(lines, lang)
                }
            } catch (e: Exception) {
                // No lexicon for this language — caller falls back to another phonemizer.
                null
            }
        }

        /** Parses lexicon lines into a [LexiconG2P]; returns null if no usable entries. */
        fun fromLines(lines: Sequence<String>, lang: String): LexiconG2P? {
            val map = HashMap<String, String>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val word = line.substring(0, tab).trim().lowercase()
                val ipa = line.substring(tab + 1).trim()
                if (word.isNotEmpty() && ipa.isNotEmpty()) map[word] = ipa
            }
            return if (map.isEmpty()) null
            else LexiconG2P(map, lang).also { Log.d(TAG, "Loaded ${map.size} entries for $lang") }
        }
    }

    override fun phonemize(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val parts = ArrayList<String>(words.size)
        var hits = 0
        var misses = 0
        for (w in words) {
            val ipa = lexicon[w]
            if (ipa != null) { parts.add(ipa); hits++ } else misses++
        }
        if (hits == 0) return null   // nothing covered → let the caller try another backend
        if (misses > 0) Log.d(TAG, "[$lang] $misses/${words.size} words OOV (skipped)")
        return parts.joinToString(" ")
    }
}
