package com.enigmacast.domain.epg

import com.enigmacast.domain.tuner.NowEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvEpgNamesTest {
    private fun iptv(url: String, name: String) = NamedService("4097:0:1:0:0:0:0:0:0:0:${url.replace(":", "%3a")}:$name", name)

    @Test fun normalizesNames() {
        assertEquals("rai1", IptvEpgNames.normalize("Rai 1 HD"))
        assertEquals("rai1", IptvEpgNames.normalize("RAI 1 (1080p)"))
        assertEquals("rai1", IptvEpgNames.normalize("Rai-1 [Geo-blocked] FHD"))
        assertEquals("canale5", IptvEpgNames.normalize("Canale 5 Ⓢ"))
        assertEquals("rtl4", IptvEpgNames.normalize("RTL4 UHD"))
        assertEquals("tf1", IptvEpgNames.normalize("TF1 4K"))
        assertEquals("arteplus", IptvEpgNames.normalize("Arté +"))
        assertEquals("rai1", IptvEpgNames.withoutRegion("Rai 1 IT"))
        assertNull(IptvEpgNames.withoutRegion("Rai 1"))
    }

    @Test fun mapsStreamsToSatelliteChannelsPreferringHd() {
        val sdRai = NamedService("1:0:1:D49:5:13E:820000:0:0:0:", "Rai 1")
        val hdRai = NamedService("1:0:19:D4B:5:13E:820000:0:0:0:", "Rai 1 HD")
        val canale = NamedService("1:0:1:1A:2:13E:820000:0:0:0:", "Canale 5")
        val rai = iptv("https://cdn/rai1.m3u8", "Rai 1 (720p)")
        val raiIt = iptv("https://cdn/rai1b.m3u8", "Rai 1 IT")
        val c5 = iptv("https://cdn/c5.m3u8", "Canale 5 HD")
        val unknown = iptv("https://cdn/x.m3u8", "Nothing Like It")
        val map = IptvEpgNames.aliases(listOf(sdRai, hdRai, canale, rai, raiIt, c5, unknown))
        assertEquals(hdRai.reference, map[rai.reference])
        assertEquals(hdRai.reference, map[raiIt.reference])
        assertEquals(canale.reference, map[c5.reference])
        assertFalse(map.containsKey(unknown.reference))
        assertFalse(map.containsKey(sdRai.reference))
    }

    @Test fun noSatelliteNoAliases() {
        assertTrue(IptvEpgNames.aliases(listOf(iptv("http://a/1", "A"), iptv("http://a/2", "A"))).isEmpty())
    }

    @Test fun streamsWithTheSameTenFieldsGetTheirOwnKey() {
        val a = iptv("https://cdn/a.m3u8", "A").reference
        val b = iptv("https://cdn/b.m3u8", "B").reference
        assertFalse(NowEvents.key(a) == NowEvents.key(b))
        assertEquals(NowEvents.key(a), NowEvents.key(a.substringBeforeLast(':') + ":Other name"))
        assertEquals(NowEvents.key("1:0:19:4651:3FC:1:C00000:0:0:0:"), NowEvents.key("1:0:1:4651:3FC:1:C00000:0:0:0:"))
    }
}
