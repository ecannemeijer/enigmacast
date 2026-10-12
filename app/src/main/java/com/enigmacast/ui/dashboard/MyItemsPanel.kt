package com.enigmacast.ui.dashboard

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.asString
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun MyItemsPanel(
    listFocus: FocusRequester,
    menuFocus: FocusRequester,
    rowsFocusable: Boolean,
    onPlay: () -> Unit,
    onRetreatTo: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyItemsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.reloadNow()
        while (true) {
            viewModel.refresh()
            delay(20_000)
        }
    }
    CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
    Column(
        modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) },
    ) {
        PanelHeader(stringResource(R.string.nav_my_items))
        val notice = viewModel.notice.asString()
        if (notice.isNotBlank()) {
            Text(notice, color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        val boxMovies = viewModel.boxMovies
        val boxTimers = viewModel.boxTimers
        val all = items + boxMovies + boxTimers
        if (all.isEmpty()) {
            Text(
                stringResource(R.string.my_items_empty),
                color = EnigmaColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .focusRequester(listFocus)
                    .focusProperties {
                        canFocus = rowsFocusable
                        left = menuFocus
                    }
                    .focusable()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        } else {
            val focusOn = viewModel.focusId?.takeIf { id -> all.any { it.id == id } } ?: all.first().id
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Sections: this app's recordings, recordings on the box, timers on the box. Headers are not focusable.
                listOf(
                    Triple("app", if (boxMovies.isEmpty() && boxTimers.isEmpty()) null else R.string.my_items_section_app, items),
                    Triple("movies", R.string.my_items_section_box, boxMovies),
                    Triple("timers", R.string.my_items_section_box_timers, boxTimers),
                ).forEach { (key, header, list) ->
                    if (list.isEmpty()) return@forEach
                    if (header != null) item(key = "h-$key") {
                        Text(stringResource(header), color = EnigmaColors.Cyan, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp))
                    }
                    itemsIndexed(list, key = { _, item -> item.id }) { _, item ->
                        RecordingRow(
                            item = item,
                            modifier = Modifier
                                .then(if (item.id == focusOn) Modifier.focusRequester(listFocus) else Modifier)
                                .focusProperties {
                                    canFocus = rowsFocusable
                                    left = menuFocus
                                },
                            onClick = { viewModel.open(item) },
                        )
                    }
                }
            }
        }
    }
    }
    val focusTokenOnEnter = remember { viewModel.focusToken }
    LaunchedEffect(viewModel.focusToken) {
        if (viewModel.focusToken == 0 || viewModel.focusToken == focusTokenOnEnter) return@LaunchedEffect
        if (viewModel.selected != null || viewModel.pendingDelete != null) return@LaunchedEffect
        val index = listIndexOf(viewModel.focusId, items, viewModel.boxMovies, viewModel.boxTimers)
        if (index >= 0) listState.scrollToItem(index)
        listFocus.requestWhenReady()
        if (viewModel.holdList) {
            repeat(2) { withFrameNanos { } }
            viewModel.releaseList()
        }
    }
    viewModel.selected?.let { item ->
        val actions = actionsFor(item, viewModel.isBox(item), viewModel.isBoxTimer(item))
        TvChoiceDialog(
            title = item.title,
            message = rowDetail(item),
            choices = actions.map { stringResource(it.label) },
            onChoose = { index ->
                when (actions[index]) {
                    ItemAction.PLAY -> viewModel.play(onPlay)
                    ItemAction.STOP -> viewModel.stop()
                    ItemAction.DELETE -> viewModel.askDelete()
                    ItemAction.CANCEL -> viewModel.dismiss()
                }
            },
            onDismiss = viewModel::dismiss,
            compact = true,
        )
    }
    viewModel.pendingDelete?.let { item ->
        TvConfirmDialog(
            title = stringResource(R.string.my_items_delete_title),
            message = stringResource(R.string.my_items_delete_message, item.title),
            confirmLabel = stringResource(R.string.my_items_delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            destructive = true,
            compact = true,
        )
    }
}

/** Position of [id] in the LazyColumn, counting the section headers drawn when box items exist. */
internal fun listIndexOf(id: String?, app: List<Recording>, movies: List<Recording>, timers: List<Recording>): Int {
    if (id == null) return -1
    val headers = movies.isNotEmpty() || timers.isNotEmpty()
    var offset = 0
    for (list in listOf(app, movies, timers)) {
        if (list.isEmpty()) continue
        if (headers) offset++
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) return offset + i
        offset += list.size
    }
    return -1
}

@Composable
private fun RecordingRow(item: Recording, modifier: Modifier, onClick: () -> Unit) {
    CompactListRow(onClick = onClick, modifier = modifier) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(item.title, color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1)
            Text(rowDetail(item), color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 1)
        }
        Text(
            statusLabel(item.status),
            color = when (item.status) {
                RecordingStatus.RECORDING -> EnigmaColors.Accent
                RecordingStatus.FAILED -> EnigmaColors.Danger
                else -> EnigmaColors.Subtle
            },
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun rowDetail(item: Recording): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(item.beginEpochSec).atZone(zone)
    val end = Instant.ofEpochSecond(item.endEpochSec).atZone(zone)
    val minutes = ((item.endEpochSec - item.beginEpochSec) / 60).coerceAtLeast(1)
    val place = stringResource(if (item.place == RecordingPlace.RECEIVER) R.string.my_items_place_receiver else R.string.my_items_place_tv)
    val clock = "${start.format(DayFormat)}  ${start.format(ClockFormat)}–${end.format(ClockFormat)}  ·  $minutes min  ·  $place"
    return if (item.status == RecordingStatus.FAILED && item.error.isNotBlank()) {
        "${item.channelName}  ·  $clock  ·  ${item.error}"
    } else {
        "${item.channelName}  ·  $clock"
    }
}

@Composable
private fun statusLabel(status: RecordingStatus): String = stringResource(
    when (status) {
        RecordingStatus.SCHEDULED -> R.string.my_items_scheduled
        RecordingStatus.RECORDING -> R.string.my_items_recording
        RecordingStatus.READY -> R.string.my_items_ready
        RecordingStatus.FAILED -> R.string.my_items_failed
    },
)

private enum class ItemAction(val label: Int) {
    PLAY(R.string.my_items_play),
    STOP(R.string.my_items_stop),
    DELETE(R.string.my_items_delete),
    CANCEL(R.string.cancel),
}

private fun actionsFor(item: Recording, box: Boolean, boxTimer: Boolean): List<ItemAction> = buildList {
    if (!boxTimer && (item.status == RecordingStatus.READY || item.status == RecordingStatus.RECORDING)) add(ItemAction.PLAY)
    if (!box && item.status == RecordingStatus.RECORDING) add(ItemAction.STOP)
    add(ItemAction.DELETE)
    add(ItemAction.CANCEL)
}
