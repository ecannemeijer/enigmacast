package com.enigmacast.domain.xtream

/**
 * Categories hidden in Settings › IPTV › Categories. Hidden ids are stored, so every
 * category is shown by default and a new category on the server is shown too.
 */
object IptvCategoryFilter {
    fun visible(categories: List<XtreamCategory>, hidden: Set<String>): List<XtreamCategory> =
        if (hidden.isEmpty()) categories else categories.filter { it.id !in hidden }

    fun shown(categories: List<XtreamCategory>, hidden: Set<String>): Int = categories.count { it.id !in hidden }

    fun toggle(hidden: Set<String>, id: String): Set<String> = if (id in hidden) hidden - id else hidden + id

    fun all(): Set<String> = emptySet()
    fun none(categories: List<XtreamCategory>): Set<String> = categories.map { it.id }.toSet()

    /** The key of a source: one playlist (by kind) or one XStreamity account on the receiver. */
    fun sourceKey(playlistKind: String?, accountKey: String): String = if (playlistKind != null) "playlist:$playlistKind" else "xtream:$accountKey"
}
