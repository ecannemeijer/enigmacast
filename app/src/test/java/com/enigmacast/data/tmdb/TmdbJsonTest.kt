package com.enigmacast.data.tmdb

import com.enigmacast.domain.films.FilmTestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TmdbJsonTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesSearch() {
        val hits = TmdbJson.parseSearch(FilmTestData.fixture("search_matrix.json"))
        val matrix = hits.first()
        assertEquals(603, matrix.id)
        assertEquals("The Matrix", matrix.title)
        assertEquals(1999, matrix.year)
        assertTrue(matrix.voteCount > 1000)
        assertEquals(listOf(28, 878), matrix.genreIds)
    }

    @Test
    fun parsesDetailsWithCreditsCertificationAndLogo() {
        val movie = TmdbJson.parseMovie(FilmTestData.fixture("movie_603.json"), "NL", "nl-NL")!!
        assertEquals(136, movie.runtimeMin)
        assertEquals(listOf("Actie", "Sciencefiction"), movie.genres)
        assertEquals("12", movie.certification)
        assertEquals("/iWxxHjuJ1vCLQcg3iiOMucvbrF1.png", movie.logoPath)
        assertTrue(movie.directors.isNotEmpty())
        assertEquals("Keanu Reeves", movie.cast.first().name)
    }

    @Test
    fun logoPrefersLanguageThenEnglishThenNoLanguage() {
        fun body(vararg logos: Pair<String?, String>) = "{\"id\":1,\"title\":\"T\",\"images\":{\"logos\":[" +
            logos.joinToString(",") { (lang, path) -> "{\"iso_639_1\":" + (lang?.let { "\"" + it + "\"" } ?: "null") + ",\"file_path\":\"" + path + "\"}" } + "]}}"
        assertEquals("/nl.png", TmdbJson.parseMovie(body("de" to "/de.png", "en" to "/en.png", "nl" to "/nl.png", null to "/x.png"), "NL", "nl-NL")!!.logoPath)
        assertEquals("/en.png", TmdbJson.parseMovie(body("de" to "/de.png", null to "/x.png", "en" to "/en.png"), "NL", "nl-NL")!!.logoPath)
        assertEquals("/x.png", TmdbJson.parseMovie(body("de" to "/de.png", null to "/x.png"), "NL", "nl-NL")!!.logoPath)
        // SVG logos are skipped (not drawable here).
        assertEquals("/de.png", TmdbJson.parseMovie(body("nl" to "/nl.svg", "de" to "/de.png"), "NL", "nl-NL")!!.logoPath)
    }

        @Test
    fun certificationFallsBackToUs() {
        val movie = TmdbJson.parseMovie(FilmTestData.fixture("movie_603.json"), "FR", "fr-FR")!!
        assertEquals("R", movie.certification)
    }

    @Test
    fun brokenOrEmptyAnswersAreSafe() {
        assertTrue(TmdbJson.parseSearch("not json").isEmpty())
        assertTrue(TmdbJson.parseSearch("""{"results":[{"title":"no id"}]}""").isEmpty())
        assertNull(TmdbJson.parseMovie("{}", "NL", "nl"))
        val minimal = TmdbJson.parseMovie("""{"id":5,"title":"X","runtime":0,"poster_path":null}""", "NL", "nl")!!
        assertNull(minimal.runtimeMin)
        assertNull(minimal.posterPath)
    }

    @Test
    fun imageUrls() {
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", TmdbJson.image("/a.jpg", "w342"))
        assertNull(TmdbJson.image(null, "w342"))
    }

    @Test
    fun diskCacheExpiresAfterTtl() {
        var now = 1_000_000L
        val cache = TmdbDiskCache(temp.newFolder(), ttlMs = 1000, clockMs = { now })
        cache.write("k", "body")
        assertEquals("body", cache.read("k"))
        now += 5000
        assertNull(cache.read("k"))
    }

    @Test
    fun rateLimiterSpacesStarts() = runTest {
        val limiter = RateLimiter(20) { testScheduler.currentTime }
        repeat(3) { limiter.acquire() }
        assertEquals(100L, testScheduler.currentTime)
    }
}
