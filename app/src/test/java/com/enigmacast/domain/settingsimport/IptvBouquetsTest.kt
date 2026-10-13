package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvBouquetsTest {
    private fun entry(name: String, url: String, vararg groups: String, ua: String? = null) =
        M3uEntry(name, url, groups.toList(), vlcOptions = ua?.let { mapOf("http-user-agent" to it) }.orEmpty())

    @Test fun encodesColonsPercentAndSpaces() {
        assertEquals("http%3a//host%3a8080/a%20b?x=1%252", IptvBouquets.encodeUrl("http://host:8080/a b?x=1%2"))
    }

    @Test fun serviceLineFormat() {
        val line = IptvBouquets.serviceLine(entry("NPO 1: HD", "https://a.example/live.m3u8", "NL"))
        assertEquals("#SERVICE 4097:0:1:0:0:0:0:0:0:0:https%3a//a.example/live.m3u8:NPO 1  HD".replace("  ", " "), line)
    }

    @Test fun serviceTypeAndUserAgent() {
        val line = IptvBouquets.serviceLine(entry("X", "http://a/b", "G", ua = "Mozilla/5.0"), 5002)
        assertTrue(line.startsWith("#SERVICE 5002:0:1:0:0:0:0:0:0:0:"))
        assertTrue(line.contains("http%3a//a/b#User-Agent=Mozilla/5.0:X"))
    }

    @Test fun slugIsSafe() {
        assertEquals("cine_series", IptvBouquets.slug("Ciné & Séries!"))
        assertEquals("group", IptvBouquets.slug("???"))
        assertTrue(IptvBouquets.slug("x".repeat(100)).length <= 40)
    }

    @Test fun buildsOneBouquetPerGroupInOrder() {
        val entries = listOf(
            entry("A", "http://a", "News"),
            entry("B", "http://b", "Kids", "Animation"),
            entry("A again", "http://a", "News"),
            entry("C", "http://c", "Sports"),
        )
        val bouquets = IptvBouquets.build(entries, listOf("Kids", "News", "Missing"))
        assertEquals(listOf("Kids", "News"), bouquets.map { it.group })
        assertEquals("userbouquet.enigmacast_iptv_kids.tv", bouquets[0].fileName)
        assertEquals(1, bouquets[1].channels) // duplicate URL once
        val lines = bouquets[1].content.trimEnd().lines()
        assertEquals("#NAME IPTV · News", lines[0])
        assertEquals("#SERVICE 4097:0:1:0:0:0:0:0:0:0:http%3a//a:A", lines[1])
        assertEquals("#DESCRIPTION A", lines[2])
    }

    @Test fun clashingSlugsGetANumber() {
        val entries = listOf(entry("A", "http://a", "Kids!"), entry("B", "http://b", "Kids?"))
        val names = IptvBouquets.build(entries, listOf("Kids!", "Kids?")).map { it.fileName }
        assertEquals(listOf("userbouquet.enigmacast_iptv_kids.tv", "userbouquet.enigmacast_iptv_kids_2.tv"), names)
    }

    @Test fun recognisesOwnFiles() {
        assertTrue(IptvBouquets.isIptvFile("userbouquet.enigmacast_iptv_news.tv"))
        assertFalse(IptvBouquets.isIptvFile("userbouquet.favourites.tv"))
    }
}
