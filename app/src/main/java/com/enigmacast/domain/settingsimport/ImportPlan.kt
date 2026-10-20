package com.enigmacast.domain.settingsimport

/**
 * What changes in /etc/enigma2 when a channel list is loaded. Pure, so it is unit tested.
 */
data class ImportPlan(
    /** File name → content to write (to [SettingsFiles.remotePath]). */
    val uploads: Map<String, ByteArray>,
    /** Old list files in /etc/enigma2 to remove after the upload. */
    val deletions: List<String>,
)

object ImportPlans {
    /**
     * A full channel list. bouquets.tv and bouquets.radio are written as the package has them, so
     * the bouquet order is the author's; only our IPTV bouquets that were on the box are appended
     * at the end of bouquets.tv. Only the userbouquets those index files (or a bouquet in them)
     * refer to are written: Enigma2 loads any other userbouquet file it finds ("load unlinked
     * userbouquets", on by default) and puts it on top of bouquets.tv in directory order. Every
     * other userbouquet on the box goes, except our IPTV ones. An old lamedb5 goes too when the
     * package only has lamedb, because Enigma2 prefers lamedb5.
     */
    fun settingsImport(packageFiles: Map<String, ByteArray>, boxFiles: List<String>, boxBouquetsTv: String?): ImportPlan {
        val linked = BouquetLinks.linked(packageFiles)
        val uploads = LinkedHashMap<String, ByteArray>()
        packageFiles.keys.filter { SettingsFiles.isSettingsFile(it) && (!it.startsWith("userbouquet.") || it in linked) }
            .sortedWith(compareBy({ SettingsFiles.uploadOrder(it) }, { it }))
            .forEach { uploads[it] = packageFiles.getValue(it) }
        val keptIptv = boxBouquetsTv?.let { BouquetsTvMerge.iptvFiles(it) }.orEmpty().filter { it in boxFiles }
        uploads["bouquets.tv"]?.let { tv ->
            val text = String(tv, Charsets.UTF_8)
            if (keptIptv.isNotEmpty() || BouquetsTvMerge.iptvFiles(text).isNotEmpty()) {
                uploads["bouquets.tv"] = BouquetsTvMerge.merge(text, keptIptv).toByteArray(Charsets.UTF_8)
            }
        }
        val deletions = boxFiles.filter { name ->
            name !in uploads && when {
                IptvBouquets.isIptvFile(name) -> false
                name.startsWith("userbouquet.") -> SettingsFiles.isEnigmaListFile(name)
                name == "lamedb5" -> "lamedb" in uploads
                else -> false
            }
        }
        return ImportPlan(uploads, deletions)
    }

    /**
     * IPTV only: writes our userbouquets, removes our old ones that are no longer chosen, and
     * rewrites only our lines in bouquets.tv. Satellite bouquets are untouched.
     */
    fun iptv(bouquets: List<IptvBouquets.Bouquet>, boxFiles: List<String>, boxBouquetsTv: String): ImportPlan {
        val uploads = LinkedHashMap<String, ByteArray>()
        bouquets.forEach { uploads[it.fileName] = it.content.toByteArray(Charsets.UTF_8) }
        uploads["bouquets.tv"] = BouquetsTvMerge.merge(boxBouquetsTv, bouquets.map { it.fileName }).toByteArray(Charsets.UTF_8)
        val deletions = boxFiles.filter { IptvBouquets.isIptvFile(it) && it !in uploads }
        return ImportPlan(uploads, deletions)
    }
}

/** Which userbouquet files a package's bouquets.tv / bouquets.radio actually use. */
object BouquetLinks {
    private val quoted = Regex("""FROM BOUQUET\s+"([^"]+)"""")

    /** Files named by `FROM BOUQUET "…"` lines, in file order. */
    fun referenced(text: String): List<String> = text.lineSequence()
        .filter { it.trimStart().startsWith("#SERVICE") }
        .mapNotNull { quoted.find(it)?.groupValues?.get(1)?.substringAfterLast('/') }
        .toList()

    /**
     * Userbouquets reachable from bouquets.tv and bouquets.radio, sub-bouquets included. When a
     * package has no index for .tv or .radio, all its files of that kind count as linked.
     */
    fun linked(files: Map<String, ByteArray>): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        for ((index, ext) in listOf("bouquets.tv" to ".tv", "bouquets.radio" to ".radio")) {
            val text = files[index]?.toString(Charsets.UTF_8)
            if (text == null) files.keys.filter { it.startsWith("userbouquet.") && it.endsWith(ext) }.forEach { queue += it }
            else queue += referenced(text)
        }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val bytes = files[name] ?: continue
            if (out.add(name)) queue += referenced(bytes.toString(Charsets.UTF_8))
        }
        return out
    }
}
