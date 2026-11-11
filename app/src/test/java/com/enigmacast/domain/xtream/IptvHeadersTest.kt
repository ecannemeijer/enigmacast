package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvHeadersTest {
    @Test
    fun defaultUserAgentWhenNothingIsSet() {
        val headers = IptvHeaders.resolve()
        assertEquals(IptvHeaders.DEFAULT_USER_AGENT, headers.userAgent)
        assertTrue(headers.headers.isEmpty())
    }

    @Test
    fun streamWinsOverProvider() {
        val headers = IptvHeaders.resolve(
            providerUserAgent = "ProviderUA",
            providerHeaders = mapOf("Referer" to "http://provider.example"),
            streamUserAgent = "StreamUA",
            streamHeaders = mapOf("Referer" to "http://stream.example", "Origin" to "http://stream.example"),
        )
        assertEquals("StreamUA", headers.userAgent)
        assertEquals("http://stream.example", headers.headers["Referer"])
        assertEquals("http://stream.example", headers.headers["Origin"])
        assertFalse(headers.asMap().containsKey("referer"))
    }

    @Test
    fun providerUsedWhenStreamHasNone() {
        val headers = IptvHeaders.resolve(
            providerUserAgent = "ProviderUA",
            providerHeaders = mapOf("Referer" to "http://provider.example"),
        )
        assertEquals("ProviderUA", headers.userAgent)
        assertEquals("http://provider.example", headers.headers["Referer"])
    }

    @Test
    fun parseLinesCanonicalisesReferrer() {
        val parsed = IptvHeaders.parseLines("http-referrer: http://x.example\nUser-Agent: Custom\n# comment\n\nOrigin: http://o")
        assertEquals("http://x.example", parsed["Referer"])
        assertEquals("Custom", parsed["User-Agent"])
        assertEquals("http://o", parsed["Origin"])
    }

    @Test
    fun kodiStreamHeadersAndExtVlcOpt() {
        val kodi = IptvHeaders.parseKodiStreamHeaders("User-Agent=FooBar&Referer=http://ref.example")
        assertEquals("FooBar", kodi["User-Agent"])
        assertEquals("http://ref.example", kodi["Referer"])
        val fromTags = IptvHeaders.fromPlaylistOptions(
            listOf(
                "http-user-agent" to "VlcUA",
                "http-referrer" to "http://vlc.example",
                "inputstream.adaptive.stream_headers" to "Referer=http://kodi.example&Origin=http://kodi.example",
            ),
        )
        assertEquals("VlcUA", fromTags["User-Agent"])
        assertEquals("http://kodi.example", fromTags["Referer"])
        assertEquals("http://kodi.example", fromTags["Origin"])
    }
}
