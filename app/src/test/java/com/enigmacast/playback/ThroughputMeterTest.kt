package com.enigmacast.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ThroughputMeterTest {
    @Test
    fun firstSampleIsTakenAsIsThenSmoothed() {
        val meter = ThroughputMeter(smoothing = 0.5)
        assertEquals(0, meter.kbps)
        meter.add(1_000_000, 1_000) // 8 Mbit/s
        assertEquals(8_000, meter.kbps)
        meter.add(0, 1_000)
        assertEquals(4_000, meter.kbps)
        meter.add(500_000, 0) // ignored
        assertEquals(4_000, meter.kbps)
        meter.reset()
        assertEquals(0, meter.kbps)
    }
}
