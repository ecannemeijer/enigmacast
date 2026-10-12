package com.enigmacast.ui

import com.enigmacast.ui.components.ArtworkPalette
import com.enigmacast.ui.home.PivotMath
import com.enigmacast.ui.soccer.FocusChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageFocusTest {
    private val soccer = listOf("top", "fd-live", "fd-today", "fd-up-12", "fd-up-13", "fd-recent", "fd-table", "p-res-1", "p-up-1", "p-clubs-1")

    @Test fun downVisitsEverySectionInOrderAndUpComesBack() {
        val down = generateSequence("top") { FocusChain.targetKey(soccer, it, 1) }.toList()
        assertEquals(soccer, down)
        val up = generateSequence("p-clubs-1") { FocusChain.targetKey(soccer, it, -1) }.toList()
        assertEquals(soccer.reversed(), up)
        assertNull(FocusChain.targetKey(soccer, "top", -1))
        assertNull(FocusChain.targetKey(soccer, "gone", 1))
    }

    @Test fun pivotPutsSectionAtTopAndKeepsDeepItemsOnScreen() {
        // Section at 1000, short item: section top to the pivot line.
        assertEquals(992, PivotMath.offset(1000, 40, 120, 8, 5000, 600))
        // Row 20 of a long table (item at 900..940 inside the section): moved so it shows.
        val o = PivotMath.offset(1000, 900, 940, 8, 5000, 600)
        assertEquals(true, 1000 + 940 - o <= 600 && 1000 + 900 - o >= 0)
        // Never past the end of the content.
        assertEquals(4400, PivotMath.offset(4800, 0, 50, 8, 5000, 600))
    }

    @Test fun artworkColourIsStablePerTitle() {
        assertEquals(ArtworkPalette.indexFor("GTST"), ArtworkPalette.indexFor(" gtst "))
    }
}
