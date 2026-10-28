package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveListFocusTest {
    private val keys = (0 until 40).map { "live:LIVE:$it" }

    @Test
    fun emptyListHasNoTarget() {
        assertEquals(-1, LiveListFocus.target(emptyList(), "live:LIVE:3", 0, emptyList()))
    }

    @Test
    fun leftFromGuideReturnsToTheChannelItShowed() {
        // Playing channel 2, the guide was opened on channel 25 (scrolled off the playing row).
        assertEquals(25, LiveListFocus.target(keys, "live:LIVE:25", 2, (18..30).toList()))
    }

    @Test
    fun rememberedChannelOffScreenIsStillTheTargetAndScrolls() {
        val visible = (0..12).toList()
        val target = LiveListFocus.target(keys, "live:LIVE:30", 2, visible)
        assertEquals(30, target)
        assertTrue(LiveListFocus.needsScroll(target, visible))
    }

    @Test
    fun withoutARememberedRowThePlayingChannel() {
        assertEquals(7, LiveListFocus.target(keys, null, 7, (0..12).toList()))
    }

    @Test
    fun rememberedKeyFromAnotherCategoryFallsBackToPlaying() {
        assertEquals(4, LiveListFocus.target(keys, "live:LIVE:999", 4, (0..12).toList()))
    }

    @Test
    fun playingChannelNotInThisCategoryTakesFirstVisibleRow() {
        // Not row 0: that row is scrolled away and never composed.
        assertEquals(20, LiveListFocus.target(keys, null, -1, (20..32).toList()))
    }

    @Test
    fun nothingLaidOutYetTakesTheFirstRow() {
        assertEquals(0, LiveListFocus.target(keys, null, -1, emptyList()))
    }

    @Test
    fun visibleRowsOutsideTheListAreIgnored() {
        assertEquals(0, LiveListFocus.target(keys.take(3), null, -1, listOf(5, 6)))
    }

    @Test
    fun visibleTargetDoesNotScroll() {
        assertFalse(LiveListFocus.needsScroll(5, (0..12).toList()))
        assertFalse(LiveListFocus.needsScroll(-1, emptyList()))
    }

    @Test
    fun repeatedIdsKeepTheirOwnRow() {
        // Playlists repeat ids: LazyKeys makes the second one unique and the focus follows that key.
        val keyed = LazyKeys.keyed(listOf("a", "b", "a"), "live") { it }.map { it.key }
        assertEquals(2, LiveListFocus.target(keyed, keyed[2], 0, listOf(0, 1, 2)))
    }
}
