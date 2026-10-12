package com.enigmacast.domain.films

import com.enigmacast.domain.epg.GuideEvent

internal object FilmTestData {
    fun event(
        title: String,
        minutes: Long = 120,
        begin: Long = 1_000_000L,
        genreId: Int = -1,
        genre: String = "",
        short: String = "",
        plot: String = "",
        reference: String = "1:0:19:1B:1:1:C00000:0:0:0:",
    ) = GuideEvent(1, reference, title, short, plot, begin, minutes * 60, genreId, genre)

    fun fixture(name: String): String =
        requireNotNull(FilmTestData::class.java.classLoader!!.getResource("tmdb/$name")) { name }.readText()

    fun movie(id: Int, vote: Double, count: Int, popularity: Double = 10.0, genres: List<Int> = emptyList()) =
        TmdbMovie(id = id, title = "Film $id", voteAverage = vote, voteCount = count, popularity = popularity, genreIds = genres)

    fun film(id: Int, vote: Double, count: Int, begin: Long, minutes: Long = 120, genres: List<Int> = emptyList()): HomeFilm {
        val event = event("Film $id", minutes, begin, reference = "1:0:19:$id:1:1:C00000:0:0:0:")
        return HomeFilm(FilmCandidate(event, "Ch $id", "Film $id", null), movie(id, vote, count, genres = genres))
    }
}

/** TMDB without a network: answers from fixtures or a map. */
internal class FakeTmdb(
    private val searches: Map<String, String> = emptyMap(),
    private val movies: Map<Int, String> = emptyMap(),
) : TmdbApi {
    val searchCalls = mutableListOf<Pair<String, Int?>>()

    override suspend fun searchMovie(query: String, year: Int?, language: String, region: String): String? {
        synchronized(searchCalls) { searchCalls += query to year }
        return searches[query] ?: """{"results":[]}"""
    }

    override suspend fun movie(id: Int, language: String): String? = movies[id]
}
