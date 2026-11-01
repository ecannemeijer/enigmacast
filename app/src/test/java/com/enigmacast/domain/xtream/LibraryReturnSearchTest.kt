package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryReturnSearchTest {
    @Test
    fun searchOriginIsNeitherHomeNorMyItems() {
        val origin = LibraryReturn.searchOrigin("2131230000", "s/77")
        assertEquals("search/2131230000/s/77", origin)
        assertTrue(LibraryReturn.isSearch(origin))
        assertFalse(LibraryReturn.isHome(origin))
        assertFalse(LibraryReturn.isMyItems(origin))
        assertTrue(LibraryReturn.infoBackLeaves(origin))
    }

    @Test
    fun searchKeyStripsThePrefixOnlyForSearch() {
        assertEquals("actors/p5", LibraryReturn.searchKey("search/actors/p5"))
        assertNull(LibraryReturn.searchKey("home/fav/m/12"))
        assertNull(LibraryReturn.searchKey(null))
    }

    @Test
    fun onlySearchHoldsBackForASearchReturn() {
        assertTrue(LibraryReturn.searchEntryYields("search/actors/p5"))
        assertFalse(LibraryReturn.searchEntryYields("home/fav/m/12"))
        assertFalse(LibraryReturn.searchEntryYields(null))
        assertFalse(LibraryReturn.entryFocusYields("search/actors/p5", homePage = true))
        assertFalse(LibraryReturn.entryFocusYields("search/actors/p5", homePage = false))
    }
}
