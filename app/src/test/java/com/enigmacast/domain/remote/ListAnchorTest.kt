package com.enigmacast.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListAnchorTest {
    @Test fun anchorSitsInTheMiddle() {
        assertEquals(43, ListAnchor.firstVisible(50, 14, 200))
        assertEquals(0, ListAnchor.firstVisible(3, 14, 200))        // near the top: from the start
        assertEquals(186, ListAnchor.firstVisible(199, 14, 200))    // near the end: the last page
        assertEquals(0, ListAnchor.firstVisible(5, 14, 8))          // shorter than the screen
        assertEquals(0, ListAnchor.firstVisible(-1, 14, 200))
        assertEquals(0, ListAnchor.firstVisible(5, 14, 0))
        assertEquals(186, ListAnchor.firstVisible(500, 14, 200))    // stale anchor past the end
    }

    @Test fun onScreenExcludesCutOffEdgeRows() {
        assertTrue(ListAnchor.onScreen(50, 43, 57))
        assertFalse(ListAnchor.onScreen(43, 43, 57))
        assertFalse(ListAnchor.onScreen(57, 43, 57))
        assertTrue(ListAnchor.onScreen(0, 0, 13))
        assertFalse(ListAnchor.onScreen(80, 43, 57))
    }
}
