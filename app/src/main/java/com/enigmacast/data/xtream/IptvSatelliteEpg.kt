package com.enigmacast.data.xtream

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.xtream.IptvNames
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.sync.SyncEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Guide for IPTV channels without one of their own (M3U, or an Xtream channel with an empty
 * short EPG): the receiver's cached satellite guide, matched by channel name. One batch query
 * per call; the name index is built once per receiver off the main thread.
 */
@Singleton
class IptvSatelliteEpg @Inject constructor(
    private val dao: CacheDao,
    private val settings: SettingsRepository,
    private val engine: SyncEngine,
) {
    private val lock = Mutex()
    private var index: Pair<String, Map<String, String>>? = null

    private suspend fun index(): Pair<String, Map<String, String>>? = lock.withLock {
        val receiver = settings.current().activeReceiver() ?: return@withLock null
        val key = engine.receiverKey(receiver)
        index?.takeIf { it.first == key }?.let { return@withLock it }
        val map = HashMap<String, String>()
        dao.channelNames(key).forEach { row -> IptvNames.normalize(row.name).takeIf { it.isNotEmpty() }?.let { map.putIfAbsent(it, row.serviceKey) } }
        (key to map as Map<String, String>).also { index = it }
    }

    /** Events per name, from [pastHours] ago through the coming [hours], for names that match a receiver channel. */
    suspend fun byName(names: Collection<String>, hours: Int = 12, pastHours: Int = 0): Map<String, List<XtreamEpg>> = withContext(Dispatchers.IO) {
        val (key, map) = index() ?: return@withContext emptyMap()
        if (map.isEmpty()) return@withContext emptyMap()
        val wanted = names.associateWith { map[IptvNames.normalize(it)] }.filterValues { it != null }.mapValues { it.value!! }
        if (wanted.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis() / 1000
        val rows = dao.eventsFor(key, wanted.values.distinct(), now - pastHours * 3600L, now + hours * 3600L).groupBy { it.serviceKey }
        wanted.mapValues { (_, service) -> rows[service].orEmpty().map { XtreamEpg(it.title, it.beginSec, it.endSec, it.shortDescription.ifBlank { it.longDescription }) } }
            .filterValues { it.isNotEmpty() }
    }
}
