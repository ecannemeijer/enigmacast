package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvSearchTest {
    private fun item(id: String, name: String, s: XtreamSection = XtreamSection.MOVIES) = XtreamItem(id, name, s, "c")

    @Test fun likePatternEscapes() {
        assertEquals("%abc%", IptvSearch.likePattern(" abc "))
        assertEquals("%50\\% off\\_x%", IptvSearch.likePattern("50% off_x"))
        assertEquals("%a\\\\b%", IptvSearch.likePattern("a\\b"))
    }

    @Test fun minimumLength() {
        assertFalse(IptvSearch.searchable("a"))
        assertTrue(IptvSearch.searchable("ab"))
        assertTrue(IptvSearch.filter(sequenceOf(item("1", "abc")), "a").isEmpty())
    }

    @Test fun filterIgnoresCaseAndDuplicates() {
        val hits = IptvSearch.filter(sequenceOf(item("1", "The Matrix"), item("1", "The Matrix"), item("2", "Other"), item("3", "MATRIX 2")), "matrix")
        assertEquals(listOf("1", "3"), hits.map { it.id })
    }

    @Test fun rowsPerSectionLimited() {
        val items = (1..80).map { item("m$it", "Film $it") } + item("l1", "Live 1", XtreamSection.LIVE) + item("s1", "Show", XtreamSection.SERIES)
        val r = IptvSearch.results("acct", items)
        assertEquals(IptvSearch.LIMIT, r.films.size)
        assertEquals(1, r.live.size)
        assertEquals(1, r.series.size)
        assertEquals("acct", r.films.first().source)
        assertEquals(setOf(52), setOf(r.films.size + r.live.size + r.series.size))
    }

    @Test fun cachedFilterIsFast() {
        val items = (1..50_000).map { item("$it", "Title number $it") }
        val start = System.nanoTime()
        val hits = IptvSearch.filter(items.asSequence(), "number 4999", Int.MAX_VALUE)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(hits.isNotEmpty())
        assertTrue("took $ms ms", ms < 300)
    }
}
