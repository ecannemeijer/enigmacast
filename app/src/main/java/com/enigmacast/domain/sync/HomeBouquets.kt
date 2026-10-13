package com.enigmacast.domain.sync

import com.enigmacast.domain.tuner.NowEvents

/** A channel in a bouquet, as far as the home selection cares. */
data class BouquetChannel(val bouquetReference: String, val reference: String, val name: String, val picon: String)

/**
 * Which bouquets feed the Home films. Stored per receiver as the chosen references, or null
 * for "all TV bouquets" (the default, which also picks up bouquets added later).
 */
object HomeBouquets {
    /** The selected references among [all]; null stored = everything. Unknown stored references are dropped. */
    fun effective(stored: Set<String>?, all: List<String>): Set<String> {
        if (stored == null) return all.toSet()
        val kept = stored.intersect(all.toSet())
        // None of the remembered bouquets exist on this box any more (renamed/removed, other box): show all.
        return if (kept.isEmpty() && stored.isNotEmpty()) all.toSet() else kept
    }

    /** Channels of the selected bouquets, once each (the first bouquet in list order wins), keyed by service key. */
    fun channels(channels: List<BouquetChannel>, selected: Set<String>): Map<String, BouquetChannel> {
        val result = LinkedHashMap<String, BouquetChannel>()
        channels.filter { it.bouquetReference in selected }.forEach { result.putIfAbsent(NowEvents.key(it.reference), it) }
        return result
    }

    /** What to store after a toggle: back to null when everything is selected again. */
    fun toggle(current: Set<String>, reference: String, all: List<String>): Set<String>? {
        val next = if (reference in current) current - reference else current + reference
        return if (next.containsAll(all)) null else next
    }
}

/** Whether the guide reads a time window from Room or asks the receiver. */
object GuideCachePolicy {
    /**
     * Use the cache when it has programmes in the window. Otherwise go to the network; when the
     * window lies within the synced days (today .. [horizonSec]) a sync is due as well.
     */
    fun decide(cachedEvents: Int, fromSec: Long, toSec: Long, todayStartSec: Long, horizonSec: Long): Decision = when {
        cachedEvents > 0 -> Decision.CACHE
        toSec > todayStartSec && fromSec < horizonSec -> Decision.NETWORK_AND_SYNC
        else -> Decision.NETWORK
    }

    enum class Decision { CACHE, NETWORK, NETWORK_AND_SYNC }
}
