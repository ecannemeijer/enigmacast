package com.enigmacast.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SanitizeUrlTest {
    @Test
    fun plainCredentialsAreHidden() {
        assertEquals("http://***@host/x", EnigmaLog.sanitizeUrl("http://user:pass@host/x"))
    }

    @Test
    fun encodedCredentialsInsideAServiceReferenceAreHidden() {
        val single = "http://192.168.1.209/api/epgservice?sRef=4097%3A0%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A0%3Ahttps%3a%2F%2Fbob%3asecret%40iptv.example%2Flive.m3u8"
        val out = EnigmaLog.sanitizeUrl(single)
        assertFalse(out, out.contains("secret"))
        assertFalse(out, out.contains("bob"))
        assertEquals(true, out.contains("iptv.example"))

        val double = "http://r/api/epgservice?sRef=4097%3A0%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A0%3Ahttps%253a%252f%252fbob%253asecret%2540iptv.example%252flive.m3u8"
        assertFalse(EnigmaLog.sanitizeUrl(double).contains("secret"))
    }

    @Test
    fun aReferenceWithoutCredentialsIsUnchanged() {
        val ref = "http://r/api/epgservice?sRef=4097%3A0%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A0%3Ahttps%253a%2F%2Fm1b2.worldcast.tv%2Fdance%2F2%2Fdance.m3u8%3ADance"
        assertEquals(ref, EnigmaLog.sanitizeUrl(ref))
    }
}
