package com.enigmacast.domain.sync

import java.security.MessageDigest

/** One bouquet as the box returns it, with its channel references in order. */
data class BouquetSnapshot(val reference: String, val name: String, val channels: List<String>)

/**
 * The fingerprint of a receiver's channel lists: bouquet order, references, names and the
 * service references in each bouquet. Any rename, reorder, addition or removal changes it.
 */
object ChannelListHash {
    fun of(bouquets: List<BouquetSnapshot>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        bouquets.forEach { b ->
            digest.update("B\u0000${b.reference}\u0000${b.name}\u0001".toByteArray())
            b.channels.forEach { digest.update("C\u0000$it\u0001".toByteArray()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What a channel check does with a freshly fetched list. Pure for tests. */
object ChannelSyncDecision {
    enum class Action { REPLACE, KEEP }

    /** Forced, empty cache, unknown stored hash or a different hash: replace everything. */
    fun decide(force: Boolean, hasCache: Boolean, storedHash: String, freshHash: String): Action =
        if (force || !hasCache || storedHash.isBlank() || storedHash != freshHash) Action.REPLACE else Action.KEEP

    /**
     * After a receiver switch: when the new box has exactly the same lists as the previous one,
     * its Home selection and channel memory carry over (only where the new box has none yet).
     */
    fun carryOver(previousHash: String, newHash: String): Boolean =
        previousHash.isNotBlank() && previousHash == newHash
}
