package com.enigmacast.domain.search

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What remains of a typed or spoken search after fillers and “yesterday” phrases. */
data class ParsedSearch(
    val text: String,
    val recordingDay: LocalDate? = null,
) {
    val isSearchable: Boolean
        get() = recordingDay != null || text.length >= SearchGrouping.MIN_QUERY
}

/**
 * Turns Assistant and remote speech into a title filter and an optional recording day.
 * “Search for BBC One” keeps BBC One; “yesterday’s recordings” / “opnames van gisteren”
 * lists recordings from the previous local calendar day.
 */
object SearchQuery {
    fun parse(raw: String, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): ParsedSearch {
        var text = FILLER.replaceFirst(raw.trim(), "").trim()
        val yesterday = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate().minusDays(1)
        val dayPhrase = YESTERDAY_RECORDING.find(text)
        if (dayPhrase != null) {
            text = text.replace(dayPhrase.value, " ").replace(SPACES, " ").trim()
            return ParsedSearch(text, yesterday)
        }
        if (YESTERDAY_ONLY.matches(text)) return ParsedSearch("", yesterday)
        return ParsedSearch(text)
    }

    private val FILLER = Regex("""^(search for|search|play|zoek naar|zoek|speel)\s+""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    private val YESTERDAY_ONLY = Regex("""^(yesterday|gisteren)$""", RegexOption.IGNORE_CASE)
    private val YESTERDAY_RECORDING = Regex(
        """(?i)\b((yesterday'?s?|gisteren)\s+(recordings?|opnames?)|(recordings?|opnames?)(\s+(van|from))?\s+(yesterday|gisteren))\b""",
    )
}
