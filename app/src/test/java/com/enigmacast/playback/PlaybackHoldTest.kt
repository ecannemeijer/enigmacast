package com.enigmacast.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHoldTest {
    private val npo1 = StreamRequest("http://box:8001/1:0:19:4651:3FC:1:C00000:0:0:0:", null, checkStart = true)
    private val npo2 = StreamRequest("http://box:8001/1:0:19:4652:3FC:1:C00000:0:0:0:", "Basic x", checkStart = false)

    @Test
    fun startsAreBlockedWhileHeldAndTheStreamResumesAfterwards() {
        val hold = PlaybackHold()
        assertTrue(hold.admit(npo1))
        assertTrue(hold.hold(npo1))
        assertFalse(hold.hold(npo2))
        // A reconnect or a periodic refresh during the download does not start anything.
        assertFalse(hold.admit(npo1))
        assertEquals(npo1, hold.release(resume = true))
        assertFalse(hold.held)
        assertTrue(hold.admit(npo2))
    }

    @Test
    fun theLatestRequestWinsAndNothingResumesWhenNothingPlayed() {
        val hold = PlaybackHold()
        hold.hold(null)
        assertFalse(hold.admit(npo2))
        assertEquals(npo2, hold.release(resume = true))

        hold.hold(null)
        assertNull(hold.release(resume = true))
        hold.hold(npo1)
        assertNull(hold.release(resume = false))
        assertNull(hold.release(resume = true))
    }
}
