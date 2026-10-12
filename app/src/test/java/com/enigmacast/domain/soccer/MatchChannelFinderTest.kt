package com.enigmacast.domain.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchChannelFinderTest {
    private val kickoff = 1_800_000_000L
    private fun team(id: Int, name: String, short: String, tla: String = "") = SoccerTeam(id, name, short, tla, "")
    private fun match(home: SoccerTeam, away: SoccerTeam) =
        SoccerMatch(1, "DED", "Eredivisie", kickoff, MatchStatus.SCHEDULED, "TIMED", 9, "", null, home, away, Score(null, null), Score(null, null), null, null, null)
    private fun epg(channel: String, title: String, begin: Long = kickoff - 900, desc: String = "") =
        EpgCandidate("1:0:19:${channel.hashCode().toUInt().toString(16)}:1:1:1:0:0:0:", channel, 1, title, desc, begin, begin + 7200)

    private val psv = team(674, "PSV", "PSV", "PSV")
    private val ajax = team(678, "AFC Ajax", "Ajax", "AJA")
    private val feyenoord = team(675, "Feyenoord Rotterdam", "Feyenoord", "FEY")
    private val sparta = team(666, "Sparta Rotterdam", "Sparta", "SPA")

    @Test
    fun normalisesAccentsAndClubWords() {
        assertEquals("fc koln", MatchChannelFinder.normalize("FC Köln"))
        assertTrue("ajax" in MatchChannelFinder.variants(ajax))
        assertTrue("bayern munchen" in MatchChannelFinder.variants(team(5, "FC Bayern München", "Bayern")))
        assertTrue("manchester united" in MatchChannelFinder.variants(team(66, "Manchester United FC", "Man United")))
    }

    @Test
    fun findsTheChannelWhoseTitleNamesBothTeams() {
        val found = MatchChannelFinder.find(
            match(psv, ajax),
            listOf(epg("NPO 1", "Journaal"), epg("ESPN 1", "Live Eredivisie: PSV - Ajax"), epg("ESPN 2", "Ajax magazine")),
        )
        assertEquals("ESPN 1", found?.channelName)
    }

    @Test
    fun cityNameAloneIsNotEnough() {
        // "Rotterdam" is in both Feyenoord's and Sparta's full names; the title must name the right club.
        val list = listOf(epg("ESPN 1", "Sparta Rotterdam - Ajax"), epg("ESPN 2", "Feyenoord - Ajax"))
        assertEquals("ESPN 2", MatchChannelFinder.find(match(feyenoord, ajax), list)?.channelName)
        assertEquals("ESPN 1", MatchChannelFinder.find(match(sparta, ajax), list)?.channelName)
    }

    @Test
    fun descriptionCountsWhenTheTitleIsGeneric() {
        val list = listOf(epg("Ziggo Sport", "Eredivisie live", desc = "Wedstrijd tussen Feyenoord en PSV"))
        assertEquals("Ziggo Sport", MatchChannelFinder.find(match(feyenoord, psv), list)?.channelName)
        val better = list + epg("ESPN 1", "Feyenoord - PSV", begin = kickoff - 1500)
        assertEquals("ESPN 1", MatchChannelFinder.find(match(feyenoord, psv), better)?.channelName)
    }

    @Test
    fun onlyAroundKickoff() {
        assertNull(MatchChannelFinder.find(match(psv, ajax), listOf(epg("ESPN 1", "PSV - Ajax", begin = kickoff - 3 * 3600))))
        assertNull(MatchChannelFinder.find(match(psv, ajax), listOf(epg("ESPN 1", "PSV - Feyenoord"))))
        val two = listOf(epg("ESPN 1", "PSV - Ajax", begin = kickoff - 1700), epg("ESPN 3", "PSV - Ajax", begin = kickoff - 300))
        assertEquals("ESPN 3", MatchChannelFinder.find(match(psv, ajax), two)?.channelName)
    }

    @Test
    fun tvSpellingsMatch() {
        val united = team(66, "Manchester United FC", "Man United")
        val city = team(65, "Manchester City FC", "Man City")
        assertEquals("Viaplay", MatchChannelFinder.find(match(united, city), listOf(epg("Viaplay", "Premier League: Man Utd v Manchester City")))?.channelName)
        val bayern = team(5, "FC Bayern München", "Bayern")
        val bvb = team(4, "Borussia Dortmund", "Dortmund")
        assertEquals("Sky", MatchChannelFinder.find(match(bayern, bvb), listOf(epg("Sky", "Bayern München – BVB")))?.channelName)
    }
}
