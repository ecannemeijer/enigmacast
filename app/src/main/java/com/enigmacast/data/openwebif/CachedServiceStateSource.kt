package com.enigmacast.data.openwebif

import com.enigmacast.domain.hotel.HotelFilter
import com.enigmacast.domain.hotel.HotelRuntime
import com.enigmacast.domain.model.BouquetChannelList
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.sync.GuideCacheReader
import javax.inject.Inject

/**
 * Channel lists come from the Room cache, and from the receiver when the cache has none.
 * The bouquet list does not: Room keeps the order `/api/bouquets` happened to return, and
 * that is not bouquets.tv. The panel asks the box each time.
 *
 * Hotel mode: only the bouquets the owner allowed exist for the guest (panels, zapping,
 * search, guide). A hidden bouquet's channels are refused, so the player falls back to an allowed one.
 */
class CachedServiceStateSource @Inject constructor(
    private val network: OpenWebifServiceStateSource,
    private val cache: GuideCacheReader,
) : ServiceStateSource by network {
    override suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry> =
        HotelFilter.bouquets(
            runCatching { network.televisionBouquets(config) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: cache.televisionBouquets(config).orEmpty(),
            HotelRuntime.config,
        )

    override suspend fun radioBouquets(config: ReceiverConfig): List<BouquetEntry> =
        HotelFilter.bouquets(network.radioBouquets(config), HotelRuntime.config)

    override suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        if (!HotelFilter.bouquetAllowed(bouquetReference, HotelRuntime.config)) throw HiddenBouquetException()
        return cache.channelsInBouquet(config, bouquetReference) ?: network.channelsInBouquet(config, bouquetReference)
    }

    override suspend fun currentBouquetChannels(config: ReceiverConfig): BouquetChannelList {
        val hotel = HotelRuntime.config
        if (!HotelFilter.restrictsBouquets(hotel)) return network.currentBouquetChannels(config)
        val current = runCatching { network.currentBouquetChannels(config) }.getOrNull()
        if (current != null && current.bouquetReference.isNotBlank() && HotelFilter.bouquetAllowed(current.bouquetReference, hotel)) return current
        val first = televisionBouquets(config).firstOrNull() ?: radioBouquets(config).firstOrNull()
            ?: return BouquetChannelList(bouquetName = "", channels = emptyList())
        return BouquetChannelList(first.name, first.reference, channelsInBouquet(config, first.reference))
    }
}

/** Hotel mode: the bouquet is not shown to guests. */
class HiddenBouquetException : IllegalStateException("bouquet hidden in hotel mode")
