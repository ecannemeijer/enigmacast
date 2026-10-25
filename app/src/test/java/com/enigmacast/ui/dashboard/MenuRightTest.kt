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

    @Test
    fun okAfterLeftReturnsToThePage() {
        assertTrue(MenuRight.okRestores(DashSection.Home, DashSection.Home, DashSection.Home, viaLeft = true))
        assertTrue(MenuRight.okRestores(DashSection.Torrent, DashSection.Torrent, DashSection.Torrent, viaLeft = true))
    }

    @Test
    fun okAfterBackOrOnAnotherItemOpensTheEntry() {
        // Back (not Left) into the menu: OK keeps its old meaning, the page's first entry.
        assertFalse(MenuRight.okRestores(DashSection.Home, DashSection.Home, DashSection.Home, viaLeft = false))
        // Another section, or a page that never had focus.
        assertFalse(MenuRight.okRestores(DashSection.Settings, DashSection.Home, DashSection.Home, viaLeft = true))
        assertFalse(MenuRight.okRestores(DashSection.Home, DashSection.Home, null, viaLeft = true))
        // Search and My items switch their rows on first.
        assertFalse(MenuRight.okRestores(DashSection.Search, DashSection.Search, DashSection.Search, viaLeft = true))
    }
}
