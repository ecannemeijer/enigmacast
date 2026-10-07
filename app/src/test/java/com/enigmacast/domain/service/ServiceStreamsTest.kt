package com.enigmacast.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStreamsTest {
    @Test
    fun aNormalDvbServiceIsNotAnInternetStream() {
        assertFalse(ServiceStreams.isInternet("1:0:19:283D:3FB:1:C00000:0:0:0:"))
        assertFalse(ServiceStreams.isInternet("1:0:19:283D:3FB:1:C00000:0:0:0::NPO 1"))
        assertNull(ServiceStreams.internetUrl("1:0:19:283D:3FB:1:C00000:0:0:0:"))
        assertFalse(ServiceStreams.isStreamPlayer("1:0:19:283D:3FB:1:C00000:0:0:0:"))
    }

    @Test
    fun aGstreamerIptvReferenceYieldsTheDecodedUrl() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:http%3a//cdn.example.com/live/channel.ts"
        assertTrue(ServiceStreams.isInternet(ref))
        assertTrue(ServiceStreams.isStreamPlayer(ref))
        assertEquals("http://cdn.example.com/live/channel.ts", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun httpsAndCredentialsStayInTheUrl() {
        val ref = "5001:0:1:1:0:0:0:0:0:0:https%3a//user%3apass@cdn.example.com/index.m3u8"
        assertEquals("https://user:pass@cdn.example.com/index.m3u8", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun enigmaHeaderFragmentsAreDropped() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:http%3a//cdn.example.com/stream#User-Agent=VLC&yt-dlp"
        assertEquals("http://cdn.example.com/stream", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun aDoubleEncodedUrlIsDecoded() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:http%253a%252f%252fcdn.example.com%252flive.ts"
        assertEquals("http://cdn.example.com/live.ts", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun aDvbTypeWithAnEmbeddedUrlIsStillInternet() {
        val ref = "1:0:1:0:0:0:0:0:0:0:http%3a//iptv.example.org/ch1"
        assertTrue(ServiceStreams.isInternet(ref))
        assertFalse(ServiceStreams.isStreamPlayer(ref))
        assertEquals("http://iptv.example.org/ch1", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun aStreamPlayerWithoutAUrlIsNotPlayableAsInternet() {
        assertTrue(ServiceStreams.isStreamPlayer("4097:0:1:0:0:0:0:0:0:0:"))
        assertNull(ServiceStreams.internetUrl("4097:0:1:0:0:0:0:0:0:0:"))
        assertFalse(ServiceStreams.isInternet("4097:0:1:0:0:0:0:0:0:0:"))
    }

    @Test
    fun alreadyDecodedUrlsInThePathAreAccepted() {
        val ref = "5002:0:1:0:0:0:0:0:0:0:http://plain.example/a.ts"
        assertEquals("http://plain.example/a.ts", ServiceStreams.internetUrl(ref))
    }
}
