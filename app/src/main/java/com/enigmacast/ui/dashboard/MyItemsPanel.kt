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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
    onPlay: () -> Unit,
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
    Column(modifier.fillMaxSize()) {
        PanelHeader(stringResource(R.string.nav_my_items))
        val notice = viewModel.notice.asString()
        if (notice.isNotBlank()) {
            Text(notice, color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.my_items_empty),
                color = EnigmaColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .focusRequester(listFocus)
                    .focusProperties { left = menuFocus }
                    .focusable()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        } else {
            val focusOn = viewModel.focusId?.takeIf { id -> items.any { it.id == id } } ?: items.first().id
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    RecordingRow(
                        item = item,
                        modifier = if (item.id == focusOn) {
                            Modifier.focusRequester(listFocus).focusProperties { left = menuFocus }
                        } else {
                            Modifier
                        },
                        onClick = { viewModel.open(item) },
                    )
                }
            }
        }
    }
    LaunchedEffect(viewModel.focusToken) {
        if (viewModel.focusToken == 0) return@LaunchedEffect
        if (viewModel.selected != null || viewModel.pendingDelete != null) return@LaunchedEffect
        val index = items.indexOfFirst { it.id == viewModel.focusId }
        if (index >= 0) listState.scrollToItem(index)
        listFocus.requestWhenReady()
        if (viewModel.holdList) {
            repeat(2) { withFrameNanos { } }
            viewModel.releaseList()
        }
    }
    viewModel.selected?.let { item ->
        val actions = actionsFor(item)
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

private fun actionsFor(item: Recording): List<ItemAction> = buildList {
    if (item.status == RecordingStatus.READY || item.status == RecordingStatus.RECORDING) add(ItemAction.PLAY)
    if (item.status == RecordingStatus.RECORDING) add(ItemAction.STOP)
    add(ItemAction.DELETE)
    add(ItemAction.CANCEL)
}
