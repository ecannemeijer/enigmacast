package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRefTest {
    private val npo1 = "1:0:19:4651:3FC:1:C00000:0:0:0:"
    private val npo2 = "1:0:19:4652:3FC:1:C00000:0:0:0:"
    private val rtl4 = "1:0:19:7D5:3F0:1:C00000:0:0:0:"

    @Test
    fun servicesOnOneTransponderShareIt() {
        assertTrue(ServiceRef.sameTransponder(npo1, npo2))
        assertFalse(ServiceRef.sameTransponder(npo1, rtl4))
        assertEquals("3FC:1:C00000", ServiceRef.transponderKey(npo1))
    }

    @Test
    fun hexCaseAndLeadingZerosDoNotMatter() {
        assertTrue(ServiceRef.sameTransponder("1:0:1:a:03fc:0001:00c00000:0:0:0:", npo1))
    }

    @Test
    fun iptvAndMarkersNeedNoTuner() {
        assertFalse(ServiceRef.needsTuner("4097:0:1:0:0:0:0:0:0:0:http%3a//example/tv:NPO"))
        assertFalse(ServiceRef.needsTuner("1:0:1:0:0:0:0:0:0:0:http%3a//example/tv:NPO"))
        assertFalse(ServiceRef.needsTuner("1:64:0:0:0:0:0:0:0:0::Nieuws"))
        assertNull(ServiceRef.transponderKey("1:64:0:0:0:0:0:0:0:0::Nieuws"))
        assertTrue(ServiceRef.needsTuner(npo1))
    }

    @Test
    fun namespaceTellsDeliverySystem() {
        assertEquals(ServiceRef.Delivery.SATELLITE, ServiceRef.delivery(npo1))
        assertEquals(ServiceRef.Delivery.CABLE, ServiceRef.delivery("1:0:19:1:2:3:FFFF0000:0:0:0:"))
        assertEquals(ServiceRef.Delivery.TERRESTRIAL, ServiceRef.delivery("1:0:19:1:2:3:EEEE0000:0:0:0:"))
    }

    @Test
    fun tunerTypesMatchDelivery() {
        assertTrue(ServiceRef.tunerSupports("BCM45208 (DVB-S2)", ServiceRef.Delivery.SATELLITE))
        assertFalse(ServiceRef.tunerSupports("BCM45208 (DVB-S2)", ServiceRef.Delivery.CABLE))
        assertTrue(ServiceRef.tunerSupports("Si2169 (DVB-T2/C)", ServiceRef.Delivery.CABLE))
        assertTrue(ServiceRef.tunerSupports("Si2169 (DVB-T2/C)", ServiceRef.Delivery.TERRESTRIAL))
        assertFalse(ServiceRef.tunerSupports("Si2169 (DVB-T2/C)", ServiceRef.Delivery.SATELLITE))
    }

    @Test
    fun piconNameUsesTheTvTypeTheReceiverStores() {
        assertEquals("/picon/1_0_1_4651_3FC_1_C00000_0_0_0.png", ServiceRef.piconPath(npo1))
        assertEquals("/picon/1_0_1_1_2_3_4_0_0_0.png", ServiceRef.piconPath("1:0:16:1:2:3:4:0:0:0:"))
        assertEquals("/picon/1_0_1_1_2_3_4_0_0_0.png", ServiceRef.piconPath("1:0:1:1:2:3:4:0:0:0:"))
        assertEquals("/picon/1_0_2_1_2_3_4_0_0_0.png", ServiceRef.piconPath("1:0:2:1:2:3:4:0:0:0:"))
    }

    @Test
    fun streamClientAddressesAreNormalised() {
        assertEquals("192.168.1.20", ServiceRef.normalizeAddress("::ffff:192.168.1.20"))
        assertEquals("fe80::1", ServiceRef.normalizeAddress("FE80::1%wlan0"))
    }
}
