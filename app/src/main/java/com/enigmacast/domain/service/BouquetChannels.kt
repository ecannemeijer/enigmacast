package com.enigmacast.domain.service

/**
 * Enigma service references look like `1:0:19:sid:...`.
 * Field 1 (flags) is 0 for a channel, 7 for a bouquet and 64 for a marker.
 * Field 2 is the DVB service type. Bouquet section titles in this app's lists use a
 * type outside the normal television and radio set, such as `21E` for `+++Friesland+++`.
 */
object BouquetChannels {
    fun isPlayableChannel(reference: String): Boolean {
        if (field(reference, 1) != "0") return false
        if (ServiceStreams.isStreamPlayer(reference)) return true
        if (field(reference, 0) != "1") return false
        val type = field(reference, 2)?.uppercase() ?: return false
        return type in PLAYABLE_SERVICE_TYPES
    }

    /** A bouquet heading such as `+++Friesland+++`, not a channel. */
    fun isSectionLabel(name: String): Boolean {
        val text = name.trim()
        return text.length > 6 && text.startsWith("+++") && text.endsWith("+++")
    }

    fun isTelevisionBouquet(reference: String): Boolean {
        return field(reference, 1) == "7" && field(reference, 2) == "1"
    }

    fun isRadioBouquet(reference: String): Boolean {
        return field(reference, 1) == "7" && field(reference, 2) == "2"
    }

    /**
     * DVB services match on the first ten fields, so a name after the reference does not
     * split one channel into two. Internet streams often reuse those fields and differ only
     * by the URL, so those match on the normalized stream address instead.
     */
    fun sameService(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        val leftUrl = ServiceStreams.internetUrl(left)
        val rightUrl = ServiceStreams.internetUrl(right)
        if (leftUrl != null || rightUrl != null) return leftUrl != null && leftUrl == rightUrl
        return identity(left) == identity(right)
    }

    private fun identity(reference: String): String {
        return reference.split(':').take(10).joinToString(":")
    }

    private fun field(reference: String, index: Int): String? {
        return reference.split(':').getOrNull(index)
    }

    /** DVB service types Enigma2 will tune: SD, HD, radio and the usual codec variants. */
    private val PLAYABLE_SERVICE_TYPES = setOf(
        "1", "2", "4", "5", "6", "A", "B", "10", "11", "16", "17", "18",
        "19", "1A", "1B", "1F", "20", "86", "87", "C3",
    )
}
