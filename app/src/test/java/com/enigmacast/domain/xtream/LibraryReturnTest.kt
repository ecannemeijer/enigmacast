package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryReturnTest {
    @Test
    fun homeAndMyItemsOriginsAreTold() {
        assertTrue(LibraryReturn.isHome("home/fav/m/12"))
        assertFalse(LibraryReturn.isMyItems("home/fav/m/12"))
        assertTrue(LibraryReturn.isMyItems("2131230000/fav/m/12"))
        assertFalse(LibraryReturn.isHome(null))
        assertFalse(LibraryReturn.isMyItems(null))
    }

    @Test
    fun onlyThePageBeingReturnedToHoldsBackItsEntryFocus() {
        val myItems = "2131230000/fav/m/12"
        assertTrue(LibraryReturn.entryFocusYields(myItems, homePage = false))
        assertFalse(LibraryReturn.entryFocusYields(myItems, homePage = true))
        assertTrue(LibraryReturn.entryFocusYields("home/fav/m/12", homePage = true))
        assertFalse(LibraryReturn.entryFocusYields(null, homePage = false))
    }

    @Test
    fun backOnALibraryInfoPageLeavesTheIptvScreen() {
        assertTrue(LibraryReturn.infoBackLeaves("2131230000/fav/m/12"))
        assertTrue(LibraryReturn.infoBackLeaves("home/c/m/3"))
        // Opened from Search or the IPTV grid itself: Back stays on the grid.
        assertFalse(LibraryReturn.infoBackLeaves(null))
    }

    @Test
    fun theReturnKeyFindsItsRowAndCard() {
        val rows = listOf(
            "1" to listOf("c/m/1", "c/e/2"),
            "2" to listOf("fav/m/7", "fav/s/9"),
            "3" to listOf("tfav/m/5"),
        )
        assertEquals(1 to 1, LibraryReturn.locate("2/fav/s/9", rows))
        assertEquals(2 to 0, LibraryReturn.locate("3/tfav/m/5", rows))
        assertNull(LibraryReturn.locate("2/fav/m/404", rows))
        assertNull(LibraryReturn.locate("9/fav/m/7", rows))
    }

    @Test
    fun aRowIdIsNotMistakenForAPrefixOfAnother() {
        val rows = listOf("21" to listOf("a"), "2" to listOf("a"))
        assertEquals(1 to 0, LibraryReturn.locate("2/a", rows))
        assertEquals(0 to 0, LibraryReturn.locate("21/a", rows))
    }
}
