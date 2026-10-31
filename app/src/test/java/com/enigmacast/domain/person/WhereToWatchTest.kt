package com.enigmacast.domain.person

import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchKind
import com.enigmacast.domain.search.SearchResults
import com.enigmacast.domain.xtream.XtreamEpg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereToWatchTest {
    @Test fun queriesDropDuplicatesAndBlanks() {
        assertEquals(listOf("The Swindle", "Il bidone"), WhereToWatch.queries("The Swindle", "Il bidone"))
        assertEquals(listOf("Roma"), WhereToWatch.queries("Roma", "ROMA "))
        assertEquals(listOf("Roma"), WhereToWatch.queries("Roma", ""))
    }

    @Test fun splitEpgBySourceDedupedInTimeOrder() {
        val sat = SearchHit(SearchKind.LATER, "A", serviceReference = "1:0:1:sat", beginEpochSec = 300)
        val net = SearchHit(SearchKind.NOW, "A", serviceReference = "4097:0:1:http", beginEpochSec = 100)
        val sat2 = SearchHit(SearchKind.NOW, "A", serviceReference = "1:0:1:sat2", beginEpochSec = 200)
        val (s, i) = WhereToWatch.splitEpg(
            listOf(SearchResults(now = listOf(net, sat2), later = listOf(sat)), SearchResults(later = listOf(sat))),
        ) { it.startsWith("4097") }
        assertEquals(listOf(sat2, sat), s)
        assertEquals(listOf(net), i)
    }

    @Test fun matchesIgnoresCase() {
        assertTrue(WhereToWatch.matches("Film: I VITELLONI (1953)", listOf("I vitelloni")))
        assertFalse(WhereToWatch.matches("La strada", listOf("I vitelloni")))
    }

    @Test fun iptvEpgSkipsEndedAndLimits() {
        val rows = listOf(
            Triple("a", "1", listOf(XtreamEpg("Roma", 0, 50), XtreamEpg("Roma", 200, 300), XtreamEpg("News", 100, 200))),
            Triple("a", "2", listOf(XtreamEpg("Roma", 150, 250))),
        )
        val hits = WhereToWatch.iptvEpg(rows, listOf("roma"), nowSec = 100)
        assertEquals(listOf("2", "1"), hits.map { it.streamId })
        assertEquals(1, WhereToWatch.iptvEpg(rows, listOf("roma"), 100, limit = 1).size)
    }
}
