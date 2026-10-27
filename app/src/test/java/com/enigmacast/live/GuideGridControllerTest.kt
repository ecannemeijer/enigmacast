package com.enigmacast.live

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.TimerResult
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.EpgSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuideGridControllerTest {
    private val now = 1_790_000_000L - 1_790_000_000L % 1800 + 600 // 10 minutes into a half hour
    private val receiver = ReceiverConfig(id = "box", name = "SF8008", host = "192.168.1.209")
    private val one = BouquetEntry("NPO 1", "1:0:19:1B6C:C88:3:EB0000:0:0:0:")
    private val two = BouquetEntry("NPO 2", "1:0:19:1B6D:C88:3:EB0000:0:0:0:")
    private val three = BouquetEntry("NPO 3", "1:0:19:1B6E:C88:3:EB0000:0:0:0:")
    private val channels = listOf(one, two, three)

    private class FakeEpg(val events: List<GuideEvent>) : EpgSource {
        val requests = mutableListOf<Pair<String, Long>>()
        var fail = false
        override suspend fun serviceEvents(config: ReceiverConfig, serviceReference: String) = emptyList<GuideEvent>()
        override suspend fun bouquetEvents(config: ReceiverConfig, bouquetReference: String, startSec: Long, minutes: Int): List<GuideEvent> {
            requests += bouquetReference to startSec
            if (fail) error("offline")
            return events.filter { it.endEpochSec > startSec && it.beginEpochSec < startSec + minutes * 60 }
        }
        override suspend fun record(config: ReceiverConfig, serviceReference: String, eventId: Int) = TimerResult(true, "")
        override suspend fun search(config: ReceiverConfig, query: String) = emptyList<com.enigmacast.domain.search.EpgSearchEvent>()
    }

    private fun event(id: Int, channel: BouquetEntry, begin: Long, minutes: Int) =
        GuideEvent(id, channel.reference, "P$id", "", "", begin, minutes * 60L)

    private val airing = event(1, two, now - 600, 30)
    private val next = event(2, two, now + 1200, 60)
    private val later = event(3, two, now + 4800, 60)

    private fun TestScope.controller(epg: EpgSource) =
        GuideGridController(this, GuideRepository(epg), { receiver }, clock = { now })

    @Test
    fun showPutsTheHighlightOnTheTunedChannelNowAndLoadsTheVisibleBlocksOnce() = runTest {
        val epg = FakeEpg(listOf(airing, next, later))
        val grid = controller(epg)
        grid.show("bouquet-a", channels, two.reference)
        advanceUntilIdle()
        val state = grid.state.value
        assertEquals(1, state.cursor.row)
        assertEquals(airing, grid.focused()?.second?.event)
        assertFalse(state.loading)
        assertEquals(listOf(airing, next, later), state.eventsOf(two))
        val firstRequests = epg.requests.size
        assertTrue(firstRequests in 1..3)
        // The same bouquet again (a refresh of the channel list) neither reloads nor moves the highlight.
        grid.moveRows(1)
        grid.show("bouquet-a", channels, one.reference)
        advanceUntilIdle()
        assertEquals(firstRequests, epg.requests.size)
        assertEquals(2, grid.state.value.cursor.row)
    }

    @Test
    fun rightMovesToTheNextProgrammeAndLeftOnTheProgrammeOnAirReportsTheEdge() = runTest {
        val grid = controller(FakeEpg(listOf(airing, next, later)))
        grid.show("bouquet-a", channels, two.reference)
        advanceUntilIdle()
        assertFalse(grid.moveTime(-1))
        assertTrue(grid.moveTime(1))
        assertEquals(next, grid.focused()?.second?.event)
        assertTrue(grid.moveTime(-1))
        assertEquals(airing, grid.focused()?.second?.event)
    }

    @Test
    fun focusChannelGoesBackToTheChannelThatPlaysAtTheCurrentTime() = runTest {
        val grid = controller(FakeEpg(listOf(airing, next, later)))
        grid.show("bouquet-a", channels, one.reference)
        advanceUntilIdle()
        grid.moveRows(2)
        grid.focusChannel(two.reference)
        assertEquals(1, grid.state.value.cursor.row)
        assertEquals(airing, grid.focused()?.second?.event)
    }

    @Test
    fun aFailedBlockIsReportedAndLoadedAgainOnRefresh() = runTest {
        val epg = FakeEpg(listOf(airing, next))
        epg.fail = true
        val grid = controller(epg)
        grid.show("bouquet-a", channels, two.reference)
        advanceUntilIdle()
        assertTrue(grid.state.value.failed)
        assertTrue(grid.state.value.eventsOf(two).isEmpty())
        epg.fail = false
        grid.refresh()
        advanceUntilIdle()
        assertFalse(grid.state.value.failed)
        assertEquals(listOf(airing, next), grid.state.value.eventsOf(two))
    }

    @Test
    fun anotherBouquetStartsAgainAtTheTopOfItsOwnProgrammes() = runTest {
        val grid = controller(FakeEpg(listOf(airing, next)))
        grid.show("bouquet-a", channels, three.reference)
        advanceUntilIdle()
        grid.show("bouquet-b", listOf(two, three), "")
        advanceUntilIdle()
        val state = grid.state.value
        assertEquals("bouquet-b", state.bouquetReference)
        assertEquals(0, state.cursor.row)
    }

    @Test
    fun aFocusAskedBeforeTheRowsLoadLandsOnThatChannelAtNow() = runTest {
        val grid = controller(FakeEpg(listOf(airing, next, later)))
        // Asked while the guide is still empty (async loading): kept until the rows are shown.
        assertFalse(grid.focusChannel(two.reference))
        grid.show("bouquet-a", channels, "")
        advanceUntilIdle()
        assertEquals(1, grid.state.value.cursor.row)
        assertEquals(airing, grid.focused()?.second?.event)
    }

    @Test
    fun theFocusWaitsForTheBouquetOfThePlayingChannel() = runTest {
        val grid = controller(FakeEpg(listOf(airing, next)))
        grid.show("bouquet-a", listOf(one, three), "")
        advanceUntilIdle()
        assertFalse(grid.focusChannel(two.reference))
        assertEquals(0, grid.state.value.cursor.row)
        grid.show("bouquet-b", listOf(one, three, two), "")
        advanceUntilIdle()
        assertEquals(2, grid.state.value.cursor.row)
        assertTrue(grid.focusChannel(one.reference))
        assertEquals(0, grid.state.value.cursor.row)
    }

    @Test
    fun focusFirstDropsAWaitingFocus() = runTest {
        val grid = controller(FakeEpg(emptyList()))
        grid.focusChannel(three.reference)
        grid.focusFirst()
        grid.show("bouquet-a", channels, "")
        advanceUntilIdle()
        assertEquals(0, grid.state.value.cursor.row)
    }

    @Test
    fun putEventsMergesIntoTheShownBouquetOnly() = runTest {
        val grid = GuideGridController(this, { _, _ -> emptyList() }, clock = { now })
        grid.show("iptvcat:a", channels, "")
        advanceUntilIdle()
        grid.putEvents("iptvcat:b", listOf(airing))
        assertTrue(grid.state.value.eventsOf(two).isEmpty())
        grid.putEvents("iptvcat:a", listOf(airing, next))
        assertEquals(listOf(airing, next), grid.state.value.eventsOf(two))
    }
}
