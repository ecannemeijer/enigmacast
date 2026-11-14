package com.enigmacast.domain.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackEpgCountriesTest {
    @Test
    fun sixtyFiveCountriesAreListedAndEnglishMapsToBritain() {
        assertEquals(65, FallbackEpgCountries.CODES.size)
        assertEquals("gb", FallbackEpgCountries.languageCountry("en"))
        assertEquals("gb", FallbackEpgCountries.languageCountry("en-GB"))
        assertEquals("nl", FallbackEpgCountries.languageCountry("nl-NL"))
        assertEquals("de", FallbackEpgCountries.languageCountry("de"))
        assertEquals("nl", FallbackEpgCountries.canonical("NLD"))
        assertEquals("gb", FallbackEpgCountries.canonical("UK"))
        assertEquals("https://iptv-epg.org/files/epg-nl.xml.gz", FallbackEpgCountries.url("NL"))
    }

    @Test
    fun autoPickUsesNamesLanguageAndExtrasButNotAUsLocaleAlone() {
        val fromNames = FallbackEpgCountries.auto(
            texts = listOf("NL - NPO 1", "IT | Rai 1"),
            appLanguage = "en",
            localeCountry = "US",
            extra = listOf("de"),
        )
        assertTrue(fromNames.containsAll(setOf("nl", "it", "gb", "de")))
        assertFalse("us" in fromNames)

        val usFromName = FallbackEpgCountries.auto(
            texts = listOf("US - CNN"),
            appLanguage = "en",
            localeCountry = "US",
        )
        assertTrue("us" in usFromName)
        assertTrue("gb" in usFromName)

        val extrasOnlyUs = FallbackEpgCountries.auto(
            texts = emptyList(),
            appLanguage = "fr",
            extra = listOf("US"),
        )
        assertEquals(setOf("fr", "us"), extrasOnlyUs)
    }


    @Test
    fun nineteenPointTwoEastAddsGermanyAustriaAndSwitzerland() {
        val picked = FallbackEpgCountries.auto(
            texts = listOf("HD+ Deutschland", "Das Erste HD"),
            appLanguage = "en",
            orbitalTenths = listOf(192),
        )
        assertTrue(picked.containsAll(setOf("de", "at", "ch", "gb")))
        assertEquals(setOf("de", "at", "ch"), FallbackEpgCountries.countriesFromOrbitals(listOf(192)))
        assertTrue(FallbackEpgCountries.countriesFromOrbitals(listOf(130, 235, 282)).isEmpty())
        assertTrue("de" in FallbackEpgCountries.auto(
            texts = listOf("---------- ASTRA 19.2°E ----------"),
            appLanguage = "it",
        ))
    }

    @Test
    fun midTitleTwoLetterTokensDoNotAddIcelandOrMontenegro() {
        val picked = FallbackEpgCountries.auto(
            texts = listOf(
                "Now You See Me: Now You Don\'t",
                "IT - ME-CONTRO-TE CHANNEL HD",
                "NEXT | HANDBOLTAHÖLLIN (IS) | IS: LIVEY PPV 51",
                "NL - NPO 1",
            ),
            appLanguage = "en",
        )
        assertTrue("nl" in picked)
        assertTrue("gb" in picked)
        assertTrue("it" in picked)
        assertFalse("is" in picked)
        assertFalse("me" in picked)
    }
}
