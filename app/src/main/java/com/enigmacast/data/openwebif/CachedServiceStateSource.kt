package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.sync.GuideCacheReader
import javax.inject.Inject

/** TV bouquets and channel lists from the Room cache, the receiver when the cache has none. Everything else is live. */
class CachedServiceStateSource @Inject constructor(
    private val network: OpenWebifServiceStateSource,
    private val cache: GuideCacheReader,
) : ServiceStateSource by network {
    override suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry> =
        cache.televisionBouquets(config) ?: network.televisionBouquets(config)

    override suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> =
        cache.channelsInBouquet(config, bouquetReference) ?: network.channelsInBouquet(config, bouquetReference)
}
