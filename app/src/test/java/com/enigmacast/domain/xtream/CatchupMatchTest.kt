package com.enigmacast.domain.xtream

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupMatchTest {
    private val account = XtreamAccount("Box", "http://iptv.example", "user", "secret", "ts")

    @Test
    fun namesMatchWithoutQualityAndPreferTheSameTag() {
        val channels = listOf("NPO 1 HD", "NPO 1", "NPO 1 UHD", "RTL 4")
        assertEquals("NPO 1", ChannelMatch.best("npo 1", channels) { it })
        assertEquals("NPO 1 HD", ChannelMatch.best("NL - NPO 1 HD", channels) { it })
        assertEquals("NPO 1 UHD", ChannelMatch.best("NPO 1 4K", channels) { it })
        assertNull(ChannelMatch.best("BBC One", channels) { it })
    }

    @Test
    fun archiveWindowAndTimeshiftAddress() {
        val now = 1_700_000_000L
        assertTrue(Catchup.inWindow(now, now - 3_600, now - 60, 7))
        assertFalse(Catchup.inWindow(now, now - 3_600, now + 60, 7))
        assertFalse(Catchup.inWindow(now, now - 10 * 86_400, now - 9 * 86_400, 7))
        val url = Catchup.xtream(account, "44", 1_700_000_000L, 3_600, ZoneOffset.UTC)
        assertEquals("http://iptv.example/timeshift/user/secret/60/2023-11-14:22-13/44.ts", url)
    }

    @Test
    fun theGuideKeepsPastProgrammesInsideTheArchiveWindow() {
        val now = 1_700_000_000L
        val events = listOf(
            XtreamEpg("old", now - 10 * 86_400, now - 9 * 86_400),
            XtreamEpg("yesterday", now - 86_400, now - 80_000),
            XtreamEpg("now", now - 600, now + 600),
            XtreamEpg("later", now + 3_600, now + 7_200),
        )
        val shown = Catchup.listed(events, now, pastDays = 7)
        assertEquals(listOf("yesterday", "now", "later"), shown.map { it.title })
    }

    @Test
    fun m3uTemplateFillsStartAndDuration() {
        val url = Catchup.m3u("http://x/index-{utc}-{duration}.m3u8", 100, 250, nowSec = 200, zone = ZoneOffset.UTC)
        assertEquals("http://x/index-100-150.m3u8", url)
        val item = XtreamItem("1", "NPO 1", XtreamSection.LIVE, "c", catchupSource = "http://x/{utc}")
        assertEquals("http://x/50", Catchup.url(null, item, XtreamEpg("News", 50, 80), nowSec = 100))
    }

    @Test
    fun m3uTagsMarkTheChannel() {
        val found = mutableListOf<M3uPlaylist.Entry>()
        M3uPlaylist.parse(
            sequenceOf(
                """#EXTINF:-1 catchup="shift" catchup-days="3" catchup-source="http://a/{utc}" tvg-name="NPO 1",NPO 1""",
                "http://live/1",
            ),
        ) { found += it }
        assertEquals(3, found.single().archiveDays)
        assertEquals("http://a/{utc}", found.single().catchupSource)
    }
}
