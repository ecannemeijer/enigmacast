package com.enigmacast.domain.films

import com.enigmacast.data.tmdb.TmdbJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Turns guide film candidates into matched [HomeFilm]s: one search per distinct title, a
 * confidence check, then the details (runtime is checked again against the guide slot).
 */
class HomeFilmsBuilder(private val tmdb: TmdbApi) {

    suspend fun build(candidates: List<FilmCandidate>, country: String, language: String): List<HomeFilm> = coroutineScope {
        val byTitle = candidates.groupBy { FilmDetector.normalize(it.searchTitle) to it.year }
        val matched = byTitle.values.map { group ->
            async { match(group.first(), country, language)?.let { movie -> group.map { HomeFilm(it, movie) } } }
        }.awaitAll()
        matched.filterNotNull().flatten()
    }

    private val details = HashMap<Int, TmdbMovie?>()

    suspend fun match(candidate: FilmCandidate, country: String, language: String): TmdbMovie? {
        var hits = TmdbJson.parseSearch(tmdb.searchMovie(candidate.searchTitle, candidate.year, language, country) ?: return null)
        if (hits.isEmpty() && candidate.year != null) {
            // The guide's year is sometimes the broadcast or a remake year; try without it.
            hits = TmdbJson.parseSearch(tmdb.searchMovie(candidate.searchTitle, null, language, country).orEmpty())
        }
        val hit = FilmMatcher.best(candidate, hits) ?: return null
        val movie = movie(hit.id, country, language) ?: fromHit(hit)
        if (FilmMatcher.confidence(candidate, hit, movie.runtimeMin) < FilmMatcher.MIN_CONFIDENCE) return null
        return movie
    }

    private suspend fun movie(id: Int, country: String, language: String): TmdbMovie? {
        synchronized(details) { if (details.containsKey(id)) return details[id] }
        val parsed = tmdb.movie(id, language)?.let { TmdbJson.parseMovie(it, country, language) }
        synchronized(details) { details[id] = parsed }
        return parsed
    }

    private fun fromHit(hit: TmdbSearchHit) = TmdbMovie(
        id = hit.id,
        title = hit.title,
        originalTitle = hit.originalTitle,
        year = hit.year,
        overview = hit.overview,
        voteAverage = hit.voteAverage,
        voteCount = hit.voteCount,
        popularity = hit.popularity,
        posterPath = hit.posterPath,
        backdropPath = hit.backdropPath,
        genreIds = hit.genreIds,
    )
}
