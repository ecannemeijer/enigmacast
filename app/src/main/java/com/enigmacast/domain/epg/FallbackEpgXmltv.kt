package com.enigmacast.domain.epg

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory

/** One programme kept from an iptv-epg.org XMLTV file. */
data class FallbackProgramme(
    val xmltvId: String,
    val title: String,
    val description: String,
    val beginSec: Long,
    val endSec: Long,
)

/** A channel from the fallback file that matched a name or tvg-id we asked for. */
data class FallbackChannel(
    val xmltvId: String,
    val displayName: String,
)

/** Channel ids from the fallback file that matched a name or tvg-id we asked for. */
class FallbackWanted(
    nameKeys: Collection<String>,
    tvgIds: Collection<String> = emptyList(),
) {
    private val names = nameKeys.filter { it.isNotEmpty() }.toHashSet()
    private val tvg = HashSet<String>()

    init {
        tvgIds.forEach { id ->
            FallbackEpgNames.tvgKeys(id).forEach { tvg += it }
        }
    }

    val isEmpty: Boolean get() = names.isEmpty() && tvg.isEmpty()

    fun accepts(xmltvId: String, displayNames: List<String>): Boolean {
        if (xmltvId.isNotEmpty()) {
            if (xmltvId.lowercase() in tvg) return true
            if (FallbackEpgNames.tvgKeys(xmltvId).any { it in tvg || it in names }) return true
        }
        return displayNames.any { name ->
            val keys = FallbackEpgNames.keys(name) ?: return@any false
            keys.full in names || (keys.short != null && keys.short in names)
        }
    }
}

/**
 * Streaming XMLTV reader. Channels are decided as they appear (the file lists them first);
 * only programmes for accepted channels are reported, one at a time.
 */
object FallbackEpgXmltv {
    fun parse(
        input: InputStream,
        wanted: FallbackWanted,
        onProgramme: (FallbackProgramme) -> Unit,
        onChannel: (FallbackChannel) -> Unit = {},
    ): Int {
        if (wanted.isEmpty) return 0
        var count = 0
        val handler = Handler(wanted, onChannel) { programme ->
            onProgramme(programme)
            count++
        }
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(input, handler)
        return count
    }

    /**
     * XMLTV time: `20261113001500 +0000`, `20261113001500+0100`, or 14 digits (UTC).
     * Returns epoch seconds, or null when the value cannot be read.
     */
    fun parseTime(raw: String): Long? {
        val text = raw.trim()
        if (text.length < 14) return null
        val digits = text.substring(0, 14)
        if (digits.any { it !in '0'..'9' }) return null
        return try {
            val year = digits.substring(0, 4).toInt()
            val month = digits.substring(4, 6).toInt()
            val day = digits.substring(6, 8).toInt()
            val hour = digits.substring(8, 10).toInt()
            val minute = digits.substring(10, 12).toInt()
            val second = digits.substring(12, 14).toInt()
            val offset = parseOffset(text.substring(14).trim())
            LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(offset)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOffset(rest: String): ZoneOffset {
        if (rest.isEmpty()) return ZoneOffset.UTC
        val compact = rest.replace(":", "").replace(" ", "")
        if (compact.length < 3) return ZoneOffset.UTC
        val sign = compact[0]
        if (sign != '+' && sign != '-') return ZoneOffset.UTC
        val hours = compact.substring(1, 3).toIntOrNull() ?: return ZoneOffset.UTC
        val minutes = if (compact.length >= 5) compact.substring(3, 5).toIntOrNull() ?: 0 else 0
        val total = hours * 3600 + minutes * 60
        return ZoneOffset.ofTotalSeconds(if (sign == '-') -total else total)
    }

    private class Handler(
        private val wanted: FallbackWanted,
        private val onChannel: (FallbackChannel) -> Unit,
        private val onProgramme: (FallbackProgramme) -> Unit,
    ) : DefaultHandler() {
        private val accepted = HashSet<String>()
        private val text = StringBuilder()
        private var inChannel = false
        private var inProgramme = false
        private var channelId = ""
        private val displayNames = ArrayList<String>()
        private var startRaw = ""
        private var stopRaw = ""
        private var programmeChannel = ""
        private var title = ""
        private var description = ""
        private var tag = ""

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            text.setLength(0)
            val name = (qName ?: localName).orEmpty()
            tag = name
            when (name) {
                "channel" -> {
                    inChannel = true
                    channelId = attributes.getValue("id").orEmpty()
                    displayNames.clear()
                }
                "programme" -> {
                    inProgramme = true
                    programmeChannel = attributes.getValue("channel").orEmpty()
                    startRaw = attributes.getValue("start").orEmpty()
                    stopRaw = attributes.getValue("stop").orEmpty()
                    title = ""
                    description = ""
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inChannel || inProgramme) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (qName ?: localName).orEmpty()
            val body = text.toString().trim()
            when {
                inChannel && name == "display-name" -> if (body.isNotEmpty()) displayNames += body
                inChannel && name == "channel" -> {
                    if (wanted.accepts(channelId, displayNames)) {
                        accepted += channelId
                        onChannel(FallbackChannel(channelId, displayNames.firstOrNull().orEmpty()))
                    }
                    inChannel = false
                    channelId = ""
                    displayNames.clear()
                }
                inProgramme && name == "title" -> if (title.isEmpty()) title = body
                inProgramme && name == "desc" -> if (description.isEmpty()) description = body
                inProgramme && name == "sub-title" -> if (description.isEmpty()) description = body
                inProgramme && name == "programme" -> {
                    if (programmeChannel in accepted) {
                        val begin = parseTime(startRaw)
                        val end = parseTime(stopRaw)
                        if (begin != null && end != null && end > begin && title.isNotEmpty()) {
                            onProgramme(FallbackProgramme(programmeChannel, title, description, begin, end))
                        }
                    }
                    inProgramme = false
                }
            }
            text.setLength(0)
            tag = ""
        }
    }
}
