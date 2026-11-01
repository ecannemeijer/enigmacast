package com.enigmacast.domain.search

import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.xtream.IptvLibraryCard
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection

/** The Torrents row of Search: TMDB films and series (as on the Torrent page) matching the query. */
object TorrentSearch {
    const val MAX = 20
    const val MIN_QUERY = 2

    /** Films and series taken in turns (TMDB order kept in each), without doubles, at most [max]. */
    fun merge(films: List<TmdbTitle>, series: List<TmdbTitle>, max: Int = MAX): List<TmdbTitle> {
        val out = ArrayList<TmdbTitle>()
        val seen = HashSet<String>()
        for (i in 0 until maxOf(films.size, series.size)) {
            listOfNotNull(films.getOrNull(i), series.getOrNull(i)).forEach { if (seen.add(it.key)) out += it }
        }
        return out.take(max)
    }

    /** A poster card for the shared Search rows; its key is the title's key. */
    fun card(title: TmdbTitle): IptvLibraryCard {
        val series = title.kind == TorrentKind.TV
        return IptvLibraryCard(
            key = title.key,
            source = "torrent",
            kind = if (series) "s" else "m",
            id = title.id.toString(),
            title = title.title,
            subtitle = title.year?.toString().orEmpty(),
            poster = title.poster,
            progress = 0f,
            open = XtreamItem(title.id.toString(), title.title, if (series) XtreamSection.SERIES else XtreamSection.MOVIES, ""),
        )
    }
}
