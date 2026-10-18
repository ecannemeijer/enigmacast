package com.enigmacast.ui.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterMenuTest {
    @Test
    fun opensUnderTheChipLeftAligned() {
        // Sort chip at x=900, bottom 140 on 1920x1080; a 3-row popup of 300x170.
        assertEquals(900 to 152, FilterMenu.position(900, 140, 1920, 1080, 300, 170, gapPx = 12, marginPx = 32))
    }

    @Test
    fun staysOnScreenAtTheRightEdge() {
        assertEquals(1920 - 300 - 32 to 152, FilterMenu.position(1800, 140, 1920, 1080, 300, 170, 12, 32))
    }

    @Test
    fun tallListIsKeptAboveTheBottom() {
        val (_, y) = FilterMenu.position(900, 900, 1920, 1080, 300, 600, 12, 32)
        assertEquals(1080 - 600 - 32, y)
    }

    @Test
    fun selectedRowIsScrolledIntoView() {
        assertEquals(0, FilterMenu.firstVisible(2))
        assertEquals(9, FilterMenu.firstVisible(12))
    }
}
