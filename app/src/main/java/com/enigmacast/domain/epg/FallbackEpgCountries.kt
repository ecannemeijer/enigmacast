package com.enigmacast.domain.epg

import com.enigmacast.domain.osdinfo.OrbitalPosition

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

    /**
     * Astra 1 at 19.2°E carries the main German, Austrian and Swiss bouquets
     * (HD+ Deutschland, Sky Deutschland, and the German public channels).
     */
    private val ORBITAL_COUNTRIES: Map<Int, Set<String>> = mapOf(
        192 to setOf("de", "at", "ch"),
    )

    /** Country names and well-known German channel or platform names in titles. */
    private val WORD_COUNTRIES: List<Pair<Regex, String>> = listOf(
        Regex("""\bdeutschland\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bgermany\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bgerman\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bdeutsch\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bhd\s*\+""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bösterreich\b""", RegexOption.IGNORE_CASE) to "at",
        Regex("""\bosterreich\b""", RegexOption.IGNORE_CASE) to "at",
        Regex("""\baustria\b""", RegexOption.IGNORE_CASE) to "at",
        Regex("""\bschweiz\b""", RegexOption.IGNORE_CASE) to "ch",
        Regex("""\bswitzerland\b""", RegexOption.IGNORE_CASE) to "ch",
        Regex("""\bsuisse\b""", RegexOption.IGNORE_CASE) to "ch",
        Regex("""\bsvizzera\b""", RegexOption.IGNORE_CASE) to "ch",
        Regex("""\bdas\s+erste\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bzdf(?:_?neo|info)?\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bsat\.?\s*1\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bpro\s*sieben\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bpro\s*7\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bkabel\s*eins\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bkabel\s*1\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\brtlzwei\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\brtl\s*z(?:wei|wo)\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bsuper\s*rtl\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\b3sat\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bkika\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\btagesschau24\b""", RegexOption.IGNORE_CASE) to "de",
        Regex("""\bard\s+alpha\b""", RegexOption.IGNORE_CASE) to "de",
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

    fun countriesFromWords(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val found = LinkedHashSet<String>()
        WORD_COUNTRIES.forEach { (pattern, code) ->
            if (pattern.containsMatchIn(text)) found += code
        }
        return found
    }

    /** Orbital positions in tenths of a degree found in a bouquet or satellite title. */
    fun orbitalsIn(text: String): Set<Int> {
        val first = OrbitalPosition.parse(text)?.tenths ?: return emptySet()
        return setOf(first)
    }

    fun countriesFromOrbitals(tenths: Collection<Int>): Set<String> {
        val found = LinkedHashSet<String>()
        tenths.forEach { value -> ORBITAL_COUNTRIES[value]?.let { found += it } }
        return found
    }

    /**
     * Countries to download: codes found in channel names and groups, the app language,
     * a device/IP country (never US unless a name asked for it), satellite positions
     * (19.2°E adds Germany, Austria and Switzerland) and the user's extras.
     */
    fun auto(
        texts: Collection<String>,
        appLanguage: String?,
        localeCountry: String? = null,
        extra: Collection<String> = emptyList(),
        orbitalTenths: Collection<Int> = emptyList(),
    ): Set<String> {
        val fromNames = LinkedHashSet<String>()
        texts.forEach { fromNames += FallbackEpgNames.countriesIn(it) }
        fromNames += countriesFromOrbitals(orbitalTenths)
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
