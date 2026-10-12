package com.enigmacast.domain.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test fun caseAndAccentInsensitive() {
        assertTrue(SearchText.contains("Café Society", "cafe"))
        assertTrue(SearchText.contains("Amélie", "AMELIE"))
        assertTrue(SearchText.contains("Straße", "strasse"))
        assertTrue(SearchText.contains("zdf neo", "ZDF"))
        assertFalse(SearchText.contains("NPO 1", "rtl"))
    }
}
