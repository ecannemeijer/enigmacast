package com.enigmacast.data.epg

import com.enigmacast.data.db.FallbackEpgDao
import com.enigmacast.data.db.FallbackEpgProgrammeRow
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.FallbackEpgLookup
import com.enigmacast.domain.epg.FallbackEpgNames
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.util.AppDispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Disk-backed fallback programmes, keyed by normalised name and tvg-id. Lookups are empty when
 * the setting is off, or when the cache has no match.
 */
@Singleton
class FallbackEpg @Inject constructor(
    private val dao: FallbackEpgDao,
    private val settings: SettingsRepository,
    private val dispatchers: AppDispatchers,
) : FallbackEpgLookup {
    private class Index(
        val byName: Map<String, String>,
        val byTvg: Map<String, String>,
    )

    private val mutex = Mutex()
    @Volatile private var index: Index? = null

    suspend fun invalidate() = mutex.withLock { index = null }

    override suspend fun eventsFor(name: String, reference: String, tvgId: String, fromSec: Long, toSec: Long): List<GuideEvent> {
        if (name.isBlank() && tvgId.isBlank()) return emptyList()
        return eventsByName(listOf(name), if (tvgId.isBlank()) emptyMap() else mapOf(name to tvgId), fromSec, toSec)[name].orEmpty()
            .map { it.copy(reference = reference) }
    }

    override suspend fun eventsByName(
        names: Collection<String>,
        tvgIds: Map<String, String>,
        fromSec: Long,
        toSec: Long,
    ): Map<String, List<GuideEvent>> = withContext(dispatchers.io) {
        if (!settings.current().fallbackEpgEnabled) return@withContext emptyMap()
        val unique = names.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty() && tvgIds.isEmpty()) return@withContext emptyMap()
        val index = index()
        val wanted = LinkedHashMap<String, String>()
        unique.forEach { name ->
            xmltvId(index, name, tvgIds[name].orEmpty())?.let { wanted[name] = it }
        }
        tvgIds.forEach { (name, tvg) ->
            if (name !in wanted) xmltvId(index, name, tvg)?.let { wanted[name] = it }
        }
        if (wanted.isEmpty()) return@withContext emptyMap()
        val rows = wanted.values.distinct().chunked(400).flatMap { ids ->
            runCatching { dao.programmes(ids, fromSec, toSec) }.getOrDefault(emptyList())
        }.groupBy { it.xmltvId }
        wanted.mapNotNull { (name, xmltvId) ->
            val events = rows[xmltvId].orEmpty().sortedBy { it.beginSec }.map { event(it, name) }
            events.takeIf { it.isNotEmpty() }?.let { name to it }
        }.toMap()
    }

    private suspend fun index(): Index = mutex.withLock {
        index ?: loadIndex().also { index = it }
    }

    private suspend fun loadIndex(): Index = withContext(dispatchers.default) {
        val channels = runCatching { dao.channels() }.getOrDefault(emptyList())
        val byName = HashMap<String, String>()
        val byTvg = HashMap<String, String>()
        fun offer(map: HashMap<String, String>, key: String, id: String) {
            if (key.isNotEmpty()) map.putIfAbsent(key, id)
        }
        channels.forEach { row ->
            offer(byName, row.nameKey, row.xmltvId)
            FallbackEpgNames.keys(row.displayName)?.let { keys ->
                offer(byName, keys.full, row.xmltvId)
                keys.short?.let { offer(byName, it, row.xmltvId) }
            }
            FallbackEpgNames.tvgKeys(row.xmltvId).forEach { offer(byTvg, it, row.xmltvId) }
        }
        Index(byName, byTvg)
    }

    private fun xmltvId(index: Index, name: String, tvgId: String): String? {
        if (tvgId.isNotBlank()) {
            FallbackEpgNames.tvgKeys(tvgId).forEach { key -> index.byTvg[key]?.let { return it } }
        }
        val keys = FallbackEpgNames.keys(name) ?: return if (tvgId.isBlank()) null else null
        return index.byName[keys.full] ?: keys.short?.let { index.byName[it] }
    }

    private fun event(row: FallbackEpgProgrammeRow, name: String) = GuideEvent(
        eventId = eventId(row.xmltvId, row.beginSec),
        reference = name,
        title = row.title,
        shortDescription = row.description,
        plot = row.description,
        beginEpochSec = row.beginSec,
        durationSec = (row.endSec - row.beginSec).coerceAtLeast(0L),
        serviceName = name,
    )

    private fun eventId(xmltvId: String, beginSec: Long): Int =
        ((xmltvId.hashCode().toLong() * 31 + beginSec) and 0x7fffffff).toInt()
}
