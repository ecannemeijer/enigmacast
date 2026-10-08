package com.enigmacast.domain.search

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.tuner.NowEvents

/** One programme from `/api/epgsearch`, with the channel name OpenWebif puts in `sname`. */
data class EpgSearchEvent(
    val event: GuideEvent,
    val channelName: String,
)

/**
 * What a search row is. [TMDB] is reserved for a later metadata source and is not produced yet.
 */
enum class SearchKind {
    NOW,
    LATER,
    RECORDING,
    CHANNEL,
    BOUQUET,
    TMDB,
}

/** One row in the search list. The panel formats the second line from these fields. */
data class SearchHit(
    val kind: SearchKind,
    val title: String,
    val serviceReference: String = "",
    val channelName: String = "",
    val bouquetReference: String = "",
    val bouquetName: String = "",
    val recordingId: String = "",
    val beginEpochSec: Long = 0L,
    val endEpochSec: Long = 0L,
)

/** A channel as it sits in one bouquet, so choosing it can switch the bouquet too. */
data class ListedChannel(
    val bouquet: BouquetEntry,
    val channel: BouquetEntry,
)

data class SearchResults(
    val now: List<SearchHit> = emptyList(),
    val later: List<SearchHit> = emptyList(),
    val recordings: List<SearchHit> = emptyList(),
    val stations: List<SearchHit> = emptyList(),
) {
    val isEmpty: Boolean
        get() = now.isEmpty() && later.isEmpty() && recordings.isEmpty() && stations.isEmpty()
}

/**
 * Groups a query into what is on now, later EPG, recordings and channel or bouquet names.
 * Matching is case-insensitive. A programme is kept when its title contains the query;
 * the same programme on the same channel is listed once.
 */
object SearchGrouping {
    const val MIN_QUERY = 2
    const val MAX_PER_GROUP = 40

    fun group(
        query: String,
        nowSec: Long,
        programmes: List<EpgSearchEvent>,
        recordings: List<Recording>,
        bouquets: List<BouquetEntry>,
        channels: List<ListedChannel>,
    ): SearchResults {
        val text = query.trim()
        if (text.length < MIN_QUERY) return SearchResults()
        val seen = HashSet<String>()
        val now = ArrayList<SearchHit>()
        val later = ArrayList<SearchHit>()
        programmes.forEach { found ->
            if (!contains(found.event.title, text)) return@forEach
            val key = found.event.programmeKey
            if (!seen.add(key)) return@forEach
            val hit = SearchHit(
                kind = if (found.event.isAiring(nowSec)) SearchKind.NOW else SearchKind.LATER,
                title = found.event.title,
                serviceReference = found.event.reference,
                channelName = found.channelName,
                beginEpochSec = found.event.beginEpochSec,
                endEpochSec = found.event.endEpochSec,
            )
            if (hit.kind == SearchKind.NOW) now += hit else later += hit
        }
        val recorded = recordings.filter { item ->
            contains(item.title, text) || contains(item.channelName, text)
        }.sortedByDescending { it.beginEpochSec }.take(MAX_PER_GROUP).map { item ->
            SearchHit(
                kind = SearchKind.RECORDING,
                title = item.title,
                serviceReference = item.reference,
                channelName = item.channelName,
                recordingId = item.id,
                beginEpochSec = item.beginEpochSec,
                endEpochSec = item.endEpochSec,
            )
        }
        val bouquetHits = bouquets.filter { contains(it.name, text) }
            .sortedBy { it.name.lowercase() }
            .take(MAX_PER_GROUP)
            .map { bouquet ->
                SearchHit(
                    kind = SearchKind.BOUQUET,
                    title = bouquet.name,
                    bouquetReference = bouquet.reference,
                    bouquetName = bouquet.name,
                )
            }
        val channelHits = channels.filter { contains(it.channel.name, text) }
            .distinctBy { NowEvents.key(it.channel.reference) }
            .sortedBy { it.channel.name.lowercase() }
            .take(MAX_PER_GROUP)
            .map { listed ->
                SearchHit(
                    kind = SearchKind.CHANNEL,
                    title = listed.channel.name,
                    serviceReference = listed.channel.reference,
                    channelName = listed.channel.name,
                    bouquetReference = listed.bouquet.reference,
                    bouquetName = listed.bouquet.name,
                )
            }
        return SearchResults(
            now = now.sortedBy { it.title.lowercase() }.take(MAX_PER_GROUP),
            later = later.sortedWith(compareBy<SearchHit> { it.beginEpochSec }.thenBy { it.title.lowercase() }).take(MAX_PER_GROUP),
            recordings = recorded,
            stations = bouquetHits + channelHits,
        )
    }

    /** The bouquet row that contains [serviceReference], using the same service identity as zapping. */
    fun placeOf(serviceReference: String, channels: List<ListedChannel>): ListedChannel? {
        return channels.firstOrNull { BouquetChannels.sameService(it.channel.reference, serviceReference) }
    }

    private fun contains(value: String, query: String): Boolean = value.contains(query, ignoreCase = true)
}
