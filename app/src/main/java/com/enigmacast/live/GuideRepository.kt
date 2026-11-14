package com.enigmacast.live

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.GuideTimeline
import com.enigmacast.domain.epg.TimerResult
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.EpgSource
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Programme guide data with a small in-memory cache:
 * - one channel's whole EPG (`/api/epgservice`) for the channel list, kept 10 minutes;
 * - a bouquet's programmes in 3-hour blocks (`/api/epgmulti`) for the guide grid, kept
 *   30 minutes. The grid asks only for the blocks around the visible time window, so
 *   scrolling never makes one request per channel or per cell.
 *
 * Failures are logged by type only and give the cached data, or nothing.
 */
@Singleton
class GuideRepository @Inject constructor(
    private val source: EpgSource,
    private val cache: com.enigmacast.domain.sync.GuideCacheReader,
    private val fallback: com.enigmacast.domain.epg.FallbackEpgLookup,
) {
    /** Tests: the receiver only. */
    constructor(source: EpgSource) : this(source, com.enigmacast.domain.sync.GuideCacheReader.None, com.enigmacast.domain.epg.FallbackEpgLookup.None)

    /** Tests: the receiver and a cache, without the public fallback guide. */
    constructor(source: EpgSource, cache: com.enigmacast.domain.sync.GuideCacheReader) : this(source, cache, com.enigmacast.domain.epg.FallbackEpgLookup.None)

    private class Entry(val fetchedAtMs: Long, val events: List<GuideEvent>)

    private val services = LinkedHashMap<String, Entry>()
    private val chunks = LinkedHashMap<String, Entry>()
    private val mutex = Mutex()

    suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String): List<GuideEvent> {
        if (serviceReference.isBlank()) return emptyList()
        val key = "${receiverKey(receiver)}|${NowEvents.key(serviceReference)}"
        val nowMs = System.currentTimeMillis()
        cachedFresh(services, key, nowMs, SERVICE_TTL_MS)?.let { return it }
        cache.serviceEvents(receiver, serviceReference)?.let { return it }
        val events = fetch("epgservice") { source.serviceEvents(receiver, serviceReference) }
        if (events == null) {
            val old = mutex.withLock { services[key]?.events }
            if (!old.isNullOrEmpty()) return old
            return fallbackService(receiver, serviceReference, nowMs / 1000)
        }
        val sorted = events.sortedBy { it.beginEpochSec }.ifEmpty { fallbackService(receiver, serviceReference, nowMs / 1000) }
        store(services, key, Entry(nowMs, sorted), MAX_SERVICES)
        return sorted
    }

    /** Cached block (start aligned to [GuideTimeline.CHUNK_SEC]) without a request, or null. */
    suspend fun cachedChunk(receiver: ReceiverConfig, bouquetReference: String, chunkStartSec: Long): List<GuideEvent>? =
        cachedFresh(chunks, chunkKey(receiver, bouquetReference, chunkStartSec), System.currentTimeMillis(), CHUNK_TTL_MS)

    /**
     * Programmes already on disk (Room or the public fallback) for this block. Never asks the
     * receiver, so the grid can fill empty rows while [bouquetChunk] is still on the wire.
     */
    suspend fun localBouquetChunk(receiver: ReceiverConfig, bouquetReference: String, chunkStartSec: Long): List<GuideEvent> {
        if (bouquetReference.isBlank()) return emptyList()
        val nowMs = System.currentTimeMillis()
        cachedFresh(chunks, chunkKey(receiver, bouquetReference, chunkStartSec), nowMs, CHUNK_TTL_MS)?.let { return it }
        val until = chunkStartSec + GuideTimeline.CHUNK_SEC
        cache.bouquetEvents(receiver, bouquetReference, chunkStartSec, until)?.let { return it }
        val alias = runCatching { cache.aliasEvents(receiver, bouquetReference, chunkStartSec, until) }.getOrDefault(emptyList())
        return alias + fallbackChunk(receiver, bouquetReference, alias, chunkStartSec, until)
    }

    /** One block of a bouquet's guide; on failure the older cached copy, the fallback, or null so the caller can try again. */
    suspend fun bouquetChunk(receiver: ReceiverConfig, bouquetReference: String, chunkStartSec: Long): List<GuideEvent>? {
        if (bouquetReference.isBlank()) return emptyList()
        val key = chunkKey(receiver, bouquetReference, chunkStartSec)
        val nowMs = System.currentTimeMillis()
        cachedFresh(chunks, key, nowMs, CHUNK_TTL_MS)?.let { return it }
        val minutes = (GuideTimeline.CHUNK_SEC / 60).toInt()
        val until = chunkStartSec + GuideTimeline.CHUNK_SEC
        cache.bouquetEvents(receiver, bouquetReference, chunkStartSec, until)?.let { return it }
        val fetched = fetch("epgmulti") { source.bouquetEvents(receiver, bouquetReference, chunkStartSec, minutes) }
        if (fetched == null) {
            val old = mutex.withLock { chunks[key]?.events }
            if (old != null) return old
            // Do not store: a later refresh should ask the receiver again.
            return fallbackChunk(receiver, bouquetReference, emptyList(), chunkStartSec, until).takeIf { it.isNotEmpty() }
        }
        // IPTV channels: the guide of the satellite channel with the same name.
        val events = fetched + runCatching { cache.aliasEvents(receiver, bouquetReference, chunkStartSec, until) }.getOrDefault(emptyList())
        val filled = events + fallbackChunk(receiver, bouquetReference, events, chunkStartSec, until)
        store(chunks, key, Entry(nowMs, filled), MAX_CHUNKS)
        return filled
    }

    /**
     * Sets a receiver timer for [event]. Not used by the screens yet: Record in the programme
     * dialog says "coming soon" until recording from the app is finished.
     */
    suspend fun record(receiver: ReceiverConfig, event: GuideEvent): TimerResult =
        source.record(receiver, event.reference, event.eventId)

    private suspend fun cachedFresh(map: Map<String, Entry>, key: String, nowMs: Long, ttlMs: Long): List<GuideEvent>? =
        mutex.withLock { map[key]?.takeIf { nowMs - it.fetchedAtMs < ttlMs }?.events }

    private suspend fun store(map: LinkedHashMap<String, Entry>, key: String, entry: Entry, max: Int) = mutex.withLock {
        map.remove(key)
        map[key] = entry
        while (map.size > max) map.remove(map.keys.first())
    }

    private suspend fun fetch(what: String, block: suspend () -> List<GuideEvent>): List<GuideEvent>? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        EnigmaLog.service("$what failed: ${error.javaClass.simpleName}")
        null
    }

    private suspend fun fallbackService(receiver: ReceiverConfig, serviceReference: String, nowSec: Long): List<GuideEvent> {
        val bouquet = runCatching { cache.bouquetOf(receiver, serviceReference) }.getOrNull() ?: return emptyList()
        val name = runCatching { cache.channelsInBouquet(receiver, bouquet.reference) }.getOrNull()
            ?.firstOrNull { com.enigmacast.domain.tuner.NowEvents.key(it.reference) == com.enigmacast.domain.tuner.NowEvents.key(serviceReference) }
            ?.name.orEmpty()
        if (name.isBlank()) return emptyList()
        return runCatching { fallback.eventsFor(name, serviceReference, fromSec = nowSec, toSec = nowSec + 7 * 24 * 3600L) }.getOrDefault(emptyList())
    }

    private suspend fun fallbackChunk(
        receiver: ReceiverConfig,
        bouquetReference: String,
        already: List<GuideEvent>,
        fromSec: Long,
        toSec: Long,
    ): List<GuideEvent> {
        val covered = already.mapTo(HashSet()) { com.enigmacast.domain.tuner.NowEvents.key(it.reference) }
        val channels = runCatching { cache.channelsInBouquet(receiver, bouquetReference) }.getOrNull().orEmpty()
            .filter { it.name.isNotBlank() && com.enigmacast.domain.tuner.NowEvents.key(it.reference) !in covered }
        if (channels.isEmpty()) return emptyList()
        val byName = runCatching { fallback.eventsByName(channels.map { it.name }.distinct(), fromSec = fromSec, toSec = toSec) }.getOrDefault(emptyMap())
        if (byName.isEmpty()) return emptyList()
        return channels.flatMap { channel -> byName[channel.name].orEmpty().map { it.copy(reference = channel.reference) } }
    }

    private fun chunkKey(receiver: ReceiverConfig, bouquetReference: String, chunkStartSec: Long) =
        "${receiverKey(receiver)}|$bouquetReference|$chunkStartSec"

    private fun receiverKey(receiver: ReceiverConfig) = "${receiver.host}:${receiver.httpPort}"

    private companion object {
        const val SERVICE_TTL_MS = 10 * 60 * 1000L
        const val CHUNK_TTL_MS = 30 * 60 * 1000L
        const val MAX_SERVICES = 40
        const val MAX_CHUNKS = 48
    }
}
