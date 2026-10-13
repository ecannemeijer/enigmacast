package com.enigmacast.domain.settingsimport

/**
 * After `servicelistreload` Enigma2 rebuilds its lists on its own thread. These checks tell
 * from OpenWebif's bouquet list (`/api/getservices` of bouquets.tv) whether the new lists are live.
 */
object ReloadCheck {
    private val quoted = Regex("\"([^\"]+)\"")

    /** The userbouquet files the uploaded bouquets.tv refers to, or null when it was not uploaded. */
    fun expectedFiles(uploads: Map<String, ByteArray>): Set<String>? =
        uploads["bouquets.tv"]?.toString(Charsets.UTF_8)?.lineSequence()
            ?.filter { it.startsWith("#SERVICE") && it.contains("FROM BOUQUET") }
            ?.mapNotNull { quoted.find(it)?.groupValues?.get(1) }
            ?.toSet()

    /** True when every expected file is listed and no removed .tv file is listed any more. */
    fun isLive(body: String, expected: Set<String>, removed: Collection<String>): Boolean {
        val text = body.replace("\\\"", "\"")
        return expected.all { text.contains("\"$it\"") } &&
            removed.filter { it.endsWith(".tv") }.none { text.contains("\"$it\"") }
    }
}
