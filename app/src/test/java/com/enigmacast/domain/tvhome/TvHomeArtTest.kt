package com.enigmacast.domain.tvhome

import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.model.BouquetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeArtTest {
    private val npo = "1:0:19:283D:3FB:1:C00000:0:0:0:"

    @Test
    fun bouquetPiconComesBeforeTheReferencePicon() {
        val channels = listOf(
            BouquetEntry("RTL 4 HD", "1:0:19:7D0:3F3:1:C00000:0:0:0:", picon = "/picon/rtl4hd.png"),
            BouquetEntry("NPO 1 HD", npo, picon = "/picon/npo1hd.png"),
        )
        assertEquals(
            listOf("/picon/npo1hd.png", "/picon/1_0_1_283D_3FB_1_C00000_0_0_0.png"),
            TvHomeArt.piconCandidates(npo, "NPO 1 HD", channels),
        )
    }

    @Test
    fun referenceMatchIgnoresHexCaseAndFallsBackToTheName() {
        val channels = listOf(BouquetEntry("NPO 1 HD", npo, picon = "/picon/npo1hd.png"))
        assertEquals("/picon/npo1hd.png", TvHomeArt.piconCandidates(npo.lowercase(), "", channels).first())
        val byName = listOf(BouquetEntry("npo 1 hd", "1:0:19:1:1:1:1:0:0:0:", picon = "/picon/by-name.png"))
        assertEquals("/picon/by-name.png", TvHomeArt.piconCandidates(npo, " NPO 1 HD ", byName).first())
    }

    @Test
    fun withoutBouquetsOnlyTheReferencePiconIsLeft() {
        assertEquals(listOf("/picon/1_0_1_283D_3FB_1_C00000_0_0_0.png"), TvHomeArt.piconCandidates(npo, "NPO 1 HD", emptyList()))
        assertTrue(TvHomeArt.piconCandidates("", "Unknown", emptyList()).isEmpty())
    }

    @Test
    fun recordingsFindTheirPiconByChannelName() {
        val channels = listOf(BouquetEntry("NPO 1 HD", npo, picon = "/picon/npo1hd.png"), BouquetEntry("Blank", "1:0:1:1:1:1:1:0:0:0:"))
        assertEquals(listOf("/picon/npo1hd.png"), TvHomeArt.piconCandidates("", "npo 1 hd", channels))
    }

    @Test
    fun iptvLogosAreDownloadedNotPublished() {
        assertEquals("http://panel.example/logo.png", TvHomeArt.downloadableLogo(" http://panel.example/logo.png "))
        assertEquals("http://panel.example/l.php?id=1", TvHomeArt.downloadableLogo("http://panel.example/l.php?id=1"))
        assertNull(TvHomeArt.downloadableLogo("file:///sdcard/logo.png"))
        assertNull(TvHomeArt.downloadableLogo("http://a b/logo.png"))
        assertNull(TvHomeArt.downloadableLogo(null))
    }

    @Test
    fun recordingPicturePrefersTheBackdrop() {
        val both = TmdbMovie(id = 7, title = "Film", posterPath = "/p.jpg", backdropPath = "/b.jpg")
        assertEquals(TvHomeArt.Picture("https://image.tmdb.org/t/p/w780/b.jpg", TvHomeArt.Shape.WIDE), TvHomeArt.recordingPicture(both))
        val poster = TmdbMovie(id = 7, title = "Film", posterPath = "/p.jpg")
        assertEquals(TvHomeArt.Picture("https://image.tmdb.org/t/p/w342/p.jpg", TvHomeArt.Shape.POSTER), TvHomeArt.recordingPicture(poster))
    }

    @Test
    fun noMatchMeansNoTmdbPicture() {
        assertNull(TvHomeArt.recordingPicture(null))
        assertNull(TvHomeArt.recordingPicture(TmdbMovie(id = 0, title = "Placeholder", backdropPath = "/b.jpg")))
        assertNull(TvHomeArt.recordingPicture(TmdbMovie(id = 3, title = "No art")))
        assertNull(TvHomeArt.recordingPicture(TmdbMovie(id = 3, title = "Odd", backdropPath = "http://elsewhere.example/b.jpg")))
    }

    @Test
    fun recordingDescriptionUsesOwnTextThenOverview() {
        val movie = TmdbMovie(id = 1, title = "Film", overview = "TMDB plot")
        assertEquals("NPO 1 HD · 27 okt\nOwn text", TvHomeArt.recordingDescription("Own text", movie, "NPO 1 HD", "27 okt"))
        assertEquals("NPO 1 HD · 27 okt\nTMDB plot", TvHomeArt.recordingDescription(" ", movie, "NPO 1 HD", "27 okt"))
        assertEquals("NPO 1 HD", TvHomeArt.recordingDescription("", null, "NPO 1 HD", null))
    }

    @Test
    fun logosFitTheCardWithoutCropping() {
        val wide = TvHomeArt.fit(220, 132)
        assertEquals(TvHomeArt.CARD_WIDTH, wide.left + wide.right)
        assertTrue(wide.bottom - wide.top <= TvHomeArt.CARD_HEIGHT * 0.70 + 1)
        val square = TvHomeArt.fit(512, 512)
        assertEquals(square.right - square.left, square.bottom - square.top)
        assertTrue(square.top >= 0 && square.bottom <= TvHomeArt.CARD_HEIGHT)
        val banner = TvHomeArt.fit(1000, 100)
        assertTrue(banner.right - banner.left <= TvHomeArt.CARD_WIDTH * 0.78 + 1)
        assertFalse(TvHomeArt.fit(0, 10).right > 0)
    }
}
