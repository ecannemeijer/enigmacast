package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.sync.GuideCacheReader
import javax.inject.Inject

/**
 * Channel lists come from the Room cache, and from the receiver when the cache has none.
 * The bouquet list does not: Room keeps the order `/api/bouquets` happened to return, and
 * that is not bouquets.tv. The panel asks the box each time.
 */
class CachedServiceStateSource @Inject constructor(
    private val network: OpenWebifServiceStateSource,
    private val cache: GuideCacheReader,
) : ServiceStateSource by network {
    override suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry> =
        runCatching { network.televisionBouquets(config) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: cache.televisionBouquets(config).orEmpty()

    override suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> =
        cache.channelsInBouquet(config, bouquetReference) ?: network.channelsInBouquet(config, bouquetReference)
}
