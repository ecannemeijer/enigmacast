package com.enigmacast.domain.settingsimport

import java.io.Reader
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

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

    private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

    /** `20261012000000 +0000` → epoch seconds; no offset means UTC. */
    fun parseTime(text: String): Long? {
        val t = text.trim()
        if (t.length < 14) return null
        val withZone = if (t.length >= 20) t.substring(0, 14) + " " + t.substring(t.length - 5) else t.substring(0, 14) + " +0000"
        return runCatching { OffsetDateTime.parse(withZone, XMLTV_TIME).toEpochSecond() }.getOrNull()
    }
}

/**
 * Streaming XMLTV reader: one pass over the text, never the whole document in memory. Channels
 * come first; [onProgramme] gets only programmes whose channel [wanted] accepts. Return false
 * from [onChannelsDone] to stop after the channel list (EPGImport needs only the ids).
 */
class XmltvReader(private val reader: Reader) {
    fun read(
        onChannel: (id: String, names: List<String>) -> Unit,
        onChannelsDone: () -> Boolean,
        wanted: (String) -> Boolean,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        val lexer = XmlLexer(reader)
        var channelId: String? = null
        val names = ArrayList<String>()
        var channelsDone = false
        var programme: Map<String, String>? = null
        var field: String? = null
        val text = StringBuilder()
        var title = ""
        var desc = ""
        while (true) {
            val token = lexer.next() ?: break
            when (token) {
                is XmlLexer.Token.Start -> {
                    when (token.name) {
                        "channel" -> if (programme == null) { channelId = token.attrs["id"]; names.clear() }
                        "programme" -> {
                            if (!channelsDone) {
                                channelsDone = true
                                if (!onChannelsDone()) return
                            }
                            val ch = token.attrs["channel"].orEmpty()
                            programme = if (wanted(ch)) token.attrs else null
                            title = ""; desc = ""
                            if (programme == null) lexer.skipUntilEnd("programme")
                        }
                        "display-name", "title", "desc" -> { field = token.name; text.clear() }
                    }
                    if (token.selfClosing && token.name == "channel") { channelId?.let { onChannel(it, names.toList()) }; channelId = null }
                }
                is XmlLexer.Token.Text -> if (field != null) text.append(token.text)
                is XmlLexer.Token.End -> when (token.name) {
                    "display-name" -> { if (channelId != null) names += text.toString().trim(); field = null }
                    "title" -> { if (title.isEmpty()) title = text.toString().trim(); field = null }
                    "desc" -> { if (desc.isEmpty()) desc = text.toString().trim(); field = null }
                    "channel" -> { channelId?.let { onChannel(it, names.toList()) }; channelId = null }
                    "programme" -> {
                        val p = programme
                        if (p != null) {
                            val start = IptvEpg.parseTime(p["start"].orEmpty())
                            val stop = IptvEpg.parseTime(p["stop"].orEmpty())
                            if (start != null && stop != null && stop > start && title.isNotEmpty()) {
                                onProgramme(XmltvProgramme(p["channel"].orEmpty(), start, stop, title, desc))
                            }
                        }
                        programme = null
                    }
                }
            }
        }
        if (!channelsDone) onChannelsDone()
    }
}

/** Minimal pull lexer for XMLTV: tags with attributes, text with entities, CDATA; skips PIs, comments, DOCTYPE. */
internal class XmlLexer(private val reader: Reader) {
    sealed interface Token {
        data class Start(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Token
        data class End(val name: String) : Token
        data class Text(val text: String) : Token
    }

    private var peeked = -2

    private fun read(): Int = if (peeked != -2) peeked.also { peeked = -2 } else reader.read()
    private fun unread(c: Int) { peeked = c }

    fun next(): Token? {
        while (true) {
            val c = read()
            if (c < 0) return null
            if (c != '<'.code) {
                val sb = StringBuilder().append(c.toChar())
                while (true) {
                    val d = read()
                    if (d < 0) break
                    if (d == '<'.code) { unread(d); break }
                    sb.append(d.toChar())
                }
                return Token.Text(decode(sb.toString()))
            }
            val d = read()
            when (d) {
                '?'.code -> skipPast("?>")
                '!'.code -> {
                    val head = StringBuilder()
                    repeat(2) { val e = read(); if (e >= 0) head.append(e.toChar()) }
                    when {
                        head.toString() == "--" -> skipPast("-->")
                        head.toString() == "[C" -> {
                            skipPast("[") // "DATA["
                            return Token.Text(readUntil("]]>"))
                        }
                        else -> skipPast(">")
                    }
                }
                '/'.code -> {
                    val name = readUntil(">").trim()
                    return Token.End(name)
                }
                else -> {
                    if (d < 0) return null
                    val raw = StringBuilder().append(d.toChar())
                    var quote = 0
                    while (true) {
                        val e = read()
                        if (e < 0) break
                        if (quote != 0) { if (e == quote) quote = 0 }
                        else if (e == '"'.code || e == '\''.code) quote = e
                        else if (e == '>'.code) break
                        raw.append(e.toChar())
                    }
                    var body = raw.toString()
                    val self = body.endsWith("/")
                    if (self) body = body.dropLast(1)
                    val name = body.takeWhile { !it.isWhitespace() }
                    val attrs = HashMap<String, String>()
                    ATTR.findAll(body.substring(name.length)).forEach { m ->
                        attrs[m.groupValues[1]] = decode(m.groups[2]?.value ?: m.groups[3]?.value.orEmpty())
                    }
                    return Token.Start(name, attrs, self)
                }
            }
        }
    }

    /** Skips everything up to and including `</name>` (nested same-name tags do not occur in XMLTV). */
    fun skipUntilEnd(name: String) { skipPast("</$name>") }

    private fun skipPast(marker: String) { readUntil(marker) }

    private fun readUntil(marker: String): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return sb.toString()
            sb.append(c.toChar())
            if (sb.length >= marker.length && sb.endsWith(marker)) return sb.substring(0, sb.length - marker.length)
            if (sb.length > 1_000_000) sb.setLength(0) // never hold a huge skipped block
        }
    }

    private companion object {
        val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
        fun decode(text: String): String = if (!text.contains('&')) text else ENTITY.replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> runCatching {
                    val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
            }
        }
    }
}
