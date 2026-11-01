package com.enigmacast.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFocusTest {
    private val rows = listOf(
        "actors" to listOf("p1", "p2"),
        "10" to listOf("m/1", "m/2", "m/3"),
        "11" to listOf("s/9"),
    )

    @Test
    fun returnSpotFindsTheChosenCard() {
        assertEquals(1 to 2, SearchFocus.returnSpot("10/m/3", rows, leadingItems = 0))
        assertEquals(2 to 0, SearchFocus.returnSpot("11/s/9", rows, leadingItems = 0))
        assertEquals(0 to 1, SearchFocus.returnSpot("actors/p2", rows, leadingItems = 0))
        assertEquals(3 to 0, SearchFocus.returnSpot("11/s/9", rows, leadingItems = 1))
    }

    @Test
    fun returnSpotIsNullWhenTheResultIsGone() {
        assertNull(SearchFocus.returnSpot("10/m/7", rows, 0))
        assertNull(SearchFocus.returnSpot("12/m/1", rows, 0))
        // Same card key in another row is not the chosen one.
        assertNull(SearchFocus.returnSpot("11/m/1", rows, 0))
    }

    @Test
    fun firstResultBlock() {
        assertEquals(SearchFocus.First.ACTORS, SearchFocus.first(2, 1, 1))
        assertEquals(SearchFocus.First.IPTV, SearchFocus.first(0, 1, 1))
        assertEquals(SearchFocus.First.HITS, SearchFocus.first(0, 0, 3))
        assertEquals(SearchFocus.First.NONE, SearchFocus.first(0, 0, 0))
    }

    @Test
    fun firstResultTakesFocusOnlyWhenAskedAndReady() {
        assertTrue(SearchFocus.focusFirstNow(pending = true, searching = false, hasResults = true))
        assertFalse(SearchFocus.focusFirstNow(pending = true, searching = true, hasResults = true))
        assertFalse(SearchFocus.focusFirstNow(pending = true, searching = false, hasResults = false))
        assertFalse(SearchFocus.focusFirstNow(pending = false, searching = false, hasResults = true))
    }
}
