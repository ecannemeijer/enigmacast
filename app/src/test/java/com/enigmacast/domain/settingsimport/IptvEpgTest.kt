package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvEpgTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("m3u/$name")!!.readText()

    @Test fun headerUrlsFromRealPlaylists() {
        val free = IptvEpg.headerUrls(res("freetv-sample.m3u8"))
        assertTrue(free.size > 50)
        assertTrue(free.any { it.endsWith("epg_ripper_NL1.xml.gz") })
        assertEquals(listOf("https://worker-9dd4.onrender.com/guide.xml.gz"), IptvEpg.headerUrls(res("iptvorg-categories-sample.m3u")))
        assertEquals(emptyList<String>(), IptvEpg.headerUrls("#EXTINF:-1,x\nhttp://a"))
    }

    @Test fun chooseOnlyCountriesOfTheSelection() {
        val header = IptvEpg.headerUrls(res("freetv-sample.m3u8"))
        val links = listOf(
            EpgLink("RTVNoord.nl", "RTV Noord", "4097:0:1:1:1:EC:0:0:0:0:", "NL"),
            EpgLink("BBCOne.uk", "BBC One", "4097:0:1:2:1:EC:0:0:0:0:", "GB"),
        )
        val urls = IptvEpg.chooseUrls(header, links)
        assertTrue(urls.toString(), urls.any { it.endsWith("epg_ripper_NL1.xml.gz") })
        assertTrue(urls.toString(), urls.any { it.endsWith("epg_ripper_UK1.xml.gz") })
        assertFalse(urls.any { it.contains("ALL_SOURCES") })
        assertTrue(urls.none { it.contains("RAKUTEN_NL") })
        assertEquals(listOf("https://x/guide.xml.gz"), IptvEpg.chooseUrls(listOf("https://x/guide.xml.gz"), links))
        assertEquals(emptyList<String>(), IptvEpg.chooseUrls(header, emptyList()))
    }

    @Test fun readsRealXmltvAndMatchesLooseIds() {
        val channels = LinkedHashMap<String, List<String>>()
        val links = listOf(
            EpgLink("RTVNoord.nl", "RTV Noord", "4097:0:1:A:B:EC:0:0:0:0:", "NL"),
            EpgLink("RTVDrenthe.nl", "RTV Drenthe", "4097:0:1:C:D:EC:0:0:0:0:", "NL"),
            EpgLink("Unknown.nl", "Unknown", "4097:0:1:E:F:EC:0:0:0:0:", "NL"),
        )
        var matches = emptyMap<String, String>()
        val programmes = ArrayList<XmltvProgramme>()
        XmltvReader(res("epgshare-nl-sample.xml").reader()).read(
            onChannel = { id, names -> channels[id] = names },
            onChannelsDone = { matches = IptvEpg.match(links, channels); true },
            wanted = { it in matches.values },
            onProgramme = { programmes += it },
        )
        assertEquals(3, channels.size)
        assertEquals(listOf("RTV Noord"), channels["RTV.Noord.nl"])
        assertEquals(mapOf("4097:0:1:A:B:EC:0:0:0:0:" to "RTV.Noord.nl", "4097:0:1:C:D:EC:0:0:0:0:" to "RTV.Drenthe.nl"), matches)
        assertEquals(6, programmes.size)
        assertTrue(programmes.all { it.channel != "24Kitchen.nl" && it.title.isNotBlank() && it.stopSec > it.startSec })
    }

    @Test fun stopsAfterChannelsWhenAsked() {
        var programmes = 0
        XmltvReader(res("epgshare-nl-sample.xml").reader()).read({ _, _ -> }, { false }, { true }, { programmes++ })
        assertEquals(0, programmes)
    }

    @Test fun lexerEntitiesCdataAndTimes() {
        val xml = """<?xml version="1.0"?><!-- c --><tv><channel id="a&amp;b"><display-name>A &amp; B</display-name></channel>
            <programme start="20261013200000 +0200" stop="20261013210000 +0200" channel="a&amp;b"><title><![CDATA[News <live>]]></title><desc>x &#233;</desc></programme></tv>"""
        val got = ArrayList<XmltvProgramme>()
        XmltvReader(xml.reader()).read({ _, _ -> }, { true }, { it == "a&b" }, { got += it })
        assertEquals(1, got.size)
        assertEquals("News <live>", got[0].title)
        assertEquals("x é", got[0].desc)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-13T18:00:00Z").toEpochSecond(), got[0].startSec)
        assertEquals(3600, got[0].stopSec - got[0].startSec)
    }

    @Test fun epgImportFiles() {
        val sources = IptvEpg.sourcesXml(listOf("https://e/epg_ripper_NL1.xml.gz"))
        assertTrue(sources.contains("<url>https://e/epg_ripper_NL1.xml.gz</url>"))
        assertTrue(sources.contains("channels=\"/etc/epgimport/enigmacast.channels.xml\""))
        val ch = IptvEpg.channelsXml(mapOf("4097:0:1:A:B:EC:0:0:0:0:" to "RTV.Noord.nl"))
        assertTrue(ch.contains("<channel id=\"RTV.Noord.nl\">4097:0:1:A:B:EC:0:0:0:0:</channel>"))
    }

    @Test fun uniqueIdsPerUrlAndStableAcrossBuilds() {
        val entries = (1..300).map { M3uEntry("C$it", "http://h/$it.m3u8", listOf("G")) } + M3uEntry("C1 again", "http://h/1.m3u8", listOf("H"))
        val a = IptvBouquets.build(entries, listOf("G", "H"))
        val b = IptvBouquets.build(entries, listOf("G", "H"))
        assertEquals(a.map { it.content }, b.map { it.content })
        val refs = a[0].links.map { it.reference }
        assertEquals(refs.size, refs.toSet().size)
        assertEquals(a[0].links.first().reference, a[1].links.first().reference)
        assertTrue(refs.all { !it.startsWith("4097:0:1:0:0:") })
    }
}
