package com.enigmacast.domain.films

import com.enigmacast.data.tmdb.TmdbJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Where earlier TMDB answers are kept (Room in the app, a map in tests). */
interface FilmCache {
    /** null = not cached; [CachedMatch.tmdbId] null = cached "no film". */
    suspend fun match(key: String): CachedMatch?
    suspend fun putMatch(key: String, tmdbId: Int?, confidence: Double)
    suspend fun movie(id: Int, language: String): TmdbMovie?
    suspend fun putMovie(movie: TmdbMovie, language: String)

    data class CachedMatch(val tmdbId: Int?)

    object None : FilmCache {
        override suspend fun match(key: String): CachedMatch? = null
        override suspend fun putMatch(key: String, tmdbId: Int?, confidence: Double) = Unit
        override suspend fun movie(id: Int, language: String): TmdbMovie? = null
        override suspend fun putMovie(movie: TmdbMovie, language: String) = Unit
    }
}

/**
 * Turns guide film candidates into matched [HomeFilm]s: one search per distinct title (or a
 * cached answer), a confidence check, then the details; each broadcast is checked against
 * the film's runtime, because a slot shorter than the film cannot be that film.
 */
class HomeFilmsBuilder(private val tmdb: TmdbApi, private val cache: FilmCache = FilmCache.None) {

    suspend fun build(
        candidates: List<FilmCandidate>,
        country: String,
        language: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<HomeFilm> = coroutineScope {
        val byTitle = candidates.groupBy { FilmDetector.normalize(it.searchTitle) to it.year }
        val total = byTitle.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        val matched = byTitle.values.map { group ->
            async {
                val movie = try {
                    resolve(group.first(), country, language)
                } finally {
                    onProgress(done.incrementAndGet(), total)
                }
                if (movie == null) return@async emptyList()
                group.filter { fits(it, movie) }.map { HomeFilm(it, movie) }
            }
        }.awaitAll()
        matched.flatten()
    }

    /** The film for this title, from the cache or TMDB. */
    suspend fun resolve(candidate: FilmCandidate, country: String, language: String): TmdbMovie? {
        val key = matchKey(candidate, language, country)
        cache.match(key)?.let { cached ->
            val id = cached.tmdbId ?: return null
            return movie(id, country, language)
        }
        val body = tmdb.searchMovie(candidate.searchTitle, candidate.year, language, country) ?: return null
        var hits = TmdbJson.parseSearch(body)
        if (hits.isEmpty() && candidate.year != null) {
            // The guide's year is sometimes the broadcast or a remake year; try without it.
            hits = TmdbJson.parseSearch(tmdb.searchMovie(candidate.searchTitle, null, language, country).orEmpty())
        }
        val hit = FilmMatcher.best(candidate, hits)
        if (hit == null) {
            cache.putMatch(key, null, 0.0)
            return null
        }
        val movie = movie(hit.id, country, language) ?: fromHit(hit)
        cache.putMatch(key, hit.id, FilmMatcher.confidence(candidate, hit))
        return movie
    }

    private fun fits(candidate: FilmCandidate, movie: TmdbMovie): Boolean {
        val runtime = movie.runtimeMin ?: return true
        return candidate.event.durationSec / 60 + 15 >= runtime
    }

    private suspend fun movie(id: Int, country: String, language: String): TmdbMovie? {
        cache.movie(id, language)?.let { return it }
        val parsed = tmdb.movie(id, language)?.let { TmdbJson.parseMovie(it, country, language) } ?: return null
        cache.putMovie(parsed, language)
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

    companion object {
        fun matchKey(candidate: FilmCandidate, language: String, country: String): String =
            "${FilmDetector.normalize(candidate.searchTitle)}|${candidate.year ?: ""}|$language|$country"
    }
}
