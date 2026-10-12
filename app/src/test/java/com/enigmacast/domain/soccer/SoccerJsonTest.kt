package com.enigmacast.domain.soccer

import com.enigmacast.data.soccer.FdResult
import com.enigmacast.data.soccer.FootballDataHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real football-data.org v4 answers saved on 2026-10-12. */
class SoccerJsonTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("soccer/$name")!!.readText()

    @Test
    fun parsesEredivisieMatches() {
        val matches = SoccerJson.matches(fixture("m_DED.json"))
        assertEquals(22, matches.size)
        val first = matches.first { it.id == 558876 }
        assertEquals("DED", first.competition)
        assertEquals("FC Utrecht", first.home.name)
        assertEquals("Utrecht", first.home.label)
        assertEquals("Willem II", first.away.label)
        assertEquals(MatchStatus.FINISHED, first.status)
        assertEquals(Score(3, 1), first.fullTime)
        assertEquals(Score(0, 0), first.halfTime)
        assertEquals(8, first.matchday)
        assertEquals("Jannick van der Laan", first.referee)
        assertEquals(java.time.Instant.parse("2026-10-11T10:15:00Z").epochSecond, first.kickoffSec)
        assertTrue(matches.any { it.status == MatchStatus.SCHEDULED && !it.fullTime.known })
    }

    @Test
    fun storedMatchJsonRoundTrips() {
        val (match, json) = SoccerJson.matchObjects(fixture("m_PL.json")).first()
        assertEquals(match, SoccerJson.matchFromStored(json))
    }

    @Test
    fun parsesLeagueAndCupStandings() {
        val ded = SoccerJson.standings(fixture("s_DED.json"))
        assertEquals(1, ded.size)
        val top = ded.first().rows.first()
        assertEquals(1, top.position)
        assertEquals("Feyenoord", top.team.label)
        assertEquals(20, top.points)
        assertEquals(20, top.goalDifference)
        assertEquals(6, top.won)
        assertEquals(2, top.draw)
        assertNull(top.form)
        assertEquals(18, ded.first().rows.size)
        val cl = SoccerJson.standings(fixture("s_CL.json"))
        assertEquals("LEAGUE_STAGE", cl.first().stage)
        assertEquals(36, cl.first().rows.size)
    }

    @Test
    fun parsesCompetitionsAndHeadToHead() {
        val codes = SoccerJson.competitions(fixture("competitions.json")).map { it.code }.toSet()
        assertEquals(setOf("BSA", "ELC", "PL", "CL", "EC", "FL1", "BL1", "SA", "DED", "PPL", "CLI", "PD", "WC"), codes)
        val h2h = SoccerJson.headToHead(fixture("h2h.json"), homeTeamId = 673)
        assertEquals(h2h.matches.size, h2h.homeWins + h2h.draws + h2h.awayWins)
        assertNotNull(h2h.matches.firstOrNull())
    }

    @Test
    fun errorAnswersAreClassified() {
        assertEquals(FdResult.InvalidKey, FootballDataHttp.classify(400, fixture("badkey.json")))
        assertEquals(FdResult.NotInPlan, FootballDataHttp.classify(403, fixture("restricted.json")))
        assertEquals(FdResult.RateLimited(60), FootballDataHttp.classify(429, null))
        assertTrue(FootballDataHttp.classify(500, "") is FdResult.Failed)
    }
}
