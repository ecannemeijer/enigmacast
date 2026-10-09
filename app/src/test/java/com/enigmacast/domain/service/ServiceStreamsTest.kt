package com.enigmacast.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStreamsTest {
    private val dance = "https://m1b2.worldcast.tv/dancetelevisionone/2/dancetelevisionone.m3u8"

    @Test
    fun aNormalDvbServiceIsNotAnInternetStream() {
        assertFalse(ServiceStreams.isInternet("1:0:19:283D:3FB:1:C00000:0:0:0:"))
        assertFalse(ServiceStreams.isInternet("1:0:19:283D:3FB:1:C00000:0:0:0::NPO 1"))
        assertNull(ServiceStreams.internetUrl("1:0:19:283D:3FB:1:C00000:0:0:0:"))
        assertFalse(ServiceStreams.isStreamPlayer("1:0:19:283D:3FB:1:C00000:0:0:0:"))
    }

    @Test
    fun theChannelNameAfterTheUrlIsNotPartOfTheUrl() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:https%3a//m1b2.worldcast.tv/dancetelevisionone/2/dancetelevisionone.m3u8:Dance TV - Best of Dance TV"
        assertEquals(dance, ServiceStreams.internetUrl(ref))
        assertTrue(ServiceStreams.isStreamPlayer(ref))
    }

    @Test
    fun aDoubleEncodedUrlFieldIsDecodedToo() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:https%253a//m1b2.worldcast.tv/dancetelevisionone/2/dancetelevisionone.m3u8:Dance TV - Best of Dance TV"
        assertEquals(dance, ServiceStreams.internetUrl(ref))
    }

    @Test
    fun theReferenceAsSentInTheEpgserviceRequestGivesTheSameUrl() {
        val ref = "4097%3A0%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A0%3Ahttps%253a%2F%2Fm1b2.worldcast.tv%2Fdancetelevisionone%2F2%2F" +
            "dancetelevisionone.m3u8%3ADance%20TV%20-%20Best%20of%20Dance%20TV"
        assertEquals(dance, ServiceStreams.internetUrl(ref))
    }

    @Test
    fun aPortInTheUrlStaysAndANameWithColonsIsIgnored() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:http%3a//iptv.example.org%3a8080/live/ch1.ts:News: 24/7 : Live"
        assertEquals("http://iptv.example.org:8080/live/ch1.ts", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun httpsAndCredentialsStayInTheUrl() {
        val ref = "5001:0:1:1:0:0:0:0:0:0:https%3a//user%3apass@cdn.example.com/index.m3u8:Channel"
        assertEquals("https://user:pass@cdn.example.com/index.m3u8", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun enigmaPlayerOptionsAfterTheHashAreDropped() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:http%3a//cdn.example.com/stream#User-Agent=VLC:Name"
        assertEquals("http://cdn.example.com/stream", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun plusSignsAndEncodedQueryCharactersAreKept() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:http%3a//cdn.example.com/live.m3u8?token=a+b%2Fc:Name"
        assertEquals("http://cdn.example.com/live.m3u8?token=a+b%2Fc", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun aDvbTypeWithAnEmbeddedUrlIsStillInternet() {
        val ref = "1:0:1:0:0:0:0:0:0:0:http%3a//iptv.example.org/ch1:Ch 1"
        assertTrue(ServiceStreams.isInternet(ref))
        assertFalse(ServiceStreams.isStreamPlayer(ref))
        assertEquals("http://iptv.example.org/ch1", ServiceStreams.internetUrl(ref))
    }

    @Test
    fun aStreamPlayerWithoutAUrlIsNotPlayableAsInternet() {
        assertTrue(ServiceStreams.isStreamPlayer("4097:0:1:0:0:0:0:0:0:0:"))
        assertNull(ServiceStreams.internetUrl("4097:0:1:0:0:0:0:0:0:0:"))
        assertNull(ServiceStreams.internetUrl("4097:0:1:0:0:0:0:0:0:0:Just a name"))
    }

    @Test
    fun aSchemeWhosePercentSignWasDroppedIsStillADirectUrl() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:https3a//redebrasil.nuvemplay.live/hls/stream.m3u8:Rede Brasil"
        assertEquals("https://redebrasil.nuvemplay.live/hls/stream.m3u8", ServiceStreams.internetUrl(ref))
        assertEquals(
            "http://cdn.example.com/live.ts",
            ServiceStreams.internetUrl("4097:0:1:0:0:0:0:0:0:0:http3a//cdn.example.com/live.ts:Name"),
        )
    }

    @Test
    fun anUnencodedUrlIsRebuiltIncludingItsPort() {
        assertEquals("http://plain.example/a.ts", ServiceStreams.internetUrl("5002:0:1:0:0:0:0:0:0:0:http://plain.example/a.ts:Name"))
        assertEquals("http://plain.example:8000/a.ts", ServiceStreams.internetUrl("5002:0:1:0:0:0:0:0:0:0:http://plain.example:8000/a.ts:Name"))
    }
}
