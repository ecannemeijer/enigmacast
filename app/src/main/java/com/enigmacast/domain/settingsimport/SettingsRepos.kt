package com.enigmacast.domain.settingsimport

/**
 * The GitHub repositories with ready-made Enigma2 channel lists. All three keep one folder per
 * tuner configuration at the top of the repository, holding the plain Enigma2 files
 * (lamedb, bouquets.tv/radio, userbouquet.*, blacklist, whitelist and, except Hans, satellites.xml).
 */
enum class SettingsProvider(
    val title: String,
    val owner: String,
    val repo: String,
    val branch: String,
    /** Folder name prefix that marks a tuner configuration; stripped for the display name. */
    val folderPrefix: String,
) {
    MORPHEUS("Morpheus883", "morpheus883", "enigma2-settings", "master", "morph883_"),
    CIEFP("Ciefp", "ciefp", "ciefpsettings-enigma2", "master", "ciefp-E2-"),
    HANS("Hans", "OpenVisionE2", "Hans-settings", "master", "e2_hanssettings_"),
    ;

    val treeUrl: String get() = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"

    fun rawUrl(path: String): String =
        "https://raw.githubusercontent.com/$owner/$repo/$branch/" + path.split('/').joinToString("/") { encodePathSegment(it) }

    fun lastCommitUrl(folder: String): String =
        "https://api.github.com/repos/$owner/$repo/commits?per_page=1&path=" + encodePathSegment(folder)

    companion object {
        fun byName(name: String?): SettingsProvider? = entries.firstOrNull { it.name == name }

        internal fun encodePathSegment(segment: String): String = buildString {
            segment.toByteArray(Charsets.UTF_8).forEach { b ->
                val c = (b.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c) else append("%%%02X".format(b.toInt() and 0xFF))
            }
        }
    }
}

/** One tuner configuration (one folder) in a provider's repository. */
data class TunerPackage(
    val provider: SettingsProvider,
    val folder: String,
    val files: List<String>,
) {
    val id: String get() = "${provider.name}/$folder"

    /** "13E · 19.2E · 23.5E", "Motor", "10sat 39E · 28E …". */
    val displayName: String get() = SettingsRepoLayout.displayName(provider, folder)

    val tvBouquets: Int get() = files.count { it.startsWith("userbouquet.") && it.endsWith(".tv") }
    val radioBouquets: Int get() = files.count { it.startsWith("userbouquet.") && it.endsWith(".radio") }
    val hasSatellitesXml: Boolean get() = "satellites.xml" in files

    /** The files that go to the receiver, in upload order: bouquets first, lamedb last. */
    val uploadFiles: List<String> get() = files.filter { SettingsFiles.isSettingsFile(it) }
        .sortedWith(compareBy({ SettingsFiles.uploadOrder(it) }, { it }))
}

/** Which files of an Enigma2 channel list we read, back up and write. */
object SettingsFiles {
    const val ENIGMA_DIR = "/etc/enigma2"
    const val TUXBOX_DIR = "/etc/tuxbox"
    const val SATELLITES = "satellites.xml"

    private val fixed = setOf("lamedb", "lamedb5", "bouquets.tv", "bouquets.radio", "blacklist", "whitelist", SATELLITES)

    fun isSettingsFile(name: String): Boolean =
        name in fixed || (name.startsWith("userbouquet.") && (name.endsWith(".tv") || name.endsWith(".radio")))

    /** Files in /etc/enigma2 that belong to the channel list (backup + cleanup), satellites.xml lives in /etc/tuxbox. */
    fun isEnigmaListFile(name: String): Boolean = isSettingsFile(name) && name != SATELLITES

    fun remotePath(name: String): String = if (name == SATELLITES) "$TUXBOX_DIR/$name" else "$ENIGMA_DIR/$name"

    fun uploadOrder(name: String): Int = when {
        name.startsWith("userbouquet.") -> 0
        name == "bouquets.tv" || name == "bouquets.radio" -> 1
        name == "blacklist" || name == "whitelist" -> 2
        name == SATELLITES -> 3
        else -> 4 // lamedb / lamedb5 last
    }
}

/** Turns a GitHub git tree (recursive) into the provider's tuner configurations. */
object SettingsRepoLayout {
    data class TreeEntry(val path: String, val type: String)

    fun packages(provider: SettingsProvider, tree: List<TreeEntry>): List<TunerPackage> {
        val byFolder = linkedMapOf<String, MutableList<String>>()
        tree.asSequence()
            .filter { it.type == "blob" }
            .mapNotNull { entry ->
                val parts = entry.path.split('/')
                if (parts.size != 2) null else parts[0] to parts[1]
            }
            .filter { (folder, _) -> !folder.startsWith(".") }
            .forEach { (folder, file) -> byFolder.getOrPut(folder) { mutableListOf() }.add(file) }
        return byFolder
            .filter { (_, files) -> ("lamedb" in files || "lamedb5" in files) && "bouquets.tv" in files }
            .map { (folder, files) -> TunerPackage(provider, folder, files.sorted()) }
            .sortedWith(compareBy({ sortKey(provider, it.folder) }, { it.folder.lowercase() }))
    }

    fun displayName(provider: SettingsProvider, folder: String): String {
        val core = folder.removePrefix(provider.folderPrefix)
        val parts = core.split('-', '_').filter { it.isNotBlank() }
        return parts.joinToString(" · ") { part ->
            when {
                part.equals("motor", true) || part.equals("rotating", true) -> part.replaceFirstChar { it.uppercase() }
                part.equals("AND", false) -> "+"
                Regex("""\d+(\.\d+)?[ewEW]""").matches(part) -> part.uppercase()
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }.replace(" · + · ", " + ")
    }

    /** Ciefp numbers its sets "1sat", "10sat": sort numerically instead of 1, 10, 13, 2. */
    private fun sortKey(provider: SettingsProvider, folder: String): Int {
        if (provider != SettingsProvider.CIEFP) return 0
        val n = Regex("""(\d+)sat""").find(folder)?.groupValues?.get(1)?.toIntOrNull()
        return n ?: 999
    }
}
