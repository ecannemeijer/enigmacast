package com.enigmacast.data.db

import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.films.FilmCache
import com.enigmacast.domain.films.FilmDetector
import com.enigmacast.domain.films.FakeTmdb
import com.enigmacast.domain.films.FilmTestData
import com.enigmacast.domain.films.HomeFilmsBuilder
import com.enigmacast.domain.films.TmdbMovie
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheMappersTest {
    @Test
    fun movieSurvivesTheRoomPayload() {
        val movie = TmdbJson.parseMovie(FilmTestData.fixture("movie_603.json"), "NL", "nl-NL")!!
        val row = CacheMappers.movieRow(movie, "nl-NL", 123L)
        assertEquals("12", row.certification)
        assertEquals(movie, CacheMappers.movie(row))
    }

    @Test
    fun brokenPayloadFallsBackToColumns() {
        val row = TmdbMovieRow(5, "nl-NL", "X", "/p.jpg", null, null, 90, "", "", "{broken", 0)
        assertEquals(TmdbMovie(5, "X", posterPath = "/p.jpg"), CacheMappers.movie(row))
    }

    @Test
    fun epgRowRoundTrip() {
        val event = FilmTestData.event("Film: Heat", 170, genreId = 0x10, genre = "Movie")
        assertEquals(event, CacheMappers.event(CacheMappers.epgRow("rk", event)))
    }

    @Test
    fun homeFilmRowsBecomeFilmsAndSkipMissingMovies() {
        val film = FilmTestData.film(7, 7.0, 100, begin = 5000)
        val row = CacheMappers.homeFilmRow("rk", film, "2026-10-12", "nl-NL")
        val other = row.copy(serviceKey = "x", tmdbId = 99)
        val films = CacheMappers.homeFilms(listOf(row, other), mapOf(7 to film.movie))
        assertEquals(1, films.size)
        assertEquals(film.event.beginEpochSec, films.single().event.beginEpochSec)
        assertEquals("Ch 7", films.single().candidate.channelName)
    }

    @Test
    fun receiverKeyPrefersId() {
        assertEquals("abc", CacheMappers.receiverKey("abc", "192.168.1.209", 80))
        assertEquals("192.168.1.209:80", CacheMappers.receiverKey("", "192.168.1.209", 80))
    }

    /** An in-memory stand-in for the Room cache. */
    private class MapCache : FilmCache {
        val matches = HashMap<String, FilmCache.CachedMatch>()
        val movies = HashMap<Pair<Int, String>, TmdbMovie>()
        override suspend fun match(key: String) = matches[key]
        override suspend fun putMatch(key: String, tmdbId: Int?, confidence: Double) { matches[key] = FilmCache.CachedMatch(tmdbId) }
        override suspend fun movie(id: Int, language: String) = movies[id to language]
        override suspend fun putMovie(movie: TmdbMovie, language: String) { movies[movie.id to language] = movie }
    }

    @Test
    fun secondBuildUsesTheCacheOnly() = runTest {
        val cache = MapCache()
        val candidate = FilmDetector.candidate(FilmTestData.event("Film: The Matrix (1999)", 155), "Veronica")!!
        val first = FakeTmdb(mapOf("The Matrix" to FilmTestData.fixture("search_matrix.json")), mapOf(603 to FilmTestData.fixture("movie_603.json")))
        assertEquals(1, HomeFilmsBuilder(first, cache).build(listOf(candidate), "NL", "nl-NL").size)
        val offline = FakeTmdb()
        val again = HomeFilmsBuilder(offline, cache).build(listOf(candidate), "NL", "nl-NL")
        assertEquals(603, again.single().movie.id)
        assertTrue(offline.searchCalls.isEmpty())
    }

    @Test
    fun noMatchIsCachedToo() = runTest {
        val cache = MapCache()
        val candidate = FilmDetector.candidate(FilmTestData.event("Film: Onbekend", 120), "X")!!
        val tmdb = FakeTmdb()
        HomeFilmsBuilder(tmdb, cache).build(listOf(candidate), "NL", "nl-NL")
        HomeFilmsBuilder(tmdb, cache).build(listOf(candidate), "NL", "nl-NL")
        assertEquals(1, tmdb.searchCalls.size)
    }
}
