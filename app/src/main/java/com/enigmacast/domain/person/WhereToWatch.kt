package com.enigmacast.domain.person

import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchResults
import com.enigmacast.domain.xtream.XtreamEpg

/** One source row of "where to watch": off (not available), still searching, or done (maybe empty). */
sealed interface SourceState<out T> {
    data object Off : SourceState<Nothing>
    data object Searching : SourceState<Nothing>
    data class Done<T>(val items: List<T>) : SourceState<T>

    val found: List<T> get() = (this as? Done<T>)?.items.orEmpty()
}

/** A programme in an IPTV channel's own (Xtream) guide that matches the title. */
data class IptvEpgHit(
    val source: String,
    val streamId: String,
    val programme: XtreamEpg,
)

object WhereToWatch {
    /** The titles to search for: the shown title, then the original one when it differs. */
    fun queries(title: String, originalTitle: String): List<String> =
        listOf(title, originalTitle).map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }

    /**
     * Guide results split by where the channel comes from: satellite (the receiver's own
     * services) first, IPTV (stream services in the receiver's bouquets) second. Only
     * programmes (now and later), each once, in time order.
     */
    fun splitEpg(results: List<SearchResults>, isIptv: (String) -> Boolean): Pair<List<SearchHit>, List<SearchHit>> {
        val all = results.flatMap { it.now + it.later }
            .distinctBy { "${it.serviceReference}:${it.beginEpochSec}" }
            .sortedBy { it.beginEpochSec }
        return all.partition { !isIptv(it.serviceReference) }
    }

    /** Recordings found by any of the queries, each once. */
    fun recordings(results: List<SearchResults>): List<SearchHit> =
        results.flatMap { it.recordings }.distinctBy { it.recordingId }

    /** Whether a guide title is the searched title (ignoring case; the guide may add a prefix or suffix). */
    fun matches(programme: String, queries: List<String>): Boolean =
        queries.any { q -> programme.contains(q, ignoreCase = true) }

    /**
     * Programmes in the IPTV channels' own guides ([rows]: source, stream id, listings) that
     * match and have not ended at [nowSec], in time order, at most [limit].
     */
    fun iptvEpg(rows: List<Triple<String, String, List<XtreamEpg>>>, queries: List<String>, nowSec: Long, limit: Int = LIMIT): List<IptvEpgHit> =
        rows.flatMap { (source, id, events) ->
            events.filter { it.endSec > nowSec && matches(it.title, queries) }.map { IptvEpgHit(source, id, it) }
        }
            .distinctBy { "${it.source}/${it.streamId}/${it.programme.startSec}" }
            .sortedBy { it.programme.startSec }
            .take(limit)

    const val LIMIT = 50
}
