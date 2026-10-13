package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private val sample = """
        #EXTM3U x-tvg-url="https://example.com/guide.xml.gz"
        #EXTINF:-1 tvg-id="1Plus1.ua@SD" tvg-name="1+1, International" tvg-logo="https://i.imgur.com/x.png" http-user-agent="Mozilla/5.0 (X11)" group-title="General",1+1 International, HD
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)
        #EXTVLCOPT:http-referrer=https://example.org/
        http://158.101.222.193:88/georgia_play.php?id=1plus1
        #EXTINF:-1 tvg-country="NL" group-title="Animation;Kids",Cartoon TV
        https://cdn.example.com/live/cartoon.m3u8

        #EXTINF:-1,No group here
        #EXTGRP:Music
        rtmp://music.example.com/live
        #EXTINF:-1 group-title="News",Missing URL
        #EXTINF:-1 tvg-name="Only TVG name" group-title="",
        https://example.com/b.m3u8
    """.trimIndent()

    @Test fun parsesAttributesNamesAndCommas() {
        val entries = M3uParser.parse(sample)
        assertEquals(4, entries.size)
        val first = entries[0]
        assertEquals("1+1 International, HD", first.name)
        assertEquals("1+1, International", first.tvgName)
        assertEquals(listOf("General"), first.groups)
        assertEquals("http://158.101.222.193:88/georgia_play.php?id=1plus1", first.url)
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", first.userAgent)
        assertEquals("https://example.org/", first.referrer)
    }

    @Test fun splitsMultipleGroupsAndReadsCountry() {
        val cartoon = M3uParser.parse(sample)[1]
        assertEquals(listOf("Animation", "Kids"), cartoon.groups)
        assertEquals("NL", cartoon.country)
        assertNull(cartoon.userAgent)
    }

    @Test fun extgrpAndMissingGroup() {
        val entries = M3uParser.parse(sample)
        assertEquals(listOf("Music"), entries[2].groups)
        assertEquals("No group here", entries[2].name)
        // An EXTINF without URL is dropped; the next EXTINF takes the URL.
        assertEquals("Only TVG name", entries[3].name)
        assertEquals(listOf(M3uParser.NO_GROUP), entries[3].groups)
    }

    @Test fun emptyTitleFallsBackToTvgName() {
        val entries = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-name=\"Only TVG name\" group-title=\"\",\nhttps://example.com/b.m3u8\n")
        assertEquals("Only TVG name", entries.single().name)
        assertEquals(listOf(M3uParser.NO_GROUP), entries.single().groups)
    }

    @Test fun vlcOptionsDoNotLeakToNextEntry() {
        val entries = M3uParser.parse(sample)
        assertEquals(emptyMap<String, String>(), entries[1].vlcOptions)
    }

    @Test fun groupCounts() {
        val counts = M3uParser.groupCounts(M3uParser.parse(sample))
        assertEquals(1, counts["Kids"])
        assertEquals(1, counts["General"])
    }

    @Test fun crlfAndBom() {
        val entries = M3uParser.parse("\uFEFF#EXTM3U\r\n#EXTINF:-1 group-title=\"A\",X\r\nhttp://a/b\r\n")
        assertEquals("http://a/b", entries.single().url)
        assertEquals("X", entries.single().name)
    }
}
