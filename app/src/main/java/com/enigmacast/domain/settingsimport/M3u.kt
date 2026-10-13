package com.enigmacast.domain.settingsimport

/** One stream from an M3U playlist. */
data class M3uEntry(
    val name: String,
    val url: String,
    val groups: List<String>,
    val attributes: Map<String, String> = emptyMap(),
    /** `#EXTVLCOPT:` options, e.g. http-user-agent, http-referrer. */
    val vlcOptions: Map<String, String> = emptyMap(),
) {
    val tvgName: String? get() = attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    val country: String? get() = attributes["tvg-country"]?.takeIf { it.isNotBlank() }
    val userAgent: String? get() = (vlcOptions["http-user-agent"] ?: attributes["http-user-agent"])?.takeIf { it.isNotBlank() }
    val referrer: String? get() = (vlcOptions["http-referrer"] ?: vlcOptions["http-referer"] ?: attributes["http-referrer"])?.takeIf { it.isNotBlank() }
}

/** Reads `#EXTM3U` playlists: EXTINF attributes (quoted values may hold commas), EXTGRP, EXTVLCOPT. */
object M3uParser {
    const val NO_GROUP = "Other"

    private val attribute = Regex("""([A-Za-z0-9_-]+)=(?:"([^"]*)"|([^\s"]+))""")

    fun parse(text: String): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        var info: Pair<Map<String, String>, String>? = null
        var extGroup: String? = null
        val options = linkedMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = parseExtinf(line)
                    extGroup = null
                    options.clear()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    if (key.isNotEmpty() && option.contains('=')) options[key] = option.substringAfter('=').trim()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val current = info
                    if (current != null) {
                        val (attrs, title) = current
                        val name = title.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { line }
                        val groupText = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup
                        out += M3uEntry(name, line, groups(groupText), attrs, LinkedHashMap(options))
                    }
                    info = null
                    extGroup = null
                    options.clear()
                }
            }
        }
        return out
    }

    /** `#EXTINF:-1 a="x, y" b="z",Title, with comma` → attributes and title. */
    internal fun parseExtinf(line: String): Pair<Map<String, String>, String> {
        val body = line.substringAfter(':', "")
        var inQuotes = false
        var split = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { split = i; break }
        }
        val head = if (split >= 0) body.substring(0, split) else body
        val title = if (split >= 0) body.substring(split + 1).trim() else ""
        val attrs = linkedMapOf<String, String>()
        attribute.findAll(head).forEach { m ->
            attrs[m.groupValues[1].lowercase()] = (m.groups[2]?.value ?: m.groups[3]?.value).orEmpty().trim()
        }
        return attrs to title
    }

    /** iptv-org lists several categories as "Animation;Kids". */
    internal fun groups(text: String?): List<String> {
        val parts = text.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return parts.ifEmpty { listOf(NO_GROUP) }
    }

    /** Group name → number of streams, in first-seen order. */
    fun groupCounts(entries: List<M3uEntry>): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        entries.forEach { e -> e.groups.forEach { g -> counts[g] = (counts[g] ?: 0) + 1 } }
        return counts
    }
}

/** Enigma2 IPTV bouquets built from M3U groups. */
object IptvBouquets {
    const val FILE_PREFIX = "userbouquet.enigmacast_iptv_"
    const val DEFAULT_SERVICE_TYPE = 4097
    val SERVICE_TYPES = listOf(4097, 5001, 5002)

    data class Bouquet(val group: String, val fileName: String, val content: String, val channels: Int)

    fun isIptvFile(name: String): Boolean = name.startsWith(FILE_PREFIX) && (name.endsWith(".tv") || name.endsWith(".radio"))

    /** a–z, 0–9 and '_' only, at most 40 characters. */
    fun slug(group: String): String {
        val ascii = java.text.Normalizer.normalize(group, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val s = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).trim('_')
        return s.ifEmpty { "group" }
    }

    /**
     * Characters Enigma2 would read as field separators or decode: '%' first, then ':' and
     * whitespace. Enigma2 url-decodes the path again when it reads the reference.
     */
    fun encodeUrl(url: String): String = buildString {
        url.trim().forEach { c ->
            when (c) {
                '%' -> append("%25")
                ':' -> append("%3a")
                ' ' -> append("%20")
                '\t', '\r', '\n' -> Unit
                else -> append(c)
            }
        }
    }

    /** Names are the last field of the reference: no line breaks, no ':' (would start a new field on some images). */
    fun cleanName(name: String): String =
        name.replace(Regex("[\\r\\n\\t]+"), " ").replace(':', ' ').replace(Regex(" {2,}"), " ").trim().ifEmpty { "Stream" }

    const val NAME_PREFIX = "IPTV · "
    private val oldPrefix = Regex("^(?:IPTV\\s*[·\\-–:|]\\s*)+", RegexOption.IGNORE_CASE)

    /** "IPTV · News": the prefix shows these are internet streams; never doubled on refresh. */
    fun bouquetName(group: String): String = NAME_PREFIX + cleanName(group).replace(oldPrefix, "").trim().ifEmpty { "Stream" }

    /** `#SERVICE 4097:0:1:0:0:0:0:0:0:0:<url>:<name>`; a user agent rides along as `#User-Agent=` (servicemp3/gstplayer). */
    fun serviceLine(entry: M3uEntry, serviceType: Int = DEFAULT_SERVICE_TYPE): String {
        var url = entry.url.trim()
        entry.userAgent?.let { if (!url.contains("#User-Agent=")) url += "#User-Agent=$it" }
        return "#SERVICE $serviceType:0:1:0:0:0:0:0:0:0:${encodeUrl(url)}:${cleanName(entry.name)}"
    }

    /**
     * One userbouquet per selected group, in [groups] order. Entries keep the playlist order;
     * a URL shows once per bouquet.
     */
    fun build(entries: List<M3uEntry>, groups: List<String>, serviceType: Int = DEFAULT_SERVICE_TYPE): List<Bouquet> {
        val used = HashSet<String>()
        return groups.distinct().mapNotNull { group ->
            val members = entries.filter { group in it.groups }.distinctBy { it.url.trim() }
            if (members.isEmpty()) return@mapNotNull null
            var base = slug(group)
            var n = 2
            var file = "$FILE_PREFIX$base.tv"
            while (!used.add(file)) { file = "$FILE_PREFIX${base}_${n++}.tv" }
            val content = buildString {
                append("#NAME ").append(bouquetName(group)).append('\n')
                members.forEach { e ->
                    append(serviceLine(e, serviceType)).append('\n')
                    append("#DESCRIPTION ").append(cleanName(e.name)).append('\n')
                }
            }
            Bouquet(group, file, content, members.size)
        }
    }
}

/** bouquets.tv: our IPTV lines come after the satellite bouquets; everything else is left as it is. */
object BouquetsTvMerge {
    private const val DEFAULT_NAME = "#NAME User - Bouquets (TV)"

    fun line(fileName: String): String = "#SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"$fileName\" ORDER BY bouquet"

    fun referencesIptv(line: String): Boolean = line.contains("\"${IptvBouquets.FILE_PREFIX}")

    /** The IPTV bouquet files bouquets.tv refers to now. */
    fun iptvFiles(bouquetsTv: String): List<String> = bouquetsTv.lineSequence()
        .filter { referencesIptv(it) }
        .mapNotNull { Regex("\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        .toList()

    /**
     * Drops earlier `enigmacast_iptv_*` lines and appends [iptvFiles] at the end. The same input
     * gives the same output, so a refresh never duplicates lines.
     */
    fun merge(existing: String, iptvFiles: List<String>): String {
        val kept = existing.replace("\r\n", "\n").split('\n')
            .filterNot { referencesIptv(it) }
            .toMutableList()
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.lastIndex)
        if (kept.none { it.startsWith("#NAME") }) kept.add(0, DEFAULT_NAME)
        iptvFiles.distinct().forEach { kept += line(it) }
        return kept.joinToString("\n", postfix = "\n")
    }
}
