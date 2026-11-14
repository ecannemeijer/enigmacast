package com.enigmacast.domain.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackEpgPolicyTest {
    @Test
    fun refreshIsDueAfterTwelveHoursOrWhenNeverFetched() {
        val now = 20 * 60 * 60 * 1000L
        assertTrue(FallbackEpgPolicy.isDue(null, now))
        assertFalse(FallbackEpgPolicy.isDue(now - 11 * 60 * 60 * 1000L, now))
        assertTrue(FallbackEpgPolicy.isDue(now - 12 * 60 * 60 * 1000L, now))
    }

    @Test
    fun aRealGuideIsNeverReplaced() {
        assertEquals(listOf("real"), FallbackEpgPolicy.choose(listOf("real"), listOf("fallback")))
        assertEquals(listOf("fallback"), FallbackEpgPolicy.choose(emptyList(), listOf("fallback")))
        assertEquals(emptyList<String>(), FallbackEpgPolicy.choose(emptyList<String>(), emptyList<String>()))
    }
}
