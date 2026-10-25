package com.enigmacast.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTest {
    private val hour = 3_600_000L

    @Test fun cardOnlyInTheLastMinute() {
        assertNull(UpNext.seconds(hour, hour - 61_000, ended = false))
        assertEquals(60, UpNext.seconds(hour, hour - 60_000, ended = false))
        assertEquals(30, UpNext.seconds(hour, hour - 29_500, ended = false))
        assertEquals(1, UpNext.seconds(hour, hour - 1, ended = false))
    }

    @Test fun cardStaysAtTheEnd() {
        // The old card vanished at 0 s left, which cancelled its own countdown.
        assertEquals(0, UpNext.seconds(hour, hour, ended = false))
        assertEquals(0, UpNext.seconds(hour, hour + 500, ended = false))
        assertEquals(0, UpNext.seconds(0, 0, ended = true))
    }

    @Test fun unknownLengthHasNoCard() {
        assertNull(UpNext.seconds(0, 5_000, ended = false))
        assertNull(UpNext.seconds(-1, 5_000, ended = false))
    }

    @Test fun endStartsTheNextUnlessCancelled() {
        assertTrue(UpNext.advanceOnEnd(hasNext = true, dismissed = false))
        assertFalse(UpNext.advanceOnEnd(hasNext = true, dismissed = true))
        assertFalse(UpNext.advanceOnEnd(hasNext = false, dismissed = false))
    }

    @Test fun errorInTheLastSecondsIsTheEnd() {
        assertTrue(UpNext.errorIsEnd(hour, hour - 4_000))
        assertFalse(UpNext.errorIsEnd(hour, hour - 30_000))
        assertFalse(UpNext.errorIsEnd(0, 0))
    }
}
