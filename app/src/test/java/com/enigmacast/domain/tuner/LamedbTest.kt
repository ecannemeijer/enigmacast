package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LamedbTest {
    @Test
    fun referenceTransponderUsesTheTelevisionService() {
        val hit = Lamedb.serviceOn(SAMPLE, SignalReferences.satellites().first { it.label.contains("19.2") }.transponders)
        val (transponder, service) = requireNotNull(hit)
        assertEquals("11836 H 27500", transponder.caption)
        assertEquals("Das Erste", service.name)
        assertEquals("1:0:1:D49:ABC:1:C00000:0:0:0:", service.reference)
    }

    @Test
    fun westPositionAndTheFirstListedTransponderWin() {
        val satellite = SignalReferences.satellites().first { it.label.contains("5.0") }
        val hit = Lamedb.serviceOn(SAMPLE, satellite.transponders)
        val (transponder, service) = requireNotNull(hit)
        assertEquals(11554, transponder.frequencyMhz)
        assertEquals(false, transponder.horizontal)
        assertEquals("West channel", service.name)
        assertEquals(3550, service.orbitalTenths)
    }

    @Test
    fun aTransponderWithNoServiceIsSkipped() {
        val nowhere = listOf(ReferenceTransponder(192, 11000, horizontal = true, symbolRateKsym = 27500))
        assertNull(Lamedb.serviceOn(SAMPLE, nowhere))
    }

    @Test
    fun theMeterListsTheReferenceSatellites() {
        val labels = SignalReferences.satellites().map { it.label }
        assertEquals(12, labels.size)
        assertEquals("0.8°W · Thor 5 / 6 / 7", labels[1])
        assertEquals("42.0°E · Türksat 3A / 4A / 5B", labels.last())
    }

    private companion object {
        val SAMPLE = """
            eDVB services /4/
            transponders
            00c00000:0abc:0001
            	s 11836000:27500000:0:3:192:2:0
            /
            01620000:1111:0001
            	s 11554000:29900000:1:3:-50:2:0:1:3:0:0
            /
            end
            services
            0001:00c00000:0abc:0001:2:0:0
            Radio
            p:ARD
            00d49:00c00000:0abc:0001:1:0:0
            Das Erste
            p:ARD
            0002:01620000:1111:0001:1:0:0
            West channel
            p:Eutelsat
            end
            """.trimIndent()
    }
}
