package com.enigmacast.domain.soccer.pitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real pitchapi.dev answers saved on 2026-10-12 (Excelsior – FC Groningen 1–5, 11 Oct). */
class PitchJsonTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("pitch/$name")!!.readText()

    @Test
    fun leaguesIncludeEredivisieAndEersteDivisie() {
        val all = PitchJson.leagues(fixture("leagues.json"))
        assertEquals(77, all.size)
        val nl = PitchStats.leaguesFor("NL", all)
        assertEquals(listOf("Eredivisie", "Eerste Divisie"), nl.map { it.name })
        assertEquals("2026/2027", nl.first().currentSeason)
    }

    @Test
    fun seasonMatchesAndTable() {
        val matches = PitchJson.leagueMatches(fixture("ere_all.json"))
        assertEquals(306, matches.size)
        assertTrue(matches.all { it.leagueName == "Eredivisie" })
        val played = PitchStats.played(matches)
        assertEquals(72, played.size)
        val table = PitchStats.table(matches)
        assertEquals(18, table.size)
        assertEquals(played.size * 2, table.sumOf { it.stats.played })
        assertTrue(table.zipWithNext().all { (a, b) -> a.stats.points >= b.stats.points })
    }

    @Test
    fun matchEventsLineupsStats() {
        val m = PitchJson.matchDetail(fixture("match.json"))!!
        assertEquals("Excelsior", m.home.name)
        assertEquals(1, m.scoreHome); assertEquals(5, m.scoreAway)
        assertEquals("Stadion Woudestein", m.stadium)
        val ev = PitchJson.events(fixture("events.json"))
        assertTrue(ev.any { it.kind == EventKind.OWN_GOAL })
        assertTrue(ev.any { it.kind == EventKind.PENALTY_GOAL })
        assertTrue(ev.any { it.kind == EventKind.YELLOW })
        assertNotNull(ev.first { it.kind == EventKind.SUBSTITUTION }.subIn)
        val lu = PitchJson.lineups(fixture("lineups.json"))!!
        assertEquals("4-3-3", lu.home!!.formation)
        assertEquals(11, lu.home!!.starters.size)
        assertTrue(lu.home!!.subs.isNotEmpty())
        assertTrue(lu.home!!.confirmed)
        val stats = PitchJson.stats(fixture("stats.json"))
        assertTrue(stats.any { g -> g.items.any { it.key == "yellow_cards" && it.away == "2" } })
        val h2h = PitchJson.h2h(fixture("h2h.json"))!!
        assertEquals(21, h2h.total)
    }

    @Test
    fun playerProfileAndMatchLine() {
        val p = PitchJson.player(fixture("player.json"))!!
        assertEquals("Thom van Bergen", p.name)
        assertEquals("NED", p.countryCode)
        val line = PitchJson.playerStats(fixture("player_match.json")).single()
        assertEquals(1, line.goals)
        assertEquals(85, line.minutes)
        assertEquals(7.97, line.rating!!, 0.001)
    }

    @Test
    fun teamNamesMatchAcrossApis() {
        assertTrue(PitchStats.sameTeam("FC Groningen", "FC Groningen"))
        assertTrue(PitchStats.sameTeam("PSV", "PSV Eindhoven"))
        assertTrue(PitchStats.sameTeam("AFC Ajax", "Ajax"))
        assertTrue(PitchStats.sameTeam("SBV Excelsior", "Excelsior"))
        assertFalse(PitchStats.sameTeam("FC Utrecht", "FC Twente"))
    }

    @Test
    fun statuses() {
        assertEquals(PitchStatus.SCHEDULED, PitchStatus.of("not_started"))
        assertEquals(PitchStatus.FINISHED, PitchStatus.of("finished"))
        assertEquals(PitchStatus.LIVE, PitchStatus.of("in_progress"))
        assertEquals(PitchStats.Line.GK, PitchStats.line(11))
        assertEquals(PitchStats.Line.ATT, PitchStats.line(105))
    }
}
