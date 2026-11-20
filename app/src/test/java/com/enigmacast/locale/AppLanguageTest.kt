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
        assertNull(AppLanguage.fromTag("es"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
    }

    @Test
    fun dutchTagsSelectNederlands() {
        assertEquals(AppLanguage.DUTCH, AppLanguage.fromTag("nl"))
        assertEquals(AppLanguage.DUTCH, AppLanguage.fromTag("nl-NL"))
        assertEquals("Nederlands", AppLanguage.DUTCH.nativeName)
    }

    @Test
    fun germanItalianFrenchTurkishAndPolishTagsSelectTheirLanguage() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de-AT"))
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromTag("it"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromTag("fr_BE"))
        assertEquals(AppLanguage.TURKISH, AppLanguage.fromTag("tr"))
        assertEquals(AppLanguage.POLISH, AppLanguage.fromTag("pl-PL"))
        assertEquals(listOf("en", "nl", "de", "it", "fr", "tr", "pl"), AppLanguage.entries.map { it.tag })
    }
}
