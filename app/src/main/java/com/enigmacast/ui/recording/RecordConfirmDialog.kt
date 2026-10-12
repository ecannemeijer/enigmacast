package com.enigmacast.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.enigmacast.R
import com.enigmacast.domain.model.RecordingTarget
import com.enigmacast.domain.recording.RecordAsk
import com.enigmacast.ui.theme.TvChoiceDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Record choice: on this device (local capture of the stream) or on the Enigma2 box (an
 * OpenWebif timer). The time is shown; the first choice has focus, Back or Cancel closes.
 */
@Composable
fun RecordConfirmDialog(
    ask: RecordAsk,
    onConfirm: (RecordingTarget, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(ask.event.beginEpochSec).atZone(zone)
    val end = Instant.ofEpochSecond(ask.event.endEpochSec).atZone(zone)
    val whenText = "${start.format(DayFormat)}  ${start.format(ClockFormat)} – ${end.format(ClockFormat)}"
    val title = ask.event.title.ifBlank { ask.channelName }
    // The box option names the receiver ("Record on SF8008"), first and focused.
    val settingsModel: com.enigmacast.ui.settings.SettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val settings by settingsModel.settings.collectAsStateWithLifecycle()
    val boxName = settings.activeReceiver()?.name?.trim().orEmpty()
    val onBox = if (boxName.isNotBlank()) stringResource(R.string.record_on_named, boxName) else stringResource(R.string.record_on_box)
    // "Record all upcoming" asks where next (TV or box), in a second step.
    var all by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (all) {
        TvChoiceDialog(
            title = stringResource(R.string.record_all_upcoming),
            message = stringResource(R.string.record_all_where, com.enigmacast.domain.series.SeriesDetector.cleanTitle(title).ifBlank { title }, ask.channelName),
            choices = listOf(onBox, stringResource(R.string.record_on_device), stringResource(R.string.cancel)),
            stacked = true,
            onChoose = { i ->
                when (i) {
                    0 -> onConfirm(RecordingTarget.RECEIVER, true)
                    1 -> onConfirm(RecordingTarget.TV, true)
                    else -> all = false
                }
            },
            onDismiss = { all = false },
        )
        return
    }
    if (ask.onlyAll) {
        TvChoiceDialog(
            title = stringResource(R.string.epg_record),
            message = stringResource(R.string.record_already_set),
            choices = listOf(stringResource(R.string.record_all_upcoming), stringResource(R.string.cancel)),
            stacked = true,
            onChoose = { i -> if (i == 0) all = true else onDismiss() },
            onDismiss = onDismiss,
        )
        return
    }
    val choices = buildList {
        add(onBox)
        add(stringResource(R.string.record_on_device))
        if (ask.upcoming > 0) add(stringResource(R.string.record_all_upcoming))
        add(stringResource(R.string.cancel))
    }
    TvChoiceDialog(
        title = stringResource(R.string.epg_record),
        message = stringResource(R.string.record_choose, title, whenText),
        choices = choices,
        stacked = true,
        onChoose = { i ->
            when {
                i == 0 -> onConfirm(RecordingTarget.RECEIVER, false)
                i == 1 -> onConfirm(RecordingTarget.TV, false)
                i == 2 && ask.upcoming > 0 -> all = true
                else -> onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}
