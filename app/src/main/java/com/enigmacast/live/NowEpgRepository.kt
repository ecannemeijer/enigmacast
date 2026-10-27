package com.enigmacast.live

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.domain.tuner.ServiceMatch
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
    private val guideCache: com.enigmacast.domain.sync.GuideCacheReader,
) {
    private class Entry(val fetchedAtSec: Long, val events: List<NowEvent>, val index: Map<String, NowEvent>)

    private val cache = mutableMapOf<String, Entry>()
    private val mutex = Mutex()

    fun cached(receiver: ReceiverConfig, bouquetReference: String): Map<String, NowEvent> {
        return cache[key(receiver, bouquetReference)]?.index.orEmpty()
    }

    /**
     * Now on air for [bouquetReference], keyed by [NowEvents.key]. [channels] is the bouquet as
     * the list shows it (the synced copy is used when empty): programmes whose reference differs
     * from the list's are put on the right channel ([ServiceMatch]), and channels still without
     * one get it from the synced guide.
     */
    suspend fun load(
        receiver: ReceiverConfig,
        bouquetReference: String,
        channels: List<BouquetEntry> = emptyList(),
    ): Map<String, NowEvent> {
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
            val list = channels.ifEmpty {
                runCatching { guideCache.channelsInBouquet(receiver, bouquetReference) }.getOrNull().orEmpty()
            }
            val matched = ServiceMatch.nowEvents(events, list)
            val all = matched + fromGuide(receiver, list, matched, nowSec)
            val fresh = Entry(nowSec, all, NowEvents.index(all))
            cache[key] = fresh
            fresh.index
        }
    }

    /**
     * Channels without a programme in the bouquet answer: now on air from the synced guide. An
     * IPTV channel reads the satellite channel with the same name there.
     */
    private suspend fun fromGuide(receiver: ReceiverConfig, channels: List<BouquetEntry>, own: List<NowEvent>, nowSec: Long): List<NowEvent> {
        if (channels.isEmpty()) return emptyList()
        val known = NowEvents.index(own).keys
        return channels
            .filter { BouquetChannels.isPlayableChannel(it.reference) && NowEvents.key(it.reference) !in known }
            .distinctBy { NowEvents.key(it.reference) }
            .take(GUIDE_FILL_MAX)
            .mapNotNull { channel ->
                val events = runCatching { guideCache.serviceEvents(receiver, channel.reference) }.getOrNull() ?: return@mapNotNull null
                val now = events.firstOrNull { it.beginEpochSec <= nowSec && it.endEpochSec > nowSec } ?: return@mapNotNull null
                NowEvent(channel.reference, now.title, now.beginEpochSec, now.endEpochSec - now.beginEpochSec, now.eventId)
            }
    }

    private fun key(receiver: ReceiverConfig, bouquetReference: String) =
        "${receiver.host}:${receiver.httpPort}|$bouquetReference"
}

/** The most channels of one bouquet read from the synced guide per refresh. */
private const val GUIDE_FILL_MAX = 250
