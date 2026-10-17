package com.enigmacast.domain.service

import com.enigmacast.domain.model.BouquetEntry
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The bouquet list on screen follows bouquets.tv. OpenWebif's bouquet list is not always
 * that file: some images put the stream bouquets first. The file is the order the user set.
 */
object BouquetOrder {
    private val quoted = Regex("""FROM BOUQUET\s+["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val bare = Regex("""(userbouquet\.[^"'\s:]+\.(?:tv|radio))""", RegexOption.IGNORE_CASE)

    /** Userbouquet file names in the order bouquets.tv lists them. */
    fun filesIn(bouquetsTv: String): List<String> = bouquetsTv.lineSequence()
        .mapNotNull { fileOf(it) }
        .toList()

    /** The userbouquet file a service reference points at, without a directory. */
    fun fileOf(reference: String): String? {
        val text = decode(reference)
        val raw = quoted.find(text)?.groupValues?.get(1) ?: bare.find(text)?.groupValues?.get(1) ?: return null
        return raw.substringAfterLast('/').substringAfterLast('\\')
    }

    /**
     * How many of [bouquets] bouquets.tv actually names. Zero means the references do not
     * carry a file name, so the list cannot be reordered from the file.
     */
    fun matched(bouquets: List<BouquetEntry>, bouquetsTv: String): Int {
        val known = filesIn(bouquetsTv).map { it.lowercase() }.toSet()
        if (known.isEmpty()) return 0
        return bouquets.count { fileOf(it.reference)?.lowercase() in known }
    }

    /**
     * [bouquets] in bouquets.tv order. A bouquet the file does not mention stays at the end,
     * in the order the receiver gave it.
     */
    fun apply(bouquets: List<BouquetEntry>, bouquetsTv: String): List<BouquetEntry> {
        val order = filesIn(bouquetsTv)
        if (order.isEmpty()) return bouquets
        val index = order.withIndex().associate { (position, name) -> name.lowercase() to position }
        val known = ArrayList<BouquetEntry>(bouquets.size)
        val rest = ArrayList<BouquetEntry>()
        for (bouquet in bouquets) {
            val file = fileOf(bouquet.reference)?.lowercase()
            if (file != null && file in index) known += bouquet else rest += bouquet
        }
        known.sortBy { index.getValue(fileOf(it.reference)!!.lowercase()) }
        return known + rest
    }

    private fun decode(value: String): String {
        val url = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
        return url
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
    }
}
