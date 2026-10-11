package com.enigmacast.domain.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamReuseTest {
    private val npo = "1:0:19:1:2:3:4:0:0:0:"
    private val sbs = "1:0:19:9:2:3:4:0:0:0:"

    @Test
    fun keepsTheStreamWhenTheSameChannelIsAlreadyPlaying() {
        assertTrue(StreamReuse.alreadyPlaying(npo, "$npo:NPO 1", playing = true))
    }

    @Test
    fun rebuildsForAnotherChannelAStoppedStreamOrARetry() {
        assertFalse(StreamReuse.alreadyPlaying(npo, sbs, playing = true))
        assertFalse(StreamReuse.alreadyPlaying(npo, npo, playing = false))
        assertFalse(StreamReuse.alreadyPlaying(npo, npo, playing = true, force = true))
        assertFalse(StreamReuse.alreadyPlaying(null, npo, playing = true))
        assertFalse(StreamReuse.alreadyPlaying("", npo, playing = true))
    }
}
