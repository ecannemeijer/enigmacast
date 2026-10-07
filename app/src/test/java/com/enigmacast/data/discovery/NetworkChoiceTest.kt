package com.enigmacast.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkChoiceTest {
    @Test
    fun theLanIsChosenAndVpnsAreSkipped() {
        val chosen = NetworkChoice.choose(
            listOf(
                LocalNetwork("10.8.0.2", 32, "tun0", "interface", vpn = true),
                LocalNetwork("10.9.0.2", 24, "wg0", "interface"),
                LocalNetwork("192.168.1.42", 24, "eth0", "connectivity"),
                LocalNetwork("192.168.1.42", 24, "eth0", "interface"),
            ),
        )
        assertEquals(listOf("192.168.1.0/24"), chosen.map { it.label })
    }

    @Test
    fun ethernetComesBeforeWifiAndPublicAddressesAreIgnored() {
        val chosen = NetworkChoice.choose(
            listOf(
                LocalNetwork("192.168.2.7", 24, "wlan0"),
                LocalNetwork("84.10.20.30", 24, "eth1"),
                LocalNetwork("192.168.1.42", 24, "eth0"),
                LocalNetwork("172.17.0.1", 16, "docker0"),
            ),
        )
        assertEquals(listOf("192.168.1.0/24", "192.168.2.0/24"), chosen.map { it.label })
    }

    @Test
    fun aVpnAsTheActiveNetworkDoesNotHideTheLan() {
        val chosen = NetworkChoice.choose(
            listOf(
                LocalNetwork("10.66.66.2", 32, "", "connectivity", vpn = true),
                LocalNetwork("192.168.1.42", 24, "wlan0", "interface"),
            ),
        )
        assertEquals(listOf("192.168.1.42"), chosen.map { it.address })
    }

    @Test
    fun nothingUsableGivesAnEmptyChoice() {
        assertEquals(emptyList<LocalNetwork>(), NetworkChoice.choose(listOf(LocalNetwork("100.70.1.2", 10, "rmnet0"))))
    }
}
