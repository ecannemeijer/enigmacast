package com.enigmacast.domain.network

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.validation.ReceiverConfigValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverRoutingTest {
    private val home = ReceiverConfig(id = "a", name = "Box", host = "192.168.1.10", httpPort = 80, streamingPort = 8001)
    private val withRemote = home.copy(remoteHost = "mybox.dyndns.org", remoteHttpPort = 8443, remoteUseHttps = true, remoteStreamingPort = 18001)

    @Test
    fun automaticPrefersHomeThenTunnelThenRemote() {
        assertEquals(ReceiverRoute.HOME, ReceiverRouting.choose(AccessMode.AUTO, homeReachable = true, remoteConfigured = true, tunnelEnabled = true))
        assertEquals(ReceiverRoute.TUNNEL, ReceiverRouting.choose(AccessMode.AUTO, homeReachable = false, remoteConfigured = true, tunnelEnabled = true))
        assertEquals(ReceiverRoute.REMOTE, ReceiverRouting.choose(AccessMode.AUTO, homeReachable = false, remoteConfigured = true, tunnelEnabled = false))
        assertEquals(ReceiverRoute.HOME, ReceiverRouting.choose(AccessMode.AUTO, homeReachable = false, remoteConfigured = false, tunnelEnabled = false))
    }

    @Test
    fun fixedModesIgnoreReachability() {
        assertEquals(ReceiverRoute.HOME, ReceiverRouting.choose(AccessMode.HOME, homeReachable = false, remoteConfigured = true, tunnelEnabled = true))
        assertEquals(ReceiverRoute.REMOTE, ReceiverRouting.choose(AccessMode.REMOTE, homeReachable = true, remoteConfigured = true, tunnelEnabled = false))
        assertEquals(ReceiverRoute.HOME, ReceiverRouting.choose(AccessMode.REMOTE, homeReachable = true, remoteConfigured = false, tunnelEnabled = false))
    }

    @Test
    fun probesOnlyWhenThereIsAnAlternative() {
        assertFalse(ReceiverRouting.needsProbe(home, tunnelEnabled = false))
        assertTrue(ReceiverRouting.needsProbe(withRemote, tunnelEnabled = false))
        assertTrue(ReceiverRouting.needsProbe(home, tunnelEnabled = true))
        assertFalse(ReceiverRouting.needsProbe(withRemote.copy(accessMode = AccessMode.REMOTE), tunnelEnabled = false))
    }

    @Test
    fun remoteProfileReplacesAddressAndPorts() {
        val remote = ReceiverRouting.effective(withRemote, ReceiverRoute.REMOTE)
        assertEquals("mybox.dyndns.org", remote.host)
        assertEquals(8443, remote.httpPort)
        assertTrue(remote.useHttps)
        assertEquals(18001, remote.streamingPort)
        assertEquals(8002, remote.transcodingPort)
        assertSame(withRemote, ReceiverRouting.effective(withRemote, ReceiverRoute.HOME))
        assertSame(withRemote, ReceiverRouting.effective(withRemote, ReceiverRoute.TUNNEL))
    }

    @Test
    fun emptyRemotePortsKeepHomeValuesOrTheHttpsDefault() {
        val sameScheme = ReceiverRouting.effective(home.copy(remoteHost = "x.example.net"), ReceiverRoute.REMOTE)
        assertEquals(80, sameScheme.httpPort)
        assertEquals(8001, sameScheme.streamingPort)
        val https = ReceiverRouting.effective(home.copy(remoteHost = "x.example.net", remoteUseHttps = true), ReceiverRoute.REMOTE)
        assertEquals(443, https.httpPort)
    }

    @Test
    fun plainHttpRiskOnTheInternetOnly() {
        assertEquals(PlainHttpRisk.NONE, ReceiverRouting.plainHttpRisk(withRemote, ReceiverRoute.HOME))
        assertEquals(PlainHttpRisk.NONE, ReceiverRouting.plainHttpRisk(withRemote, ReceiverRoute.TUNNEL))
        assertEquals(PlainHttpRisk.STREAM, ReceiverRouting.plainHttpRisk(withRemote, ReceiverRoute.REMOTE))
        assertEquals(PlainHttpRisk.WEB_AND_STREAM, ReceiverRouting.plainHttpRisk(withRemote.copy(remoteUseHttps = false), ReceiverRoute.REMOTE))
        assertEquals(PlainHttpRisk.WEB_AND_STREAM, ReceiverRouting.plainHttpRisk(home.copy(host = "mybox.dyndns.org"), ReceiverRoute.HOME))
    }

    @Test
    fun recognisesLocalAddresses() {
        listOf("192.168.1.10", "10.0.0.2", "172.20.1.1", "127.0.0.1", "169.254.3.4", "100.101.102.103", "vuplus", "box.local", "box.fritz.box", "fe80::1", "fd12::4").forEach {
            assertTrue(it, ReceiverRouting.isLocalAddress(it))
        }
        listOf("mybox.dyndns.org", "8.8.8.8", "172.32.0.1", "fdserver.example.com", "2a02:1810::1").forEach {
            assertFalse(it, ReceiverRouting.isLocalAddress(it))
        }
    }

    @Test
    fun validatesTheRemoteProfile() {
        assertTrue(ReceiverConfigValidator.validate(withRemote).isValid)
        assertFalse(ReceiverConfigValidator.validate(withRemote.copy(remoteHost = "bad host")).isValid)
        assertFalse(ReceiverConfigValidator.validate(withRemote.copy(remoteStreamingPort = -1)).isValid)
    }
}
