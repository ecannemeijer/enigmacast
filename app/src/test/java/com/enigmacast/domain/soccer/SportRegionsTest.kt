package com.enigmacast.domain.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportRegionsTest {
    @Test fun everyCountryIsACardInOrderBeforeTheKeyIsChecked() {
        val cards = SportRegions.cards(null, null)
        assertEquals(SoccerCountries.ALL.map { it.id }, cards.map { it.id })
        assertTrue(cards.all { it.emblem.isEmpty() })
        assertEquals(listOf("Eredivisie"), cards.first { it.id == "NL" }.competitionNames)
        assertEquals(listOf("UEFA Champions League", "UEFA EURO"), cards.first { it.id == "EU" }.competitionNames)
        assertEquals(listOf("Série A", "Copa Libertadores"), cards.first { it.id == "BR" }.competitionNames)
    }

    @Test fun regionsOutsideThePlanDisappearAndEmblemsComeFromTheApi() {
        val competitions = listOf(
            Competition("DED", "Eredivisie", "Netherlands", "https://crests.football-data.org/ED.png"),
            Competition("CL", "UEFA Champions League", "Europe", "https://crests.football-data.org/CL.png"),
        )
        val cards = SportRegions.cards(setOf("DED", "CL"), competitions)
        assertEquals(listOf("NL", "EU"), cards.map { it.id })
        assertEquals("https://crests.football-data.org/ED.png", cards[0].emblem)
        // EC is not in the plan: Europe only lists the Champions League.
        assertEquals(listOf("CL"), cards[1].competitions)
        assertEquals("CL", cards[1].logoCompetition)
    }

    @Test fun unknownCodesFallBackToTheApiName() {
        val cards = SportRegions.cards(null, listOf(Competition("PPL", "Primeira Liga", "Portugal", "")))
        assertEquals(listOf("Primeira Liga"), cards.first { it.id == "PT" }.competitionNames)
    }

    @Test fun initialFocusIsTheFollowedRegion() {
        val cards = SportRegions.cards(null, null)
        assertEquals(cards.indexOfFirst { it.id == "IT" }, SportRegions.initialIndex(cards, "IT"))
        assertEquals(0, SportRegions.initialIndex(cards, "XX"))
        assertEquals(0, SportRegions.initialIndex(cards, null))
        assertEquals(0, SportRegions.initialIndex(emptyList(), "NL"))
    }

    @Test fun everyRegionHasItsOwnColours() {
        val looks = SportRegions.cards(null, null).map { it.look }
        assertEquals(looks.size, looks.toSet().size)
    }
}
