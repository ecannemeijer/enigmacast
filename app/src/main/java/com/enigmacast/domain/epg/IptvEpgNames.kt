package com.enigmacast.domain.epg

import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.ServiceStreams

/** One channel as the alias builder sees it. */
data class NamedService(val reference: String, val name: String)

/**
 * IPTV channels have no EPG of their own on the receiver. They borrow it from the satellite
 * channel with the same name: "Rai 1 HD" (stream) shows the guide of "Rai 1 HD" / "Rai 1" (sat).
 * Keys are full IPTV references (with URL): all IPTV lines share the same ten fields.
 */
object IptvEpgNames {
    private val brackets = Regex("""\([^)]*\)|\[[^]]*]""")
    private val quality = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p|1080i|720p|576p|480p|50fps|backup|geo\s*blocked|ⓢ|ⓖ|ⓨ|ⓣ)\b""")
    private val region = Regex("""\s+[a-z]{2}$""")
    private val marks = Regex("\\p{M}+")
    private val circled = Regex("[ⓢⓖⓨⓣ]")
    private val nonWord = Regex("[^a-z0-9+&]+")
    private val spaces = Regex("\\s+")
    private val hdName = Regex("""\b(hd|fhd|uhd|4k)\b""", RegexOption.IGNORE_CASE)

    /** Both keys of one name from a single clean pass. */
    private class Keys(val full: String, val short: String?)

    private fun keys(name: String): Keys {
        val text = clean(name)
        val short = if (region.containsMatchIn(text)) squash(text.replace(region, "")).takeIf { it.isNotEmpty() } else null
        return Keys(squash(text), short)
    }

    /** Lowercase, no accents, no quality / bracket tags, letters and digits only. */
    fun normalize(name: String): String = squash(clean(name))

    /** The same with a trailing two-letter region tag ("rai 1 it") removed; null when there is none. */
    fun withoutRegion(name: String): String? {
        val text = clean(name)
        if (!region.containsMatchIn(text)) return null
        return squash(text.replace(region, "")).takeIf { it.isNotEmpty() }
    }

    private fun clean(name: String): String {
        val ascii = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(marks, "")
        return ascii.lowercase()
            .replace(brackets, " ")
            .replace(circled, " ")
            .replace(nonWord, " ")
            .replace(quality, " ")
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace(spaces, " ")
            .trim()
    }

    private fun squash(text: String) = text.replace(" ", "")

    /** HD satellite channels win: an HD service type or "HD" in the name. */
    private fun isHd(service: NamedService): Boolean {
        val type = service.reference.split(':').getOrNull(2)?.uppercase().orEmpty()
        return type in HD_TYPES || hdName.containsMatchIn(service.name)
    }

    /**
     * IPTV reference → satellite reference for every IPTV channel whose normalized name matches a
     * satellite channel: exact first, then without a region suffix on either side.
     */
    fun aliases(services: List<NamedService>): Map<String, String> {
        val satellite = services.filter { !ServiceStreams.isInternet(it.reference) && BouquetChannels.isPlayableChannel(it.reference) }
        if (satellite.isEmpty()) return emptyMap()
        val byName = HashMap<String, NamedService>()
        val byShort = HashMap<String, NamedService>()
        fun offer(map: HashMap<String, NamedService>, key: String, s: NamedService) {
            if (key.isEmpty()) return
            val old = map[key]
            if (old == null || (!isHd(old) && isHd(s))) map[key] = s
        }
        satellite.forEach { s ->
            val k = keys(s.name)
            offer(byName, k.full, s)
            k.short?.let { offer(byShort, it, s) }
        }
        val out = LinkedHashMap<String, String>()
        services.filter { ServiceStreams.isInternet(it.reference) }.forEach { iptv ->
            val k = keys(iptv.name)
            val name = k.full
            val short = k.short
            val hit = byName[name]
                ?: short?.let { byName[it] }
                ?: byShort[name]
                ?: short?.let { byShort[it] }
            if (hit != null && name.isNotEmpty()) out[iptv.reference] = hit.reference
        }
        return out
    }

    private val HD_TYPES = setOf("19", "1F", "25", "11")
}
