package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowEventsTest {
    private val npo1 = "1:0:19:4651:3FC:1:C00000:0:0:0:"
    private val events = listOf(
        NowEvent(npo1, "NOS Journaal", beginEpochSec = 1_000, durationSec = 1_800),
        NowEvent("1:0:19:7D5:3F0:1:C00000:0:0:0:", "RTL Nieuws", beginEpochSec = 400, durationSec = 1_000),
    )

    @Test
    fun matchesChannelsIgnoringCaseAndLeadingZeros() {
        val index = NowEvents.index(events)
        assertEquals("NOS Journaal", NowEvents.lookup(index, "1:0:19:4651:03fc:0001:00c00000:0:0:0:", 1_500)?.title)
        assertNull(NowEvents.lookup(index, "1:0:19:9999:3FC:1:C00000:0:0:0:", 1_500))
    }

    @Test
    fun programmesThatEndedAreNotShown() {
        val index = NowEvents.index(events)
        assertNull(NowEvents.lookup(index, "1:0:19:7D5:3F0:1:C00000:0:0:0:", 1_400))
        assertEquals(0.5f, events[0].progress(1_900), 0.001f)
    }

    @Test
    fun bouquetIsReloadedWhenOldOrWhenAProgrammeEnded() {
        assertFalse(NowEvents.isStale(fetchedAtSec = 1_000, events = events, nowSec = 1_010))
        // RTL ended at 1400, but the list was read only 20 s ago.
        assertFalse(NowEvents.isStale(fetchedAtSec = 1_390, events = events, nowSec = 1_410))
        assertTrue(NowEvents.isStale(fetchedAtSec = 1_300, events = events, nowSec = 1_410))
        assertTrue(NowEvents.isStale(fetchedAtSec = 0, events = emptyList(), nowSec = NowEvents.MAX_AGE_SEC))
    }
}

class ChannelListNavigationTest {
    private val favourites = BouquetEntry("Favorieten", "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.favourites.tv\" ORDER BY bouquet")
    private val sport = BouquetEntry("Sport", "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.sport.tv\" ORDER BY bouquet")

    @Test
    fun leftOpensBouquetsAndBackStepsBack() {
        var pane = ChannelListNavigation.open()
        assertEquals(ListPane.CHANNELS, pane)
        pane = ChannelListNavigation.left(pane)
        assertEquals(ListPane.BOUQUETS, pane)
        assertEquals(ListPane.BOUQUETS, ChannelListNavigation.left(pane))
        pane = ChannelListNavigation.back(pane)
        assertEquals(ListPane.CHANNELS, pane)
        assertEquals(ListPane.CLOSED, ChannelListNavigation.back(pane))
        assertEquals(ListPane.CLOSED, ChannelListNavigation.left(ListPane.CLOSED))
        assertEquals(ListPane.CHANNELS, ChannelListNavigation.bouquetChosen())
    }

    @Test
    fun bouquetColumnFocusesTheActiveBouquet() {
        val bouquets = listOf(favourites, sport)
        val encoded = "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET %22userbouquet.sport.tv%22 ORDER BY bouquet"
        assertEquals(1, ChannelListNavigation.bouquetFocusIndex(bouquets, encoded))
        assertEquals(0, ChannelListNavigation.bouquetFocusIndex(bouquets, ""))
        assertEquals(0, ChannelListNavigation.bouquetFocusIndex(bouquets, "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"other.tv\""))
    }

    @Test
    fun channelListFocusesThePlayingChannel() {
        val channels = listOf(
            BouquetEntry("NPO 1", "1:0:19:4651:3FC:1:C00000:0:0:0:"),
            BouquetEntry("NPO 2", "1:0:19:4652:3FC:1:C00000:0:0:0:"),
        )
        assertEquals(1, ChannelListNavigation.channelFocusIndex(channels, "1:0:19:4652:3fc:1:c00000:0:0:0:"))
        assertEquals(0, ChannelListNavigation.channelFocusIndex(channels, "1:0:19:1:1:1:C00000:0:0:0:"))
    }
}
