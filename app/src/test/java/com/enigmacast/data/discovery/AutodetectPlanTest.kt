package com.enigmacast.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutodetectPlanTest {
    @Test
    fun theScanCoversTheOwnSlash24ButNotTheTvItself() {
        val hosts = SubnetScan.candidates("192.168.1.42")
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.42" in hosts)
        assertTrue("192.168.1.209" in hosts)
        assertEquals("192.168.1.0/24", SubnetScan.subnetLabel("192.168.1.42"))
    }

    @Test
    fun onlyPrivateNetworksAreScanned() {
        assertEquals(253, SubnetScan.candidates("10.0.0.5").size)
        assertEquals(253, SubnetScan.candidates("172.20.3.7").size)
        assertTrue(SubnetScan.candidates("172.32.0.1").isEmpty())
        assertTrue(SubnetScan.candidates("8.8.8.8").isEmpty())
        assertTrue(SubnetScan.candidates("not-an-ip").isEmpty())
        assertEquals(listOf(80, 8080), SubnetScan.PORTS)
    }

    @Test
    fun mdnsHostsTryTheirPortFirstAndScannedHostsOnlyTheOpenOne() {
        val plan = AutodetectPlan.probes(
            mdns = listOf(DiscoveredHost("192.168.1.209", 8081)),
            scanned = listOf(DiscoveredHost("192.168.1.209", 80), DiscoveredHost("192.168.1.50", 8080)),
        ).toMap()
        assertEquals(listOf(8081, 80, 443, 8080), plan["192.168.1.209"])
        assertEquals(listOf(8080), plan["192.168.1.50"])
        assertEquals(2, plan.size)
    }

    @Test
    fun resultsAreOrderedByAddressNumerically() {
        val sorted = listOf("192.168.1.20", "192.168.1.9", "vuduo4k.local", "192.168.1.100").sortedBy(AutodetectPlan::sortKey)
        assertEquals(listOf("192.168.1.9", "192.168.1.20", "192.168.1.100", "vuduo4k.local"), sorted)
    }

    @Test
    fun theLabelShowsModelAndOnlyANonDefaultPort() {
        assertEquals("Vu+ Duo 4K (192.168.1.209)", DiscoveredReceiver("192.168.1.209", 80, model = "Vu+ Duo 4K").label)
        assertEquals("192.168.1.50:8080", DiscoveredReceiver("192.168.1.50", 8080).label)
        assertEquals("box (10.0.0.2)", DiscoveredReceiver("10.0.0.2", 443, useHttps = true, model = "box").label)
    }
}
