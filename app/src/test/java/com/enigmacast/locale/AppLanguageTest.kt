package com.enigmacast.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test
    fun englishIsTheDefaultWhenNothingSupportedWasChosen() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.DEFAULT)
        assertNull(AppLanguage.fromTag(null))
        assertNull(AppLanguage.fromTag(""))
        assertNull(AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
    }

    @Test
    fun dutchTagsSelectNederlands() {
        assertEquals(AppLanguage.DUTCH, AppLanguage.fromTag("nl"))
        assertEquals(AppLanguage.DUTCH, AppLanguage.fromTag("nl-NL"))
        assertEquals("Nederlands", AppLanguage.DUTCH.nativeName)
    }
}
