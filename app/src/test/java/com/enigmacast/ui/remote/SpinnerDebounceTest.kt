package com.enigmacast.ui.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinnerDebounceTest {
    @Test fun `short flaps never show the spinner`() {
        val d = SpinnerDebounce()
        var t = 0L
        repeat(20) {
            assertFalse(d.update(true, t)); t += 400
            assertFalse(d.update(false, t)); t += 400
        }
    }

    @Test fun `shows after a second of loading and stays through flaps`() {
        val d = SpinnerDebounce()
        d.update(true, 0)
        assertFalse(d.update(true, 900))
        assertTrue(d.update(true, 1_000))
        assertTrue(d.update(false, 1_100))
        assertTrue(d.update(true, 1_800))
        assertTrue(d.update(false, 1_900))
        assertTrue(d.update(false, 2_800))
        assertFalse(d.update(false, 2_900))
    }
}
