package com.enigmacast.domain.films

import com.enigmacast.domain.films.FilmTestData.film
import com.enigmacast.domain.films.FilmTestData.movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmRankingTest {
    private val now = 1_000_000L

    @Test
    fun fewVotesDoNotBeatManyVotes() {
        assertTrue(FilmRanking.quality(movie(1, 8.1, 20_000)) > FilmRanking.quality(movie(2, 9.5, 3)))
    }

    @Test
    fun endedFilmsAreDroppedAndBestComesFirst() {
        val ended = film(1, 9.0, 9000, begin = now - 3 * 3600, minutes = 120)
        val good = film(2, 8.2, 9000, begin = now + 7200)
        val weak = film(3, 5.1, 9000, begin = now + 3600)
        val ranked = FilmRanking.ranked(listOf(ended, good, weak), now)
        assertEquals(listOf(2, 3), ranked.map { it.movie.id })
    }

    @Test
    fun heroSkipsAFilmThatIsAlmostOver() {
        val almostOver = film(1, 9.0, 9000, begin = now - 100 * 60, minutes = 120)
        val upcoming = film(2, 7.5, 9000, begin = now + 1800)
        assertEquals(2, FilmRanking.hero(listOf(almostOver, upcoming), now)?.movie?.id)
    }

    @Test
    fun rowsSplitNowTonightTopAndGenres() {
        val prime = now + 4 * 3600
        val films = listOf(
            film(1, 7.0, 500, begin = now - 600, genres = listOf(28)),
            film(2, 7.5, 500, begin = prime + 600, genres = listOf(28)),
            film(3, 6.5, 500, begin = prime + 1200, genres = listOf(28)),
            film(4, 8.0, 500, begin = now + 3600, genres = listOf(35)),
        )
        val rows = FilmRanking.rows(films, now, prime)
        val kinds = rows.map { it.kind }
        assertEquals(listOf(RowKind.NOW, RowKind.TONIGHT, RowKind.TOP_RATED, RowKind.GENRE, RowKind.LATER), kinds)
        assertEquals(listOf(1), rows[0].films.map { it.movie.id })
        assertEquals(listOf(2, 3), rows[1].films.map { it.movie.id })
        assertEquals(28, rows[3].genreId)
        assertEquals(listOf(4), rows[4].films.map { it.movie.id })
    }

    @Test
    fun noFilmsNoRows() {
        assertTrue(FilmRanking.rows(emptyList(), now, now).isEmpty())
    }
}
