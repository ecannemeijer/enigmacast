package com.enigmacast.domain.films

import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.films.FilmTestData.event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmMatcherTest {
    private fun hit(id: Int, title: String, year: Int?, votes: Int = 1000, original: String = title) =
        TmdbSearchHit(id, title, original, year, 7.0, votes, 10.0, null, null, "", emptyList())

    private fun candidate(title: String, year: Int? = null, minutes: Long = 140) =
        FilmCandidate(event(title, minutes), "Ch", title, year)

    @Test
    fun similarTitlesScoreHigh() {
        assertEquals(1.0, FilmMatcher.titleSimilarity("The Matrix", "Matrix"), 0.001)
        assertTrue(FilmMatcher.titleSimilarity("Ocean's Eleven", "Oceans Eleven") > 0.9)
        assertTrue(FilmMatcher.titleSimilarity("Heat", "Frozen") < 0.4)
    }

    @Test
    fun yearOffByOneIsFineButFarOffIsNot() {
        val c = candidate("Heat", 1995)
        assertTrue(FilmMatcher.confidence(c, hit(1, "Heat", 1996)) >= FilmMatcher.MIN_CONFIDENCE)
        assertTrue(FilmMatcher.confidence(c, hit(2, "Heat", 1986)) < FilmMatcher.MIN_CONFIDENCE)
    }

    @Test
    fun runtimeLongerThanSlotLowersConfidence() {
        val c = candidate("Heat", null, minutes = 100)
        val h = hit(1, "Heat", 1995)
        assertTrue(FilmMatcher.confidence(c, h, runtimeMin = 170) < FilmMatcher.confidence(c, h, runtimeMin = 95))
    }

    @Test
    fun picksTheRightRemake() {
        val c = candidate("Dune", 2021)
        val best = FilmMatcher.best(c, listOf(hit(1, "Dune", 1984), hit(2, "Dune", 2021), hit(3, "Dune: Part Two", 2024)))
        assertEquals(2, best?.id)
    }

    @Test
    fun rejectsUnrelatedResults() {
        assertNull(FilmMatcher.best(candidate("Boer zoekt vrouw"), listOf(hit(1, "Farmer Wants a Wife", 2001))))
    }

    @Test
    fun matchesOriginalTitleToo() {
        val c = candidate("Das Boot", 1981)
        val best = FilmMatcher.best(c, listOf(hit(7, "The Boat", 1981, original = "Das Boot")))
        assertEquals(7, best?.id)
    }

    @Test
    fun fixtureSearchMatchesTheMatrix() {
        val hits = TmdbJson.parseSearch(FilmTestData.fixture("search_matrix.json"))
        assertEquals(603, FilmMatcher.best(candidate("The Matrix", 1999), hits)?.id)
    }
}
