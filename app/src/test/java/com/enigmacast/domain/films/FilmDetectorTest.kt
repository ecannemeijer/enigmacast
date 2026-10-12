package com.enigmacast.domain.films

import com.enigmacast.domain.films.FilmTestData.event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmDetectorTest {
    @Test
    fun movieGenreAndLengthIsAFilm() {
        assertTrue(FilmDetector.isFilm(event("Inception", 150, genreId = 0x10)))
        assertTrue(FilmDetector.isFilm(event("Inception", 150, genre = "Movie/Drama")))
    }

    @Test
    fun shortProgrammesAreNotFilms() {
        assertFalse(FilmDetector.isFilm(event("Film: Shorty", 45, genreId = 0x10)))
    }

    @Test
    fun newsSportAndSeriesAreNotFilms() {
        assertFalse(FilmDetector.isFilm(event("NOS Journaal", 90)))
        assertFalse(FilmDetector.isFilm(event("Studio Sport", 120)))
        assertFalse(FilmDetector.isFilm(event("Grey's Anatomy S12E04", 90, genreId = 0x10)))
        assertFalse(FilmDetector.isFilm(event("Talkshow", 100, genreId = 0x30)))
    }

    @Test
    fun prefixOrSoftCluesWithoutGenre() {
        assertTrue(FilmDetector.isFilm(event("Film: The Matrix", 150)))
        assertTrue(FilmDetector.isFilm(event("Heat (1995)", 180)))
        assertTrue(FilmDetector.isFilm(event("Heat", 120, short = "Amerikaanse actiefilm uit 1995")))
        assertFalse(FilmDetector.isFilm(event("Boer zoekt vrouw", 70)))
    }

    @Test
    fun cleansTitles() {
        assertEquals("The Matrix", FilmDetector.cleanTitle("Film: The Matrix (1999)"))
        assertEquals("Heat", FilmDetector.cleanTitle("Speelfilm - Heat (HD)"))
        assertEquals("Die Hard", FilmDetector.cleanTitle("Spielfilm: Die Hard [1988]"))
        assertEquals("Titanic", FilmDetector.cleanTitle("Titanic 1997"))
        assertEquals("Ocean's Eleven", FilmDetector.cleanTitle("Movie | Ocean's Eleven"))
        assertEquals("2012", FilmDetector.cleanTitle("2012"))
    }

    @Test
    fun yearFromTitleOrDescription() {
        assertEquals(1999, FilmDetector.yearOf(event("The Matrix (1999)")))
        assertEquals(1995, FilmDetector.yearOf(event("Heat", short = "Amerikaanse film uit 1995 van Michael Mann.")))
        assertNull(FilmDetector.yearOf(event("Heat", short = "Een film over een overval.")))
    }

    @Test
    fun normalizeDropsAccentsArticlesAndPunctuation() {
        assertEquals("amelie", FilmDetector.normalize("Amélie!"))
        assertEquals("matrix", FilmDetector.normalize("The Matrix"))
        assertEquals("fast and furious", FilmDetector.normalize("Fast & Furious"))
    }

    @Test
    fun candidateCarriesCleanTitleAndYear() {
        val candidate = FilmDetector.candidate(event("Film: The Matrix (1999)", 160), "Veronica")!!
        assertEquals("The Matrix", candidate.searchTitle)
        assertEquals(1999, candidate.year)
        assertEquals("Veronica", candidate.channelName)
    }
}
