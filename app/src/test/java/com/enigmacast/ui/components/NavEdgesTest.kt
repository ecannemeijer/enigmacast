package com.enigmacast.ui.components

import androidx.compose.ui.focus.FocusDirection
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
}
