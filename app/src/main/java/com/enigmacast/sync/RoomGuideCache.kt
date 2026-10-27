package com.enigmacast.sync

import android.content.Context
import com.enigmacast.data.db.BouquetNameRow
import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.tmdb.SyncSettings
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.domain.sync.DayWindow
import com.enigmacast.domain.sync.GuideCachePolicy
import com.enigmacast.domain.sync.GuideCacheReader
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.withContext
import javax.inject.Singleton

/**
 * The guide grid, channel EPG, bouquet list and channel lists read Room first. A miss returns
 * null so the caller asks the receiver; a miss inside the synced days also starts a sync
 * (at most once a minute).
 */
@Singleton
class RoomGuideCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CacheDao,
    private val settings: SyncSettings,
    private val aliases: IptvEpgAliases,
    private val dispatchers: com.enigmacast.util.AppDispatchers,
) : GuideCacheReader {
    @Volatile private var lastSyncAskMs = 0L

    private fun key(receiver: ReceiverConfig) = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)

    override suspend fun bouquetEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long): List<GuideEvent>? {
        val rows = runCatching { dao.bouquetEvents(key(receiver), bouquetReference, fromSec, toSec) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val today = DayWindow.of(now, ZoneId.systemDefault())
        val horizon = today.startSec + settings.values.value.epgDays * 24 * 3600L
        val covered = rows.mapTo(HashSet()) { it.serviceKey }.size
        val channelCount = if (rows.isEmpty()) -1
        else runCatching { dao.channelsIn(key(receiver), bouquetReference) }.getOrNull()
            ?.filter { BouquetChannels.isPlayableChannel(it.reference) && !ServiceStreams.isInternet(it.reference) }
            ?.size ?: -1
        return when (GuideCachePolicy.decide(rows.size, fromSec, toSec, today.startSec, horizon, covered, channelCount)) {
            // A bouquet block is thousands of rows: mapped off the caller (often Main).
            GuideCachePolicy.Decision.CACHE -> withContext(dispatchers.default) { rows.map(CacheMappers::event) } + aliasEvents(receiver, bouquetReference, fromSec, toSec)
            GuideCachePolicy.Decision.NETWORK_AND_SYNC -> { askSync(now); null }
            GuideCachePolicy.Decision.NETWORK -> null
        }
    }

    override suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String): List<GuideEvent>? {
        val now = System.currentTimeMillis()
        // An IPTV channel reads the guide of the satellite channel with the same name.
        val source = aliases.resolve(key(receiver), serviceReference) ?: serviceReference
        val rows = runCatching { dao.serviceEvents(key(receiver), NowEvents.key(source), now / 1000) }.getOrNull()
        if (rows.isNullOrEmpty()) return null
        // Programmes that started earlier today are kept by the query (it filters on end time).
        return withContext(dispatchers.default) { rows.map { CacheMappers.event(it).copy(reference = serviceReference) } }
    }

    override suspend fun aliasEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long): List<GuideEvent> {
        val rk = key(receiver)
        val map = aliases.map(rk)
        if (map.isEmpty()) return emptyList()
        val channels = runCatching { dao.channelsIn(rk, bouquetReference) }.getOrDefault(emptyList())
        return withContext(dispatchers.default) {
            channels.mapNotNull { c -> map[c.reference]?.let { c.reference to it } }.flatMap { (iptv, sat) ->
                runCatching { dao.serviceEvents(rk, NowEvents.key(sat), fromSec) }.getOrDefault(emptyList())
                    .filter { it.beginSec < toSec }
                    .map { CacheMappers.event(it).copy(reference = iptv) }
            }
        }
    }

    override suspend fun televisionBouquets(receiver: ReceiverConfig): List<BouquetEntry>? =
        runCatching { dao.bouquets(key(receiver)) }.getOrNull()?.takeIf { it.isNotEmpty() }?.map { BouquetEntry(it.name, it.reference) }

    override suspend fun channelsInBouquet(receiver: ReceiverConfig, bouquetReference: String): List<BouquetEntry>? =
        runCatching { dao.channelsIn(key(receiver), bouquetReference) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?.map { BouquetEntry(it.name, it.reference, it.number, it.picon) }

    override suspend fun bouquetOf(receiver: ReceiverConfig, serviceReference: String): BouquetEntry? = runCatching {
        val rk = key(receiver)
        val want = NowEvents.key(serviceReference)
        val direct = dao.bouquetHolding(rk, want)
        val row = direct ?: dao.channelsWithoutServiceKey(rk)
            .let { rows -> withContext(dispatchers.default) { rows.filter { NowEvents.key(it.reference) == want }.map { it.bouquetReference }.toSet() } }
            .let { holding -> dao.bouquets(rk).firstOrNull { it.reference in holding } }
            ?.let { BouquetNameRow(it.name, it.reference) }
        row?.let { BouquetEntry(it.name, it.reference) }
    }.getOrNull()

    private fun askSync(nowMs: Long) {
        if (nowMs - lastSyncAskMs < 60_000) return
        lastSyncAskMs = nowMs
        EnigmaLog.service("guide cache miss inside synced days; asking for a sync")
        SyncScheduler.runNow(context, force = false)
    }
}
