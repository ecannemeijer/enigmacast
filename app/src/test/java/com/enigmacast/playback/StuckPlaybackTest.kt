package com.enigmacast.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckPlaybackTest {
    @Test fun `no new frames while state flaps reports stuck once`() {
        val s = StuckPlayback(10_000)
        assertFalse(s.update(true, 500, 0))
        for (t in 1_000L..9_000L step 1_000) assertFalse(s.update(true, 500, t))
        assertTrue(s.update(true, 500, 10_000))
        assertFalse(s.update(true, 500, 11_000))
    }

    @Test fun `progress restarts the timer`() {
        val s = StuckPlayback(10_000)
        s.update(true, 1, 0)
        s.update(true, 2, 9_000)
        assertFalse(s.update(true, 2, 18_000))
        assertTrue(s.update(true, 2, 19_000))
    }

    @Test fun `paused or not started never counts`() {
        val s = StuckPlayback(10_000)
        s.update(true, 1, 0)
        assertFalse(s.update(false, 1, 20_000))
        assertFalse(s.update(true, 1, 21_000))
        assertFalse(s.update(true, 1, 30_000))
        assertTrue(s.update(true, 1, 31_000))
    }
}
