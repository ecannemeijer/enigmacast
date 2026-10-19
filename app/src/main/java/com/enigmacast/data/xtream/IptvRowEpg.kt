package com.enigmacast.data.xtream

import com.enigmacast.data.db.IptvDao
import com.enigmacast.data.db.IptvRowEpgRow
import com.enigmacast.domain.xtream.RowGuide
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.domain.xtream.airingAt
import com.enigmacast.util.EnigmaLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Now/next for the rows of the IPTV channel list, per account and stream: in memory, and in
 * Room so a restart has them at once. Fetched from the same Xtream listings the programme column
 * uses (short EPG, and the full table when that has nothing on now), [RowGuide.CONCURRENCY] at a
 * time. A failed request is not stored, so the row is asked again instead of staying empty.
 */
@Singleton
class IptvRowEpg @Inject constructor(
    private val dao: IptvDao,
    private val repository: XtreamRepository,
) {
    class Entry(val fetchedAtMs: Long, val events: List<XtreamEpg>)

    private val memory = ConcurrentHashMap<String, Entry>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val gate = Semaphore(RowGuide.CONCURRENCY)
    @Volatile private var pruned = false

    private fun key(source: String, id: String) = "$source/$id"

    fun needsFetch(source: String, id: String, nowMs: Long): Boolean {
        val k = key(source, id)
        val hit = memory[k]
        return RowGuide.needsFetch(hit?.fetchedAtMs, hit?.events, failed[k], nowMs)
    }

    /** Rows that got their now/next elsewhere (the receiver's guide, the programme column). */
    fun remember(source: String, id: String, events: List<XtreamEpg>, nowMs: Long = System.currentTimeMillis()) {
        memory[key(source, id)] = Entry(nowMs, events)
    }

    /** Stored rows that are still good, from memory or Room (one query), off the main thread. */
    suspend fun stored(account: XtreamAccount, ids: List<String>): Map<String, List<XtreamEpg>> = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        if (!pruned) {
            pruned = true
            runCatching { dao.pruneRowEpg(nowMs - RowGuide.TTL_MS) }
        }
        val found = HashMap<String, List<XtreamEpg>>()
        val ask = ArrayList<String>()
        ids.forEach { id ->
            val hit = memory[key(account.key, id)]
            if (hit != null && !RowGuide.needsFetch(hit.fetchedAtMs, hit.events, null, nowMs)) found[id] = hit.events else ask += id
        }
        if (ask.isNotEmpty()) {
            val rows = try { dao.rowEpg(account.key, ask) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            rows.forEach { row ->
                val events = RowGuide.decode(row.events) ?: return@forEach
                if (RowGuide.needsFetch(row.fetchedAtMs, events, null, nowMs)) return@forEach
                memory[key(account.key, row.streamId)] = Entry(row.fetchedAtMs, events)
                found[row.streamId] = events
            }
        }
        found
    }

    /**
     * The channel's listings from Xtream, or null when the request failed. Waits for one of the
     * [RowGuide.CONCURRENCY] slots; a cancelled caller (the rows scrolled away) gives its slot up.
     */
    suspend fun fetch(account: XtreamAccount, id: String): List<XtreamEpg>? {
        gate.acquire()
        try {
            val k = key(account.key, id)
            return try {
                val now = System.currentTimeMillis() / 1000
                val short = repository.shortGuideOrThrow(account, id)
                val list = if (short.airingAt(now) != null) short else repository.fullGuideOrThrow(account, id).ifEmpty { short }
                val trimmed = withContext(Dispatchers.Default) { RowGuide.trim(list, now) }
                store(account, id, trimmed)
                failed.remove(k)
                trimmed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[k] = System.currentTimeMillis()
                EnigmaLog.networkError("IPTV row guide for stream $id failed: ${e.javaClass.simpleName}")
                null
            }
        } finally {
            gate.release()
        }
    }

    /** Listings fetched elsewhere (the programme column), kept for the row too. */
    suspend fun store(account: XtreamAccount, id: String, events: List<XtreamEpg>) = withContext(Dispatchers.IO) {
        val at = System.currentTimeMillis()
        val trimmed = RowGuide.trim(events, at / 1000)
        memory[key(account.key, id)] = Entry(at, trimmed)
        runCatching { dao.putRowEpg(IptvRowEpgRow(account.key, id, at, RowGuide.encode(trimmed))) }
        Unit
    }
}
