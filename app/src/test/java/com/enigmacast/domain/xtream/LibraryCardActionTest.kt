package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCardActionTest {
    @Test
    fun filmsSeriesAndTorrentsOpenTheirInfoPage() {
        assertEquals(LibraryCardAction.Ok.INFO, LibraryCardAction.onOk(torrent = false, section = XtreamSection.MOVIES))
        assertEquals(LibraryCardAction.Ok.INFO, LibraryCardAction.onOk(torrent = false, section = XtreamSection.SERIES))
        assertEquals(LibraryCardAction.Ok.INFO, LibraryCardAction.onOk(torrent = true, section = XtreamSection.MOVIES))
    }

    @Test
    fun aLiveChannelHasNoInfoPageAndKeepsTheOptions() {
        assertEquals(LibraryCardAction.Ok.OPTIONS, LibraryCardAction.onOk(torrent = false, section = XtreamSection.LIVE))
    }
}
