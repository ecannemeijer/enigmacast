package com.enigmacast.domain.xtream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.longOrNull

/**
 * Now/next for the rows of the IPTV channel list: when a row needs a lookup, what is kept of
 * a channel's listings, and how they are stored. Pure, so it is tested without Android.
 */
object RowGuide {
    /** Rows are looked up once scrolling has rested this long (holding Up/Down never fetches). */
    const val SETTLE_MS = 450L

    /** Xtream requests in flight at once for the visible rows. */
    const val CONCURRENCY = 4

    /** A channel's listings stay good this long. */
    const val TTL_MS = 20 * 60_000L

    /** A channel without a programme on now is asked again after this long. */
    const val EMPTY_RETRY_MS = 10 * 60_000L

    /** A failed request (timeout, HTTP error) is not repeated sooner than this. */
    const val FAIL_RETRY_MS = 60_000L

    /** What a row keeps: what is on now and the coming day, at most [MAX_EVENTS]. */
    const val MAX_EVENTS = 24
    private const val AHEAD_SEC = 24 * 3600L

    /**
     * Whether a row needs its listings (again). [fetchedAtMs] null: never looked up.
     * A failure only blocks the row for [FAIL_RETRY_MS]; it is never remembered as "no guide".
     */
    fun needsFetch(fetchedAtMs: Long?, events: List<XtreamEpg>?, failedAtMs: Long?, nowMs: Long): Boolean {
        if (failedAtMs != null && nowMs - failedAtMs < FAIL_RETRY_MS) return false
        if (fetchedAtMs == null || events == null) return true
        val age = nowMs - fetchedAtMs
        if (age >= TTL_MS) return true
        if (events.airingAt(nowMs / 1000) != null) return false
        return age >= EMPTY_RETRY_MS
    }

    /** The programme on now and what follows within a day; a full table's archive is dropped. */
    fun trim(events: List<XtreamEpg>, nowSec: Long): List<XtreamEpg> =
        events.asSequence()
            .filter { it.endSec > nowSec && it.startSec < nowSec + AHEAD_SEC }
            .sortedBy { it.startSec }
            .take(MAX_EVENTS)
            .toList()

    fun encode(events: List<XtreamEpg>): String = JsonArray(
        events.map {
            JsonObject(
                mapOf(
                    "t" to JsonPrimitive(it.title),
                    "s" to JsonPrimitive(it.startSec),
                    "e" to JsonPrimitive(it.endSec),
                    "d" to JsonPrimitive(it.description),
                ),
            )
        },
    ).toString()

    fun decode(text: String): List<XtreamEpg>? = runCatching {
        (Json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = (o["s"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val end = (o["e"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            XtreamEpg((o["t"] as? JsonPrimitive)?.content.orEmpty(), start, end, (o["d"] as? JsonPrimitive)?.content.orEmpty())
        }
    }.getOrNull()
}
