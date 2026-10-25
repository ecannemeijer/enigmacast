package com.enigmacast.domain.xtream

import com.enigmacast.domain.xtream.IptvZapList.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvZapListTest {
    private fun live(id: String, provider: String = "a", category: String = "1") =
        XtreamItem(id, "ch$id", XtreamSection.LIVE, category, providerId = provider)

    private val news = XtreamCategory("1", "News", "a")
    private val sport = XtreamCategory("2", "Sport", "a")
    private val newsList = listOf(live("10"), live("11"), live("12"))
    private val sportList = listOf(live("20", category = "2"), live("21", category = "2"))

    @Test fun zapWalksThePlayingChannelsCategory() {
        assertEquals("11", IptvZapList.step(newsList, live("10"), 1)?.id)
        assertEquals("12", IptvZapList.step(newsList, live("10"), -1)?.id)
        // The browsed category does not hold the channel: no step from it.
        assertNull(IptvZapList.step(sportList, live("10"), 1))
    }

    @Test fun sameIdOnAnotherProviderIsAnotherChannel() {
        val mixed = listOf(live("10", provider = "b"), live("10"), live("11"))
        assertEquals("11", IptvZapList.step(mixed, live("10"), 1)?.id)
        assertFalse(IptvZapList.holds(newsList, live("10", provider = "b")))
    }

    @Test fun choosingInTheShownCategoryCommitsIt() {
        assertEquals(Source.SHOWN, IptvZapList.source(true, sportList, newsList, live("20", category = "2")))
        assertEquals(Source.KEPT, IptvZapList.source(true, sportList, newsList, live("11")))
        assertEquals(Source.LOAD, IptvZapList.source(true, sportList, newsList, live("30", category = "3")))
        // A film list on screen is never the zap list.
        assertEquals(Source.KEPT, IptvZapList.source(false, newsList, newsList, live("11")))
    }

    @Test fun closingAfterBrowsingRestoresThePlayingCategory() {
        assertTrue(IptvZapList.needsRestore(sport, news, newsList, live("11")))
        assertFalse(IptvZapList.needsRestore(XtreamCategory("1", "News", "a"), news, newsList, live("11")))
        assertFalse(IptvZapList.needsRestore(sport, news, newsList, null))
        assertFalse(IptvZapList.needsRestore(sport, null, emptyList(), live("11")))
        // Same category id on another provider is another category.
        assertTrue(IptvZapList.needsRestore(XtreamCategory("1", "News", "b"), news, newsList, live("11")))
    }
}
