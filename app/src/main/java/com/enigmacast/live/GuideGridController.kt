package com.enigmacast.live

import com.enigmacast.domain.tuner.ServiceMatch
import com.enigmacast.domain.epg.GuideCell
import com.enigmacast.domain.epg.GuideCursor
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.GuideTimeline
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.tuner.ChannelListNavigation
import com.enigmacast.domain.tuner.NowEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the programme grid shows: one bouquet's channels, the highlight and the loaded programmes. */
data class GuideGridState(
    val bouquetReference: String = "",
    val channels: List<BouquetEntry> = emptyList(),
    val cursor: GuideCursor = GuideCursor(),
    /** Programmes per channel (key [NowEvents.key]), loaded in blocks. */
    val events: Map<String, List<GuideEvent>> = emptyMap(),
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val focusedChannel: BouquetEntry? get() = channels.getOrNull(cursor.row)

    fun eventsOf(entry: BouquetEntry): List<GuideEvent> = events[NowEvents.key(entry.reference)].orEmpty()
}

/**
 * The programme grid of the dashboard: channels as rows, programmes on a time axis. The
 * highlight moves here (no per-cell focus), and programmes are loaded per bouquet in 3-hour
 * blocks for the visible window only ([GuideRepository.bouquetChunk], cached there).
 */
class GuideGridController(
    private val scope: CoroutineScope,
    /** One 3-hour block of the shown list's programmes; null when it failed (asked again on refresh). */
    private val loadChunk: suspend (bouquetReference: String, chunkStartSec: Long) -> List<GuideEvent>?,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** The receiver's bouquets (OpenWebif, cached in [GuideRepository]). */
    constructor(
        scope: CoroutineScope,
        repository: GuideRepository,
        receiver: suspend () -> ReceiverConfig?,
        clock: () -> Long = { System.currentTimeMillis() / 1000 },
    ) : this(scope, { bouquet, chunk -> receiver()?.let { repository.bouquetChunk(it, bouquet, chunk) } }, clock)

    private val _state = MutableStateFlow(GuideGridState())
    val state: StateFlow<GuideGridState> = _state.asStateFlow()
    private val loaded = mutableSetOf<Long>()
    private val pending = mutableSetOf<Long>()

    /** Shows [channels] of [bouquetReference]; a new bouquet starts at now on [focusReference]. */
    fun show(bouquetReference: String, channels: List<BouquetEntry>, focusReference: String) {
        val current = _state.value
        if (bouquetReference == current.bouquetReference && channels == current.channels) return
        val now = clock()
        val sameBouquet = bouquetReference == current.bouquetReference
        if (!sameBouquet) {
            loaded.clear()
            pending.clear()
        }
        // A focus asked for before this list arrived (the playing channel in another bouquet, or
        // a grid that was still loading) wins once its channel is in the list.
        val pending = pendingFocus
        val pendingRow = pending?.let { ChannelListNavigation.channelIndexOrNull(channels, it) }
        val row = pendingRow ?: ChannelListNavigation.channelFocusIndex(channels, focusReference)
        val cursor = if (pendingRow != null) {
            pendingFocus = null
            GuideCursor(row, now, GuideTimeline.slotFloor(now))
        } else if (sameBouquet && current.channels.isNotEmpty()) {
            current.cursor.copy(row = row)
        } else {
            GuideCursor(row, now, GuideTimeline.slotFloor(now))
        }
        _state.update {
            it.copy(
                bouquetReference = bouquetReference,
                channels = channels,
                cursor = GuideTimeline.clamp(cursor, now, channels.size),
                events = if (sameBouquet) it.events else emptyMap(),
                failed = false,
            )
        }
        ensureData()
    }

    /** Channel waiting for its bouquet (see [focusChannel]); null when none. */
    @Volatile private var pendingFocus: String? = null

    /** Puts the highlight on the first channel at the current time. */
    fun focusFirst() {
        pendingFocus = null
        val now = clock()
        val channels = _state.value.channels
        _state.update { it.copy(cursor = GuideTimeline.clamp(GuideCursor(0, now, GuideTimeline.slotFloor(now)), now, channels.size)) }
        ensureData()
    }

    /**
     * Puts the highlight on [reference] at the current time, e.g. after Back from the player.
     * Returns false when the shown list does not hold it (yet): the highlight then moves there
     * as soon as a list with that channel is shown, and stays where it is until then.
     */
    fun focusChannel(reference: String): Boolean {
        val now = clock()
        val channels = _state.value.channels
        val row = ChannelListNavigation.channelIndexOrNull(channels, reference)
        if (row == null) {
            pendingFocus = reference.takeIf { it.isNotBlank() }
            return false
        }
        pendingFocus = null
        _state.update { it.copy(cursor = GuideTimeline.clamp(GuideCursor(row, now, GuideTimeline.slotFloor(now)), now, channels.size)) }
        ensureData()
        return true
    }

    /**
     * Programmes that arrive per channel instead of per block (IPTV listings), merged into the
     * list on screen; ignored when [bouquetReference] is no longer shown.
     */
    fun putEvents(bouquetReference: String, events: List<GuideEvent>) {
        if (events.isEmpty()) return
        _state.update {
            if (it.bouquetReference != bouquetReference) it
            else it.copy(events = GuideTimeline.merge(it.events, ServiceMatch.guideEvents(events, it.channels)))
        }
    }

    /** Loads blocks again that were not loaded yet or failed (the repository answers fresh ones from its cache). */
    fun refresh() {
        loaded.clear()
        ensureData()
    }

    fun moveRows(delta: Int) {
        val state = _state.value
        val rows = state.channels.size
        val cursor = GuideTimeline.clamp(GuideTimeline.moveVertical(state.cursor, delta, rows), clock(), rows)
        _state.update { it.copy(cursor = cursor) }
        if (cursor.windowStartSec != state.cursor.windowStartSec) ensureData()
    }

    /** Moves one programme left or right; false when Left is already on the programme on air. */
    fun moveTime(direction: Int): Boolean {
        val state = _state.value
        val entry = state.focusedChannel ?: return direction > 0
        val now = clock()
        val cursor = GuideTimeline.clamp(state.cursor, now, state.channels.size)
        val move = GuideTimeline.moveHorizontal(state.eventsOf(entry), cursor, direction, now)
        if (move.atLeftEdge) return false
        _state.update { it.copy(cursor = move.cursor) }
        if (move.cursor.windowStartSec != state.cursor.windowStartSec) ensureData()
        return true
    }

    /** The highlighted channel and cell. */
    fun focused(): Pair<BouquetEntry, GuideCell>? {
        val state = _state.value
        val entry = state.focusedChannel ?: return null
        val cell = GuideTimeline.focusedCell(state.eventsOf(entry), state.cursor, clock()) ?: return null
        return entry to cell
    }

    private fun ensureData() {
        val bouquet = _state.value.bouquetReference
        if (bouquet.isBlank()) return
        GuideTimeline.chunksFor(_state.value.cursor.windowStartSec).forEach { chunk ->
            if (chunk in loaded || chunk in pending) return@forEach
            pending += chunk
            scope.launch {
                val events = loadChunk(bouquet, chunk)
                if (bouquet != _state.value.bouquetReference) return@launch
                pending -= chunk
                if (events == null) {
                    _state.update { it.copy(failed = true, loading = pending.isNotEmpty()) }
                    return@launch
                }
                loaded += chunk
                _state.update {
                    // Programmes whose reference is written differently from the list's still land on their row.
                    val matched = ServiceMatch.guideEvents(events, it.channels)
                    it.copy(events = GuideTimeline.merge(it.events, matched), failed = false, loading = pending.isNotEmpty())
                }
            }
        }
        _state.update { it.copy(loading = pending.isNotEmpty()) }
    }
}
