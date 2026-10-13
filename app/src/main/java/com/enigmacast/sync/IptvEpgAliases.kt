package com.enigmacast.sync

import com.enigmacast.data.db.CacheDao
import com.enigmacast.domain.epg.IptvEpgNames
import com.enigmacast.domain.epg.NamedService
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.domain.sync.SyncKind
import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per receiver: IPTV reference (with URL) → satellite reference whose EPG it shows. Built from
 * the cached channel lists and rebuilt when their content hash changes (checked at most every 30 s).
 */
@Singleton
class IptvEpgAliases @Inject constructor(private val dao: CacheDao) {
    private class Entry(val hash: String, val checkedMs: Long, val map: Map<String, String>)

    private val entries = HashMap<String, Entry>()
    private val mutex = Mutex()

    /** The satellite reference for [reference], or null (not a stream, or no channel of that name). */
    suspend fun resolve(receiverKey: String, reference: String): String? {
        if (!ServiceStreams.isInternet(reference)) return null
        return map(receiverKey)[reference]
    }

    suspend fun map(receiverKey: String): Map<String, String> = mutex.withLock {
        val now = System.currentTimeMillis()
        val old = entries[receiverKey]
        if (old != null && now - old.checkedMs < CHECK_MS) return@withLock old.map
        val hash = runCatching { dao.syncState(receiverKey, SyncKind.CHANNELS.name)?.contentHash }.getOrNull().orEmpty()
        if (old != null && old.hash == hash && hash.isNotEmpty()) {
            entries[receiverKey] = Entry(hash, now, old.map)
            return@withLock old.map
        }
        val rows = runCatching { dao.channels(receiverKey) }.getOrDefault(emptyList())
        val map = IptvEpgNames.aliases(rows.distinctBy { it.reference }.map { NamedService(it.reference, it.name) })
        EnigmaLog.service("iptv epg aliases receiver=$receiverKey: ${map.size} streams use satellite EPG")
        entries[receiverKey] = Entry(hash, now, map)
        map
    }

    /** Drops the map so the next lookup rebuilds it (new channel lists). */
    suspend fun invalidate(receiverKey: String) = mutex.withLock { entries.remove(receiverKey); Unit }

    private companion object { const val CHECK_MS = 30_000L }
}
