package com.enigmacast.domain.xtream

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.domain.resume.ResumePoint
import com.enigmacast.domain.resume.ResumePolicy

/** One IPTV card in Library. [open] is the film to play or the series to show. */
data class IptvLibraryCard(
    val key: String,
    val source: String,
    /** "m" film, "s" series favorite, "e" episode in Continue watching. */
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val poster: String?,
    val progress: Float,
    val open: XtreamItem,
    /** A series card on its next episode, not started yet. */
    val upNext: Boolean = false,
)

object IptvLibraryRows {
    /**
     * Continue watching: films and episodes with a saved place, newest first, one card per
     * series (its latest episode). Entries of playlists no longer configured are left out
     * (kept in the database), as are old entries without a title (moved from preferences).
     */
    fun continueWatching(rows: List<IptvResumeRow>, activeSources: Set<String>): List<IptvLibraryCard> = rows
        .asSequence()
        .filter { it.source in activeSources && it.title.isNotBlank() }
        .filter { it.kind == "m" || (it.kind == "e" && !it.seriesId.isNullOrBlank()) }
        .sortedByDescending { it.updatedMs }
        .distinctBy { if (it.kind == "e") "${it.source}/s/${it.seriesId}" else "${it.source}/m/${it.id}" }
        .map { row ->
            val progress = ResumePolicy.fraction(ResumePoint(row.id, row.positionMs, row.durationMs), 0L)
            if (row.kind == "e") {
                val mark = if (row.season > 0) "S${row.season}E${row.episode}" else "E${row.episode}"
                IptvLibraryCard(
                    "cw/${row.source}/e/${row.id}", row.source, "e", row.id, row.seriesTitle ?: row.title,
                    listOf(mark, row.title).filter { it.isNotBlank() }.joinToString(" · "), row.poster, progress,
                    XtreamItem(row.seriesId!!, row.seriesTitle ?: row.title, XtreamSection.SERIES, row.categoryId, row.poster.orEmpty()),
                    upNext = com.enigmacast.domain.resume.SeriesContinue.isUpNext(row.kind, row.positionMs),
                )
            } else {
                IptvLibraryCard(
                    "cw/${row.source}/m/${row.id}", row.source, "m", row.id, row.title, ResumePolicy.clock(row.positionMs), row.poster, progress,
                    XtreamItem(row.id, row.title, XtreamSection.MOVIES, row.categoryId, row.poster.orEmpty(), row.extension, url = row.url),
                )
            }
        }
        .toList()

    /**
     * The (kind, id) rows behind one Continue watching card: the film itself, or every saved
     * episode of the card's series (the card shows only the newest of them).
     */
    fun continueGroup(rows: List<IptvResumeRow>, source: String, kind: String, id: String): List<Pair<String, String>> {
        val self = listOf(kind to id)
        if (kind != "e") return self
        val series = rows.firstOrNull { it.source == source && it.kind == "e" && it.id == id }?.seriesId
        if (series.isNullOrBlank()) return self
        return (self + rows.filter { it.source == source && it.kind == "e" && it.seriesId == series }.map { "e" to it.id }).distinct()
    }

    /** IPTV favorites, newest first, with the film's progress when it has a saved place. */
    fun favorites(rows: List<IptvLibraryRow>, resumes: Map<String, IptvResumeRow>, activeSources: Set<String>): List<IptvLibraryCard> = rows
        .filter { it.source in activeSources }
        .sortedByDescending { it.addedMs }
        .map { row ->
            val resume = if (row.kind == "m") resumes["${row.source}/m/${row.id}"] else null
            val progress = resume?.let { ResumePolicy.fraction(ResumePoint(it.id, it.positionMs, it.durationMs), 0L) } ?: 0f
            val section = if (row.kind == "s") XtreamSection.SERIES else XtreamSection.MOVIES
            IptvLibraryCard(
                "fav/${row.source}/${row.kind}/${row.id}", row.source, row.kind, row.id, row.title,
                row.year?.toString().orEmpty(), row.poster, progress,
                XtreamItem(row.id, row.title, section, row.categoryId, row.poster.orEmpty(), row.extension, url = row.url),
            )
        }
}
