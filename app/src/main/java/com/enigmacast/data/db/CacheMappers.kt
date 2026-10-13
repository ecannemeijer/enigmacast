package com.enigmacast.data.db

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.films.FilmCandidate
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.tuner.NowEvents
import kotlinx.serialization.json.Json

/** Conversions between Room rows and the app's models. Pure, so they are unit tested. */
object CacheMappers {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun receiverKey(id: String, host: String, port: Int): String = id.ifBlank { "$host:$port" }

    fun epgRow(receiverKey: String, event: GuideEvent) = EpgRow(
        receiverKey = receiverKey,
        serviceKey = NowEvents.key(event.reference),
        reference = event.reference,
        beginSec = event.beginEpochSec,
        endSec = event.endEpochSec,
        eventId = event.eventId,
        title = event.title,
        shortDescription = event.shortDescription,
        longDescription = event.plot,
        genreId = event.genreId,
        genre = event.genre,
    )

    /** An XMLTV programme as a guide row; the event id is derived from service and start (XMLTV has none). */
    fun iptvGuideRow(row: IptvEpgRow) = EpgRow(
        receiverKey = row.receiverKey,
        serviceKey = row.serviceKey,
        reference = row.reference,
        beginSec = row.beginSec,
        endSec = row.endSec,
        eventId = ((row.serviceKey.hashCode() * 31 + row.beginSec.toInt()) and 0x7FFFFFFF),
        title = row.title,
        shortDescription = "",
        longDescription = row.description,
        genreId = 0,
        genre = "",
    )

    fun event(row: EpgRow) = GuideEvent(
        eventId = row.eventId,
        reference = row.reference,
        title = row.title,
        shortDescription = row.shortDescription,
        plot = row.longDescription,
        beginEpochSec = row.beginSec,
        durationSec = row.endSec - row.beginSec,
        genreId = row.genreId,
        genre = row.genre,
    )

    fun movieRow(movie: TmdbMovie, language: String, cachedUntilMs: Long) = TmdbMovieRow(
        tmdbId = movie.id,
        language = language,
        title = movie.title,
        posterPath = movie.posterPath,
        backdropPath = movie.backdropPath,
        logoPath = movie.logoPath,
        runtimeMin = movie.runtimeMin,
        certification = movie.certification,
        tagline = movie.tagline,
        payload = json.encodeToString(TmdbMovie.serializer(), movie),
        cachedUntilMs = cachedUntilMs,
    )

    fun movie(row: TmdbMovieRow): TmdbMovie =
        runCatching { json.decodeFromString(TmdbMovie.serializer(), row.payload) }.getOrElse {
            TmdbMovie(row.tmdbId, row.title, posterPath = row.posterPath, backdropPath = row.backdropPath, logoPath = row.logoPath)
        }

    fun homeFilmRow(receiverKey: String, film: HomeFilm, day: String, language: String, kind: String = HomeKinds.FILM): HomeFilmRow {
        val event = film.event
        return HomeFilmRow(
            receiverKey = receiverKey,
            serviceKey = NowEvents.key(event.reference),
            reference = event.reference,
            beginSec = event.beginEpochSec,
            endSec = event.endEpochSec,
            day = day,
            eventId = event.eventId,
            epgTitle = event.title,
            shortDescription = event.shortDescription,
            longDescription = event.plot,
            channelName = film.candidate.channelName,
            channelPicon = film.candidate.channelPicon,
            searchTitle = film.candidate.searchTitle,
            year = film.candidate.year,
            tmdbId = film.movie.id,
            language = language,
            kind = kind,
        )
    }

    /** Rows whose movie is missing are left out. */
    fun homeFilms(rows: List<HomeFilmRow>, movies: Map<Int, TmdbMovie>): List<HomeFilm> = rows.mapNotNull { row ->
        val movie = movies[row.tmdbId] ?: return@mapNotNull null
        val event = GuideEvent(
            eventId = row.eventId,
            reference = row.reference,
            title = row.epgTitle,
            shortDescription = row.shortDescription,
            plot = row.longDescription,
            beginEpochSec = row.beginSec,
            durationSec = row.endSec - row.beginSec,
        )
        HomeFilm(FilmCandidate(event, row.channelName, row.searchTitle, row.year, row.channelPicon), movie)
    }
}
