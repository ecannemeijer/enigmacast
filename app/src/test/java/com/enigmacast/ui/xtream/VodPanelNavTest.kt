package com.enigmacast.ui.xtream

import com.enigmacast.ui.xtream.VodPanelNav.Column
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPanelNavTest {
    @Test fun rightEntersGridWheneverCardsAreShown() {
        assertTrue(VodPanelNav.canEnterGrid(216))
        assertFalse(VodPanelNav.canEnterGrid(0))
    }
    @Test fun backIsOnePressPerColumn() {
        assertEquals(Column.CATEGORIES, VodPanelNav.back(Column.GRID))
        assertEquals(Column.SECTIONS, VodPanelNav.back(Column.CATEGORIES))
    }

    @Test fun categoriesCollapseWhileGridHasFocus() {
        assertFalse(VodPanelNav.categoriesShown(gridActive = true))
        assertTrue(VodPanelNav.categoriesShown(gridActive = false))
    }

    @Test fun collapsedGridGetsMoreColumns() {
        val narrow = VodPanelNav.columns(1000f - 256f, 132f, 16f)
        val wide = VodPanelNav.columns(1000f, 132f, 16f)
        assertEquals(5, narrow)
        assertEquals(6, wide)
        assertEquals(1, VodPanelNav.columns(10f, 132f, 16f))
    }

    @Test fun focusedPosition() {
        assertEquals("5/60", VodPanelNav.position(4, 60))
        assertEquals("60", VodPanelNav.position(null, 60))
        assertEquals("60", VodPanelNav.position(99, 60))
        assertEquals(null, VodPanelNav.position(0, 0))
    }
}
