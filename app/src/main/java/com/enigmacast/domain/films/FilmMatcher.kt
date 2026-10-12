package com.enigmacast.domain.films

import kotlin.math.abs
import kotlin.math.max

/** One result of TMDB `/search/movie`. */
data class TmdbSearchHit(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val voteAverage: Double,
    val voteCount: Int,
    val popularity: Double,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String,
    val genreIds: List<Int>,
)

/** Picks the TMDB result that is really the film in the guide, or none. */
object FilmMatcher {
    const val MIN_CONFIDENCE = 0.62

    /** 0..1 similarity of two titles after [FilmDetector.normalize]: Levenshtein ratio, with a bonus for containment. */
    fun titleSimilarity(a: String, b: String): Double {
        val x = FilmDetector.normalize(a)
        val y = FilmDetector.normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val ratio = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
        val contained = (x.length >= 4 && y.startsWith(x)) || (y.length >= 4 && x.startsWith(y))
        return if (contained) max(ratio, 0.8) else ratio
    }

    /**
     * Confidence that [hit] is [candidate]: the best title similarity (local or original title),
     * nudged by the year (±1 is fine) and the runtime when [runtimeMin] is known.
     */
    fun confidence(candidate: FilmCandidate, hit: TmdbSearchHit, runtimeMin: Int? = null): Double {
        var score = max(titleSimilarity(candidate.searchTitle, hit.title), titleSimilarity(candidate.searchTitle, hit.originalTitle))
        val year = candidate.year
        if (year != null && hit.year != null) {
            score += when (abs(year - hit.year)) {
                0, 1 -> 0.15
                2 -> 0.0
                else -> -0.45
            }
        }
        if (runtimeMin != null && runtimeMin > 0) {
            val epgMin = candidate.event.durationSec / 60
            // Broadcast slots include ads, so the slot may be up to ~45% longer than the film.
            // A slot clearly shorter than the film cannot be that film.
            if (epgMin + 15 < runtimeMin) return 0.0
            score += when {
                epgMin > runtimeMin * 1.6 + 15 -> -0.15
                else -> 0.05
            }
        }
        if (hit.voteCount < 5) score -= 0.1
        return score.coerceIn(0.0, 1.2)
    }

    /** The best hit at or above [MIN_CONFIDENCE]; ties go to the more voted film. */
    fun best(candidate: FilmCandidate, hits: List<TmdbSearchHit>): TmdbSearchHit? = hits
        .take(8)
        .map { it to confidence(candidate, it) }
        .filter { it.second >= MIN_CONFIDENCE }
        .maxWithOrNull(compareBy<Pair<TmdbSearchHit, Double>> { it.second }.thenBy { it.first.voteCount })
        ?.first

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
