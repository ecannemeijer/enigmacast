package com.enigmacast.domain.epg

/**
 * The 65 country files published by iptv-epg.org (`epg-<cc>.xml.gz`).
 * English as the app language maps to the United Kingdom, never the US file
 * (that one is huge and is only used when a channel name asks for it, or the user adds it).
 */
object FallbackEpgCountries {
    const val URL = "https://iptv-epg.org/files/epg-%s.xml.gz"

    val CODES: Set<String> = linkedSetOf(
        "al", "am", "au", "at", "bs", "by", "be", "bo", "ba", "br",
        "bg", "ca", "cr", "hr", "cz", "dk", "do", "sv", "fi", "fr",
        "ge", "de", "gr", "gt", "hn", "hk", "hu", "is", "in", "id",
        "il", "it", "jm", "jp", "lv", "lt", "lu", "mk", "my", "mt",
        "mx", "me", "nl", "nz", "ni", "no", "pa", "py", "pl", "pt",
        "ro", "ru", "rs", "sg", "za", "es", "se", "ch", "tw", "th",
        "tt", "tr", "gb", "us", "ve",
    )

    /** Three-letter or everyday aliases used in bouquet and group titles. */
    private val ALIAS = mapOf(
        "uk" to "gb", "eng" to "gb", "usa" to "us",
        "ned" to "nl", "nld" to "nl",
        "ger" to "de", "deu" to "de",
        "ita" to "it", "fra" to "fr",
        "esp" to "es", "spa" to "es",
        "por" to "pt", "pol" to "pl",
        "tur" to "tr", "den" to "dk", "dnk" to "dk",
        "swe" to "se", "nor" to "no", "fin" to "fi",
        "gre" to "gr", "bel" to "be", "aus" to "au",
        "can" to "ca", "sui" to "ch", "swi" to "ch",
        "cze" to "cz", "cro" to "hr", "ser" to "rs",
        "mac" to "mk", "rom" to "ro", "hun" to "hu",
        "lux" to "lu", "ire" to "ie",
    )

    fun canonical(code: String?): String? {
        val raw = code?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return null
        val mapped = ALIAS[raw] ?: raw
        return mapped.takeIf { it in CODES }
    }

    fun url(code: String): String = URL.format(canonical(code) ?: code.lowercase())

    /** App language tag ("nl", "en-GB") → a fallback country. English becomes GB, not US. */
    fun languageCountry(tag: String?): String? {
        val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()
        return when (language) {
            "en" -> "gb"
            "nl" -> "nl"
            "de" -> "de"
            "it" -> "it"
            "fr" -> "fr"
            "tr" -> "tr"
            "pl" -> "pl"
            else -> canonical(language)
        }
    }

    /**
     * Countries to download: codes found in channel names and groups, the app language,
     * a device/IP country (never US unless a name asked for it), and the user's extras.
     */
    fun auto(
        texts: Collection<String>,
        appLanguage: String?,
        localeCountry: String? = null,
        extra: Collection<String> = emptyList(),
    ): Set<String> {
        val fromNames = LinkedHashSet<String>()
        texts.forEach { fromNames += FallbackEpgNames.countriesIn(it) }
        val out = LinkedHashSet<String>()
        out += fromNames
        languageCountry(appLanguage)?.let { out += it }
        canonical(localeCountry)?.let { code ->
            if (code != "us" || "us" in fromNames) out += code
        }
        extra.forEach { canonical(it)?.let { code -> out += code } }
        return out
    }
}
