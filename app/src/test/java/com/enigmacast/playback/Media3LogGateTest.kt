package com.enigmacast.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3LogGateTest {
    private val spam = "Target buffer size reached with less than 500ms of buffered media data."

    @Test
    fun releaseDropsLoadControlSpamButKeepsOtherTags() {
        val gate = Media3LogGate(release = true)
        assertNull(gate.admit("DefaultLoadControl", spam))
        assertEquals("boom", gate.admit("ExoPlayerImplInternal", "boom"))
    }

    @Test
    fun debugShowsTheFirstThenOnePerWindowWithACount() {
        var clock = 0L
        val gate = Media3LogGate(release = false, windowMs = 5_000) { clock }
        assertEquals(spam, gate.admit("DefaultLoadControl", spam))
        clock = 100; assertNull(gate.admit("DefaultLoadControl", spam))
        clock = 200; assertNull(gate.admit("DefaultLoadControl", spam))
        clock = 5_000
        assertEquals("$spam (+2 suppressed)", gate.admit("DefaultLoadControl", spam))
        clock = 10_000
        assertEquals(spam, gate.admit("DefaultLoadControl", spam))
    }

    @Test
    fun otherTagsAreNeverThrottled() {
        val gate = Media3LogGate(release = false) { 0L }
        repeat(5) { assertEquals("x", gate.admit("MediaCodecRenderer", "x")) }
    }
}
