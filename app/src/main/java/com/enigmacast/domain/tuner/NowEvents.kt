package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry

/** The programme on air on one channel, from OpenWebif `/api/epgnow?bRef=…`. */
data class NowEvent(
    val reference: String,
    val title: String,
    val beginEpochSec: Long,
    val durationSec: Long,
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
 */
object NowEvents {
    /** Re-read a bouquet after this long, even when nothing has ended. */
    const val MAX_AGE_SEC = 300L

    /** Never re-read a bouquet more often than this, even when a programme has ended. */
    const val MIN_AGE_SEC = 30L

    fun key(reference: String): String {
        return reference.trim().split(':').take(10).joinToString(":") { field ->
            field.uppercase().trimStart('0').ifEmpty { "0" }
        }
    }

    fun index(events: List<NowEvent>): Map<String, NowEvent> = events.associateBy { key(it.reference) }

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

    /** The channel to focus in the list: the one playing, else the first. */
    fun channelFocusIndex(channels: List<BouquetEntry>, currentReference: String): Int {
        if (currentReference.isBlank()) return 0
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
