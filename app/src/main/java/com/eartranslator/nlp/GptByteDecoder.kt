package com.eartranslator.nlp

/**
 * GPT-2 / Whisper byte-level BPE detokenizer.
 *
 * Whisper's vocab.json keys are byte-level-encoded strings: each raw UTF-8 byte is mapped
 * to a printable Unicode char via GPT-2's `bytes_to_unicode` table (so a space byte 0x20
 * shows up as 'Ġ'). [decode] reverses that: look up token strings, concatenate, map each
 * char back to its byte, and UTF-8-decode — correct for multibyte scripts too.
 *
 * Pure logic (no Android dependencies) so it is directly unit-testable.
 */
object GptByteDecoder {

    /** Unicode char (as used in byte-level token strings) → original byte value (0..255). */
    val byteDecoder: Map<Char, Int> by lazy { build() }

    /**
     * @param tokens generated token ids
     * @param idToToken vocab.json mapping id → token string
     * @param specialFloor ids >= this are special/non-text tokens and are skipped
     */
    fun decode(tokens: IntArray, idToToken: Map<Int, String>, specialFloor: Int): String {
        val sb = StringBuilder()
        for (id in tokens) {
            if (id >= specialFloor) continue
            sb.append(idToToken[id] ?: continue)
        }
        val bytes = ArrayList<Byte>(sb.length)
        for (ch in sb) byteDecoder[ch]?.let { bytes.add(it.toByte()) }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun build(): Map<Char, Int> {
        val bs = ArrayList<Int>()
        for (b in '!'.code..'~'.code) bs.add(b)
        for (b in '¡'.code..'¬'.code) bs.add(b)
        for (b in '®'.code..'ÿ'.code) bs.add(b)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val map = HashMap<Char, Int>(bs.size)
        for (i in bs.indices) map[cs[i].toChar()] = bs[i]
        return map
    }
}
