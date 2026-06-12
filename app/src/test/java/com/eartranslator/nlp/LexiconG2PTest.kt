package com.eartranslator.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LexiconG2PTest {

    private val g2p = LexiconG2P.fromLines(
        sequenceOf(
            "# comment line is ignored",
            "hello\thəˈloʊ",
            "world\twɜːld",
            ""
        ),
        "en"
    )!!

    @Test
    fun joinsWordPhonemesWithSpace() {
        assertEquals("həˈloʊ wɜːld", g2p.phonemize("Hello world"))
    }

    @Test
    fun skipsOutOfVocabWordsButKeepsHits() {
        assertEquals("həˈloʊ", g2p.phonemize("hello xyzzy"))
    }

    @Test
    fun returnsNullWhenNothingCovered() {
        // No covered words → null, so PiperTTS can fall back to another phonemizer.
        assertNull(g2p.phonemize("xyzzy foobar"))
    }

    @Test
    fun emptyLexiconIsNull() {
        assertNull(LexiconG2P.fromLines(sequenceOf("# only comments"), "en"))
    }

    @Test
    fun emptyAndPunctuationOnlyInputReturnNull() {
        assertNull(g2p.phonemize(""))
        assertNull(g2p.phonemize("   "))
        assertNull(g2p.phonemize("!!! ??? ..."))
    }

    @Test
    fun numbersAndPunctuationAreStrippedAroundWords() {
        // "hello, world! 123" → words hello/world covered, digits dropped.
        assertEquals("həˈloʊ wɜːld", g2p.phonemize("hello, world! 123"))
    }

    @Test
    fun malformedLinesAreIgnored() {
        val g = LexiconG2P.fromLines(
            sequenceOf("noTabHere", "good\tɡʊd", "   ", "alsoBad"),
            "en"
        )!!
        assertEquals("ɡʊd", g.phonemize("good"))
    }

    @Test
    fun apostropheKeepsWordIntact() {
        val g = LexiconG2P.fromLines(sequenceOf("it's\tɪts"), "en")!!
        assertEquals("ɪts", g.phonemize("It's"))
    }

    @Test
    fun duplicateWordLastDefinitionWins() {
        val g = LexiconG2P.fromLines(sequenceOf("a\tx", "a\ty"), "en")!!
        assertEquals("y", g.phonemize("a"))
    }
}
