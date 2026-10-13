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
     * A full channel list: every list file of the package is written. Old userbouquets that the
     * new list does not have are removed, so stale bouquets do not pile up. An old lamedb5 goes too
     * when the package only has lamedb, because Enigma2 prefers lamedb5. Our IPTV bouquets stay and
     * are appended to the new bouquets.tv again.
     */
    fun settingsImport(packageFiles: Map<String, ByteArray>, boxFiles: List<String>, boxBouquetsTv: String?): ImportPlan {
        val uploads = LinkedHashMap<String, ByteArray>()
        packageFiles.keys.filter { SettingsFiles.isSettingsFile(it) }
            .sortedWith(compareBy({ SettingsFiles.uploadOrder(it) }, { it }))
            .forEach { uploads[it] = packageFiles.getValue(it) }
        val keptIptv = boxBouquetsTv?.let { BouquetsTvMerge.iptvFiles(it) }.orEmpty().filter { it in boxFiles }
        uploads["bouquets.tv"]?.let { tv ->
            uploads["bouquets.tv"] = BouquetsTvMerge.merge(String(tv, Charsets.UTF_8), keptIptv).toByteArray(Charsets.UTF_8)
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
