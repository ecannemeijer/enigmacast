package com.enigmacast.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusAfterRemoveTest {
    private val page = listOf(listOf("a", "b", "c"), listOf("d"), emptyList(), listOf("e", "f"))

    @Test fun nextInRow() = assertEquals("c", FocusAfterRemove.next(page, "b"))
    @Test fun previousWhenLast() = assertEquals("b", FocusAfterRemove.next(page, "c"))
    @Test fun rowBelowWhenEmptied() = assertEquals("e", FocusAfterRemove.next(page, "d"))
    @Test fun rowAboveWhenNothingBelow() = assertEquals("x", FocusAfterRemove.next(listOf(listOf("x", "y"), listOf("z")), "z"))
    @Test fun nothingLeft() = assertNull(FocusAfterRemove.next(listOf(listOf("a")), "a"))
    @Test fun unknown() = assertNull(FocusAfterRemove.next(page, "q"))
}
