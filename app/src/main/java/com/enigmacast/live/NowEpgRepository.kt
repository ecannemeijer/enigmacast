package com.enigmacast.live

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Now on air" for whole bouquets, one `/api/epgnow` request per bouquet. Results are
 * kept per receiver and bouquet and read again after five minutes, or sooner once a
 * programme in the list has ended.
 */
@Singleton
class NowEpgRepository @Inject constructor(
    private val tunerSource: TunerStatusSource,
) {
    private class Entry(val fetchedAtSec: Long, val events: List<NowEvent>, val index: Map<String, NowEvent>)

    private val cache = mutableMapOf<String, Entry>()
    private val mutex = Mutex()

    fun cached(receiver: ReceiverConfig, bouquetReference: String): Map<String, NowEvent> {
        return cache[key(receiver, bouquetReference)]?.index.orEmpty()
    }

    suspend fun load(receiver: ReceiverConfig, bouquetReference: String): Map<String, NowEvent> {
        if (bouquetReference.isBlank()) return emptyMap()
        val key = key(receiver, bouquetReference)
        return mutex.withLock {
            val nowSec = System.currentTimeMillis() / 1000
            val entry = cache[key]
            if (entry != null && !NowEvents.isStale(entry.fetchedAtSec, entry.events, nowSec)) return@withLock entry.index
            val events = try {
                tunerSource.nowEvents(receiver, bouquetReference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EnigmaLog.service("epgnow failed: ${error.javaClass.simpleName}")
                return@withLock entry?.index.orEmpty()
            }
            val fresh = Entry(nowSec, events, NowEvents.index(events))
            cache[key] = fresh
            fresh.index
        }
    }

    private fun key(receiver: ReceiverConfig, bouquetReference: String) =
        "${receiver.host}:${receiver.httpPort}|$bouquetReference"
}
