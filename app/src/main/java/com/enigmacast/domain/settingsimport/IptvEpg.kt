package com.enigmacast.domain.settingsimport

import java.io.Reader
import org.xmlpull.v1.XmlPullParser

/** EPG key of one IPTV service: what the playlist says about it and its Enigma2 reference (first ten fields). */
data class EpgLink(val tvgId: String, val name: String, val reference: String, val country: String? = null)

/** One programme from an XMLTV guide. */
data class XmltvProgramme(val channel: String, val startSec: Long, val stopSec: Long, val title: String, val desc: String)

/** EPG for imported IPTV bouquets: which XMLTV files, how channels match, EPGImport files. */
object IptvEpg {
    const val SOURCES_FILE = "enigmacast.sources.xml"
    const val CHANNELS_FILE = "enigmacast.channels.xml"
    const val EPGIMPORT_DIR = "/etc/epgimport"
    /** At most this many guide files per load: each is a few MB on a TV. */
    const val MAX_FILES = 8

    private val countryFile = Regex("""epg_ripper_([A-Z]{2})\d*\.xml(\.gz)?$""", RegexOption.IGNORE_CASE)

    /** `#EXTM3U x-tvg-url="a, b" url-tvg="c"` → [a, b, c]. */
    fun headerUrls(playlist: String): List<String> {
        val first = playlist.lineSequence().map { it.trim().removePrefix("\uFEFF") }.firstOrNull { it.isNotEmpty() } ?: return emptyList()
        if (!first.startsWith("#EXTM3U", ignoreCase = true)) return emptyList()
        val (attrs, _) = M3uParser.parseExtinf("x:" + first.removePrefix("#EXTM3U").removePrefix("#extm3u") + ",")
        return listOfNotNull(attrs["x-tvg-url"], attrs["url-tvg"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    /** Country codes a link belongs to: tvg-country and the `.xx` suffix of the tvg-id; GB is UK at epgshare. */
    fun countries(link: EpgLink): Set<String> {
        val codes = mutableSetOf<String>()
        link.country?.split(';', ',')?.map { it.trim().uppercase() }?.filter { it.length == 2 }?.let(codes::addAll)
        link.tvgId.substringBefore('@').substringAfterLast('.', "").takeIf { it.length == 2 && it.all(Char::isLetter) }?.let { codes += it.uppercase() }
        return codes.map { if (it == "GB") "UK" else it }.toSet()
    }

    /**
     * Guide files for the chosen services. Per-country files (epgshare01 `epg_ripper_NL1.xml.gz`) only for
     * countries in the selection; ALL_SOURCES and other multi-country files are never taken. A playlist with
     * one guide URL (iptv-org) uses that one.
     */
    fun chooseUrls(header: List<String>, links: List<EpgLink>): List<String> {
        if (header.isEmpty() || links.isEmpty()) return emptyList()
        val usable = header.filterNot { it.contains("ALL_SOURCES", ignoreCase = true) }
        if (usable.size == 1 && countryFile.find(usable[0]) == null) return usable
        val wanted = links.flatMap { countries(it) }.groupingBy { it }.eachCount()
        return usable
            .mapNotNull { url -> countryFile.find(url.substringAfterLast('/'))?.groupValues?.get(1)?.uppercase()?.let { url to it } }
            .filter { (_, cc) -> cc in wanted }
            .sortedByDescending { (_, cc) -> wanted[cc] ?: 0 }
            .map { it.first }
            .take(MAX_FILES)
    }

    fun normalize(text: String): String {
        val ascii = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * XMLTV channel id per link reference: exact tvg-id, then the same id without punctuation, then the
     * display name. [channels] is XMLTV id → display names.
     */
    fun match(links: List<EpgLink>, channels: Map<String, List<String>>): Map<String, String> {
        val byId = HashMap<String, String>()
        val byName = HashMap<String, String>()
        channels.forEach { (id, names) ->
            byId.putIfAbsent(normalize(id), id)
            names.forEach { byName.putIfAbsent(normalize(it), id) }
        }
        val out = LinkedHashMap<String, String>()
        links.forEach { link ->
            val tvg = link.tvgId.substringBefore('@')
            val id = channels.keys.firstOrNull { it == link.tvgId || it == tvg }
                ?: tvg.takeIf { it.isNotBlank() }?.let { byId[normalize(it)] }
                ?: byName[normalize(link.name)].takeIf { link.name.isNotBlank() }
                ?: tvg.substringBeforeLast('.').takeIf { it.isNotBlank() }?.let { byName[normalize(it)] }
            if (id != null) out[link.reference] = id
        }
        return out
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** EPGImport source list: one gen_xmltv source per guide file, all in the "EnigmaCast IPTV" category. */
    fun sourcesXml(urls: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<sources>\n  <sourcecat sourcecatname=\"EnigmaCast IPTV\">\n")
        urls.forEach { url ->
            val name = url.substringAfterLast('/').substringBefore(".xml")
            append("    <source type=\"gen_xmltv\" nocheck=\"1\" channels=\"").append(EPGIMPORT_DIR).append('/').append(CHANNELS_FILE).append("\">\n")
            append("      <description>EnigmaCast ").append(esc(name)).append("</description>\n")
            append("      <url>").append(esc(url)).append("</url>\n")
            append("    </source>\n")
        }
        append("  </sourcecat>\n</sources>\n")
    }

    /** EPGImport channel map: XMLTV id → service reference (EPG is keyed on the first ten fields). */
    fun channelsXml(matches: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<channels>\n")
        matches.entries.sortedBy { it.value }.forEach { (reference, id) ->
            append("  <channel id=\"").append(esc(id)).append("\">").append(reference).append("</channel>\n")
        }
        append("</channels>\n")
    }

    /** `20261012000000 +0000` → epoch seconds; no offset means UTC. Digit arithmetic, no formatter objects. */
    fun parseTime(text: String): Long? {
        val t = text.trim()
        if (t.length < 14) return null
        fun num(from: Int, len: Int): Int {
            var v = 0
            for (i in from until from + len) {
                val d = t[i] - '0'
                if (d !in 0..9) return -1
                v = v * 10 + d
            }
            return v
        }
        val y = num(0, 4); val mo = num(4, 2); val d = num(6, 2); val h = num(8, 2); val mi = num(10, 2); val se = num(12, 2)
        if (y < 0 || mo !in 1..12 || d !in 1..31 || h < 0 || mi < 0 || se < 0) return null
        val days = java.time.LocalDate.of(y, mo, d).toEpochDay()
        var sec = days * 86_400 + h * 3600 + mi * 60 + se
        val zone = t.substring(14).trim()
        if (zone.length == 5 && (zone[0] == '+' || zone[0] == '-')) {
            val zh = zone.substring(1, 3).toIntOrNull() ?: return null
            val zm = zone.substring(3, 5).toIntOrNull() ?: return null
            val off = zh * 3600 + zm * 60
            sec -= if (zone[0] == '+') off else -off
        }
        return sec
    }
}

/**
 * Streaming XMLTV reader on an [XmlPullParser] (Android's native-backed parser on the TV, kXML in
 * tests): one pass, never the whole document in memory. Channels come first; programmes of
 * channels that [wanted] rejects are skipped without building strings. Return false from
 * [onChannelsDone] to stop after the channel list.
 */
class XmltvReader(private val parser: XmlPullParser, private val reader: Reader) {
    fun read(
        onChannel: (id: String, names: List<String>) -> Unit,
        onChannelsDone: () -> Boolean,
        wanted: (String) -> Boolean,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(reader)
        var channelsDone = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.depth == 2) {
                when (parser.name) {
                    "channel" -> readChannel(onChannel)
                    "programme" -> {
                        if (!channelsDone) {
                            channelsDone = true
                            if (!onChannelsDone()) return
                        }
                        val channel = parser.getAttributeValue(null, "channel").orEmpty()
                        if (wanted(channel)) readProgramme(channel)?.let(onProgramme) else skip()
                    }
                    else -> skip()
                }
            }
            event = parser.next()
        }
        if (!channelsDone) onChannelsDone()
    }

    private fun readChannel(onChannel: (String, List<String>) -> Unit) {
        val id = parser.getAttributeValue(null, "id")
        val names = ArrayList<String>(2)
        val depth = parser.depth
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && parser.depth == depth)) break
            if (e == XmlPullParser.START_TAG && parser.name == "display-name") names += parser.nextText().trim()
        }
        if (!id.isNullOrEmpty()) onChannel(id, names)
    }

    private fun readProgramme(channel: String): XmltvProgramme? {
        val start = IptvEpg.parseTime(parser.getAttributeValue(null, "start").orEmpty())
        val stop = IptvEpg.parseTime(parser.getAttributeValue(null, "stop").orEmpty())
        var title = ""
        var desc = ""
        val depth = parser.depth
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && parser.depth == depth)) break
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title.isEmpty()) title = parser.nextText().trim() else skip()
                    "desc" -> if (desc.isEmpty()) desc = parser.nextText().trim() else skip()
                    else -> skip()
                }
            }
        }
        if (start == null || stop == null || stop <= start || title.isEmpty()) return null
        return XmltvProgramme(channel, start, stop, title, desc)
    }

    /** Skips the element the parser is on, with its children. */
    private fun skip() {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var level = 1
        while (level > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> level++
                XmlPullParser.END_TAG -> level--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
