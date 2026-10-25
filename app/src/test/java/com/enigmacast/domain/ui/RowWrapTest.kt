package com.enigmacast.domain.ui

import com.enigmacast.domain.ui.RowWrap.Move
import org.junit.Assert.assertEquals
import org.junit.Test

class RowWrapTest {
    @Test
    fun leftOnTheFirstGoesToTheLast() {
        assertEquals(Move.TO_LAST, RowWrap.at(0, 4, forward = false, repeat = false))
    }

    @Test
    fun rightOnTheLastGoesToTheFirst() {
        assertEquals(Move.TO_FIRST, RowWrap.at(4, 4, forward = true, repeat = false))
    }

    @Test
    fun insideTheRowTheNormalMoveRuns() {
        assertEquals(Move.NONE, RowWrap.at(0, 4, forward = true, repeat = false))
        assertEquals(Move.NONE, RowWrap.at(2, 4, forward = false, repeat = true))
        assertEquals(Move.NONE, RowWrap.at(4, 4, forward = false, repeat = false))
    }

    @Test
    fun aHeldKeyStopsAtTheEnd() {
        assertEquals(Move.HOLD, RowWrap.at(0, 4, forward = false, repeat = true))
        assertEquals(Move.HOLD, RowWrap.at(4, 4, forward = true, repeat = true))
    }

    @Test
    fun aRowOfOneDoesNotWrap() {
        assertEquals(Move.NONE, RowWrap.at(0, 0, forward = false, repeat = false))
        assertEquals(Move.NONE, RowWrap.at(0, 0, forward = true, repeat = false))
    }

    @Test
    fun playButtonsTwoOrThree() {
        // Play · Rewind: Left on Play → Rewind; Play · Rewind · Next: Right on Next → Play.
        assertEquals(Move.TO_LAST, RowWrap.at(0, 1, forward = false, repeat = false))
        assertEquals(Move.TO_FIRST, RowWrap.at(2, 2, forward = true, repeat = false))
    }

    @Test
    fun stepWrapsASelectionRow() {
        assertEquals(3, RowWrap.step(0, 4, forward = false, repeat = false))
        assertEquals(0, RowWrap.step(3, 4, forward = true, repeat = false))
        assertEquals(2, RowWrap.step(1, 4, forward = true, repeat = false))
        assertEquals(0, RowWrap.step(1, 4, forward = false, repeat = true))
    }

    @Test
    fun stepHoldsAtTheEndsWhileHeld() {
        assertEquals(0, RowWrap.step(0, 4, forward = false, repeat = true))
        assertEquals(3, RowWrap.step(3, 4, forward = true, repeat = true))
    }

    @Test
    fun stepOnTinyRows() {
        assertEquals(0, RowWrap.step(0, 1, forward = true, repeat = false))
        assertEquals(0, RowWrap.step(0, 0, forward = false, repeat = false))
        assertEquals(1, RowWrap.step(0, 2, forward = false, repeat = false))
    }
}
