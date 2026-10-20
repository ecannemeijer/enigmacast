package com.enigmacast.domain.resume

import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.domain.torrent.TmdbSeason
import com.enigmacast.domain.xtream.IptvLibraryRows
import com.enigmacast.domain.xtream.XtreamEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesContinueTest {
    private val eps = listOf(
        XtreamEpisode("a", 1, 1, "Pilot", "mkv"), XtreamEpisode("b", 1, 2, "Two", "mkv"),
        XtreamEpisode("c", 2, 1, "S2 start", "mkv"),
    )

    @Test
    fun anIptvSeriesMovesToTheNextEpisodeThenTheNextSeason() {
        assertEquals(EpisodeDone.Next(1, 2, "b"), SeriesContinue.afterIptv(eps[0], eps))
        assertEquals(EpisodeDone.Next(2, 1, "c"), SeriesContinue.afterIptv(eps[1], eps.shuffled()))
        assertEquals(EpisodeDone.SeriesDone, SeriesContinue.afterIptv(eps[2], eps))
        // Episode list not loaded: keep the card rather than drop the series.
        assertEquals(EpisodeDone.Unknown, SeriesContinue.afterIptv(eps[0], emptyList()))
    }

    @Test
    fun aTorrentSeriesUsesTheTmdbSeasons() {
        val seasons = listOf(TmdbSeason(1, "S1", 2), TmdbSeason(3, "S3", 1), TmdbSeason(0, "Specials", 4))
        assertEquals(EpisodeDone.Next(1, 2), SeriesContinue.afterTorrent(seasons, 1, 1))
        assertEquals(EpisodeDone.Next(3, 1), SeriesContinue.afterTorrent(seasons, 1, 2))
        assertEquals(EpisodeDone.SeriesDone, SeriesContinue.afterTorrent(seasons, 3, 1))
        assertEquals(EpisodeDone.Unknown, SeriesContinue.afterTorrent(emptyList(), 1, 1))
        assertEquals(EpisodeDone.Unknown, SeriesContinue.afterTorrent(seasons, 2, 1))
    }

    @Test
    fun theNextEpisodeCardShowsItsNextState() {
        assertTrue(SeriesContinue.isUpNext("e", 0))
        assertFalse(SeriesContinue.isUpNext("e", 600_000))
        assertFalse(SeriesContinue.isUpNext("m", 0))
        assertTrue(SeriesContinue.keepNext(Any()))
        assertFalse(SeriesContinue.keepNext(null))
        val next = IptvResumeRow("acc", "e", "b", 0, 0, 5, "Two", null, "mkv", "", "", "s1", "Show", 1, 2)
        val card = IptvLibraryRows.continueWatching(listOf(next), setOf("acc")).single()
        assertTrue(card.upNext)
        assertEquals("Show", card.title)
    }
}
