package com.enigmacast.domain.films

import kotlin.math.ln

/** Full TMDB details used by the info dialog. Everything optional except the id. */
@kotlinx.serialization.Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    val originalTitle: String = "",
    val year: Int? = null,
    val overview: String = "",
    val tagline: String = "",
    val runtimeMin: Int? = null,
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val popularity: Double = 0.0,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val genres: List<String> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val certification: String = "",
    val directors: List<String> = emptyList(),
    val cast: List<TmdbCastMember> = emptyList(),
    /** TV series (TMDB /tv): [id] is then negative, see SeriesIds. */
    val isSeries: Boolean = false,
    val seasons: Int? = null,
    val episodes: Int? = null,
)

@kotlinx.serialization.Serializable
data class TmdbCastMember(val name: String, val character: String, val profilePath: String?)

/** A guide film with its TMDB match. */
data class HomeFilm(
    val candidate: FilmCandidate,
    val movie: TmdbMovie,
) {
    val event get() = candidate.event
    val key: String get() = candidate.event.programmeKey
    val displayTitle: String get() = movie.title.ifBlank { candidate.searchTitle }
}

/** A horizontal row on the home page. [titleKey] maps to a string resource in the UI. */
data class HomeRow(val kind: RowKind, val films: List<HomeFilm>, val genreId: Int = 0)

enum class RowKind { NOW, TONIGHT, TOP_RATED, GENRE, LATER }

/** Orders today's films and builds the hero and the rows. */
object FilmRanking {
    const val PRIME_TIME_HOUR = 20
    const val MIN_GENRE_ROW = 3

    /** TMDB genre ids shown as their own row, in this order. */
    val GENRE_ROWS = listOf(28, 35, 18, 10751, 53, 878, 27, 16, 10749, 80)

    /**
     * Quality: a Bayesian average of vote_average (prior 6.5 with 150 votes) so a 9.0 with three
     * votes does not beat a 8.1 with ten thousand, plus a little for popularity.
     */
    fun quality(movie: TmdbMovie): Double {
        val prior = 6.5
        val weight = 150.0
        val votes = movie.voteCount.coerceAtLeast(0).toDouble()
        val bayes = (movie.voteAverage * votes + prior * weight) / (votes + weight)
        return bayes + 0.15 * ln(1.0 + movie.popularity.coerceAtLeast(0.0))
    }

    /** Films that have not ended, best first; ones airing now or soon break near-ties. */
    fun ranked(films: List<HomeFilm>, nowSec: Long): List<HomeFilm> = films
        .filter { it.event.endEpochSec > nowSec }
        .distinctBy { it.movie.id to it.event.beginEpochSec }
        .sortedWith(compareByDescending<HomeFilm> { quality(it.movie) + timeBonus(it, nowSec) }.thenBy { it.event.beginEpochSec })

    private fun timeBonus(film: HomeFilm, nowSec: Long): Double {
        val event = film.event
        return when {
            event.isAiring(nowSec) -> if (event.progress(nowSec) < 0.35f) 0.3 else 0.05
            event.beginEpochSec - nowSec <= 3 * 3600 -> 0.2
            else -> 0.0
        }
    }

    /** The top film still to air or airing now (and not almost over). */
    fun hero(films: List<HomeFilm>, nowSec: Long): HomeFilm? =
        ranked(films, nowSec).firstOrNull { !it.event.isAiring(nowSec) || it.event.progress(nowSec) < 0.6f }
            ?: ranked(films, nowSec).firstOrNull()

    /** [primeTimeStartSec] is today 20:00 local. Rows with nothing in them are left out. */
    fun rows(films: List<HomeFilm>, nowSec: Long, primeTimeStartSec: Long): List<HomeRow> {
        val ranked = ranked(films, nowSec)
        if (ranked.isEmpty()) return emptyList()
        val rows = mutableListOf<HomeRow>()
        val now = ranked.filter { it.event.isAiring(nowSec) }.sortedBy { it.event.beginEpochSec }
        if (now.isNotEmpty()) rows += HomeRow(RowKind.NOW, now)
        val tonight = ranked.filter { it.event.beginEpochSec >= primeTimeStartSec && !it.event.isAiring(nowSec) }
            .sortedBy { it.event.beginEpochSec }
        if (tonight.isNotEmpty()) rows += HomeRow(RowKind.TONIGHT, tonight)
        rows += HomeRow(RowKind.TOP_RATED, ranked.take(20))
        for (genre in GENRE_ROWS) {
            val list = ranked.filter { genre in it.movie.genreIds }
            if (list.size >= MIN_GENRE_ROW) rows += HomeRow(RowKind.GENRE, list, genre)
        }
        val later = ranked.filter { it.event.beginEpochSec in (nowSec + 1) until primeTimeStartSec }.sortedBy { it.event.beginEpochSec }
        if (later.isNotEmpty()) rows += HomeRow(RowKind.LATER, later)
        return rows
    }
}
