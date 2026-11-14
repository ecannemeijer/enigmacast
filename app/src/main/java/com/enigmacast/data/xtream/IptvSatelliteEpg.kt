package com.enigmacast.data.xtream

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.SatEpgNames
import com.enigmacast.domain.sync.SyncKind
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.sync.SyncEngine
import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Guide for IPTV channels without one of their own (M3U, or an Xtream channel with an empty
 * short EPG): the receiver's cached satellite guide, matched by channel name ([SatEpgNames]).
 * One batch query per call. The name index is rebuilt whenever the receiver's channel list
 * changes (count, sync time or content), and an empty one is retried after a minute.
 */
@Singleton
class IptvSatelliteEpg @Inject constructor(
    private val dao: CacheDao,
    private val settings: SettingsRepository,
    private val engine: SyncEngine,
    private val fallback: com.enigmacast.domain.epg.FallbackEpgLookup,
) {
    private class Built(val stamp: SatEpgNames.Stamp, val atMs: Long, val index: SatEpgNames.Index)

    private val lock = Mutex()
    private var built: Built? = null

    private suspend fun index(): Built? = lock.withLock {
        val receiver = settings.current().activeReceiver() ?: return@withLock null
        val key = engine.receiverKey(receiver)
        val state = runCatching { dao.syncState(key, SyncKind.CHANNELS.name) }.getOrNull()
        val stamp = SatEpgNames.Stamp(key, dao.channelCount(key), state?.lastSuccessMs ?: 0L, state?.contentHash.orEmpty())
        val now = System.currentTimeMillis()
        val cached = built
        if (cached != null && !SatEpgNames.needsRebuild(cached.stamp, cached.atMs, cached.index.isEmpty, stamp, now)) return@withLock cached
        val channels = dao.channelMatchRows(key).map { row ->
            // Rows stored before their key was filled in get it from the reference, as the guide sync does.
            SatEpgNames.Channel(row.name, row.reference, row.serviceKey.ifBlank { NowEvents.key(row.reference) })
        }
        Built(stamp, now, SatEpgNames.index(channels)).also {
            built = it
            log("iptv sat-epg: index ${it.index.size} names from ${channels.size} receiver channels")
        }
    }

    /** Events per name, from [pastHours] ago through the coming [hours], for names that match a receiver channel. */
    suspend fun byName(names: Collection<String>, hours: Int = 12, pastHours: Int = 0): Map<String, List<XtreamEpg>> = withContext(Dispatchers.IO) {
        val unique = names.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return@withContext emptyMap()
        val built = index()
        if (built == null) {
            log("iptv sat-epg: matched 0 of ${unique.size} (no receiver)")
            return@withContext emptyMap()
        }
        val candidates = unique.associateWith { built.index.lookup(it) }.filterValues { it.isNotEmpty() }
        if (candidates.isEmpty()) {
            log("iptv sat-epg: matched 0 of ${unique.size} (index ${built.index.size})")
            return@withContext emptyMap()
        }
        val now = System.currentTimeMillis() / 1000
        val rows = candidates.values.flatten().distinct().chunked(500).flatMap { keys ->
            dao.eventsFor(built.stamp.receiverKey, keys, now - pastHours * 3600L, now + hours * 3600L)
        }.groupBy { it.serviceKey }
        // The first candidate (HD satellite first) that has programmes; an SD twin or a receiver
        // stream with no guide no longer hides the one that has it.
        val result = candidates.mapNotNull { (name, services) ->
            val service = services.firstOrNull { !rows[it].isNullOrEmpty() } ?: return@mapNotNull null
            name to rows[service].orEmpty().map { XtreamEpg(it.title, it.beginSec, it.endSec, it.shortDescription.ifBlank { it.longDescription }) }
        }.toMap()
        log("iptv sat-epg: matched ${result.size} of ${unique.size} (by name ${candidates.size})")
        val missing = unique.filter { result[it].isNullOrEmpty() }
        if (missing.isEmpty()) return@withContext result
        val fromSec = now - pastHours * 3600L
        val toSec = now + hours * 3600L
        val extra = runCatching { fallback.eventsByName(missing, fromSec = fromSec, toSec = toSec) }.getOrDefault(emptyMap())
        if (extra.isEmpty()) return@withContext result
        val filled = result + extra.mapValues { (_, events) ->
            events.map { com.enigmacast.domain.xtream.XtreamEpg(it.title, it.beginEpochSec, it.endEpochSec, it.description) }
        }.filterKeys { result[it].isNullOrEmpty() }
        log("iptv fallback-epg: filled ${filled.size - result.size} of ${missing.size}")
        filled
    }

    private fun log(message: String) {
        EnigmaLog.satEpg(message)
    }
}
