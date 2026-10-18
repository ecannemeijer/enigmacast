package com.enigmacast.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuRightTest {
    @Test
    fun restoresOnlyThePageThatHadFocus() {
        assertTrue(MenuRight.mayRestore(DashSection.Guide, DashSection.Guide))
        assertTrue(MenuRight.mayRestore(DashSection.Torrent, DashSection.Torrent))
        assertFalse(MenuRight.mayRestore(DashSection.Guide, null))
        assertFalse(MenuRight.mayRestore(DashSection.Guide, DashSection.Home))
    }

    @Test
    fun searchAndMyItemsAlwaysTakeTheirOwnEntry() {
        assertFalse(MenuRight.mayRestore(DashSection.Search, DashSection.Search))
        assertFalse(MenuRight.mayRestore(DashSection.MyItems, DashSection.MyItems))
    }

    @Test
    fun everySectionHasAnAnswer() {
        DashSection.entries.forEach { MenuRight.mayRestore(it, it) }
    }
}
