package com.enigmacast.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStreamResolverTest {

    @Test
    fun parsePlsTakesFile1() {
        val body = """
            [playlist]
            NumberOfEntries=1
            File1=http://example.com/stream
            Title1=Radio
        """.trimIndent()

        val urls = RadioStreamFormats.parsePlaylist(body, "http://example.com/station.pls")

        assertEquals(listOf("http://example.com/stream"), urls)
    }

    @Test
    fun parseM3uSkipsComments() {
        val body = """
            #EXTM3U
            #EXTINF:-1,Radio
            https://example.com/live
        """.trimIndent()

        val urls = RadioStreamFormats.parsePlaylist(body, "https://example.com/list.m3u")

        assertEquals(listOf("https://example.com/live"), urls)
    }

    @Test
    fun detectHlsFromExtXTags() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nchunk.m3u8\n"
        val kind = RadioStreamFormats.detectFromPreview(body.toByteArray(), "application/vnd.apple.mpegurl")
        assertEquals(RadioPreviewKind.HLS, kind)
    }

    @Test
    fun detectHtml() {
        val body = "<!DOCTYPE html><html><body>player</body></html>"
        val kind = RadioStreamFormats.detectFromPreview(body.toByteArray(), "text/html")
        assertEquals(RadioPreviewKind.HTML, kind)
    }

    @Test
    fun streamTheWorldMp3GetsHttpsAndHlsCandidates() {
        val http = "http://playerservices.streamtheworld.com/api/livestream-redirect/RADIO538.mp3"
        val https = RadioStreamFormats.upgradeStreamTheWorldHttps(http)
        val candidates = RadioStreamFormats.streamTheWorldHlsCandidates(http)

        assertEquals(
            "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO538.mp3",
            https
        )
        assertTrue(candidates.any { it.endsWith("RADIO538.m3u8") })
        assertTrue(candidates.any { it.endsWith("RADIO538AAC.m3u8") })
    }

    @Test
    fun mimeFromHlsCodec() {
        assertEquals(
            RadioStreamFormats.MIME_HLS,
            RadioStreamFormats.mimeFromCodec("HLS", "http://example.com/station", hls = false)
        )
        assertEquals(
            RadioStreamFormats.MIME_AAC,
            RadioStreamFormats.mimeFromCodec("AAC+", "http://example.com/station")
        )
    }

    @Test
    fun nowPlayingSplitsArtistAndTitle() {
        val parsed = RadioNowPlayingParser.parse("Artist Name - Track Title")
        assertEquals("Artist Name", parsed.artist)
        assertEquals("Track Title", parsed.title)
    }

    @Test
    fun nowPlayingSplitsEnDash() {
        val parsed = RadioNowPlayingParser.parse("Adele – Easy On Me")
        assertEquals("Adele", parsed.artist)
        assertEquals("Easy On Me", parsed.title)
    }
}
