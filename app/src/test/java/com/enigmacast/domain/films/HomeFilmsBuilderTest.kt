package com.enigmacast.domain.films

import com.enigmacast.domain.films.FilmTestData.event
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFilmsBuilderTest {
    private val tmdb = FakeTmdb(
        searches = mapOf("The Matrix" to FilmTestData.fixture("search_matrix.json")),
        movies = mapOf(603 to FilmTestData.fixture("movie_603.json")),
    )

    @Test
    fun matchesAndFillsDetails() = runTest {
        val candidate = FilmDetector.candidate(event("Film: The Matrix (1999)", 155), "Veronica")!!
        val films = HomeFilmsBuilder(tmdb).build(listOf(candidate), "NL", "nl-NL")
        assertEquals(1, films.size)
        val movie = films.single().movie
        assertEquals(603, movie.id)
        assertEquals(136, movie.runtimeMin)
        assertEquals("12", movie.certification)
        assertTrue(movie.cast.isNotEmpty())
    }

    @Test
    fun searchesEachTitleOnce() = runTest {
        val a = FilmDetector.candidate(event("The Matrix (1999)", 150, begin = 1000), "A")!!
        val b = FilmDetector.candidate(event("The Matrix (1999)", 150, begin = 9000, reference = "1:0:1:2:3:4:5:0:0:0:"), "B")!!
        val films = HomeFilmsBuilder(tmdb).build(listOf(a, b), "NL", "nl-NL")
        assertEquals(2, films.size)
        assertEquals(1, tmdb.searchCalls.size)
    }

    @Test
    fun slotShorterThanTheFilmIsRejected() = runTest {
        // A 75-minute slot cannot hold the 136-minute Matrix.
        val candidate = FilmDetector.candidate(event("Film: The Matrix (1999)", 75), "Veronica")!!
        assertTrue(HomeFilmsBuilder(tmdb).build(listOf(candidate), "NL", "nl-NL").isEmpty())
    }

    @Test
    fun unknownTitleGivesNothing() = runTest {
        val candidate = FilmDetector.candidate(event("Film: Onbekend", 120), "X")!!
        assertTrue(HomeFilmsBuilder(tmdb).build(listOf(candidate), "NL", "nl-NL").isEmpty())
    }
}
