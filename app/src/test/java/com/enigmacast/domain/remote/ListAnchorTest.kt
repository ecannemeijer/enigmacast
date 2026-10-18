package com.enigmacast.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListAnchorTest {
    @Test fun anchorSitsInTheMiddle() {
        assertEquals(44, ListAnchor.firstVisible(50, 13, 200))      // 6 rows above, 6 below
        assertEquals(44, ListAnchor.firstVisible(50, 14, 200))
        assertEquals(0, ListAnchor.firstVisible(3, 14, 200))        // near the top: from the start
        assertEquals(186, ListAnchor.firstVisible(199, 14, 200))    // near the end: the last page
        assertEquals(0, ListAnchor.firstVisible(5, 14, 8))          // shorter than the screen
        assertEquals(0, ListAnchor.firstVisible(-1, 14, 200))
        assertEquals(0, ListAnchor.firstVisible(5, 14, 0))
        assertEquals(186, ListAnchor.firstVisible(500, 14, 200))    // stale anchor past the end
    }

    @Test fun rowsFromTheMeasuredLayout() {
        assertEquals(15, ListAnchor.fullRows(920, 61))
        assertEquals(1, ListAnchor.fullRows(40, 61))
        assertEquals(ListAnchor.DEFAULT_ROWS, ListAnchor.fullRows(0, 61))
    }

    /**
     * The position set while hidden is where the list opens, and focusing the anchor there moves
     * nothing: the row is fully visible, so the open check does not scroll and the bring-into-view
     * distance is 0. For every viewport / row height and anchor, including the first open
     * (rows not measured yet: [ListAnchor.DEFAULT_ROWS]) and both ends of the list.
     */
    @Test fun presetPositionIsTheFinalPosition() {
        for (viewport in listOf(560, 720, 920, 1000)) for (pitch in listOf(48, 57, 61, 75)) for (size in listOf(1, 5, 30, 400)) {
            val measured = ListAnchor.fullRows(viewport, pitch)
            for (rows in listOf(measured, minOf(measured, ListAnchor.DEFAULT_ROWS))) for (anchor in 0 until size) {
                val first = ListAnchor.firstVisible(anchor, rows, size)
                val top = (anchor - first) * pitch
                val row = pitch - 1   // the row without its spacing
                assertTrue("vp=$viewport pitch=$pitch size=$size rows=$rows anchor=$anchor", ListAnchor.fullyVisible(top, top + row, 0, viewport))
                assertEquals(0f, ListAnchor.bringDistance(top.toFloat(), row.toFloat(), viewport.toFloat()))
            }
        }
    }

    @Test fun bringIntoViewOnlyWhenNeeded() {
        assertEquals(0f, ListAnchor.bringDistance(100f, 60f, 900f))
        assertEquals(-20f, ListAnchor.bringDistance(-20f, 60f, 900f))     // cut off at the top
        assertEquals(40f, ListAnchor.bringDistance(880f, 60f, 900f))      // cut off at the bottom
        assertFalse(ListAnchor.fullyVisible(880, 940, 0, 900))
    }

    @Test fun cardsKeepRoomForTheirFocusScale() {
        // A 272 dp card (as px) in a 900 px grid, 7 % margin = 19 px each side.
        val m = 272f * 0.07f
        assertEquals(0f, ListAnchor.bringDistance(100f, 272f, 900f, m))
        // Its bottom at the very edge: scrolled on by the margin, so the scaled lines stay on screen.
        assertEquals(m, ListAnchor.bringDistance(628f, 272f, 900f, m), 0.01f)
        assertEquals(-m, ListAnchor.bringDistance(0f, 272f, 900f, m), 0.01f)
        // No room for the margin: the plain rule.
        assertEquals(10f, ListAnchor.bringDistance(20f, 890f, 900f, 50f))
        assertEquals(0f, ListAnchor.bringDistance(10f, 890f, 900f, 50f))
    }
}
