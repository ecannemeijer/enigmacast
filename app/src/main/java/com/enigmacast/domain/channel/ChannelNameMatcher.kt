package com.enigmacast.domain.channel

import java.util.Locale

/**
 * Compares channel names across providers. Country/quality suffixes and separators are ignored:
 * "NL - SBS6 HD", "SBS6" and "SBS 6" all become "SBS6"; "NPO 1" becomes "NPO1". The match is
 * exact on the remaining core, so "NPO 1" does not swallow "NPO 1 Extra".
 */
object ChannelNameMatcher {
    private val noise = setOf(
        "NL", "NLD", "NEDERLAND", "NETHERLANDS", "DUTCH",
        "BE", "BEL", "BELGIE", "BELGIUM", "VL", "WAL",
        "DE", "GER", "DUITSLAND", "GERMANY",
        "EN", "ENG", "UK", "GB", "BRITAIN", "BRITISH",
        "US", "USA", "FR", "FRA", "ES", "ESP", "IT", "ITA", "PT", "POR",
        "PL", "POL", "TR", "TUR", "AR", "ARG", "BR", "BRA", "MX", "MEX",
        "SE", "SWE", "NO", "NOR", "DK", "DAN", "FI", "FIN", "IE", "IRL",
        "AT", "AUT", "CH", "SUI", "CZ", "SVK", "HU", "RO", "GR", "HR", "RS",
        "HD", "FHD", "UHD", "SD", "4K", "8K", "2160P", "1080P", "1080I", "720P", "576P",
        "H264", "H265", "HEVC", "AVC", "MPEG4", "RAW", "VIP",
        "LIVE", "STREAM", "IPTV", "SAT", "SATELLITE", "CHANNEL", "TV",
        "50FPS", "25FPS", "60FPS", "30FPS",
    )

    fun canonical(raw: String): String = raw
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .split(' ')
        .asSequence()
        .filter { it.isNotBlank() }
        .filterNot { it in noise }
        .joinToString("")

    fun matches(left: String, right: String): Boolean {
        val a = canonical(left)
        val b = canonical(right)
        return a.isNotBlank() && a == b
    }
}
