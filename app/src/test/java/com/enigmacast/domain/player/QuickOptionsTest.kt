package com.enigmacast.domain.player

import com.enigmacast.domain.model.LastChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickOptionsTest {
    @Test
    fun followingTheReceiverLeavesOutZapTiles() {
        assertEquals(QuickOption.SEARCH, QuickOptions.tiles(ownTuner = true).first())
        assertEquals(QuickOption.RECORD, QuickOptions.tiles(ownTuner = true)[1])
        assertTrue(QuickOption.GUIDE in QuickOptions.tiles(ownTuner = true))
        assertFalse(QuickOption.SEARCH in QuickOptions.tiles(ownTuner = true, file = true))
        val follow = QuickOptions.tiles(ownTuner = false)
        assertEquals(QuickOption.SEARCH, follow.first())
        assertFalse(QuickOption.GUIDE in follow)
        assertFalse(QuickOption.RECENT in follow)
        assertTrue(QuickOption.PICTURE in follow)
        assertTrue(QuickOption.PIP in QuickOptions.tiles(ownTuner = true))
        assertFalse(QuickOption.PIP in follow)
        assertFalse(QuickOption.PIP in QuickOptions.tiles(ownTuner = true, file = true))
        assertTrue(QuickOption.PIP in QuickOptions.iptvTiles(vod = false))
        assertFalse(QuickOption.PIP in QuickOptions.iptvTiles(vod = true))
        assertTrue(QuickOption.SUBTITLE_DELAY in QuickOptions.tiles(ownTuner = true))
        assertTrue(QuickOption.SUBTITLE_DELAY in QuickOptions.tiles(ownTuner = true, file = true))
        assertTrue(QuickOption.SUBTITLE_DELAY in QuickOptions.tiles(ownTuner = false))
        assertTrue(QuickOption.SUBTITLE_DELAY in QuickOptions.iptvTiles(vod = true))
    }

    @Test
    fun sleepMinutesRoundUpAndStopAtZero() {
        assertEquals(0, SleepTimerChoices.minutesLeft(0L, 1_000L))
        assertEquals(30, SleepTimerChoices.minutesLeft(30 * 60_000L, 0L))
        assertEquals(1, SleepTimerChoices.minutesLeft(61_000L, 60_000L))
        assertEquals(0, SleepTimerChoices.minutesLeft(5_000L, 6_000L))
    }

    @Test
    fun recentChannelsKeepTheNewestFirstWithoutDuplicates() {
        val npo1 = LastChannel("1:0:19:1B1F:802:2:11A0000:0:0:0:", "NPO 1")
        val rtl4 = LastChannel("1:0:19:1B1D:802:2:11A0000:0:0:0:", "RTL 4")
        var list = RecentChannels.push(emptyList(), npo1)
        list = RecentChannels.push(list, rtl4)
        list = RecentChannels.push(list, npo1.copy(reference = "1:0:19:1b1f:802:2:11a0000:0:0:0:"))
        assertEquals(listOf("NPO 1", "RTL 4"), list.map { it.name })
        val many = (1..15).fold(emptyList<LastChannel>()) { acc, n -> RecentChannels.push(acc, LastChannel("1:0:1:$n:0:0:0:0:0:0:", "C$n")) }
        assertEquals(RecentChannels.MAX, many.size)
        assertEquals("C15", many.first().name)
        assertEquals(list, RecentChannels.push(list, LastChannel()))
    }

    @Test
    fun audioDelayChoicesCoverTheRangeInSteps() {
        val values = AudioDelayChoices.values(-500, 500, 20)
        assertEquals(51, values.size)
        assertTrue(0 in values)
    }
}
