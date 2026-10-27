package com.enigmacast.domain.tvhome

import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.tvhome.TvHome.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvHomeTest {
    @Test
    fun linksRoundTrip() {
        val links = listOf(
            Link.Sat("1:0:19:283D:3FB:1:C00000:0:0:0:", "NPO 1 HD"),
            Link.Iptv("a1b2", "12345", "Ziggo Sport & Co", "7"),
            Link.Recording("/media/hdd/movie/20261027 2000 - NPO 1 HD - Journaal+.ts"),
            Link.Open,
        )
        links.forEach { assertEquals(it, TvHome.parse(TvHome.uri(it))) }
    }

    @Test
    fun foreignOrIncompleteLinksAreIgnored() {
        assertNull(TvHome.parse(null))
        assertNull(TvHome.parse("enigmacast://watchnext?source=a&kind=m&id=1"))
        assertNull(TvHome.parse("https://tvhome/sat?ref=1"))
        assertNull(TvHome.parse("enigmacast://tvhome/sat?name=x"))
        assertNull(TvHome.parse("enigmacast://tvhome/iptv?source=a"))
        assertNull(TvHome.parse("enigmacast://tvhome/other"))
    }

    @Test
    fun liveCardsInterleaveAndCap() {
        val sat = (1..3).map { LastChannel("s$it", "Sat $it") }
        val iptv = (1..2).map { "acc" to LastChannel("i$it", "Iptv $it", "c", "https://logo.example/$it.png") }
        val cards = TvHome.liveCards(sat, iptv, iptvFirst = false)
        assertEquals(listOf("Sat 1", "Iptv 1", "Sat 2", "Iptv 2", "Sat 3"), cards.map { it.title })
        assertEquals("https://logo.example/1.png", cards[1].logo)
        assertEquals(listOf("Iptv 1", "Sat 1"), TvHome.liveCards(sat, iptv, iptvFirst = true, max = 2).map { it.title })
    }

    @Test
    fun liveCardsSkipBlankAndDuplicates() {
        val sat = listOf(LastChannel("", "x"), LastChannel("a", "A"), LastChannel("a", "A again"))
        assertEquals(listOf("A"), TvHome.liveCards(sat, emptyList(), iptvFirst = false).map { it.title })
    }

    @Test
    fun onlyPublicLogos() {
        assertEquals("https://cdn.example/logo.png", TvHome.publicLogo("https://cdn.example/logo.png"))
        assertNull(TvHome.publicLogo("http://user:pass@panel.example/logo.png"))
        assertNull(TvHome.publicLogo("http://panel.example/logo.php?username=u&password=p"))
        assertNull(TvHome.publicLogo("file:///sdcard/logo.png"))
        assertNull(TvHome.publicLogo(""))
    }
}
