package com.enigmacast.domain.service

/**
 * Enigma service references look like `1:0:19:sid:...`.
 * Field 2 is the type: 0 is a channel, 7 is a bouquet, 64 is a marker.
 * Field 3 on a bouquet is 1 for television and 2 for radio.
 */
object BouquetChannels {
    fun isPlayableChannel(reference: String): Boolean = field(reference, 1) == "0"

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
}
