package com.enigmacast.ui.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.enigmacast.domain.remote.ListAnchor
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The scroll state of a list that is hidden most of the time (the live TV channel and category
 * lists), kept above the overlay so it outlives the list. While hidden it is moved silently to
 * its anchor on every zap: nothing is composed or drawn for it, it only records the position the
 * list will be measured at when it opens. The list must use [com.enigmacast.ui.theme.MinimalBringIntoView]
 * so focusing the (visible) anchor does not scroll it again.
 */
@Stable
class PresetList {
    val state = LazyListState()
    /** Rows that fitted fully the last time the list was laid out (viewport ÷ tallest row + spacing). */
    var rows by mutableIntStateOf(ListAnchor.DEFAULT_ROWS)

    /** The one place the anchor goes: the same rule while hidden and when the open list checks it. */
    fun firstVisibleFor(anchor: Int, size: Int): Int = ListAnchor.firstVisible(anchor, rows, size)

    internal fun measure(info: LazyListLayoutInfo) {
        val items = info.visibleItemsInfo
        if (items.size < 2) return
        val pitch = items.maxOf { it.size } + info.mainAxisItemSpacing
        val viewport = info.viewportEndOffset - info.viewportStartOffset - info.beforeContentPadding - info.afterContentPadding
        rows = ListAnchor.fullRows(viewport, pitch)
    }
}

@Composable
fun rememberPresetList(): PresetList = remember { PresetList() }

/**
 * While the list is hidden: [anchor] in the middle, without scrolling (applied when the list is
 * next measured). Runs only when the anchor, the list or [hidden] change.
 */
@Composable
fun PresetList.KeepAnchored(anchor: Int, size: Int, hidden: Boolean) {
    LaunchedEffect(this, anchor, size, hidden) {
        if (hidden && anchor >= 0 && size > 0) state.requestScrollToItem(firstVisibleFor(anchor, size))
    }
}

/** While the list is shown: measures how many rows fit (for the next preset). */
@Composable
fun PresetList.TrackRows() {
    LaunchedEffect(this) {
        snapshotFlow { state.layoutInfo }.filter { it.visibleItemsInfo.size > 2 }.collect { measure(it) }
    }
}

/**
 * Once the open list is laid out: when [anchor] is not fully on screen (it moved while open, the
 * list changed), put it where [PresetList.firstVisibleFor] puts it. When it is, nothing moves.
 */
suspend fun PresetList.ensureVisible(anchor: Int, size: Int) {
    if (anchor < 0 || size <= 0) return
    withTimeoutOrNull(1_000) { snapshotFlow { state.layoutInfo.visibleItemsInfo.size }.first { it > 0 } } ?: return
    val info = state.layoutInfo
    measure(info)
    val row = info.visibleItemsInfo.firstOrNull { it.index == anchor }
    val visible = row != null && ListAnchor.fullyVisible(row.offset, row.offset + row.size, info.viewportStartOffset, info.viewportEndOffset)
    if (!visible) state.scrollToItem(firstVisibleFor(anchor, size))
}
