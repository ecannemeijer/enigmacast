package com.enigmacast.domain.xtream

/** The episode that follows [current] in season and episode order. */
object NextEpisode {
    fun after(current: XtreamEpisode, list: List<XtreamEpisode>): XtreamEpisode? {
        if (list.isEmpty()) return null
        val sorted = list.sortedWith(compareBy({ it.season }, { it.episode }, { it.id }))
        val index = sorted.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return sorted.getOrNull(index + 1)
    }
}
