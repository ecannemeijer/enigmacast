package com.enigmacast.domain.xtream

/** Which episode Play on a series info page starts. */
object SeriesPlay {
    /**
     * The episode the page was opened for ([hint], a Continue watching card), else the latest
     * one watched ([latest]), else the first episode (regular seasons before specials). The
     * episode list's own entry is used when it has the same id (its cover and extension).
     */
    fun target(hint: XtreamEpisode?, latest: XtreamEpisode?, episodes: List<XtreamEpisode>): XtreamEpisode? {
        val wanted = hint ?: latest
        if (wanted != null) return episodes.firstOrNull { it.id == wanted.id } ?: wanted
        val regular = episodes.filter { it.season > 0 }.ifEmpty { episodes }
        return regular.minWithOrNull(compareBy<XtreamEpisode>({ it.season }, { it.episode }))
    }
}
