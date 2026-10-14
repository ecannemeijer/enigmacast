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
}
