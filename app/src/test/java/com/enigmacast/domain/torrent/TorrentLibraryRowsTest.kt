package com.enigmacast.domain.torrent

import com.enigmacast.domain.xtream.IptvLibraryRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentLibraryRowsTest {
    private val movie = TmdbTitle(603, TorrentKind.MOVIE, "The Matrix", 1999, "https://image.tmdb.org/t/p/w342/m.jpg", "https://b", "")
    private val show = TmdbTitle(1399, TorrentKind.TV, "Game of Thrones", 2011, "https://p", null, "")

    @Test
    fun favoritesRoundTripToTheInfoPage() {
        val row = TorrentLibraryRows.favoriteRow(show, 5)
        assertEquals("torrent", row.source)
        assertEquals("s", row.kind)
        assertEquals("1399", row.id)
        assertEquals(show, TorrentLibraryRows.titleOf(row))
    }

    @Test
    fun episodesAreContinueWatchingCardsOfTheirSeries() {
        val e = TorrentLibraryRows.resumeRow(show, 2, 3, "The Ghost", 120_000, 3_000_000, 10)
        val m = TorrentLibraryRows.resumeRow(movie, null, null, null, 600_000, 8_000_000, 20)
        assertEquals("1399:2:3", e.id)
        assertEquals("e", e.kind)
        assertEquals("603", m.id)
        val cards = IptvLibraryRows.continueWatching(listOf(e, m), setOf(TorrentLibraryRows.SOURCE))
        assertEquals(listOf("cw/torrent/m/603", "cw/torrent/e/1399:2:3"), cards.map { it.key })
        assertEquals("Game of Thrones", cards[1].title)
        assertEquals("S2E3 · The Ghost", cards[1].subtitle)
        // Older apps: "torrent" is never one of their playlist accounts, so nothing shows there.
        assertEquals(emptyList<Any>(), IptvLibraryRows.continueWatching(listOf(e, m), setOf("abc1")))
    }

    @Test
    fun continueWatchingOpensTheSeriesAtItsEpisode() {
        val e = TorrentLibraryRows.resumeRow(show, 2, 3, "The Ghost", 120_000, 3_000_000, 10)
        val request = TorrentLibraryRows.requestOf(e)!!
        assertEquals(1399, request.title.id)
        assertEquals(TorrentKind.TV, request.title.kind)
        assertEquals(2, request.season)
        assertEquals(3, request.episode)
        val later = TorrentLibraryRows.resumeRow(show, 3, 1, null, 70_000, 3_000_000, 30)
        assertEquals("3:1", TorrentLibraryRows.latestEpisode(listOf(e, later), 1399)!!.let { "${it.season}:${it.episode}" })
        assertNull(TorrentLibraryRows.latestEpisode(listOf(e), 1))
    }
}
