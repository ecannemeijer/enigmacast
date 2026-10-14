package com.enigmacast.domain.xtream

/** IPTV search results, one row per section (each at most [IptvSearch.LIMIT]). */
data class IptvSearchResults(
    val live: List<IptvLibraryCard> = emptyList(),
    val films: List<IptvLibraryCard> = emptyList(),
    val series: List<IptvLibraryCard> = emptyList(),
) {
    val isEmpty get() = live.isEmpty() && films.isEmpty() && series.isEmpty()
}

object IptvSearch {
    const val LIMIT = 50
    const val MIN_CHARS = 2

    fun searchable(query: String) = query.trim().length >= MIN_CHARS

    /** SQL LIKE pattern for "contains", with % _ and \ escaped (ESCAPE '\'). */
    fun likePattern(query: String): String {
        val q = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$q%"
    }

    /** Already fetched lists (receiver mode): name contains the query, ignoring case; unique ids. */
    fun filter(items: Sequence<XtreamItem>, query: String, limit: Int = LIMIT): List<XtreamItem> {
        val q = query.trim()
        if (q.length < MIN_CHARS) return emptyList()
        return items.filter { it.name.contains(q, ignoreCase = true) }.distinctBy { it.id }.take(limit).toList()
    }

    fun card(source: String, item: XtreamItem) = IptvLibraryCard(
        key = "search:$source/${item.section}/${item.id}",
        source = source,
        kind = when (item.section) { XtreamSection.LIVE -> "l"; XtreamSection.MOVIES -> "m"; XtreamSection.SERIES -> "s" },
        id = item.id,
        title = item.name,
        subtitle = "",
        poster = item.icon.ifBlank { null },
        progress = 0f,
        open = item,
    )

    fun results(source: String, items: List<XtreamItem>) = IptvSearchResults(
        live = items.filter { it.section == XtreamSection.LIVE }.take(LIMIT).map { card(source, it) },
        films = items.filter { it.section == XtreamSection.MOVIES }.take(LIMIT).map { card(source, it) },
        series = items.filter { it.section == XtreamSection.SERIES }.take(LIMIT).map { card(source, it) },
    )
}
