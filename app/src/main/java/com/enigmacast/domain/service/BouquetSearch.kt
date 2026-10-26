package com.enigmacast.domain.service

import com.enigmacast.domain.model.BouquetChannelList
import com.enigmacast.domain.model.BouquetEntry

/**
 * Finds the bouquet that holds the receiver's current channel, loading as few channel lists
 * as possible: the hinted bouquet first, then the one the synced cache names, and only then
 * the rest in order. Without a current channel (standby, first connect) it stops at the first
 * bouquet that has channels instead of reading every bouquet on the box.
 */
object BouquetSearch {
    suspend fun find(
        bouquets: List<BouquetEntry>,
        hinted: BouquetEntry?,
        serviceReference: String,
        located: String?,
        channels: suspend (BouquetEntry) -> List<BouquetEntry>,
    ): BouquetChannelList {
        val wanted = serviceReference.isNotBlank()
        val cached = located?.let { ref -> bouquets.firstOrNull { it.reference == ref } }
        val order = (listOfNotNull(hinted, cached) + bouquets).distinctBy { it.reference }
        var fallback: BouquetChannelList? = null
        for (bouquet in order) {
            val list = BouquetChannelList(bouquet.name, bouquet.reference, channels(bouquet))
            if (list.channels.isEmpty()) continue
            if (!wanted) return list
            if (fallback == null) fallback = list
            if (list.channels.any { BouquetChannels.sameService(it.reference, serviceReference) }) return list
        }
        return fallback ?: BouquetChannelList("", "", emptyList())
    }
}
