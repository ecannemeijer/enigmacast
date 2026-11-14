package com.enigmacast.domain.epg

/**
 * Name and tvg-id keys for the iptv-epg.org fallback guide, and country codes found in
 * channel names or group titles ("NL - Cartoonito", "|NL| NPO 1", "NL | Algemeen").
 * Channel keys are the same as [SatEpgNames], so "NL - Cartoonito HD" matches "Cartoonito".
 */
object FallbackEpgNames {
    fun key(name: String): String = SatEpgNames.key(name)

    fun keys(name: String): SatEpgNames.Keys? = SatEpgNames.keys(name)

    /**
     * Keys for an XMLTV channel id or a playlist tvg-id ("Cartoonito.nl"): the id in
     * lowercase, the same without a trailing country suffix, and the normalised name key.
     */
    fun tvgKeys(id: String): Set<String> {
        val raw = id.trim()
        if (raw.isEmpty()) return emptySet()
        val lower = raw.lowercase()
        val out = linkedSetOf(lower)
        val dotted = DOT_SUFFIX.find(lower)
        if (dotted != null) {
            val stem = lower.substring(0, dotted.range.first)
            if (stem.isNotEmpty()) out += stem
        }
        key(raw).takeIf { it.isNotEmpty() }?.let { out += it }
        key(lower.substringBeforeLast('.')).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** ISO country codes (lowercase) mentioned in [text], only when they are a known fallback country. */
    fun countriesIn(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val found = LinkedHashSet<String>()
        BRACKET_CODE.findAll(text).forEach { match ->
            FallbackEpgCountries.canonical(match.groupValues[1])?.let { found += it }
        }
        PREFIX_CODE.findAll(text).forEach { match ->
            FallbackEpgCountries.canonical(match.groupValues[1])?.let { found += it }
        }
        return found
    }

    private val DOT_SUFFIX = Regex("""\.([a-z]{2,3})$""")
    /** "|NL|", "[IT]", "(NL)", "« GB »". */
    private val BRACKET_CODE = Regex("""[|\[(<«]\s*([A-Za-z]{2,3})\s*[|\])>»]""")
    /** "NL - ", "NL: ", "IT | ", "NLD:" at the start or after a separator. */
    private val PREFIX_CODE = Regex("""(?:^|[\s/])([A-Za-z]{2,3})\s*[:|\-\u2013\u2014\u25B6\u25B8\u27A4\u2022\u00BB/]+""")
}
