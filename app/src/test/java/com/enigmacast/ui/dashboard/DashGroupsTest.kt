package com.enigmacast.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashGroupsTest {
    @Test fun groups() {
        assertEquals(DashGroup.TV, DashGroups.groupOf(DashSection.Movies))
        assertEquals(DashGroup.TV, DashGroups.groupOf(DashSection.Stream))
        assertEquals(DashGroup.LIBRARY, DashGroups.groupOf(DashSection.Search))
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
        assertEquals(DashSection.MyItems, DashGroups.entry(DashGroup.LIBRARY, null) { true })
    }
}
