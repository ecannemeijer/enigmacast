package com.enigmacast.domain.sync

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig

/** The synced guide and channel lists. null = not cached, ask the receiver. */
interface GuideCacheReader {
    suspend fun bouquetEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long): List<GuideEvent>?
    suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String): List<GuideEvent>?
    suspend fun televisionBouquets(receiver: ReceiverConfig): List<BouquetEntry>?
    suspend fun channelsInBouquet(receiver: ReceiverConfig, bouquetReference: String): List<BouquetEntry>?

    /** The first synced TV bouquet (menu order) that contains [serviceReference], or null. */
    suspend fun bouquetOf(receiver: ReceiverConfig, serviceReference: String): BouquetEntry? = null

    /**
     * Borrowed EPG for the IPTV channels of [bouquetReference] (from satellite channels with the
     * same name), with each event's reference set to the IPTV channel.
     */
    suspend fun aliasEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long): List<GuideEvent> = emptyList()

    object None : GuideCacheReader {
        override suspend fun bouquetEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long): List<GuideEvent>? = null
        override suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String): List<GuideEvent>? = null
        override suspend fun televisionBouquets(receiver: ReceiverConfig): List<BouquetEntry>? = null
        override suspend fun channelsInBouquet(receiver: ReceiverConfig, bouquetReference: String): List<BouquetEntry>? = null
    }
}
