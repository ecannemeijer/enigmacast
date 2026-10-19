package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesPlayTest {
    private fun ep(id: String, s: Int, e: Int, cover: String = "") = XtreamEpisode(id, s, e, "S$s E$e", "mkv", cover)
    private val list = listOf(ep("0-1", 0, 1), ep("2-1", 2, 1), ep("1-2", 1, 2), ep("1-1", 1, 1, cover = "c"))

    @Test
    fun theContinueWatchingEpisodeComesFirst() {
        assertEquals("2-1", SeriesPlay.target(ep("2-1", 2, 1), ep("1-2", 1, 2), list)?.id)
    }

    @Test
    fun elseTheLatestWatchedEpisode() {
        assertEquals("1-2", SeriesPlay.target(null, ep("1-2", 1, 2), list)?.id)
    }

    @Test
    fun theListsOwnEntryIsUsed() {
        assertEquals("c", SeriesPlay.target(ep("1-1", 1, 1), null, list)?.cover)
    }

    @Test
    fun aRememberedEpisodePlaysBeforeTheListHasLoaded() {
        assertEquals("1-2", SeriesPlay.target(null, ep("1-2", 1, 2), emptyList())?.id)
    }

    @Test
    fun nothingWatchedStartsAtTheFirstRegularEpisode() {
        assertEquals("1-1", SeriesPlay.target(null, null, list)?.id)
        assertEquals("0-1", SeriesPlay.target(null, null, listOf(ep("0-1", 0, 1)))?.id)
        assertNull(SeriesPlay.target(null, null, emptyList()))
    }
}
