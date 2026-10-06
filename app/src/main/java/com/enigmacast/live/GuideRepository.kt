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
) {
    private class Entry(val fetchedAtMs: Long, val events: List<GuideEvent>)

    private val services = LinkedHashMap<String, Entry>()
    private val chunks = LinkedHashMap<String, Entry>()
    private val mutex = Mutex()

    suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String): List<GuideEvent> {
        if (serviceReference.isBlank()) return emptyList()
        val key = "${receiverKey(receiver)}|${NowEvents.key(serviceReference)}"
        val nowMs = System.currentTimeMillis()
        cachedFresh(services, key, nowMs, SERVICE_TTL_MS)?.let { return it }
        val events = fetch("epgservice") { source.serviceEvents(receiver, serviceReference) }
            ?: return mutex.withLock { services[key]?.events.orEmpty() }
        val sorted = events.sortedBy { it.beginEpochSec }
        store(services, key, Entry(nowMs, sorted), MAX_SERVICES)
        return sorted
    }

    /** Cached block (start aligned to [GuideTimeline.CHUNK_SEC]) without a request, or null. */
    suspend fun cachedChunk(receiver: ReceiverConfig, bouquetReference: String, chunkStartSec: Long): List<GuideEvent>? =
        cachedFresh(chunks, chunkKey(receiver, bouquetReference, chunkStartSec), System.currentTimeMillis(), CHUNK_TTL_MS)

    suspend fun bouquetChunk(receiver: ReceiverConfig, bouquetReference: String, chunkStartSec: Long): List<GuideEvent> {
        if (bouquetReference.isBlank()) return emptyList()
        val key = chunkKey(receiver, bouquetReference, chunkStartSec)
        val nowMs = System.currentTimeMillis()
        cachedFresh(chunks, key, nowMs, CHUNK_TTL_MS)?.let { return it }
        val minutes = (GuideTimeline.CHUNK_SEC / 60).toInt()
        val events = fetch("epgmulti") { source.bouquetEvents(receiver, bouquetReference, chunkStartSec, minutes) }
            ?: return mutex.withLock { chunks[key]?.events.orEmpty() }
        store(chunks, key, Entry(nowMs, events), MAX_CHUNKS)
        return events
    }

    suspend fun record(receiver: ReceiverConfig, event: GuideEvent): TimerResult {
        val result = source.record(receiver, event.reference, event.eventId)
        if (result.success) {
            mutex.withLock { recorded += recordKey(receiver, event) }
        }
        return result
    }

    private val recorded = mutableSetOf<String>()

    /** Programmes set to record from this app in this session, for the red dot. */
    fun isMarkedForRecording(receiver: ReceiverConfig, event: GuideEvent): Boolean = recordKey(receiver, event) in recorded

    private fun recordKey(receiver: ReceiverConfig, event: GuideEvent) =
        "${receiverKey(receiver)}|${NowEvents.key(event.reference)}|${event.eventId}|${event.beginEpochSec}"

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
