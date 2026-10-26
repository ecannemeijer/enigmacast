package com.enigmacast.data.openwebif

import com.enigmacast.domain.hotel.HotelFilter
import com.enigmacast.domain.hotel.HotelRuntime
import com.enigmacast.domain.hotel.HotelVisible
import com.enigmacast.domain.hotel.HotelVisibilityLog
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
    override suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry> {
        val list = runCatching { network.televisionBouquets(config) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: cache.televisionBouquets(config).orEmpty()
        val hotel = HotelRuntime.config
        if (!HotelFilter.restrictsBouquets(hotel)) {
            HotelVisibilityLog.bouquets(hotel, HotelVisible(list, 0, list.size, fellBack = false))
            return list
        }
        val visible = HotelFilter.visibleBouquets(list, hotel)
        // A whitelist that matches none of this box's bouquets counts as "everything" for the
        // channel lists too (channelsInBouquet, currentBouquetChannels, search).
        if (visible.fellBack) HotelRuntime.bouquetWhitelistUnmatched(hotel.allowedBouquets)
        else if (list.isNotEmpty()) HotelRuntime.bouquetWhitelistUnmatched(null)
        HotelVisibilityLog.bouquets(hotel, visible)
        return visible.items
    }

    override suspend fun radioBouquets(config: ReceiverConfig): List<BouquetEntry> {
        val list = network.radioBouquets(config)
        val hotel = HotelRuntime.guestConfig
        if (!HotelFilter.restrictsBouquets(hotel)) return list
        // Radio has no fallback of its own: the owner's whitelist holds TV bouquets.
        return list.filter { HotelFilter.bouquetAllowed(it.reference, hotel) }
    }

    override suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        if (!HotelFilter.bouquetAllowed(bouquetReference, HotelRuntime.guestConfig)) throw HiddenBouquetException()
        return cache.channelsInBouquet(config, bouquetReference) ?: network.channelsInBouquet(config, bouquetReference)
    }

    override suspend fun currentBouquetChannels(config: ReceiverConfig): BouquetChannelList {
        if (!HotelFilter.restrictsBouquets(HotelRuntime.config)) return network.currentBouquetChannels(config, cache)
        // Reads the bouquets first: that settles whether the whitelist matches this box at all.
        val tv = runCatching { televisionBouquets(config) }.getOrDefault(emptyList())
        val hotel = HotelRuntime.guestConfig
        val current = runCatching { network.currentBouquetChannels(config, cache) }.getOrNull()
        if (current != null && current.bouquetReference.isNotBlank() && HotelFilter.bouquetAllowed(current.bouquetReference, hotel)) return current
        val first = tv.firstOrNull() ?: radioBouquets(config).firstOrNull()
            ?: return current ?: BouquetChannelList(bouquetName = "", channels = emptyList())
        return BouquetChannelList(first.name, first.reference, channelsInBouquet(config, first.reference))
    }
}

/** Hotel mode: the bouquet is not shown to guests. */
class HiddenBouquetException : IllegalStateException("bouquet hidden in hotel mode")
