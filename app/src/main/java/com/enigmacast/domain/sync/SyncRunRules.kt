package com.enigmacast.domain.sync

/**
 * Small rules for how sync runs are started and grouped. Pure Kotlin so they run in JVM unit tests.
 */
object SyncRunRules {
    /** A foreground request (pairing, Sync now) may ride along with a run that started this recently. */
    const val JOIN_WINDOW_MS = 10_000L

    /** Version checks of the same receiver within this window reuse the last answer. */
    const val CHECK_REUSE_MS = 30_000L

    /** Bound for the pairing steps (certificate, join, profiles) before the card shows an error. */
    const val PAIR_TIMEOUT_MS = 30_000L

    /**
     * True when a foreground request can wait for the run in progress instead of queueing a new
     * full run: that run pulls and started at most [JOIN_WINDOW_MS] ago, so it sees the same state.
     */
    fun canJoin(runStartedMs: Long?, runPulls: Boolean, nowMs: Long, windowMs: Long = JOIN_WINDOW_MS): Boolean =
        runStartedMs != null && runPulls && nowMs >= runStartedMs && nowMs - runStartedMs <= windowMs

    /** True when the last version check of [host] is recent enough to answer again without the network. */
    fun reuseCheck(lastHost: String?, lastAtMs: Long, host: String, nowMs: Long, windowMs: Long = CHECK_REUSE_MS): Boolean =
        lastHost != null && lastHost == host && lastAtMs > 0 && nowMs >= lastAtMs && nowMs - lastAtMs < windowMs

    /**
     * Splits [items] into consecutive groups with the same [batched] value, keeping the order:
     * library rows of a pull page are written in one database transaction, while settings and
     * playlist documents (DataStore, other stores) are applied on their own between them.
     */
    fun <T> groups(items: List<T>, batched: (T) -> Boolean): List<Pair<Boolean, List<T>>> {
        val result = ArrayList<Pair<Boolean, List<T>>>()
        var current = ArrayList<T>()
        var flag: Boolean? = null
        items.forEach { item ->
            val b = batched(item)
            if (flag != null && b != flag) {
                result += flag!! to current
                current = ArrayList()
            }
            flag = b
            current += item
        }
        if (flag != null && current.isNotEmpty()) result += flag!! to current
        return result
    }
}
