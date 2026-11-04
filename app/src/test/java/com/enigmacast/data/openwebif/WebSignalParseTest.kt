package com.enigmacast.data.openwebif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSignalParseTest {
    @Test fun webSignalXml() {
        val r = OsdInfoJson.parseWebSignal("<e2frontendstatus><e2snrdb>15.25 dB</e2snrdb><e2snr>94 %</e2snr><e2ber>0</e2ber><e2acg>91 %</e2acg></e2frontendstatus>")!!
        assertEquals(94, r.snrPercent)
        assertEquals(91, r.agcPercent)
        assertEquals(15.25f, r.snrDb!!, 0.001f)
    }

    @Test fun emptyWebSignal() = assertNull(OsdInfoJson.parseWebSignal("<e2frontendstatus><e2snr>N/A</e2snr></e2frontendstatus>"))

    @Test fun numbersWithUnits() {
        assertEquals(94, OsdInfoJson.number("94 %"))
        assertEquals(15, OsdInfoJson.number("15.00"))
        assertEquals(91, OsdInfoJson.number("91"))
        assertNull(OsdInfoJson.number(""))
    }

    @Test fun apiSignalOtherTuner() {
        val r = OsdInfoJson.parseSignal("""{"tunertype":"DVB-S","tunernumber":"0","snr":"92","snr_db":"15.00","agc":"91 %","ber":"0","inStandby":"false"}""")!!
        assertEquals(92, r.snrPercent)
        assertEquals(91, r.agcPercent)
        assertEquals(0, r.tunerNumber)
    }
}
