package com.enigmacast.live

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.tmdb.TmdbHttp
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.films.CountryResolver
import com.enigmacast.domain.films.FilmCandidate
import com.enigmacast.domain.films.FilmDetector
import com.enigmacast.domain.films.HomeFilmsBuilder
import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.recording.Recording
import com.enigmacast.sync.RoomFilmCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB artwork for recordings: title (and a year in the title) searched once, the answer
 * cached in Room by the same match cache the film home uses (also "no match"). Without a key
 * or a match the card shows a placeholder.
 */
@Singleton
class RecordingArtwork @Inject constructor(
    private val dao: CacheDao,
    private val tmdb: TmdbHttp,
    private val country: CountryDetector,
) {
    private val gate = Mutex()
    private val _movies = MutableStateFlow<Map<String, TmdbMovie?>>(emptyMap())
    /** By [Recording.id]; a present null means "looked, nothing found". */
    val movies: StateFlow<Map<String, TmdbMovie?>> = _movies.asStateFlow()

    suspend fun resolve(items: List<Recording>) = gate.withLock {
        if (tmdb.apiKey().isBlank()) return@withLock
        val todo = items.filter { it.id !in _movies.value }
        if (todo.isEmpty()) return@withLock
        val countryCode = runCatching { country.country() }.getOrDefault("NL")
        val language = CountryResolver.tmdbLanguage(country.appLanguage(), countryCode)
        val builder = HomeFilmsBuilder(tmdb, RoomFilmCache(dao, CACHE_MS))
        for (item in todo) {
            val candidate = candidateOf(item)
            val movie = candidate?.let { runCatching { builder.resolve(it, countryCode, language) }.getOrNull() }
            _movies.update { it + (item.id to movie) }
        }
    }

    companion object {
        const val CACHE_MS = 30L * 24 * 3600 * 1000

        fun candidateOf(item: Recording): FilmCandidate? {
            val title = FilmDetector.cleanTitle(item.title)
            if (title.length < 2) return null
            val event = GuideEvent(
                eventId = item.eventId,
                reference = item.reference,
                title = item.title,
                shortDescription = "",
                plot = "",
                beginEpochSec = item.beginEpochSec,
                durationSec = (item.endEpochSec - item.beginEpochSec).coerceAtLeast(0),
            )
            return FilmCandidate(event, item.channelName, title, FilmDetector.yearOf(event))
        }
    }
}
