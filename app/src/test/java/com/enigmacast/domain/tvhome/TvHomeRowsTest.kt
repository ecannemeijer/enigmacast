package com.enigmacast.domain.tvhome

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.tvhome.TvHome.Link
import com.enigmacast.domain.tvhome.TvHome.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeRowsTest {
    @Test
    fun newLinksRoundTripWithoutAddresses() {
        val links = listOf(
            Link.Library("a1b2", "m", "991"),
            Link.Library("a1b2", "s", "17"),
            Link.Resume("a1b2", "e", "5521"),
            Link.Recording("/media/hdd/movie/x.ts", resume = true),
        )
        links.forEach { assertEquals(it, TvHome.parse(TvHome.uri(it))) }
        links.forEach { assertFalse(TvHome.uri(it).contains("http")) }
        assertEquals(Link.Recording("/m/y.ts"), TvHome.parse("enigmacast://tvhome/rec?file=%2Fm%2Fy.ts"))
    }

    @Test
    fun rowsFollowSettingsAndHotel() {
        val all = AppSettings()
        assertEquals(listOf(Row.FAVORITES, Row.IPTV_FAVORITES, Row.CONTINUE, Row.RECORDINGS), TvHome.rows(all, hotel = false))
        assertTrue(TvHome.rows(all, hotel = true).isEmpty())
        assertTrue(TvHome.rows(all.copy(tvHomeChannels = false), hotel = false).isEmpty())
        assertEquals(
            listOf(Row.FAVORITES, Row.RECORDINGS),
            TvHome.rows(all.copy(tvHomeIptvFavorites = false, tvHomeContinue = false), hotel = false),
        )
        assertEquals(4, TvHome.ALL_KEYS.toSet().size)
    }

    @Test
    fun iptvFavoritesOnlyOfActiveSourcesNewestFirst() {
        val rows = listOf(
            fav("acc", "m", "1", "Old film", 1),
            fav("acc", "s", "2", "Series", 3),
            fav("gone", "m", "3", "Other playlist", 5),
            fav("acc", "m", "4", "New film", 4),
        )
        val cards = TvHome.iptvFavorites(rows, setOf("acc"))
        assertEquals(listOf("New film", "Series", "Old film"), cards.map { it.title })
        assertEquals(Link.Library("acc", "s", "2"), cards[1].link)
        assertTrue(cards.all { it.tall })
    }

    @Test
    fun continueMixesIptvAndRecordingsNewestFirst() {
        val iptv = listOf(
            resume("acc", "m", "10", "Film", position = 600_000, duration = 6_000_000, updated = 100),
            resume("acc", "e", "21", "Pilot", position = 300_000, duration = 2_400_000, updated = 300, series = "s9", seriesTitle = "Show"),
            resume("torrent", "m", "x", "Elsewhere", position = 300_000, duration = 2_400_000, updated = 400),
        )
        val recordings = listOf(
            TvHome.RecordingResume("/hdd/a.ts", "Journaal", "NPO 1 HD", 120_000, 1_800_000, 200),
            // Too short to resume and watched to the end: left out.
            TvHome.RecordingResume("/hdd/b.ts", "Kort", "NPO 2", 10_000, 1_800_000, 500),
            TvHome.RecordingResume("/hdd/c.ts", "Klaar", "NPO 3", 1_790_000, 1_800_000, 600),
        )
        val cards = TvHome.continueCards(iptv, setOf("acc"), recordings)
        assertEquals(listOf("Show", "Journaal", "Film"), cards.map { it.title })
        assertEquals(Link.Resume("acc", "e", "21"), cards[0].link)
        assertEquals(Link.Recording("/hdd/a.ts", resume = true), cards[1].link)
        assertEquals("NPO 1 HD", cards[1].channel)
        assertEquals(600_000L, cards[2].positionMs)
    }

    @Test
    fun iptvNameFindsSatellitePicon() {
        val channels = listOf(
            BouquetEntry("NPO 1 HD", "1:0:19:17C0:C:3:EEEE0000:0:0:0:", picon = "/picon/npo1.png"),
            BouquetEntry("SBS 6", "1:0:19:1B1D:802:2174:EEEE0000:0:0:0:", picon = "/picon/sbs6.png"),
        )
        val paths = TvHomeArt.piconsByName("NL - SBS 6 4K", channels)
        assertEquals("/picon/sbs6.png", paths.first())
        assertTrue(TvHomeArt.piconsByName("Totally Unknown", channels).isEmpty())
        assertTrue(TvHomeArt.piconsByName("NL - SBS 6", emptyList()).isEmpty())
    }

    @Test
    fun svgIsRecognised() {
        assertTrue(TvHomeArt.looksLikeSvg("<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertTrue(TvHomeArt.looksLikeSvg("\uFEFF  <svg viewBox=\"0 0 1 1\"></svg>".toByteArray()))
        assertFalse(TvHomeArt.looksLikeSvg(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertFalse(TvHomeArt.looksLikeSvg(ByteArray(0)))
    }

    private fun fav(source: String, kind: String, id: String, title: String, added: Long) =
        IptvLibraryRow(source, kind, id, title, "https://img.example/$id.jpg", null, null, "1", "mp4", "", added)

    private fun resume(
        source: String, kind: String, id: String, title: String,
        position: Long, duration: Long, updated: Long, series: String? = null, seriesTitle: String? = null,
    ) = IptvResumeRow(source, kind, id, position, duration, updated, title, null, "mp4", "", "1", series, seriesTitle, 1, 1)
}
