package com.eartranslator.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {

    @Test
    fun lookupByCodeAndDisplay() {
        assertEquals(Language.ENGLISH, Language.byCode("en"))
        assertEquals(Language.SPANISH, Language.byDisplay("Spanish"))
        assertEquals("en", Language.PIVOT)
    }

    @Test
    fun displayNamesMatchEntryCount() {
        assertEquals(Language.entries.size, Language.displayNames().size)
    }

    @Test
    fun codesAreUnique() {
        val codes = Language.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun displayNamesAreUnique() {
        val names = Language.entries.map { it.display }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun everyLanguageHasCodeAndVoice() {
        for (l in Language.entries) {
            assertTrue("blank code", l.code.isNotBlank())
            assertTrue("blank voice for ${l.code}", l.piperVoice.isNotBlank())
        }
    }

    @Test
    fun englishIsPresentAsPivot() {
        assertEquals(Language.PIVOT, Language.byCode("en").code)
    }
}
