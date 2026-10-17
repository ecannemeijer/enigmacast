package com.enigmacast.domain.xtream

/** Same channel on satellite and IPTV: "NPO 1" and "NPO 1 HD" match; the same quality tag wins. */
object ChannelMatch {
    private val PREFIX = Regex("^\\s*[A-Za-z]{2,3}\\s*[:|\\-\\u2013\\u2014]\\s+")
    private val QUALITY = Regex("\\b(uhd|fhd|hd|sd|4k|hevc|h265|h264)\\b", RegexOption.IGNORE_CASE)

    /** Words worth a playlist search, without the country prefix and without HD/SD/UHD. */
    fun query(name: String): String =
        name.replace(PREFIX, " ")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(QUALITY, " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .take(4)
            .joinToString(" ")

    fun quality(name: String): String? = QUALITY.find(name)?.groupValues?.get(1)?.lowercase()?.let {
        when (it) {
            "4k", "uhd" -> "uhd"
            "fhd", "hd" -> "hd"
            else -> it
        }
    }

    fun <T> best(wanted: String, candidates: List<T>, name: (T) -> String): T? {
        val key = IptvNames.normalize(wanted)
        if (key.isEmpty()) return null
        val hits = candidates.filter { IptvNames.normalize(name(it)) == key }
        if (hits.isEmpty()) return null
        val tag = quality(wanted)
        return hits.firstOrNull { quality(name(it)) == tag } ?: hits.first()
    }
}
