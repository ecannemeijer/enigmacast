package com.enigmacast.ui.components

import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavEdgesTest {
    @Test
    fun verticalExitsAreBlocked() {
        assertTrue(NavEdges.blocks(FocusDirection.Up))
        assertTrue(NavEdges.blocks(FocusDirection.Down))
    }

    @Test
    fun rightAndProgrammaticMovesPass() {
        assertFalse(NavEdges.blocks(FocusDirection.Right))
        assertFalse(NavEdges.blocks(FocusDirection.Left))
        assertFalse(NavEdges.blocks(FocusDirection.Enter))
        assertFalse(NavEdges.blocks(FocusDirection.Exit))
    }

    @Test
    fun leftFromAPageEntersTheMenuOnTheOpenSection() {
        assertEquals(NavEdges.Enter.SELECTED, NavEdges.enter(FocusDirection.Left, held = false))
    }

    @Test
    fun aHeldLeftNeverEntersTheMenu() {
        // Holding Left in a long row stops on its first item; only a new press opens the menu.
        assertEquals(NavEdges.Enter.CANCEL, NavEdges.enter(FocusDirection.Left, held = true))
    }

    @Test
    fun backAndOtherMovesEnterUnchanged() {
        // Back focuses the menu with requestFocus (Enter); held or not, it is never redirected.
        for (held in listOf(false, true)) {
            assertEquals(NavEdges.Enter.PASS, NavEdges.enter(FocusDirection.Enter, held))
            assertEquals(NavEdges.Enter.PASS, NavEdges.enter(FocusDirection.Up, held))
            assertEquals(NavEdges.Enter.PASS, NavEdges.enter(FocusDirection.Down, held))
            assertEquals(NavEdges.Enter.PASS, NavEdges.enter(FocusDirection.Right, held))
        }
    }
}
