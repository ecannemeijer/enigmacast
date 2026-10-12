package com.enigmacast.sync

import android.content.Context
import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.tmdb.SyncSettings
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.sync.DayWindow
import com.enigmacast.domain.sync.GuideCachePolicy
import com.enigmacast.domain.sync.GuideCacheReader
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
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
) : GuideCacheReader {
    @Volatile private var lastSyncAskMs = 0L

    private fun key(receiver: ReceiverConfig) = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)

    override suspend fun bouquetEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long): List<GuideEvent>? {
        val rows = runCatching { dao.bouquetEvents(key(receiver), bouquetReference, fromSec, toSec) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val today = DayWindow.of(now, ZoneId.systemDefault())
        val horizon = today.startSec + settings.values.value.epgDays * 24 * 3600L
        return when (GuideCachePolicy.decide(rows.size, fromSec, toSec, today.startSec, horizon)) {
            GuideCachePolicy.Decision.CACHE -> rows.map(CacheMappers::event)
            GuideCachePolicy.Decision.NETWORK_AND_SYNC -> { askSync(now); null }
            GuideCachePolicy.Decision.NETWORK -> null
        }
    }

    override suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String): List<GuideEvent>? {
        val now = System.currentTimeMillis()
        val rows = runCatching { dao.serviceEvents(key(receiver), NowEvents.key(serviceReference), now / 1000) }.getOrNull()
        if (rows.isNullOrEmpty()) return null
        // Programmes that started earlier today are kept by the query (it filters on end time).
        return rows.map(CacheMappers::event).map { it.copy(reference = serviceReference) }
    }

    override suspend fun televisionBouquets(receiver: ReceiverConfig): List<BouquetEntry>? =
        runCatching { dao.bouquets(key(receiver)) }.getOrNull()?.takeIf { it.isNotEmpty() }?.map { BouquetEntry(it.name, it.reference) }

    override suspend fun channelsInBouquet(receiver: ReceiverConfig, bouquetReference: String): List<BouquetEntry>? =
        runCatching { dao.channelsIn(key(receiver), bouquetReference) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?.map { BouquetEntry(it.name, it.reference, it.number, it.picon) }

    override suspend fun bouquetOf(receiver: ReceiverConfig, serviceReference: String): BouquetEntry? = runCatching {
        val rk = key(receiver)
        val want = NowEvents.key(serviceReference)
        val holding = dao.channels(rk).filter { (it.serviceKey.ifBlank { NowEvents.key(it.reference) }) == want }.map { it.bouquetReference }.toSet()
        if (holding.isEmpty()) return@runCatching null
        dao.bouquets(rk).firstOrNull { it.reference in holding }?.let { BouquetEntry(it.name, it.reference) }
    }.getOrNull()

    private fun askSync(nowMs: Long) {
        if (nowMs - lastSyncAskMs < 60_000) return
        lastSyncAskMs = nowMs
        EnigmaLog.service("guide cache miss inside synced days; asking for a sync")
        SyncScheduler.runNow(context, force = false)
    }
}
