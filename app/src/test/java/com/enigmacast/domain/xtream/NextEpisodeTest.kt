package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEpisodeTest {
    private fun ep(id: String, season: Int, episode: Int) =
        XtreamEpisode(id, season, episode, id, "mp4")

    @Test fun nextIsTheFollowingEpisodeInSeasonOrder() {
        val list = listOf(ep("b", 1, 2), ep("a", 1, 1), ep("c", 2, 1))
        assertEquals("b", NextEpisode.after(ep("a", 1, 1), list)?.id)
        assertEquals("c", NextEpisode.after(ep("b", 1, 2), list)?.id)
        assertNull(NextEpisode.after(ep("c", 2, 1), list))
    }

    @Test fun missingCurrentHasNoNext() {
        assertNull(NextEpisode.after(ep("z", 1, 1), listOf(ep("a", 1, 1))))
        assertNull(NextEpisode.after(ep("a", 1, 1), emptyList()))
    }
}
