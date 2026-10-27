package com.enigmacast.ui.xtream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.LastIptvChannel
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.live.GuideGridController
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rows, programmes and the opening spot of the IPTV TV guide. Pure, so the choices are unit-tested.
 * Rows carry a synthetic reference (`iptv:<stream id>`), never a stream address.
 */
object IptvGuideRows {
    private const val PREFIX = "iptv:"
    private const val CATEGORY_PREFIX = "iptvcat:"

    fun reference(item: XtreamItem): String = PREFIX + item.id.replace(':', '_')

    fun reference(streamId: String): String = PREFIX + streamId.replace(':', '_')

    fun entry(item: XtreamItem, index: Int): BouquetEntry =
        BouquetEntry(name = item.name, reference = reference(item), number = item.number.takeIf { it > 0 } ?: (index + 1))

    fun categoryReference(category: XtreamCategory): String = "$CATEGORY_PREFIX${category.providerId}/${category.id}"

    fun categoryEntry(category: XtreamCategory): BouquetEntry = BouquetEntry(name = category.name, reference = categoryReference(category))

    /** Xtream listings as grid programmes; programmes without a length are dropped. */
    fun events(item: XtreamItem, listings: List<XtreamEpg>): List<GuideEvent> {
        val reference = reference(item)
        return listings.filter { it.endSec > it.startSec }.map {
            GuideEvent(
                eventId = (it.startSec xor item.id.hashCode().toLong()).toInt(),
                reference = reference,
                title = it.title,
                shortDescription = "",
                plot = it.description,
                beginEpochSec = it.startSec,
                durationSec = it.endSec - it.startSec,
            )
        }
    }

    /** Where the guide opens: a category and, when known, the channel to highlight there. */
    data class Target(val category: XtreamCategory, val streamId: String?)

    /**
     * The playing live channel, else the last watched one, else the first category (its first
     * channel). A channel whose category is not in [categories] (hidden, or not for hotel guests)
     * is skipped.
     */
    fun target(playing: XtreamItem?, last: LastIptvChannel?, categories: List<XtreamCategory>): Target? {
        if (categories.isEmpty()) return null
        fun categoryOf(categoryId: String, providerId: String): XtreamCategory? =
            if (categoryId.isBlank()) null else categories.firstOrNull {
                it.id == categoryId && (providerId.isBlank() || it.providerId.isBlank() || it.providerId == providerId)
            }
        playing?.takeIf { it.id.isNotBlank() }?.let { item ->
            categoryOf(item.categoryId, item.providerId)?.let { return Target(it, item.id) }
        }
        last?.takeIf { it.streamId.isNotBlank() }?.let { channel ->
            categoryOf(channel.categoryId, channel.providerId)?.let { return Target(it, channel.streamId) }
        }
        return Target(categories.first(), null)
    }

    /** The rows whose listings are looked up: those on screen around the highlight, and a few beyond. */
    fun rowsAround(row: Int, size: Int, before: Int = 6, after: Int = 12): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val center = row.coerceIn(0, size - 1)
        return (center - before).coerceAtLeast(0)..(center + after).coerceAtMost(size - 1)
    }
}

/**
 * The IPTV TV guide: the channels of one live category as grid rows, with the provider's
 * listings (the same Room-cached Xtream EPG the channel list uses, satellite guide as fallback)
 * loaded for the rows around the highlight. Categories switch like the satellite bouquets.
 */
class IptvGuide(
    private val scope: CoroutineScope,
    /** The live categories the viewer may see (hidden and hotel rules applied); null without a playlist. */
    private val categoriesOf: suspend () -> List<XtreamCategory>?,
    private val itemsOf: suspend (XtreamCategory) -> List<XtreamItem>,
    /** Listings for these channels; [onRow] once per channel as each answer arrives (empty: no guide). */
    private val listings: suspend (List<XtreamItem>, onRow: (XtreamItem, List<XtreamEpg>) -> Unit) -> Unit,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val grid = GuideGridController(scope, { _, _ -> emptyList() }, clock)

    var categories by mutableStateOf<List<XtreamCategory>>(emptyList())
        private set
    var category by mutableStateOf<XtreamCategory?>(null)
        private set
    var items by mutableStateOf<List<XtreamItem>>(emptyList())
        private set
    var loadingChannels by mutableStateOf(false)
        private set
    /** Row references whose listings were looked up (an empty one shows "no guide information"). */
    var checked by mutableStateOf<Set<String>>(emptySet())
        private set

    private var openJob: Job? = null
    private var rowsJob: Job? = null

    /**
     * Opens the guide on [target]'s category with the highlight on its channel at the current
     * time (the grid keeps that request until the rows are there).
     */
    fun open(target: suspend (List<XtreamCategory>) -> IptvGuideRows.Target?) {
        openJob?.cancel()
        openJob = scope.launch {
            loadingChannels = true
            try {
                val list = categoriesOf() ?: return@launch
                categories = list
                val chosen = target(list) ?: return@launch
                // Asked before the rows exist: the grid applies it once they are shown.
                chosen.streamId?.let { grid.focusChannel(IptvGuideRows.reference(it)) }
                show(chosen.category, chosen.streamId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EnigmaLog.service("IPTV guide failed: ${e.javaClass.simpleName}")
            } finally {
                loadingChannels = false
            }
        }
    }

    /** Another category from the column: its channels, highlight on the first at now. */
    fun previewCategory(entry: BouquetEntry) {
        val next = categories.firstOrNull { IptvGuideRows.categoryReference(it) == entry.reference } ?: return
        if (next == category) return
        openJob?.cancel()
        openJob = scope.launch {
            loadingChannels = true
            try {
                show(next, streamId = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EnigmaLog.service("IPTV guide category failed: ${e.javaClass.simpleName}")
            } finally {
                loadingChannels = false
            }
        }
    }

    private suspend fun show(next: XtreamCategory, streamId: String?) {
        val rows = itemsOf(next)
        category = next
        items = rows
        checked = emptySet()
        grid.show(IptvGuideRows.categoryReference(next), rows.mapIndexed { index, item -> IptvGuideRows.entry(item, index) }, "")
        // The same category again keeps its rows (show does nothing): put the highlight there now.
        if (streamId == null || !grid.focusChannel(IptvGuideRows.reference(streamId))) grid.focusFirst()
        requestRows(settle = false)
    }

    /** The channel on a grid row. */
    fun itemOf(entry: BouquetEntry): XtreamItem? = items.firstOrNull { IptvGuideRows.reference(it) == entry.reference }

    /**
     * Listings for the rows around the highlight. Waits until scrolling rests, so holding
     * Up/Down asks nothing; cached rows (memory, Room) come at once, the rest from the provider.
     */
    fun requestRows(settle: Boolean = true) {
        val shown = category ?: return
        val reference = IptvGuideRows.categoryReference(shown)
        rowsJob?.cancel()
        rowsJob = scope.launch {
            if (settle) delay(com.enigmacast.domain.xtream.RowGuide.SETTLE_MS)
            val state = grid.state.value
            val range = IptvGuideRows.rowsAround(state.cursor.row, items.size)
            val wanted = range.mapNotNull { items.getOrNull(it) }
            if (wanted.isEmpty()) return@launch
            listings(wanted) { item, events ->
                grid.putEvents(reference, IptvGuideRows.events(item, events))
                if (category == shown) checked = checked + IptvGuideRows.reference(item)
            }
        }
    }
}
