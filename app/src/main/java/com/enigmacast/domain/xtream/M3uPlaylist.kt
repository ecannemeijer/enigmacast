package com.enigmacast.domain.xtream

/**
 * Streaming M3U reader: `#EXTINF:-1 tvg-id=".." tvg-logo=".." group-title="News",Name`
 * followed by the stream URL. `group-title` becomes the category; the URL decides the section
 * (Xtream-style `/movie/` and `/series/` paths are films and series, everything else live).
 */
object M3uPlaylist {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val ATTR_BARE = Regex("""([A-Za-z0-9_-]+)=([^"'\s,]+)""")

    data class Entry(
        val name: String,
        val group: String,
        val logo: String,
        val url: String,
        val section: XtreamSection,
        val number: Int,
        val archiveDays: Int = 0,
        val catchupSource: String = "",
    )

    /** Calls [onEntry] for each channel in order; lines are read one by one, never all at once. */
    fun parse(lines: Sequence<String>, onEntry: (Entry) -> Unit) {
        var info: String? = null
        var group = ""
        var count = 0
        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> info = line
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                info != null -> {
                    val head = info.substringBefore(',', info)
                    val bare = ATTR_BARE.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val attrs = bare + ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val catchup = attrs["catchup"].orEmpty()
                    val days = attrs["catchup-days"]?.toIntOrNull() ?: 0
                    val template = attrs["catchup-source"].orEmpty()
                    val archiveDays = when {
                        days > 0 -> days
                        catchup.isNotBlank() && catchup != "0" -> 1
                        template.isNotBlank() -> 1
                        else -> 0
                    }
                    val name = info.substringAfterLast("\",", "").ifBlank { info.substringAfter(',', "") }.trim()
                        .ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { line.substringAfterLast('/') }
                    count++
                    onEntry(
                        Entry(
                            name = name,
                            group = attrs["group-title"]?.trim().orEmpty().ifBlank { group },
                            logo = attrs["tvg-logo"].orEmpty(),
                            url = line,
                            section = sectionOf(line),
                            number = attrs["tvg-chno"]?.toIntOrNull() ?: count,
                            archiveDays = archiveDays,
                            catchupSource = template,
                        ),
                    )
                    info = null
                    group = ""
                }
            }
        }
    }

    fun sectionOf(url: String): XtreamSection {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/movie/" in path -> XtreamSection.MOVIES
            "/series/" in path -> XtreamSection.SERIES
            else -> XtreamSection.LIVE
        }
    }
}
