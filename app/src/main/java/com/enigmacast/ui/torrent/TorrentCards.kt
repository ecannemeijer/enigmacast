package com.enigmacast.ui.torrent

import com.enigmacast.data.xtream.IptvVod
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection

/** TMDB titles as the app's film data, so the poster cards and the info page can be reused. */
object TorrentCards {
    /** PosterCard's width (FilmHomePanel); its height follows from its fixed line slots. */
    const val CARD_WIDTH_DP = 132f

    /** Card data (poster, title, year, rating), merged with the full TMDB [details] when known. */
    fun film(t: TmdbTitle, details: TmdbMovie? = null): HomeFilm {
        val base = TmdbMovie(
            id = t.id,
            title = t.title,
            year = t.year,
            overview = t.overview,
            voteAverage = t.rating,
            posterPath = t.poster,
            backdropPath = t.backdrop,
            isSeries = t.kind == TorrentKind.TV,
        )
        val movie = details?.copy(
            title = details.title.ifBlank { t.title },
            year = details.year ?: t.year,
            overview = details.overview.ifBlank { t.overview },
            voteAverage = details.voteAverage.takeIf { it > 0 } ?: t.rating,
            posterPath = details.posterPath ?: t.poster,
            backdropPath = details.backdropPath ?: t.backdrop,
            isSeries = t.kind == TorrentKind.TV,
        ) ?: base
        val item = XtreamItem("tmdb${t.id}", t.title, if (t.kind == TorrentKind.TV) XtreamSection.SERIES else XtreamSection.MOVIES, "", t.poster.orEmpty())
        return IptvVod.film(item, movie)
    }
}
