package com.enigmacast.domain.settingsimport

/**
 * The GitHub repositories with ready-made Enigma2 channel lists, as data: one entry per
 * repository. Every repository keeps one folder per tuner configuration (the variants) at
 * [root], holding the plain Enigma2 files (lamedb or lamedb5, bouquets.tv/radio,
 * userbouquet.*, alternatives.*, blacklist, whitelist and sometimes satellites.xml). The
 * variants are not listed here: they are read from the repository at runtime, so a list
 * that is added, renamed or removed upstream shows up without an app update. Nothing of a
 * list is bundled in the app.
 *
 * The enum name is stored (`settingsPackageId` = "NAME/folder"), so never rename an entry.
 */
enum class SettingsProvider(
    /** Shown as is in every language. */
    val title: String,
    val owner: String,
    val repo: String,
    val branch: String,
    /** Folder name prefix that marks a tuner configuration; stripped for the display name. */
    val folderPrefix: String,
    /** Folder name suffix; stripped for the display name. */
    val folderSuffix: String = "",
    /** Characters between the words of a folder name. */
    val nameSeparators: String = "-_",
    /** Between the words of the display name. */
    val nameJoin: String = " · ",
    /** How the variants are ordered. */
    val order: VariantOrder = VariantOrder.NAME,
    /** Folder that holds the variant folders; "" = the top of the repository. */
    val root: String = "",
) {
    MORPHEUS("Morpheus883", "morpheus883", "enigma2-settings", "master", "morph883_"),
    CIEFP("Ciefp", "ciefp", "ciefpsettings-enigma2", "master", "ciefp-E2-", order = VariantOrder.SAT_PREFIX),
    HANS("Hans", "OpenVisionE2", "Hans-settings", "master", "e2_hanssettings_"),
    VHANNIBAL("Vhannibal", "OpenVisionE2", "Vhannibal-settings", "master", "vhannibal.", nameSeparators = ".", nameJoin = " "),
    GIOPPYGIO("GioppyGio", "OpenVisionE2", "GioppyGio-settings", "master", "GioppyGio_E2_", nameSeparators = "_", order = VariantOrder.SAT_COUNT),
    MATZE("Matze", "OpenVisionE2", "Matze-settings", "master", "enigma2-plugin-settings-matze-"),
    DONA("Dona", "OpenVisionE2", "Dona-settings", "master", "", folderSuffix = "_Dona_E2", nameSeparators = "_", order = VariantOrder.SAT_COUNT),
    PREDRAG("Predrag", "OpenVisionE2", "Predrag-settings", "master", "Predrag_", folderSuffix = "_E2", nameSeparators = "_", order = VariantOrder.SAT_COUNT),
    ;

    val repoPath: String get() = "$owner/$repo"
    val webUrl: String get() = "https://github.com/$owner/$repo"
    val treeUrl: String get() = treeUrl(branch)
    val repoUrl: String get() = "https://api.github.com/repos/$owner/$repo"

    fun treeUrl(branch: String): String = "https://api.github.com/repos/$owner/$repo/git/trees/${encodePathSegment(branch)}?recursive=1"

    fun rawUrl(path: String, branch: String = this.branch): String =
        "https://raw.githubusercontent.com/$owner/$repo/${encodePathSegment(branch)}/" + path.split('/').joinToString("/") { encodePathSegment(it) }

    fun lastCommitUrl(folder: String): String =
        "https://api.github.com/repos/$owner/$repo/commits?per_page=1&path=" + encodePathSegment(folder)

    /** The newest commit on the branch: the date shown in the list picker. */
    fun repoCommitUrl(branch: String = this.branch): String =
        "https://api.github.com/repos/$owner/$repo/commits?per_page=1&sha=" + encodePathSegment(branch)

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

enum class VariantOrder {
    /** Alphabetical. */
    NAME,
    /** Ciefp: "1sat", "2satA" … "10sat" numerically. */
    SAT_PREFIX,
    /** By the number of positions in the name (13E+19E = 2), motor lists last. */
    SAT_COUNT,
}

/** One tuner configuration (one folder) in a provider's repository. */
data class TunerPackage(
    val provider: SettingsProvider,
    /** Path of the variant folder in the repository (below [SettingsProvider.root] when set). */
    val folder: String,
    val files: List<String>,
    /** The branch the files were listed from: the provider's, or the repository's default when that was renamed. */
    val branch: String = provider.branch,
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
        name in fixed || isBouquetFile(name, "userbouquet.") || isAlternatives(name)

    /**
     * `alternatives.*.tv` / `.radio`: a channel with fall-back services, used from a userbouquet
     * with `FROM BOUQUET "alternatives.…"` (Vhannibal). Written only when a bouquet uses it.
     */
    fun isAlternatives(name: String): Boolean = isBouquetFile(name, "alternatives.")

    private fun isBouquetFile(name: String, prefix: String): Boolean =
        name.startsWith(prefix) && name.length > prefix.length && (name.endsWith(".tv") || name.endsWith(".radio"))

    /** Files in /etc/enigma2 that belong to the channel list (backup + cleanup), satellites.xml lives in /etc/tuxbox. */
    fun isEnigmaListFile(name: String): Boolean = isSettingsFile(name) && name != SATELLITES

    fun remotePath(name: String): String = if (name == SATELLITES) "$TUXBOX_DIR/$name" else "$ENIGMA_DIR/$name"

    fun uploadOrder(name: String): Int = when {
        name.startsWith("userbouquet.") || isAlternatives(name) -> 0
        name == "bouquets.tv" || name == "bouquets.radio" -> 1
        name == "blacklist" || name == "whitelist" -> 2
        name == SATELLITES -> 3
        else -> 4 // lamedb / lamedb5 last
    }
}

/** Turns a GitHub git tree (recursive) into the provider's tuner configurations. */
object SettingsRepoLayout {
    data class TreeEntry(val path: String, val type: String)

    fun packages(provider: SettingsProvider, tree: List<TreeEntry>, branch: String = provider.branch): List<TunerPackage> {
        val root = provider.root.trim('/')
        val base = if (root.isEmpty()) "" else "$root/"
        val byFolder = linkedMapOf<String, MutableList<String>>()
        tree.asSequence()
            .filter { it.type == "blob" && it.path.startsWith(base) }
            .mapNotNull { entry ->
                val parts = entry.path.removePrefix(base).split('/')
                if (parts.size != 2) null else parts[0] to parts[1]
            }
            .filter { (folder, _) -> !folder.startsWith(".") }
            .forEach { (folder, file) -> byFolder.getOrPut(folder) { mutableListOf() }.add(file) }
        // A list needs its channel database and the TV index; that also skips helper folders
        // such as the "CI" scripts every OpenVisionE2 repository has.
        return byFolder
            .filter { (_, files) -> ("lamedb" in files || "lamedb5" in files) && "bouquets.tv" in files }
            .map { (folder, files) -> TunerPackage(provider, base + folder, files.sorted(), branch) }
            .sortedWith(compareBy({ sortKey(provider, it.folder) }, { it.folder.lowercase() }))
    }

    private val position = Regex("""\d+(\.\d+)?[ewEW]""")
    private val positions = Regex("""\d+(\.\d+)?[ewEW]([+-]\d+(\.\d+)?[ewEW])*""")

    /** Words that read better spelled out or in capitals. */
    private val words = mapOf("hb" to "Hot Bird", "hotbird" to "Hot Bird", "dtt" to "DTT")

    fun displayName(provider: SettingsProvider, folder: String): String {
        val core = folder.substringAfterLast('/').removePrefix(provider.folderPrefix).removeSuffix(provider.folderSuffix)
        val parts = core.split(*provider.nameSeparators.toCharArray()).filter { it.isNotBlank() }
        val name = parts.joinToString(provider.nameJoin) { part ->
            when {
                part.equals("motor", true) || part.equals("rotating", true) -> part.lowercase().replaceFirstChar { it.uppercase() }
                part == "AND" || (part == "and" && provider.nameSeparators == ".") -> "+"
                positions.matches(part) -> part.uppercase()
                part.lowercase() in words -> words.getValue(part.lowercase())
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }
        return name.replace("${provider.nameJoin}+${provider.nameJoin}", " + ").ifBlank { folder }
    }

    private fun sortKey(provider: SettingsProvider, folder: String): Int = when (provider.order) {
        VariantOrder.NAME -> 0
        // Ciefp numbers its sets "1sat", "10sat": sort numerically instead of 1, 10, 13, 2.
        VariantOrder.SAT_PREFIX -> Regex("""(\d+)sat""").find(folder)?.groupValues?.get(1)?.toIntOrNull() ?: 999
        VariantOrder.SAT_COUNT -> if (folder.contains("motor", true)) 999 else position.findAll(folder).count()
    }
}
