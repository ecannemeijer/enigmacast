package com.enigmacast.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodTitleCardTest {
    @Test
    fun episodeNameDropsSeriesAndCode() {
        assertEquals("The Trial", VodTitleCard.episodeName("Monster - S01E03 - The Trial", "Monster"))
        assertEquals("The Trial", VodTitleCard.episodeName("NF - Monster (2026) S1 E3: The Trial"))
        assertEquals("", VodTitleCard.episodeName("Monster - S01E03", "Monster"))
        assertEquals("", VodTitleCard.episodeName("Monster", "Monster"))
        assertEquals("", VodTitleCard.episodeName("12345", id = "12345"))
        assertEquals("Pilot", VodTitleCard.episodeName("Pilot"))
        assertEquals("", VodTitleCard.episodeName(null))
    }

    @Test
    fun overviewPrefersTmdbAndCollapsesWhitespace() {
        assertEquals("TMDB plot.", VodTitleCard.overview("TMDB  plot.", "Playlist plot"))
        assertEquals("Playlist plot", VodTitleCard.overview("", null, "Playlist\nplot"))
        assertEquals("", VodTitleCard.overview(null, " "))
    }

    @Test
    fun tmdbPlaceholderNamesFallBackToThePlaylist() {
        assertEquals("The Trial", VodTitleCard.preferEpisodeName("Aflevering 3", "The Trial", 3))
        assertEquals("The Trial", VodTitleCard.preferEpisodeName("Episode 3", "The Trial", 3))
        assertEquals("The Trial", VodTitleCard.preferEpisodeName("E3", "The Trial", 3))
        assertEquals("Het proces", VodTitleCard.preferEpisodeName("Het proces", "The Trial", 3))
        assertEquals("Episode 3", VodTitleCard.preferEpisodeName("Episode 3", "", 3))
        assertEquals("Pilot", VodTitleCard.preferEpisodeName(null, "Pilot", 1))
    }

    @Test
    fun onlyRealEpisodesShowTheCode() {
        assertTrue(VodTitleInfo("Monster", season = 1, episode = 3).isEpisode)
        assertFalse(VodTitleInfo("Film").isEpisode)
        assertFalse(VodTitleInfo("Show", season = 0, episode = 3).isEpisode)
    }
}
