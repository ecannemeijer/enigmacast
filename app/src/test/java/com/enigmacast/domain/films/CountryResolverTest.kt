package com.enigmacast.domain.films

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryResolverTest {
    @Test
    fun overrideWins() {
        assertEquals("BE", CountryResolver.resolve("be", "nl", "DE", "NL"))
    }

    @Test
    fun autoUsesNetworkThenIpThenLocale() {
        assertEquals("NL", CountryResolver.resolve("AUTO", "nl", "DE", "US"))
        assertEquals("DE", CountryResolver.resolve("AUTO", "", "de", "US"))
        assertEquals("US", CountryResolver.resolve(null, null, null, "US"))
        assertEquals("NL", CountryResolver.resolve(null, null, "Undefined", ""))
    }

    @Test
    fun ukIsGb() {
        assertEquals("GB", CountryResolver.canonical("uk"))
        assertNull(CountryResolver.canonical("NLD"))
    }

    @Test
    fun tmdbLanguage() {
        assertEquals("nl-NL", CountryResolver.tmdbLanguage("nl", "NL"))
        assertEquals("nl-BE", CountryResolver.tmdbLanguage("nl", "BE"))
        assertEquals("nl-NL", CountryResolver.tmdbLanguage("nl", "DE"))
        assertEquals("en-GB", CountryResolver.tmdbLanguage("en", "NL"))
        assertEquals("en-US", CountryResolver.tmdbLanguage("en", "US"))
        assertEquals("de-DE", CountryResolver.tmdbLanguage("de", "DE"))
        assertEquals("de-DE", CountryResolver.tmdbLanguage("de", "NL"))
        assertEquals("fr-BE", CountryResolver.tmdbLanguage("fr", "BE"))
        assertEquals("it-IT", CountryResolver.tmdbLanguage("it", "NL"))
    }
}
