package com.enigmacast.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRangeTest {
    @Test
    fun aSlash24CoversDot1ToDot254WithoutTheTv() {
        val hosts = LanRange.hosts("192.168.1.42", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertTrue("192.168.1.209" in hosts)
        assertFalse("192.168.1.42" in hosts)
        assertEquals("192.168.1.0/24", LanRange.subnetLabel("192.168.1.42", 24))
    }

    @Test
    fun widerOrMissingPrefixesScanTheOwnSlash24() {
        for (prefix in listOf(0, 8, 16, 23, 31, 32, 64, -1)) {
            val hosts = LanRange.hosts("192.168.1.42", prefix)
            assertEquals("prefix $prefix", 253, hosts.size)
            assertTrue("prefix $prefix", "192.168.1.209" in hosts)
        }
        assertEquals("10.0.5.0/24", LanRange.subnetLabel("10.0.5.17", 16))
    }

    @Test
    fun narrowerSubnetsStayInsideTheirRange() {
        val hosts = LanRange.hosts("192.168.1.200", 25)
        assertEquals(125, hosts.size)
        assertEquals("192.168.1.129", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertTrue("192.168.1.209" in hosts)
        assertEquals("192.168.1.128/25", LanRange.subnetLabel("192.168.1.200", 25))
    }

    @Test
    fun privateRangesAndParsing() {
        assertTrue(LanRange.isPrivate("192.168.1.42"))
        assertTrue(LanRange.isPrivate("10.1.2.3"))
        assertTrue(LanRange.isPrivate("172.16.0.1"))
        assertFalse(LanRange.isPrivate("172.32.0.1"))
        assertFalse(LanRange.isPrivate("8.8.8.8"))
        assertFalse(LanRange.isPrivate("fe80::1"))
        assertTrue(LanRange.hosts("not.an.ip.x", 24).isEmpty())
        assertTrue(LanRange.hosts("192.168.1.300", 24).isEmpty())
    }
}
