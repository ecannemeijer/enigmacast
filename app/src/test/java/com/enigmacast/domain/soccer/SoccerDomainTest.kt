package com.enigmacast.domain.soccer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoccerDomainTest {
    private fun team(id: Int, name: String, short: String = name, tla: String = "") = SoccerTeam(id, name, short, tla, "")
    private fun match(id: Int, comp: String, kickoff: Long, status: MatchStatus, home: SoccerTeam = team(1, "A"), away: SoccerTeam = team(2, "B"), matchday: Int? = 1) =
        SoccerMatch(id, comp, comp, kickoff, status, status.name, matchday, "", null, home, away, Score(null, null), Score(null, null), null, null, null)

    @Test
    fun statusMapping() {
        assertEquals(MatchStatus.SCHEDULED, MatchStatus.of("TIMED"))
        assertEquals(MatchStatus.SCHEDULED, MatchStatus.of("SCHEDULED"))
        assertEquals(MatchStatus.LIVE, MatchStatus.of("IN_PLAY"))
        assertEquals(MatchStatus.LIVE, MatchStatus.of("LIVE"))
        assertEquals(MatchStatus.LIVE, MatchStatus.of("EXTRA_TIME"))
        assertEquals(MatchStatus.HALF_TIME, MatchStatus.of("PAUSED"))
        assertEquals(MatchStatus.FINISHED, MatchStatus.of("FINISHED"))
        assertEquals(MatchStatus.FINISHED, MatchStatus.of("AWARDED"))
        assertEquals(MatchStatus.POSTPONED, MatchStatus.of("SUSPENDED"))
        assertEquals(MatchStatus.CANCELLED, MatchStatus.of("CANCELLED"))
        assertEquals(MatchStatus.SCHEDULED, MatchStatus.of(null))
    }

    @Test
    fun liveMinuteEstimate() {
        val k = 1_000_000L
        val live = match(1, "DED", k, MatchStatus.LIVE)
        assertEquals("1'", MatchClock.label(live, k))
        assertEquals("30'", MatchClock.label(live, k + 29 * 60))
        assertEquals("45+'", MatchClock.label(live, k + 50 * 60))
        assertEquals("67'", MatchClock.label(live, k + (67 + 15 - 1) * 60))
        assertEquals("90+'", MatchClock.label(live, k + 110 * 60))
        assertEquals("HT", MatchClock.label(match(2, "DED", k, MatchStatus.HALF_TIME), k + 50 * 60))
        assertEquals("12'", MatchClock.label(live.copy(minute = 12), k + 80 * 60))
        assertNull(MatchClock.label(match(3, "DED", k, MatchStatus.SCHEDULED), k))
    }

    @Test
    fun countryMapping() {
        assertEquals(listOf("PL", "ELC"), SoccerCountries.byId("ENG").competitions)
        assertEquals(listOf("DED"), SoccerCountries.byId("unknown").competitions)
        assertEquals("ENG", SoccerCountries.defaultFor("GB"))
        assertEquals("NL", SoccerCountries.defaultFor("BE"))
        assertEquals("NL", SoccerCountries.defaultFor(null))
        assertEquals(SoccerCountries.ALL, SoccerCountries.offered(null))
        val offered = SoccerCountries.offered(setOf("DED", "PL", "CL"))
        assertEquals(listOf("NL", "ENG", "EU"), offered.map { it.id })
        assertEquals(listOf("PL"), offered.first { it.id == "ENG" }.competitions)
        // Every mapped code is one the key really returned.
        val real = setOf("BSA", "ELC", "PL", "CL", "EC", "FL1", "BL1", "SA", "DED", "PPL", "CLI", "PD", "WC")
        assertTrue(SoccerCountries.ALL.flatMap { it.competitions }.all { it in real })
    }

    @Test
    fun heroPrefersLiveThenNextInChosenCompetition() {
        val now = 10_000L
        val next = match(1, "ELC", now + 600, MatchStatus.SCHEDULED)
        val nextPl = match(2, "PL", now + 3600, MatchStatus.SCHEDULED)
        val done = match(3, "PL", now - 8000, MatchStatus.FINISHED)
        assertEquals(2, SoccerHome.hero(listOf(next, nextPl, done), "PL", now)!!.id)
        assertEquals(1, SoccerHome.hero(listOf(next, done), "PL", now)!!.id)
        val live = match(4, "ELC", now - 1200, MatchStatus.LIVE)
        val livePl = match(5, "PL", now - 600, MatchStatus.HALF_TIME)
        assertEquals(5, SoccerHome.hero(listOf(next, nextPl, live, livePl), "PL", now)!!.id)
        assertEquals(4, SoccerHome.hero(listOf(next, live), "PL", now)!!.id)
        assertEquals(3, SoccerHome.hero(listOf(done), "PL", now)!!.id)
        assertNull(SoccerHome.hero(emptyList(), "PL", now))
    }

    @Test
    fun sectionsSplitLiveTodayUpcomingAndRecent() {
        val day = 86_400L
        val start = 100 * day
        val now = start + 12 * 3600
        val list = listOf(
            match(1, "DED", now - 1800, MatchStatus.LIVE),
            match(2, "DED", now + 3600, MatchStatus.SCHEDULED),
            match(3, "DED", start + 2 * day, MatchStatus.SCHEDULED, matchday = 9),
            match(4, "DED", start + 3 * day, MatchStatus.SCHEDULED, matchday = 9),
            match(5, "DED", start + 9 * day, MatchStatus.SCHEDULED, matchday = 10),
            match(6, "DED", now - day, MatchStatus.FINISHED),
        )
        val s = SoccerHome.sections(list, "DED", now, start, start + day)
        assertEquals(listOf(1), s.live.map { it.id })
        assertEquals(listOf(2), s.today.map { it.id })
        assertEquals(listOf(9 to listOf(3, 4), 10 to listOf(5)), s.upcoming.map { (md, l) -> md to l.map { it.id } })
        assertEquals(listOf(6), s.recent.map { it.id })
        assertEquals(1, s.hero!!.id)
    }

    @Test
    fun rateLimiterAllowsTenPerMinuteThenWaits() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val window = RequestWindow(max = 10, windowMs = 60_000, clockMs = { now }, sleep = { waits += it; now += it })
        repeat(10) { window.acquire(); now += 100 }
        assertTrue(waits.isEmpty())
        window.acquire()
        assertEquals(listOf(60_000L - 1000L), waits)
        window.pauseUntil(now + 30_000)
        window.acquire()
        assertEquals(30_000L, waits.last())
        assertEquals(42L, RequestWindow.retryAfterSec(" 42 "))
        assertEquals(60L, RequestWindow.retryAfterSec("soon"))
    }

    @Test
    fun zones() {
        assertEquals(Zone.TOP, StandingZones.of("PL", 1, 20))
        assertEquals(Zone.EUROPE, StandingZones.of("PL", 5, 20))
        assertEquals(Zone.NONE, StandingZones.of("PL", 10, 20))
        assertEquals(Zone.RELEGATION, StandingZones.of("PL", 18, 20))
        assertEquals(Zone.RELEGATION, StandingZones.of("CL", 30, 36))
    }
}
