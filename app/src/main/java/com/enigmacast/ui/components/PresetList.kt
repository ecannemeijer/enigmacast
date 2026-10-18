package com.enigmacast.ui.components

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
 * list will be measured at when it opens.
 */
@Stable
class PresetList {
    val state = LazyListState()
    /** Rows that fitted the last time the list was on screen. */
    var rows by mutableIntStateOf(ListAnchor.DEFAULT_ROWS)
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
        if (hidden && anchor >= 0 && size > 0) state.requestScrollToItem(ListAnchor.firstVisible(anchor, rows, size))
    }
}

/** While the list is shown: remembers how many rows fit (for the next preset). */
@Composable
fun PresetList.TrackRows() {
    LaunchedEffect(this) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.size }.filter { it > 2 }.collect { rows = it }
    }
}

/**
 * Once the open list is laid out: when [anchor] is off screen (it moved while open, or the
 * preset could not know it), jump it to the middle. When it is on screen nothing moves.
 */
suspend fun PresetList.ensureVisible(anchor: Int, size: Int) {
    if (anchor < 0 || size <= 0) return
    withTimeoutOrNull(1_000) { snapshotFlow { state.layoutInfo.visibleItemsInfo.size }.first { it > 0 } } ?: return
    val visible = state.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return
    if (!ListAnchor.onScreen(anchor, visible.first().index, visible.last().index)) {
        state.scrollToItem(ListAnchor.firstVisible(anchor, visible.size.coerceAtLeast(rows), size))
    }
}
