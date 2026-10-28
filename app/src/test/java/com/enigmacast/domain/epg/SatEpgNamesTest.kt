package com.enigmacast.domain.epg

import com.enigmacast.domain.tuner.NowEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatEpgNamesTest {
    private fun sat(name: String, reference: String) = SatEpgNames.Channel(name, reference, NowEvents.key(reference))

    private val npoSd = sat("NPO 1", "1:0:1:FAB:41A:1:C00000:0:0:0:")
    private val npoHd = sat("NPO 1 HD", "1:0:19:4651:3EB:1:C00000:0:0:0:")
    private val rtl4 = sat("RTL 4 HD", "1:0:19:4652:3EB:1:C00000:0:0:0:")
    private val sbs6 = sat("SBS6", "1:0:19:4653:3EB:1:C00000:0:0:0:")
    private val veronica = sat("Veronica HD", "1:0:19:4654:3EB:1:C00000:0:0:0:")
    private val rai1 = sat("Rai 1 HD", "1:0:19:3DCD:2134:13E:820000:0:0:0:")
    private val canale5 = sat("Canale 5", "1:0:1:3D8C:13F2:13E:820000:0:0:0:")
    private val iptvOnReceiver = SatEpgNames.Channel("NPO 1", "4097:0:1:0:0:0:0:0:0:0:http%3a//example.invalid/1:NPO 1", "4097:0:1:0:0:0:0:0:0:0")
    private val marker = SatEpgNames.Channel("NPO 1", "1:64:1:0:0:0:0:0:0:0::NPO 1", "1:64:1:0:0:0:0:0:0:0")

    private val index = SatEpgNames.index(listOf(iptvOnReceiver, marker, npoSd, npoHd, rtl4, sbs6, veronica, rai1, canale5))

    private fun first(name: String) = index.lookup(name).firstOrNull()

    @Test
    fun realisticIptvNamesFindTheSatelliteChannel() {
        mapOf(
            "NL: NPO 1 HD" to npoHd,
            "NL:NPO 1" to npoHd,
            "NL | NPO 1 FHD" to npoHd,
            "NL \u258E NPO 1 4K" to npoHd,
            "|NL| RTL 4 \u1D34\u1D30" to rtl4,
            "RTL 4\u1D34\u1D30" to rtl4,
            "[NL] SBS 6 FHD" to sbs6,
            "NL - Veronica HD" to veronica,
            "NLD: Veronica" to veronica,
            "IT | RAI 1 FHD" to rai1,
            "IT: Rai 1" to rai1,
            "Rai 1 (IT)" to rai1,
            "Rai 1 IT" to rai1,
            "Rai Uno" to rai1,
            "IT: Canale 5 HEVC" to canale5,
            "Canale 5 \u24C8" to canale5,
        ).forEach { (iptv, expected) -> assertEquals(iptv, expected.serviceKey, first(iptv)) }
    }

    @Test
    fun unknownChannelsDoNotMatch() {
        listOf("NL: NPO 2", "BBC News", "", "|NL|", "NL: RTL 5").forEach { assertTrue(it, index.lookup(it).isEmpty()) }
    }

    @Test
    fun satelliteHdComesFirstThenSdThenReceiverStreamsAndMarkersAreSkipped() {
        assertEquals(listOf(npoHd.serviceKey, npoSd.serviceKey, iptvOnReceiver.serviceKey), index.lookup("NPO 1"))
    }

    @Test
    fun bothSidesUseTheSameKeys() {
        assertEquals(SatEpgNames.key("NPO 1 HD"), SatEpgNames.key("|NL| NPO 1 \u1D34\u1D30"))
        assertEquals("rai1", SatEpgNames.key("Rai Uno HD"))
        assertEquals("natgeowild", SatEpgNames.key("NL: Nat Geo Wild HD"))
        assertEquals("rtl4", SatEpgNames.key("NL: RTL 4 (Backup)"))
    }

    @Test
    fun emptyReceiverListGivesAnEmptyIndex() {
        assertTrue(SatEpgNames.index(emptyList()).isEmpty)
        assertFalse(index.isEmpty)
    }

    @Test
    fun indexIsRebuiltWhenTheChannelListChanges() {
        val stamp = SatEpgNames.Stamp("rx", 0, 0L, "")
        val now = 1_000_000L
        assertTrue(SatEpgNames.needsRebuild(null, 0L, false, stamp, now))
        // Built before the first channel sync: empty, and the sync then stores the channels.
        val synced = SatEpgNames.Stamp("rx", 812, now, "abc")
        assertTrue(SatEpgNames.needsRebuild(stamp, now - 1_000, true, synced, now))
        assertFalse(SatEpgNames.needsRebuild(synced, now - 1_000, false, synced, now))
        assertTrue(SatEpgNames.needsRebuild(synced, now, false, synced.copy(channelCount = 815), now))
        assertTrue(SatEpgNames.needsRebuild(synced, now, false, synced.copy(syncedAtMs = now + 5), now))
        assertTrue(SatEpgNames.needsRebuild(synced, now, false, synced.copy(receiverKey = "other"), now))
        // An empty index is retried after a minute, not kept for the session.
        assertFalse(SatEpgNames.needsRebuild(stamp, now - 1_000, true, stamp, now))
        assertTrue(SatEpgNames.needsRebuild(stamp, now - SatEpgNames.EMPTY_RETRY_MS, true, stamp, now))
    }

    @Test
    fun nlDashSbs6HdFindsEverySatelliteSpelling() {
        listOf("SBS 6 HD", "SBS6 HD", "SBS 6", "SBS6").forEach { satName ->
            val channel = sat(satName, "1:0:19:4653:3EB:1:C00000:0:0:0:")
            val single = SatEpgNames.index(listOf(channel))
            assertEquals(satName, listOf(channel.serviceKey), single.lookup("NL - SBS 6 HD"))
        }
    }

    @Test
    fun digitSpacingDoesNotMergeDifferentNumbers() {
        val rtl41 = sat("RTL 41", "1:0:19:4660:3EB:1:C00000:0:0:0:")
        val npo12 = sat("NPO 12", "1:0:19:4661:3EB:1:C00000:0:0:0:")
        val both = SatEpgNames.index(listOf(rtl4, rtl41, npoHd, npo12))
        assertEquals(listOf(rtl4.serviceKey), both.lookup("NL - RTL 4 HD"))
        assertEquals(listOf(rtl41.serviceKey), both.lookup("NL - RTL 41"))
        assertEquals(listOf(npoHd.serviceKey), both.lookup("NL: NPO 1"))
        assertEquals(listOf(npo12.serviceKey), both.lookup("NL: NPO 12 HD"))
        val onlyLonger = SatEpgNames.index(listOf(rtl41, npo12))
        assertTrue(onlyLonger.lookup("RTL 4").isEmpty())
        assertTrue(onlyLonger.lookup("NPO 1").isEmpty())
        val onlyShorter = SatEpgNames.index(listOf(rtl4, npoHd))
        assertTrue(onlyShorter.lookup("RTL 41").isEmpty())
        assertTrue(onlyShorter.lookup("NPO 12").isEmpty())
    }
}
