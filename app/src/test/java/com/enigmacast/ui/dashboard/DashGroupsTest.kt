package com.enigmacast.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashGroupsTest {
    @Test fun groups() {
        assertEquals(DashGroup.TV, DashGroups.groupOf(DashSection.Movies))
        assertEquals(DashGroup.TV, DashGroups.groupOf(DashSection.Stream))
        assertEquals(DashGroup.IPTV, DashGroups.groupOf(DashSection.IptvMovies))
        assertEquals(DashGroup.IPTV, DashGroups.groupOf(DashSection.IptvSeries))
        assertNull(DashGroups.groupOf(DashSection.Search))
        assertNull(DashGroups.groupOf(DashSection.MyItems))
        assertNull(DashGroups.groupOf(DashSection.Soccer))
        assertNull(DashGroups.groupOf(DashSection.Settings))
    }

    @Test fun entryUsesLastTab() {
        assertEquals(DashSection.Series, DashGroups.entry(DashGroup.TV, DashSection.Series) { true })
    }

    @Test fun entryDefaultsToGuideAndSkipsLiveTv() {
        assertEquals(DashSection.Guide, DashGroups.entry(DashGroup.TV, null) { true })
        assertEquals(DashSection.Guide, DashGroups.entry(DashGroup.TV, DashSection.Stream) { true })
    }

    @Test fun entrySkipsLockedTabs() {
        val locked = setOf(DashSection.Guide, DashSection.Movies)
        assertEquals(DashSection.Series, DashGroups.entry(DashGroup.TV, DashSection.Movies) { it !in locked })
    }

    @Test fun libraryDefault() {
    }

    @Test fun subtitleShowsOpenPageElseLastUsed() {
        assertEquals(DashSection.Movies, DashGroups.current(DashGroup.TV, DashSection.Movies, DashSection.Guide))
        assertEquals(DashSection.Guide, DashGroups.current(DashGroup.TV, DashSection.Home, DashSection.Guide))
        assertEquals(DashSection.Guide, DashGroups.current(DashGroup.TV, DashSection.Stream, DashSection.Guide))
        assertNull(DashGroups.current(DashGroup.TV, DashSection.Home, null))
        assertNull(DashGroups.current(DashGroup.TV, DashSection.Home, DashSection.Search))
    }

    @Test fun flyoutStartsOnCurrentEntry() {
        val tv = DashGroups.tabs(DashGroup.TV)
        assertEquals(2, DashGroups.flyoutStart(tv, DashSection.Movies))
        assertEquals(0, DashGroups.flyoutStart(tv, null))
    }
}
