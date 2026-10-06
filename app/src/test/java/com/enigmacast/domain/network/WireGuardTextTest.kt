package com.enigmacast.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardTextTest {
    private val config = """
        [Interface]
        # TV
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.0.2/32
        DNS = 192.168.1.1

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        Endpoint = home.example.net:51820
        AllowedIPs = 192.168.1.0/24
        AllowedIPs = 10.8.0.0/24
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun readsACompleteConfigWithoutExposingKeys() {
        val summary = WireGuardText.summarize(config)
        assertTrue(summary.valid)
        assertEquals("home.example.net:51820", summary.endpoint)
        assertEquals(listOf("192.168.1.0/24", "10.8.0.0/24"), summary.allowedIps)
        assertEquals(1, summary.peers)
        assertFalse(summary.fullTunnel)
        assertFalse(summary.toString().contains("yAnz5TF"))
    }

    @Test
    fun listsWhatIsMissing() {
        assertEquals(listOf(WireGuardIssue.EMPTY), WireGuardText.summarize("  ").issues)
        val noPeer = WireGuardText.summarize("[Interface]\nPrivateKey = abc\n")
        assertEquals(listOf(WireGuardIssue.NO_ADDRESS, WireGuardIssue.NO_PEER), noPeer.issues)
        val noEndpoint = WireGuardText.summarize(config.lines().filterNot { it.startsWith("Endpoint") }.joinToString("\n"))
        assertEquals(listOf(WireGuardIssue.NO_ENDPOINT), noEndpoint.issues)
    }

    @Test
    fun spotsAFullTunnel() {
        assertTrue(WireGuardText.summarize(config.replace("AllowedIPs = 192.168.1.0/24", "AllowedIPs = 0.0.0.0/0, ::/0")).fullTunnel)
    }

    @Test
    fun checksWhetherTheHomeAddressGoesThroughTheTunnel() {
        val allowed = listOf("192.168.1.0/24", "10.8.0.0/24")
        assertEquals(true, WireGuardText.covers(allowed, "192.168.1.10"))
        assertEquals(false, WireGuardText.covers(allowed, "192.168.2.10"))
        assertEquals(true, WireGuardText.covers(listOf("0.0.0.0/0"), "8.8.8.8"))
        assertEquals(true, WireGuardText.covers(listOf("192.168.1.10"), "192.168.1.10"))
        assertNull(WireGuardText.covers(allowed, "vuduo4k.local"))
        assertEquals(false, WireGuardText.covers(listOf("bogus"), "192.168.1.10"))
    }
}
