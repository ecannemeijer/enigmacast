package com.enigmacast.data.xtream

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.tmdb.TmdbHttp
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.films.CountryResolver
import com.enigmacast.domain.films.FilmCandidate
import com.enigmacast.domain.films.FilmDetector
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.HomeFilmsBuilder
import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.series.HomeSeriesBuilder
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.live.CountryDetector
import com.enigmacast.sync.RoomFilmCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * TMDB artwork for IPTV films and series, matched by title + year with the same builders and
 * Room match cache as the Enigma2 Movies / TV Series pages (also "no match", 30 days). Only the
 * cards on screen are asked for. Without a key or a match the card keeps the playlist's own
 * cover and name ([IptvVod.fallback]).
 */
@Singleton
class IptvArtwork @Inject constructor(
    private val dao: CacheDao,
    private val tmdb: TmdbHttp,
    private val country: CountryDetector,
) {
    private val _movies = MutableStateFlow<Map<String, TmdbMovie?>>(emptyMap())
    /** By [IptvVod.key]; a present null means "looked, nothing found". */
    val movies: StateFlow<Map<String, TmdbMovie?>> = _movies.asStateFlow()
    private val gate = Semaphore(3)

    suspend fun resolve(items: List<XtreamItem>) = withContext(Dispatchers.IO) {
        if (tmdb.apiKey().isBlank()) return@withContext
        val todo = items.filter { it.section != XtreamSection.LIVE && IptvVod.key(it) !in _movies.value }
        if (todo.isEmpty()) return@withContext
        val countryCode = runCatching { country.country() }.getOrDefault("NL")
        val language = CountryResolver.tmdbLanguage(country.appLanguage(), countryCode)
        val cache = RoomFilmCache(dao, CACHE_MS)
        val films = HomeFilmsBuilder(tmdb, cache)
        val shows = HomeSeriesBuilder(tmdb, cache)
        coroutineScope {
            todo.map { item ->
                async {
                    gate.withPermit {
                        val candidate = IptvVod.candidate(item) ?: return@withPermit
                        val movie = runCatching {
                            if (item.section == XtreamSection.SERIES) shows.resolve(candidate, countryCode, language)
                            else films.resolve(candidate, countryCode, language)
                        }.getOrNull()
                        // A cancelled batch (scrolled on) must not be remembered as "no match".
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        _movies.update { it + (IptvVod.key(item) to movie) }
                    }
                }
            }.awaitAll()
        }
    }

    companion object {
        const val CACHE_MS = 30L * 24 * 3600 * 1000
    }
}

/** IPTV films and series as the film pages' [HomeFilm]. */
object IptvVod {
    private val PREFIX = Regex("^\\s*(\\[[^]]{1,12}]|[A-Za-z]{2,4}\\s*[:|]|[A-Za-z]{2,3}\\s+-\\s)\\s*")

    fun key(item: XtreamItem) = "${item.section}/${item.categoryId}/${item.id}"

    /** "NL: The Matrix (1999)" → "The Matrix", 1999. */
    fun title(name: String): Pair<String, Int?> {
        val bare = name.replace(PREFIX, "").trim()
        val event = event(bare, 0)
        return FilmDetector.cleanTitle(bare).ifBlank { bare } to FilmDetector.yearOf(event)
    }

    private fun event(title: String, number: Int) = GuideEvent(
        eventId = -1, reference = "iptv:$number", title = title, shortDescription = "", plot = "",
        beginEpochSec = 0, durationSec = 0,
    )

    fun candidate(item: XtreamItem): FilmCandidate? {
        val (title, year) = title(item.name)
        if (title.length < 2) return null
        return FilmCandidate(event(item.name, item.id.hashCode()), "", title, year, item.icon)
    }

    /** The playlist's own data: name and cover. */
    fun fallback(item: XtreamItem): TmdbMovie {
        val (title, year) = title(item.name)
        return TmdbMovie(id = 0, title = title.ifBlank { item.name }, year = year, posterPath = item.icon.ifBlank { null }, isSeries = item.section == XtreamSection.SERIES)
    }

    /** TMDB first; the playlist's info fills what TMDB lacks, or stands in for it. */
    fun merge(item: XtreamItem, movie: TmdbMovie?, info: com.enigmacast.domain.xtream.XtreamVodInfo?): TmdbMovie? {
        if (info == null) return movie
        if (movie != null) {
            return movie.copy(
                overview = movie.overview.ifBlank { info.plot },
                backdropPath = movie.backdropPath ?: info.backdrop,
                posterPath = movie.posterPath ?: info.cover ?: item.icon.ifBlank { null },
                runtimeMin = movie.runtimeMin ?: info.runtimeMin,
            )
        }
        val base = fallback(item)
        return base.copy(
            overview = info.plot,
            voteAverage = info.rating,
            year = base.year ?: info.year,
            posterPath = info.cover ?: base.posterPath,
            backdropPath = info.backdrop,
            genres = info.genre.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() },
            runtimeMin = info.runtimeMin,
            directors = info.director.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun film(item: XtreamItem, movie: TmdbMovie?): HomeFilm {
        val base = candidate(item) ?: FilmCandidate(event(item.name, item.id.hashCode()), "", item.name, null, item.icon)
        val fallback = fallback(item)
        val shown = movie?.let { it.copy(posterPath = it.posterPath ?: fallback.posterPath) } ?: fallback
        return HomeFilm(base, shown)
    }
}
