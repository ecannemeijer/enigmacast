package com.enigmacast.domain.epg

import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.ServiceStreams
import java.text.Normalizer

/**
 * Matching IPTV channel names to the receiver's channels, so an IPTV channel without a guide of its
 * own borrows the satellite guide: "NL: NPO 1 HD", "|NL| NPO 1 ᴴᴰ" and "NPO 1 (NL)" all find
 * "NPO 1 HD" / "NPO 1". Pure; [com.enigmacast.data.xtream.IptvSatelliteEpg] runs it against Room.
 *
 * Two keys per name: the full one (no country prefix, quality tags, brackets, accents or
 * punctuation) and a short one that also drops a trailing region code ("rai 1 it"). A name looks
 * up full → full, full → short, short → full, short → short.
 */
object SatEpgNames {
    /** One receiver channel as the index sees it. */
    data class Channel(val name: String, val reference: String, val serviceKey: String)

    /** Receiver channels per key, best first (satellite HD, satellite, then receiver streams). */
    class Index internal constructor(
        private val byFull: Map<String, List<String>>,
        private val byShort: Map<String, List<String>>,
    ) {
        val isEmpty: Boolean get() = byFull.isEmpty()
        val size: Int get() = byFull.size

        /** Service keys to try for [name], best first; empty when nothing matches. */
        fun lookup(name: String): List<String> {
            val k = keys(name) ?: return emptyList()
            return byFull[k.full]
                ?: k.short?.let { byFull[it] }
                ?: byShort[k.full]
                ?: k.short?.let { byShort[it] }
                ?: emptyList()
        }
    }

    class Keys(val full: String, val short: String?)

    /** What the index was built from: a new channel sync or another receiver means a new index. */
    data class Stamp(val receiverKey: String, val channelCount: Int, val syncedAtMs: Long, val contentHash: String)

    /** An empty index is tried again after this long, even when nothing seems to have changed. */
    const val EMPTY_RETRY_MS = 60_000L

    /**
     * Whether the cached index must be rebuilt. It used to be built once per receiver and kept for
     * the whole session, so an index built before the first channel sync (or while the receiver
     * was offline) stayed empty and no IPTV channel ever got the satellite guide.
     */
    fun needsRebuild(built: Stamp?, builtAtMs: Long, wasEmpty: Boolean, current: Stamp, nowMs: Long): Boolean = when {
        built == null -> true
        built != current -> true
        wasEmpty -> nowMs - builtAtMs >= EMPTY_RETRY_MS
        else -> false
    }

    private const val MAX_CANDIDATES = 4

    fun index(channels: List<Channel>): Index {
        val usable = channels.filter { it.name.isNotBlank() && it.serviceKey.isNotBlank() && usable(it.reference) }
        val ranked = usable.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
        val byFull = LinkedHashMap<String, MutableList<String>>()
        val byShort = LinkedHashMap<String, MutableList<String>>()
        fun add(map: MutableMap<String, MutableList<String>>, key: String, service: String) {
            val list = map.getOrPut(key) { ArrayList() }
            if (service !in list && list.size < MAX_CANDIDATES) list += service
        }
        ranked.forEach { channel ->
            val k = keys(channel.name) ?: return@forEach
            add(byFull, k.full, channel.serviceKey)
            k.short?.let { add(byShort, it, channel.serviceKey) }
        }
        return Index(byFull, byShort)
    }

    /** Both keys of a name, or null when nothing is left of it. */
    fun keys(name: String): Keys? {
        val text = clean(name)
        val full = squash(text)
        if (full.isEmpty()) return null
        val short = squash(text.replace(REGION, "")).takeIf { it.isNotEmpty() && it != full }
        return Keys(full, short)
    }

    /** The full key alone (tests and logging of counts). */
    fun key(name: String): String = keys(name)?.full.orEmpty()

    private fun clean(name: String): String {
        var s = name.replace(CIRCLED, " ")
        // "RTL 4ᴴᴰ": a space before superscript tags, so they become a separate "hd" word.
        s = s.replace(SUPERSCRIPT_RUN) { " " + it.value + " " }
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "")
        s = s.lowercase().trim()
        repeat(2) { s = s.replace(PREFIX_BRACKET, "").replace(PREFIX_SEPARATOR, "") }
        s = s.replace(BRACKETS, " ")
            .replace(NON_WORD, " ")
            .replace(QUALITY, " ")
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace(SPACES, " ")
            .trim()
        s = s.replace(RAI_WORDS) { "rai " + (RAI_NUMBERS[it.groupValues[1]] ?: it.groupValues[1]) }
        return s
    }

    private fun squash(text: String) = text.replace(" ", "")

    private fun usable(reference: String): Boolean =
        ServiceStreams.isInternet(reference) || BouquetChannels.isPlayableChannel(reference)

    private fun rank(channel: Channel): Int {
        if (ServiceStreams.isInternet(channel.reference)) return 2
        val type = channel.reference.split(':').getOrNull(2)?.uppercase().orEmpty()
        return if (type in HD_TYPES || HD_NAME.containsMatchIn(channel.name)) 0 else 1
    }

    private val CIRCLED = Regex("[\\u24B6-\\u24E9]")
    private val SUPERSCRIPT_RUN = Regex("[\\u1D2C-\\u1D6A\\u02B0-\\u02FF\\u1D9B-\\u1DBF\\u2070-\\u209F]+")
    private val MARKS = Regex("\\p{M}+")
    private const val COUNTRY3 = "nld|ned|bel|ger|deu|ita|fra|esp|spa|por|pol|tur|ara|eng|usa|can|aus|swe|nor|den|dnk|fin|gre|rus|ukr|alb|ex-yu|exyu"
    /** "|NL| ", "[NL] ", "(IT) ", "« NL »" at the start. */
    private val PREFIX_BRACKET = Regex("^[|\\[(<«]\\s*([a-z]{2}|$COUNTRY3)\\s*[|\\])>»]\\s*")
    /** "NL: ", "NL | ", "IT - ", "NL▸", "NLD: " at the start (a two-letter code or a known three-letter one). */
    private val PREFIX_SEPARATOR = Regex("^([a-z]{2}|$COUNTRY3)\\s*[:|\\-\\u2013\\u2014\\u25B6\\u25B8\\u27A4\\u2022\\u00BB\\u2500-\\u259F/]+\\s*")
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}")
    private val NON_WORD = Regex("[^a-z0-9+&]+")
    private val QUALITY = Regex("\\b(uhd|fhd|hd|sd|hq|lq|4k|8k|hevc|h265|h264|x265|1080p|1080i|720p|576p|480p|50fps|60fps|25fps|raw|backup|multi|vip|dolby|hdr|geo blocked)\\b")
    private val REGION = Regex("\\s+[a-z]{2}$")
    private val SPACES = Regex("\\s+")
    private val RAI_WORDS = Regex("\\brai (uno|due|tre)\\b")
    private val RAI_NUMBERS = mapOf("uno" to "1", "due" to "2", "tre" to "3")
    private val HD_NAME = Regex("\\b(hd|fhd|uhd|4k)\\b", RegexOption.IGNORE_CASE)
    private val HD_TYPES = setOf("19", "1F", "25", "11")
}
