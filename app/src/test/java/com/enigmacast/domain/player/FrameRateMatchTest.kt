package com.enigmacast.domain.player

import com.enigmacast.domain.player.FrameRateMatch.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatchTest {
    private val uhd60 = Mode(1, 3840, 2160, 60f)
    private val uhd50 = Mode(2, 3840, 2160, 50f)
    private val uhd24 = Mode(3, 3840, 2160, 23.976f)
    private val uhd5994 = Mode(4, 3840, 2160, 59.94f)
    private val fhd50 = Mode(5, 1920, 1080, 50f)
    private val all = listOf(uhd60, uhd50, uhd24, uhd5994, fhd50)

    @Test
    fun targets() {
        assertEquals(50f, FrameRateMatch.target(25f, dvb = false))
        assertEquals(50f, FrameRateMatch.target(50f, dvb = false))
        assertEquals(23.976f, FrameRateMatch.target(23.976f, dvb = false))
        assertEquals(24f, FrameRateMatch.target(24f, dvb = false))
        assertEquals(59.94f, FrameRateMatch.target(29.97f, dvb = false))
        assertEquals(59.94f, FrameRateMatch.target(59.94f, dvb = false))
        assertEquals(60f, FrameRateMatch.target(30f, dvb = false))
        assertEquals(60f, FrameRateMatch.target(60f, dvb = false))
        assertNull(FrameRateMatch.target(15f, dvb = false))
    }

    @Test
    fun unknownRateIs50HzOnlyForDvb() {
        assertEquals(50f, FrameRateMatch.target(-1f, dvb = true))
        assertNull(FrameRateMatch.target(-1f, dvb = false))
        assertNull(FrameRateMatch.target(Float.NaN, dvb = false))
    }

    @Test
    fun picksSameResolutionAndRate() {
        assertEquals(uhd50, FrameRateMatch.pick(all, uhd60, uhd60, 50f))
        assertEquals(uhd24, FrameRateMatch.pick(all, uhd60, uhd60, 24f))
        assertEquals(uhd24, FrameRateMatch.pick(all, uhd60, uhd60, 23.976f))
    }

    @Test
    fun staysWhenCurrentFits() {
        assertNull(FrameRateMatch.pick(all, uhd60, uhd60, 60f))
        assertNull(FrameRateMatch.pick(all, uhd60, uhd60, 59.94f))
        assertNull(FrameRateMatch.pick(all, uhd50, uhd60, 50f))
        val hz120 = Mode(9, 3840, 2160, 120f)
        assertNull(FrameRateMatch.pick(all + hz120, hz120, hz120, 24f))
    }

    @Test
    fun noMatchingModeIsNoOp() {
        assertNull(FrameRateMatch.pick(listOf(uhd60), uhd60, uhd60, 50f))
        // Never another resolution: only the 1080p mode has 50 Hz here.
        assertNull(FrameRateMatch.pick(listOf(uhd60, fhd50), uhd60, uhd60, 50f))
    }

    @Test
    fun sameRateBeatsMultiple() {
        val hz100 = Mode(7, 3840, 2160, 100f)
        assertEquals(uhd50, FrameRateMatch.pick(listOf(uhd60, hz100, uhd50), uhd60, uhd60, 50f))
        assertEquals(hz100, FrameRateMatch.pick(listOf(uhd60, hz100), uhd60, uhd60, 50f))
    }

    @Test
    fun backToOriginalResolutionFamily() {
        // Switched to 50 Hz, the next film wants 24: chosen from the original resolution.
        assertEquals(uhd24, FrameRateMatch.pick(all, uhd50, uhd60, 24f))
    }
}
