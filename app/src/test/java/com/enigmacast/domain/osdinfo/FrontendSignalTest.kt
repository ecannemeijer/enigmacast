package com.enigmacast.domain.osdinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendSignalTest {
    @Test
    fun percentStaysAPercentAndScalesTheDriverRange() {
        assertEquals(80, FrontendSignal.percent("80\n"))
        assertEquals(66, FrontendSignal.percent("66 %"))
        assertEquals((45_000L * 100 / 65535).toInt(), FrontendSignal.percent("45000"))
        assertNull(FrontendSignal.percent("N/A"))
        assertNull(FrontendSignal.percent(""))
    }

    @Test
    fun decibelsAcceptTextAndHundredths() {
        assertEquals(12.5f, FrontendSignal.decibels("12.50 dB"))
        assertEquals(12.5f, FrontendSignal.decibels("1250"))
        assertNull(FrontendSignal.decibels("N/A"))
    }

    @Test
    fun parseKeepsTheTunerAndDropsAnEmptyFrontend() {
        val reading = FrontendSignal.parse("70", "84", "11.2", tunerNumber = 1)
        assertEquals(70, reading?.snrPercent)
        assertEquals(84, reading?.agcPercent)
        assertEquals(11.2f, reading?.snrDb)
        assertEquals(1, reading?.tunerNumber)
        assertNull(FrontendSignal.parse("", "", null, 0))
    }

    @Test
    fun lockFileWinsOverANoisyReading() {
        val quiet = SignalReading(snrPercent = 0, agcPercent = 0, tunerNumber = 0)
        val live = SignalReading(snrPercent = 40, agcPercent = 0, tunerNumber = 0)
        assertTrue(FrontendSignal.locked("1", quiet))
        assertFalse(FrontendSignal.locked("0", live))
        assertTrue(FrontendSignal.locked(null, live))
        assertFalse(FrontendSignal.locked(null, quiet))
        assertTrue(FrontendSignal.locked(null, SignalReading(snrDb = 12.5f, tunerNumber = 0)))
    }
}
