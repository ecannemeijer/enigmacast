package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.ServiceStreams

/** The programme on air on one channel, from OpenWebif `/api/epgnow?bRef=…`. */
data class NowEvent(
    val reference: String,
    val title: String,
    val beginEpochSec: Long,
    val durationSec: Long,
    /** OpenWebif event id, for a receiver timer. -1 when the answer had none. */
    val eventId: Int = -1,
    /** Other service references OpenWebif listed for this same programme (`asrefs`). */
    val alternatives: List<String> = emptyList(),
) {
    val endEpochSec: Long get() = beginEpochSec + durationSec

    fun progress(nowSec: Long): Float {
        if (durationSec <= 0) return 0f
        return ((nowSec - beginEpochSec).toFloat() / durationSec).coerceIn(0f, 1f)
    }
}

/**
 * Matches now-events to channels. OpenWebif returns one list per bouquet; channels are
 * matched on the first ten fields of the service reference, ignoring hex case and
 * leading zeros, so `1:0:19:0A:…` and `1:0:19:a:…` are the same channel.
 *
 * DVB references (`1:…`) also ignore the service type, so HD (`19`), H.264 (`22`) and
 * HEVC (`25`) match the SD (`1`) entry the EPG cache often stores. A linked subservice
 * is matched on its parent: OpenWebif looks the programme up there and returns that
 * reference. IPTV references keep every field, because the stream identity is not a SID.
 */
object NowEvents {
    /** Re-read a bouquet after this long, even when nothing has ended. */
    const val MAX_AGE_SEC = 300L

    /** Never re-read a bouquet more often than this, even when a programme has ended. */
    const val MIN_AGE_SEC = 30L

    fun key(reference: String): String {
        val fields = reference.trim().split(':').take(10).map { field ->
            field.uppercase().trimStart('0').ifEmpty { "0" }
        }.toMutableList()
        if (fields.firstOrNull() == "1") {
            while (fields.size < 10) fields += "0"
            val parentTsid = fields[8]
            if (parentTsid != "0") {
                fields[3] = fields[7]
                fields[4] = parentTsid
                fields[7] = "0"
                fields[8] = "0"
            }
            fields[2] = "0"
        }
        val base = fields.joinToString(":")
        // Internet streams share their ten fields: the stream address keeps them apart.
        if (fields.firstOrNull() != "1" || reference.contains("%3a", true) || reference.contains("://")) {
            ServiceStreams.internetUrl(reference)?.let { return "$base|$it" }
        }
        return base
    }

    fun index(events: List<NowEvent>): Map<String, NowEvent> {
        val map = LinkedHashMap<String, NowEvent>()
        for (event in events) map[key(event.reference)] = event
        for (event in events) {
            for (alternative in event.alternatives) map.putIfAbsent(key(alternative), event)
        }
        return map
    }

    /** The programme on [reference] at [nowSec], or null when unknown or already over. */
    fun lookup(index: Map<String, NowEvent>, reference: String, nowSec: Long): NowEvent? {
        val event = index[key(reference)] ?: return null
        return if (event.endEpochSec > nowSec) event else null
    }

    fun isStale(fetchedAtSec: Long, events: Collection<NowEvent>, nowSec: Long): Boolean {
        val age = nowSec - fetchedAtSec
        if (age >= MAX_AGE_SEC) return true
        if (age < MIN_AGE_SEC) return false
        return events.any { it.endEpochSec <= nowSec }
    }
}

/** Which part of the full-screen channel list has the D-pad. */
enum class ListPane { CLOSED, CHANNELS, BOUQUETS, EPG }

/**
 * The full-screen channel list: OK opens it on the current channel, Left reveals the
 * bouquets on the current bouquet, OK or Right on a bouquet shows its channels and goes
 * back to the channel list, Right on a channel opens that channel's programme guide next
 * to it (Left or Back returns to the channel), Back steps back one level.
 */
object ChannelListNavigation {
    fun open(): ListPane = ListPane.CHANNELS

    fun left(pane: ListPane): ListPane = when (pane) {
        ListPane.CHANNELS -> ListPane.BOUQUETS
        ListPane.EPG -> ListPane.CHANNELS
        else -> pane
    }

    fun right(pane: ListPane): ListPane = if (pane == ListPane.CHANNELS) ListPane.EPG else pane

    fun back(pane: ListPane): ListPane = when (pane) {
        ListPane.BOUQUETS, ListPane.EPG -> ListPane.CHANNELS
        ListPane.CHANNELS, ListPane.CLOSED -> ListPane.CLOSED
    }

    fun bouquetChosen(): ListPane = ListPane.CHANNELS

    /** The bouquet to focus when the bouquet column opens: the active one, else the first. */
    fun bouquetFocusIndex(bouquets: List<BouquetEntry>, activeReference: String): Int {
        if (activeReference.isBlank()) return 0
        val active = bouquetIdentity(activeReference)
        return bouquets.indexOfFirst { bouquetIdentity(it.reference) == active }.coerceAtLeast(0)
    }

    /**
     * The channel to focus in the list: the one playing, else the first.
     * Internet streams often share the first ten service fields and differ only by URL, so those
     * are matched on the bouquet row itself. The ten-field key is only the fallback for DVB,
     * where HD and SD are the same channel.
     */
    fun channelFocusIndex(channels: List<BouquetEntry>, currentReference: String): Int {
        if (currentReference.isBlank() || channels.isEmpty()) return 0
        val exact = channels.indexOfFirst { it.reference.equals(currentReference, ignoreCase = true) }
        if (exact >= 0) return exact
        if (ServiceStreams.isInternet(currentReference)) {
            val byUrl = channels.indexOfFirst { BouquetChannels.sameService(it.reference, currentReference) }
            if (byUrl >= 0) return byUrl
        }
        val current = NowEvents.key(currentReference)
        return channels.indexOfFirst { NowEvents.key(it.reference) == current }.coerceAtLeast(0)
    }

    /** OpenWebif writes bouquet references with `"`, `%22` or `&quot;`; compare the `FROM BOUQUET …` part. */
    fun bouquetIdentity(reference: String): String {
        val decoded = reference.replace("%22", "\"").replace("&quot;", "\"").trim()
        val index = decoded.indexOf("FROM BOUQUET", ignoreCase = true)
        return if (index >= 0) decoded.substring(index) else decoded
    }
}
