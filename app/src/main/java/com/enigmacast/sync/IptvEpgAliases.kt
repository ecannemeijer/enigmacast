package com.enigmacast.sync

import com.enigmacast.data.db.CacheDao
import com.enigmacast.domain.epg.IptvEpgNames
import com.enigmacast.domain.epg.NamedService
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.domain.sync.SyncKind
import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * Per receiver: IPTV reference (with URL) → satellite reference whose EPG it shows. Built from
 * the cached channel lists and rebuilt when their content hash changes (checked at most every 30 s).
 */
@Singleton
class IptvEpgAliases @Inject constructor(private val dao: CacheDao) {
    private class Entry(val hash: String, val checkedMs: Long, val map: Map<String, String>, val builtMs: Long = checkedMs)

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
        // Same lists: keep the map. Without a stored hash it is rebuilt at most every 10 min, not every 30 s.
        if (old != null && old.hash == hash && (hash.isNotEmpty() || now - old.builtMs < REBUILD_NO_HASH_MS)) {
            entries[receiverKey] = Entry(hash, now, old.map, old.builtMs)
            return@withLock old.map
        }
        val rows = runCatching { dao.channels(receiverKey) }.getOrDefault(emptyList())
        // Thousands of names through regexes: CPU work, never on the main thread (ANR on TVs).
        val started = System.nanoTime()
        val map = withContext(Dispatchers.Default) {
            IptvEpgNames.aliases(rows.distinctBy { it.reference }.map { NamedService(it.reference, it.name) })
        }
        EnigmaLog.service("iptv epg aliases built in ${(System.nanoTime() - started) / 1_000_000} ms from ${rows.size} rows")
        EnigmaLog.service("iptv epg aliases receiver=$receiverKey: ${map.size} streams use satellite EPG")
        entries[receiverKey] = Entry(hash, now, map)
        map
    }

    /** Drops the map so the next lookup rebuilds it (new channel lists). */
    suspend fun invalidate(receiverKey: String) = mutex.withLock { entries.remove(receiverKey); Unit }

    private companion object {
        const val CHECK_MS = 30_000L
        const val REBUILD_NO_HASH_MS = 10 * 60_000L
    }
}
