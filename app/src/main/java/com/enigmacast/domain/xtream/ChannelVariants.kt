package com.enigmacast.domain.xtream

/**
 * IPTV channels that are one station in several qualities ("IT - RAI 1 SD", "IT - RAI 1 HD",
 * "IT - RAI 1 UHD", "RAI 1 FHD (backup)") become one row. Rows are grouped per provider and
 * category by a normalized name; the primary row is the one with an archive first, then the
 * quality order chosen in Settings, or the variant picked by hand.
 *
 * Plain [Regex] only (no look-behind, no Unicode classes), so ICU on every Android version accepts it.
 */
object ChannelVariants {
    enum class QualityOrder { HIGHEST, HD, SAVER }

    private val brackets = Regex("[\\[(][^\\])]*[\\])]")
    private val separators = Regex("[^a-z0-9]+")
    private val superscripts = mapOf(
        'ᴴ' to 'h', 'ᴰ' to 'd', 'ᵁ' to 'u', 'ˢ' to 's', 'ᶠ' to 'f', 'ʰ' to 'h', 'ᵈ' to 'd', '⁴' to '4', 'ᴷ' to 'k',
    )
    private val tags = setOf(
        "sd", "hd", "fhd", "uhd", "qhd", "4k", "8k", "hevc", "h265", "h264", "x265", "x264", "avc",
        "1080", "1080p", "1080i", "720", "720p", "576", "576p", "2160", "2160p", "50fps", "60fps", "25fps",
        "30fps", "fps", "hdr", "backup", "bk", "bkp", "alt", "raw", "lq", "hq",
    )

    private fun fold(name: String): String = buildString(name.length) {
        name.lowercase().forEach { append(superscripts[it] ?: it) }
    }

    private fun words(name: String): List<String> =
        fold(name).replace(brackets) { " " + it.value.trim('[', ']', '(', ')') + " " }
            .split(separators).filter { it.isNotEmpty() }

    /** "IT - RAI 1 HD (backup)" → "it rai 1". Falls back to the plain words when only tags are left. */
    fun normalize(name: String): String {
        val all = words(name)
        val kept = all.filterNot { it in tags || it.matches(fpsTag) }
        return (kept.ifEmpty { all }).joinToString(" ")
    }

    private val fpsTag = Regex("[0-9]+fps")

    /** 4 = UHD/4K, 3 = FHD/1080, 2 = HD/720 or unknown, 1 = SD. */
    fun quality(name: String): Int {
        val w = words(name).toSet()
        return when {
            w.any { it == "4k" || it == "8k" || it == "uhd" || it.startsWith("2160") } -> 4
            w.any { it == "fhd" || it.startsWith("1080") } -> 3
            w.any { it == "sd" || it.startsWith("576") || it == "lq" || it == "low" } -> 1
            else -> 2
        }
    }

    fun isBackup(name: String): Boolean = words(name).any { it == "backup" || it == "bk" || it == "bkp" || it == "alt" }

    /** The stable key of a channel: category and normalized name, never the stream address. */
    fun key(item: XtreamItem): String = "${item.categoryId}|${normalize(item.name)}"

    /** A variant's own stable label inside its group (its folded name). */
    fun label(item: XtreamItem): String = words(item.name).joinToString(" ")

    data class Merged(
        val rows: List<XtreamItem>,
        /** Channel key → its variants, the one that plays first at the head. Only groups of two or more. */
        val variants: Map<String, List<XtreamItem>>,
    )

    fun merge(
        list: List<XtreamItem>,
        order: QualityOrder = QualityOrder.HIGHEST,
        picks: Map<String, String> = emptyMap(),
    ): Merged {
        val groups = LinkedHashMap<String, MutableList<XtreamItem>>()
        list.forEach { item ->
            val k = if (item.section == XtreamSection.LIVE) item.providerId + "#" + key(item) else "\u0000" + item.providerId + item.id
            groups.getOrPut(k) { mutableListOf() }.add(item)
        }
        val variants = LinkedHashMap<String, List<XtreamItem>>()
        val rows = groups.values.map { group ->
            if (group.size == 1) return@map group.first()
            val sorted = sort(group, order)
            val pick = picks[key(group.first())]
            val head = sorted.firstOrNull { pick != null && label(it) == pick }
            val ranked = if (head == null) sorted else listOf(head) + (sorted - head)
            variants[key(group.first())] = ranked
            ranked.first()
        }
        return Merged(rows, variants)
    }

    fun sort(group: List<XtreamItem>, order: QualityOrder): List<XtreamItem> =
        group.withIndex().sortedWith(
            compareByDescending<IndexedValue<XtreamItem>> { it.value.hasArchive }
                .thenBy { isBackup(it.value.name) }
                .thenBy { score(quality(it.value.name), order) }
                .thenBy { it.index },
        ).map { it.value }

    private fun score(quality: Int, order: QualityOrder): Int = when (order) {
        QualityOrder.HIGHEST -> -quality
        QualityOrder.HD -> when (quality) { 3 -> 0; 2 -> 1; 4 -> 2; else -> 3 }
        QualityOrder.SAVER -> quality
    }

    /** [rows] without [hidden] keys, in the saved [custom] order (rows not in it keep their place after). */
    fun arrange(rows: List<XtreamItem>, hidden: Set<String>, custom: List<String>): List<XtreamItem> {
        val visible = rows.filter { it.section != XtreamSection.LIVE || key(it) !in hidden }
        if (custom.isEmpty()) return visible
        val rank = custom.withIndex().associate { it.value to it.index }
        return visible.withIndex().sortedWith(
            compareBy<IndexedValue<XtreamItem>> { rank[key(it.value)] ?: Int.MAX_VALUE }.thenBy { it.index },
        ).map { it.value }
    }

    /** [keys] with the one at [from] moved by [step] (clamped). */
    fun move(keys: List<String>, from: Int, step: Int): List<String> {
        if (from !in keys.indices) return keys
        val to = (from + step).coerceIn(0, keys.lastIndex)
        if (to == from) return keys
        val out = keys.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }
}
