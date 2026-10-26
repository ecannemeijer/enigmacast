package com.enigmacast.live

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.search.EpgSearchEvent
import com.enigmacast.domain.search.ListedChannel
import com.enigmacast.domain.search.SearchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search over what the sync stored in Room: bouquets, channels, the guide and the TMDB titles
 * of its films. The folded (lower-case, accent-free) texts are kept in memory for a few
 * minutes, so typing a query does not touch the database or the box.
 */
@Singleton
class CacheSearch @Inject constructor(private val dao: CacheDao) {
    class Index(
        val loadedAtMs: Long,
        val receiverKey: String,
        val bouquets: List<BouquetEntry>,
        val channels: List<ListedChannel>,
        val events: List<Pair<EpgSearchEvent, String>>,
        /** Hotel mode's bouquet whitelist the index was built with (null = everything). */
        val allowed: Set<String>? = null,
    ) {
        val isEmpty get() = channels.isEmpty() && events.isEmpty()
    }

    private val gate = Mutex()
    private var index: Index? = null

    suspend fun index(receiver: ReceiverConfig, nowMs: Long): Index = gate.withLock {
        val rk = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
        val hotel = com.enigmacast.domain.hotel.HotelRuntime.guestConfig
        val allowed = hotel.allowedBouquets.takeIf { com.enigmacast.domain.hotel.HotelFilter.restrictsBouquets(hotel) }
        index?.takeIf { it.receiverKey == rk && nowMs - it.loadedAtMs < TTL_MS && it.allowed == allowed }?.let { return it }
        withContext(Dispatchers.IO) {
            val nowSec = nowMs / 1000
            val bouquetRows = dao.bouquets(rk)
            // Hotel mode: hidden bouquets, their channels and their programmes are not searchable.
            val bouquets = bouquetRows.map { BouquetEntry(it.name, it.reference) }
                .filter { allowed == null || com.enigmacast.domain.hotel.HotelFilter.bouquetAllowed(it.reference, hotel) }
            val byRef = bouquets.associateBy { it.reference }
            val channelRows = dao.channels(rk).let { rows -> if (allowed == null) rows else rows.filter { com.enigmacast.domain.hotel.HotelFilter.bouquetAllowed(it.bouquetReference, hotel) } }
            val serviceKeys = if (allowed == null) null else channelRows.map { it.serviceKey }.toHashSet()
            val channels = channelRows.mapNotNull { row ->
                val bouquet = byRef[row.bouquetReference] ?: return@mapNotNull null
                ListedChannel(bouquet, BouquetEntry(row.name, row.reference, row.number, row.picon))
            }
            val names = channelRows.associate { it.serviceKey to it.name }
            val films = runCatching { dao.filmTitles(rk, nowSec) }.getOrDefault(emptyList())
                .associate { (it.serviceKey to it.beginSec) to it.title }
            val events = dao.events(rk, nowSec, nowSec + HORIZON_SEC)
                .filter { serviceKeys == null || it.serviceKey in serviceKeys }
                .map { row ->
                val aka = films[row.serviceKey to row.beginSec].orEmpty()
                val found = EpgSearchEvent(CacheMappers.event(row), names[row.serviceKey].orEmpty(), aka)
                found to SearchText.fold(row.title + "\u0000" + aka)
            }
            Index(nowMs, rk, bouquets, channels, events, allowed)
        }.also { index = it }
    }

    /** Programmes whose title (or TMDB title) holds [text]. */
    fun programmes(index: Index, text: String): List<EpgSearchEvent> {
        val folded = SearchText.fold(text.trim())
        if (folded.isEmpty()) return emptyList()
        return index.events.asSequence().filter { it.second.contains(folded) }.map { it.first }.take(MAX_EVENTS).toList()
    }

    fun invalidate() {
        index = null
    }

    private companion object {
        const val TTL_MS = 3 * 60 * 1000L
        const val HORIZON_SEC = 8L * 24 * 3600
        const val MAX_EVENTS = 400
    }
}
