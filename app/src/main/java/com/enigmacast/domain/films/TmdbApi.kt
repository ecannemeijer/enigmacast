package com.enigmacast.domain.films

/** The two TMDB calls the home page needs. Answers are raw JSON; tests use a fake. */
interface TmdbApi {
    suspend fun searchMovie(query: String, year: Int?, language: String, region: String): String?
    suspend fun movie(id: Int, language: String): String?
    suspend fun searchTv(query: String, language: String): String? = null
    suspend fun tv(id: Int, language: String): String? = null
}
