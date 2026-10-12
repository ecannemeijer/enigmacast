package com.enigmacast.domain.search

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.tuner.NowEvents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One programme from `/api/epgsearch`, with the channel name OpenWebif puts in `sname`. */
data class EpgSearchEvent(
    val event: GuideEvent,
    val channelName: String,
    /** Other names the programme is known by (the TMDB title of a film), also searched. */
    val alsoKnownAs: String = "",
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
    /** The guide programme behind a NOW/LATER hit (for AutoSwitch and Record). */
    val event: GuideEvent? = null,
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

    /** Exact channel name, else a live programme, else the first remaining row. */
    fun playHit(query: String): SearchHit? {
        val wanted = query.trim()
        val channels = stations.filter { it.kind == SearchKind.CHANNEL }
        return channels.firstOrNull { it.channelName.equals(wanted, ignoreCase = true) || it.title.equals(wanted, ignoreCase = true) }
            ?: now.firstOrNull()
            ?: (now + later + recordings + stations).firstOrNull()
    }
}

/** Visible first line: channel before the programme, bouquet before the channel. */
object SearchRows {
    fun headline(hit: SearchHit): String = when (hit.kind) {
        SearchKind.NOW, SearchKind.LATER, SearchKind.RECORDING -> join(hit.channelName, hit.title)
        SearchKind.CHANNEL -> join(hit.bouquetName, hit.channelName.ifBlank { hit.title })
        SearchKind.BOUQUET, SearchKind.TMDB -> hit.title
    }

    fun detail(hit: SearchHit, whenText: String, bouquetLabel: String): String = when (hit.kind) {
        SearchKind.LATER, SearchKind.RECORDING -> whenText
        SearchKind.BOUQUET -> bouquetLabel
        SearchKind.NOW, SearchKind.CHANNEL, SearchKind.TMDB -> ""
    }

    private fun join(prefix: String, name: String): String {
        val head = prefix.trim()
        val rest = name.trim()
        if (head.isBlank()) return rest
        if (rest.isBlank() || head.equals(rest, ignoreCase = true)) return head
        return "$head  ·  $rest"
    }
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
        zone: ZoneId = ZoneId.systemDefault(),
    ): SearchResults = group(SearchQuery.parse(query, nowSec, zone), nowSec, programmes, recordings, bouquets, channels, zone)

    fun group(
        parsed: ParsedSearch,
        nowSec: Long,
        programmes: List<EpgSearchEvent>,
        recordings: List<Recording>,
        bouquets: List<BouquetEntry>,
        channels: List<ListedChannel>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SearchResults {
        if (!parsed.isSearchable) return SearchResults()
        val text = parsed.text
        val titles = text.length >= MIN_QUERY
        val seen = HashSet<String>()
        val now = ArrayList<SearchHit>()
        val later = ArrayList<SearchHit>()
        if (titles) {
            programmes.forEach { found ->
                if (!contains(found.event.title, text) && !contains(found.alsoKnownAs, text)) return@forEach
                val key = found.event.programmeKey
                if (!seen.add(key)) return@forEach
                val hit = SearchHit(
                    kind = if (found.event.isAiring(nowSec)) SearchKind.NOW else SearchKind.LATER,
                    title = found.event.title,
                    serviceReference = found.event.reference,
                    channelName = found.channelName,
                    beginEpochSec = found.event.beginEpochSec,
                    endEpochSec = found.event.endEpochSec,
                    event = found.event,
                )
                if (hit.kind == SearchKind.NOW) now += hit else later += hit
            }
        }
        val recorded = recordings.filter { item ->
            val dayOk = parsed.recordingDay == null || item.startedOn(parsed.recordingDay, zone)
            val textOk = !titles || contains(item.title, text) || contains(item.channelName, text)
            dayOk && textOk
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
        val bouquetHits = if (titles) {
            bouquets.filter { contains(it.name, text) }
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
        } else {
            emptyList()
        }
        val channelHits = if (titles) {
            channels.filter { contains(it.channel.name, text) }
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
        } else {
            emptyList()
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

    private fun contains(value: String, query: String): Boolean = SearchText.contains(value, query)

    private fun Recording.startedOn(day: LocalDate, zone: ZoneId): Boolean {
        return Instant.ofEpochSecond(beginEpochSec).atZone(zone).toLocalDate() == day
    }
}

/** Case- and accent-insensitive matching: "cafe" finds "Café", "ZDF" finds "zdf". */
object SearchText {
    private val marks = Regex("\\p{Mn}+")

    fun fold(value: String): String {
        if (value.all { it.code < 128 }) return value.lowercase()
        return marks.replace(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD), "")
            .replace("ß", "ss").replace("ø", "o").replace("Ø", "o").replace("æ", "ae").replace("Æ", "ae").replace("ł", "l")
            .lowercase()
    }

    fun contains(value: String, query: String): Boolean = query.isEmpty() || fold(value).contains(fold(query))
}
