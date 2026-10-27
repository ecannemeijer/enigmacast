package com.enigmacast.ui.xtream

import com.enigmacast.domain.model.LastIptvChannel
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IptvGuideTest {
    private val now = 1_790_000_000L - 1_790_000_000L % 1800 + 600
    private val news = XtreamCategory("1", "News")
    private val sport = XtreamCategory("2", "Sport")
    private val categories = listOf(news, sport)
    private fun live(id: String, category: XtreamCategory, number: Int = 0) =
        XtreamItem(id, "Channel $id", XtreamSection.LIVE, category.id, number = number)
    private val rows = mapOf(
        news to listOf(live("10", news), live("11", news)),
        sport to (20 until 40).map { live("$it", sport) },
    )

    @Test fun targetPrefersThePlayingChannelThenTheLastOneThenTheFirstCategory() {
        val playing = live("25", sport)
        val last = LastIptvChannel(categoryId = "1", streamId = "11")
        assertEquals(IptvGuideRows.Target(sport, "25"), IptvGuideRows.target(playing, last, categories))
        assertEquals(IptvGuideRows.Target(news, "11"), IptvGuideRows.target(null, last, categories))
        // A category the viewer cannot see (hidden, hotel whitelist) is skipped.
        assertEquals(IptvGuideRows.Target(news, "11"), IptvGuideRows.target(live("9", XtreamCategory("hidden", "X")), last, categories))
        assertEquals(IptvGuideRows.Target(news, null), IptvGuideRows.target(null, null, categories))
        assertNull(IptvGuideRows.target(playing, last, emptyList()))
    }

    @Test fun rowsNumberAndListingsMapToGuideRows() {
        val item = live("7", news)
        assertEquals("iptv:7", IptvGuideRows.reference(item))
        assertEquals(3, IptvGuideRows.entry(item, 2).number)
        assertEquals(12, IptvGuideRows.entry(live("8", news, number = 12), 0).number)
        val events = IptvGuideRows.events(item, listOf(
            XtreamEpg("News", now - 600, now + 1200, "Today"),
            XtreamEpg("Broken", now, now),
        ))
        assertEquals(1, events.size)
        assertEquals("News", events[0].title)
        assertEquals("Today", events[0].plot)
        assertEquals(1800L, events[0].durationSec)
        assertEquals("iptv:7", events[0].reference)
    }

    @Test fun rowsAroundStayInsideTheList() {
        assertEquals(0..12, IptvGuideRows.rowsAround(0, 30))
        assertEquals(14..29, IptvGuideRows.rowsAround(20, 30))
        assertTrue(IptvGuideRows.rowsAround(0, 0).isEmpty())
    }

    @Test fun opensOnThePlayingChannelInItsOwnCategoryAfterTheRowsLoad() = runTest {
        val guide = IptvGuide(
            this,
            categoriesOf = { delay(50); categories },
            itemsOf = { delay(200); rows.getValue(it) },
            listings = { items, onRow -> items.forEach { onRow(it, if (it.id == "35") listOf(XtreamEpg("Match", now - 60, now + 3600)) else emptyList()) } },
            clock = { now },
        )
        guide.open { IptvGuideRows.target(live("35", sport), null, it) }
        advanceUntilIdle()
        assertEquals(sport, guide.category)
        val state = guide.grid.state.value
        assertEquals(15, state.cursor.row)
        assertEquals("iptv:35", state.focusedChannel?.reference)
        assertEquals("Match", guide.grid.focused()?.second?.event?.title)
        // Looked up without listings: shown as "no guide information".
        assertTrue("iptv:30" in guide.checked)
        assertTrue(state.eventsOf(state.channels[10]).isEmpty())
    }

    @Test fun reopeningTheSameCategoryMovesTheHighlightBackToThePlayingChannel() = runTest {
        val guide = IptvGuide(this, { categories }, { rows.getValue(it) }, { _, _ -> }, clock = { now })
        guide.open { IptvGuideRows.target(live("22", sport), null, it) }
        advanceUntilIdle()
        guide.grid.moveRows(5)
        guide.open { IptvGuideRows.target(live("22", sport), null, it) }
        advanceUntilIdle()
        assertEquals(2, guide.grid.state.value.cursor.row)
        // Nothing playing or remembered: the first channel of the first category.
        guide.open { IptvGuideRows.target(null, null, it) }
        advanceUntilIdle()
        assertEquals(news, guide.category)
        assertEquals(0, guide.grid.state.value.cursor.row)
    }
}
