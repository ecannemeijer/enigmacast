package com.enigmacast.live

import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.search.ListedChannel
import com.enigmacast.domain.search.SearchGrouping
import com.enigmacast.domain.search.SearchQuery
import com.enigmacast.domain.search.SearchResults
import com.enigmacast.domain.source.EpgSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Where a service sits: its bouquet and that bouquet's channels, for zapping and CH+/CH−. */
data class ChannelPlace(
    val bouquet: BouquetEntry,
    val channel: BouquetEntry,
    val channels: List<BouquetEntry>,
)

/**
 * One search over live and future EPG, recordings and the bouquet lists.
 * Channel lists are loaded once and kept for a few minutes.
 */
@Singleton
class ProgrammeSearch @Inject constructor(
    private val epg: EpgSource,
    private val services: ServiceStateSource,
    private val settings: SettingsRepository,
    private val cache: CacheSearch,
) {
    private val gate = Mutex()
    private var catalog: Catalog? = null

    suspend fun search(query: String, recordings: List<Recording>, nowSec: Long): SearchResults {
        val parsed = SearchQuery.parse(query, nowSec)
        if (!parsed.isSearchable) return SearchResults()
        val receiver = settings.current().activeReceiver() ?: return SearchResults()
        // The synced Room cache first: fast and offline. The box only when nothing is cached yet.
        val index = runCatching { cache.index(receiver, nowSec * 1000) }.getOrNull()
        if (index != null && !index.isEmpty) {
            val found = if (parsed.text.length < SearchGrouping.MIN_QUERY) emptyList() else cache.programmes(index, parsed.text)
            return SearchGrouping.group(parsed, nowSec, found, recordings, index.bouquets, index.channels)
        }
        val programmes = if (parsed.text.length < SearchGrouping.MIN_QUERY) {
            emptyList()
        } else {
            try {
                epg.search(receiver, parsed.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EnigmaLog.service("epg search failed: ${error.javaClass.simpleName}")
                emptyList()
            }
        }
        val lists = catalog(nowSec * 1000)
        return SearchGrouping.group(parsed, nowSec, programmes, recordings, lists.bouquets, lists.channels)
    }

    /** The bouquet and its channels that contain [serviceReference], or null when none does. */
    suspend fun placeOf(serviceReference: String): ChannelPlace? {
        if (serviceReference.isBlank()) return null
        val receiver = settings.current().activeReceiver()
        val index = receiver?.let { runCatching { cache.index(it, System.currentTimeMillis()) }.getOrNull() }
        if (index != null && index.channels.isNotEmpty()) {
            SearchGrouping.placeOf(serviceReference, index.channels)?.let { listed ->
                val channels = index.channels.filter { it.bouquet.reference == listed.bouquet.reference }.map { it.channel }
                return ChannelPlace(listed.bouquet, listed.channel, channels)
            }
        }
        val lists = catalog(System.currentTimeMillis())
        val listed = SearchGrouping.placeOf(serviceReference, lists.channels) ?: return null
        val channels = lists.channels
            .filter { it.bouquet.reference == listed.bouquet.reference }
            .map { it.channel }
        return ChannelPlace(listed.bouquet, listed.channel, channels)
    }

    private suspend fun catalog(nowMs: Long): Catalog {
        gate.withLock {
            val cached = catalog
            if (cached != null && nowMs - cached.loadedAtMs < CATALOG_TTL_MS) return cached
            val receiver = settings.current().activeReceiver()
            if (receiver == null) return Catalog(nowMs, emptyList(), emptyList()).also { catalog = it }
            val bouquets = try {
                services.televisionBouquets(receiver) + services.radioBouquets(receiver)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EnigmaLog.service("search bouquets failed: ${error.javaClass.simpleName}")
                emptyList()
            }
            val channels = ArrayList<ListedChannel>()
            for (bouquet in bouquets) {
                val list = try {
                    services.channelsInBouquet(receiver, bouquet.reference)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    EnigmaLog.service("search channels ${bouquet.name} failed: ${error.javaClass.simpleName}")
                    emptyList()
                }
                list.forEach { channel -> channels += ListedChannel(bouquet, channel) }
            }
            return Catalog(nowMs, bouquets, channels).also { catalog = it }
        }
    }

    private data class Catalog(
        val loadedAtMs: Long,
        val bouquets: List<BouquetEntry>,
        val channels: List<ListedChannel>,
    )

    private companion object {
        const val CATALOG_TTL_MS = 5 * 60 * 1000L
    }
}
