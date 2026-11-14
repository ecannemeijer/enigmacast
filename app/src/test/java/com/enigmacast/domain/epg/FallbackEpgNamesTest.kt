package com.enigmacast.domain.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackEpgNamesTest {
    @Test
    fun countryPrefixAndQualityAreStripped() {
        assertEquals("cartoonito", FallbackEpgNames.key("NL - Cartoonito"))
        assertEquals("cartoonito", FallbackEpgNames.key("NL - Cartoonito HD"))
        assertEquals("cartoonito", FallbackEpgNames.key("Cartoonito FHD"))
        assertEquals("cartoonito", FallbackEpgNames.key("Cartoonito 4K"))
        assertEquals("cartoonito", FallbackEpgNames.key("Cartoonito UHD"))
        assertEquals(FallbackEpgNames.key("Cartoonito"), FallbackEpgNames.key("|NL| Cartoonito HD"))
    }

    @Test
    fun plusAndAmpersandAreNormalised() {
        assertEquals(FallbackEpgNames.key("Eurosport plus 1 HD"), FallbackEpgNames.key("Eurosport + 1 HD"))
        // "+" becomes "plus"; a channel named "Discovery & TLC" matches "Discovery and TLC".
        assertEquals(FallbackEpgNames.key("Discovery and TLC"), FallbackEpgNames.key("Discovery & TLC HD"))
        assertEquals("1plus1", FallbackEpgNames.key("1+1"))
    }

    @Test
    fun tvgIdKeysIncludeTheStemAndTheNormalisedName() {
        val keys = FallbackEpgNames.tvgKeys("Cartoonito.nl")
        assertTrue(keys.contains("cartoonito.nl"))
        assertTrue(keys.contains("cartoonito"))
        assertTrue(FallbackEpgNames.tvgKeys("NPO1.NL").contains("npo1"))
    }

    @Test
    fun countriesAreReadFromPrefixesAndGroups() {
        assertEquals(setOf("nl"), FallbackEpgNames.countriesIn("NL - Cartoonito"))
        assertEquals(setOf("nl"), FallbackEpgNames.countriesIn("|NL| NPO 1 HD"))
        assertEquals(setOf("nl"), FallbackEpgNames.countriesIn("NL | Algemeen"))
        assertEquals(setOf("it"), FallbackEpgNames.countriesIn("IT: Rai 1"))
        assertEquals(setOf("gb"), FallbackEpgNames.countriesIn("UK - BBC One"))
        assertEquals(setOf("nl"), FallbackEpgNames.countriesIn("NLD: Veronica"))
        assertTrue(FallbackEpgNames.countriesIn("International Sports").isEmpty())
        assertTrue(FallbackEpgNames.countriesIn("NPO 1 HD").isEmpty())
    }


    @Test
    fun germanBouquetsAndChannelsSelectGermany() {
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("HD+ Deutschland"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("Sky Deutschland"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("Das Erste HD"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("ZDF HD"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("SAT.1 HD"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("ProSieben HD"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("kabel eins HD"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("SUPER RTL HD"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("DE - RTL Television"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("|DE| Entertainment"))
        assertEquals(setOf("de"), FallbackEpgNames.countriesIn("DE: ZDF"))
        assertTrue("de" in FallbackEpgNames.countriesIn("---------- ASTRA 19.2°E ----------"))
        assertTrue("at" in FallbackEpgNames.countriesIn("Canal+Austria"))
    }

    @Test
    fun twoLetterWordsInTitlesAreNotCountries() {
        assertTrue(FallbackEpgNames.countriesIn("Now You See Me: Now You Don\'t").isEmpty())
        assertEquals(setOf("it"), FallbackEpgNames.countriesIn("IT - ME-CONTRO-TE CHANNEL HD"))
        assertTrue(FallbackEpgNames.countriesIn("I Am: Celine Dion").isEmpty())
        assertTrue(FallbackEpgNames.countriesIn("Dragon Ball GT: A Hero\'s Legacy").isEmpty())
        assertEquals(emptySet<String>(), FallbackEpgNames.countriesIn("Stargate SG-1"))
        assertTrue(FallbackEpgNames.countriesIn("Bel-Air").isEmpty())
        assertTrue(FallbackEpgNames.countriesIn("NEXT | HANDBOLTAHÖLLIN (IS) | IS: LIVEY PPV 51").isEmpty())
        assertFalse("me" in FallbackEpgNames.countriesIn("NL - Believe Me: The Abduction"))
        assertEquals(setOf("nl"), FallbackEpgNames.countriesIn("NL - Believe Me: The Abduction"))
    }
}
