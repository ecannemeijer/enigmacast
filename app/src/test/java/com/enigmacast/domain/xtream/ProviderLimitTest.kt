package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLimitTest {
    @Test
    fun maxConnectionAnswersAreProviderLimits() {
        listOf(458, 429, 509, 403).forEach { assertTrue("$it", ProviderLimit.isLimit(it)) }
        listOf(null, 200, 401, 404, 500, 502, 503).forEach { assertFalse("$it", ProviderLimit.isLimit(it)) }
    }

    @Test
    fun budgetIsTwoSlowRetriesThenTheReason() {
        val budget = ProviderLimit.budget()
        assertEquals(2, budget.maxAttempts)
        assertEquals(5_000L, budget.next())
        assertEquals(10_000L, budget.next())
        assertNull(budget.next())
        assertTrue(budget.exhausted)
        budget.reset()
        assertEquals(5_000L, budget.next())
    }

    @Test
    fun sameProviderComparesHostsOnly() {
        val a = "http://line.example.com:8080/movie/user/pass/123.mkv"
        val b = "http://LINE.example.com:8080/live/user/pass/9.ts"
        assertEquals("line.example.com", ProviderLimit.host(a))
        assertTrue(ProviderLimit.sameProvider(a, b))
        assertFalse(ProviderLimit.sameProvider(a, "http://other.example.net/live/1.ts"))
        assertFalse(ProviderLimit.sameProvider(null, b))
        assertFalse(ProviderLimit.sameProvider("", ""))
        assertEquals("host", ProviderLimit.host("https://user:pw@host:443/x"))
        assertNull(ProviderLimit.host("not a url"))
    }

    @Test
    fun budgetReportsItsOwnMaximumNotTheDefault() {
        val iptv = com.enigmacast.domain.tuner.RestartBudget(5, 1_000L, 8_000L)
        assertEquals(5, iptv.maxAttempts)
        repeat(5) { iptv.next() }
        assertEquals(iptv.maxAttempts, iptv.attempts)
        assertNull(iptv.next())
        assertEquals(5, iptv.attempts)
    }

    @Test
    fun episodePlotIsReadFromSeriesInfo() {
        val eps = XtreamJson.episodes("""{"episodes":{"1":[{"id":"11","episode_num":"3","title":"Show - S01E03 - The Trial","container_extension":"mkv","season":"1","info":{"plot":"  Lizzie stands trial. ","movie_image":"http://x/c.jpg"}},{"id":"12","episode_num":"4","title":"E4","container_extension":"mkv","season":"1"}]}}""")
        assertEquals("Lizzie stands trial.", eps.first { it.id == "11" }.plot)
        assertEquals("", eps.first { it.id == "12" }.plot)
    }
}
