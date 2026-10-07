package com.enigmacast.data.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutodetectPlanTest {
    @Test
    fun scannedHostsUseTheOpenPortAndMdnsHostsAlsoTheUsualOnes() {
        val plan = AutodetectPlan.probes(
            mdns = listOf(DiscoveredHost("192.168.1.30", 8081)),
            scanned = listOf(DiscoveredHost("192.168.1.209", 80), DiscoveredHost("192.168.1.50", 8080)),
        ).toMap()
        assertEquals(listOf(80), plan["192.168.1.209"])
        assertEquals(listOf(8080), plan["192.168.1.50"])
        assertEquals(listOf(8081, 80, 8080), plan["192.168.1.30"])
    }

    @Test
    fun resultsAreOrderedByAddressNumerically() {
        val sorted = listOf("192.168.1.20", "192.168.1.9", "vuduo4k.local", "192.168.1.100").sortedBy(AutodetectPlan::sortKey)
        assertEquals(listOf("192.168.1.9", "192.168.1.20", "192.168.1.100", "vuduo4k.local"), sorted)
    }

    @Test
    fun theLabelAlwaysShowsModelAndAddressWithPort() {
        assertEquals("SF8008 4K (192.168.1.209:80)", DiscoveredReceiver("192.168.1.209", 80, model = "SF8008 4K").label)
        assertEquals("192.168.1.50:8080", DiscoveredReceiver("192.168.1.50", 8080).label)
    }

    @Test
    fun theScanStopsAtItsBudgetAndKeepsWhatItFound() = runBlocking {
        val scanner = TcpPortScanner(parallel = 4, connectTimeoutMs = 400) { host, _, _ ->
            Thread.sleep(50)
            host.endsWith(".2")
        }
        val hosts = LanRange.hosts("192.168.1.42", 24)
        val started = System.currentTimeMillis()
        val outcome = scanner.scan(hosts, listOf(80), budgetMs = 300)
        val took = System.currentTimeMillis() - started
        assertTrue(outcome.timedOut)
        assertEquals(listOf(DiscoveredHost("192.168.1.2", 80)), outcome.hits)
        assertTrue("checked ${outcome.checked}", outcome.checked in 4 until outcome.total)
        assertTrue("took $took ms", took < 1_000)
    }

    @Test
    fun allPortEightyChecksComeBeforePort8080() = runBlocking {
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val scanner = TcpPortScanner(parallel = 1) { _, port, _ -> order += port; false }
        scanner.scan(listOf("10.0.0.1", "10.0.0.2"), listOf(80, 8080), budgetMs = 5_000)
        assertEquals(listOf(80, 80, 8080, 8080), order)
    }
}
