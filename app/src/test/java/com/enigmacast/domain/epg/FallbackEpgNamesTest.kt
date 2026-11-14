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
}
