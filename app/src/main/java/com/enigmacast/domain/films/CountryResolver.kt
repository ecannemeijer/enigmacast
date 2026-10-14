package com.enigmacast.domain.films

import java.util.Locale

/** Where the viewer is, for TMDB's region/language and the default channel scan. */
object CountryResolver {
    const val AUTO = "AUTO"

    /** Choices in Settings, after Auto. */
    val CHOICES = listOf("NL", "BE", "DE", "GB", "FR", "AT", "CH", "US")

    /**
     * Order: the user's override, then the network (SIM/operator) country, then an IP lookup,
     * then the TV's own locale. Blank or malformed values are skipped. Falls back to NL.
     */
    fun resolve(override: String?, networkCountry: String?, ipCountry: String?, localeCountry: String?): String {
        val chosen = override?.trim()?.uppercase(Locale.ROOT)
        if (!chosen.isNullOrBlank() && chosen != AUTO) return canonical(chosen) ?: "NL"
        return listOf(networkCountry, ipCountry, localeCountry).firstNotNullOfOrNull { canonical(it) } ?: "NL"
    }

    /** "uk" → "GB"; anything that is not two letters → null. */
    fun canonical(value: String?): String? {
        val code = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (code == "UK") return "GB"
        return code.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    /** TMDB `language`: the app language with the country, for example nl-NL, nl-BE, de-DE, en-GB. */
    fun tmdbLanguage(appLanguage: String, country: String): String {
        val lang = appLanguage.lowercase(Locale.ROOT).take(2).ifBlank { "en" }
        return when {
            lang == "nl" && country == "BE" -> "nl-BE"
            lang == "nl" -> "nl-NL"
            lang == "en" && country == "US" -> "en-US"
            lang == "en" -> "en-GB"
            // TMDB has translations per language region; a Dutch country with German text still wants de-DE.
            lang == "de" -> if (country in setOf("AT", "CH")) "de-$country" else "de-DE"
            lang == "it" -> if (country == "CH") "it-CH" else "it-IT"
            lang == "fr" -> if (country in setOf("BE", "CH", "CA")) "fr-$country" else "fr-FR"
            else -> "$lang-$country"
        }
    }
}
