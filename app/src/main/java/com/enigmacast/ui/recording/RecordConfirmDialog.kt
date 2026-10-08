package com.enigmacast.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.enigmacast.R
import com.enigmacast.domain.model.RecordingTarget
import com.enigmacast.domain.recording.RecordAsk
import com.enigmacast.ui.theme.TvConfirmDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Confirms the destination and the time before a recording is stored. */
@Composable
fun RecordConfirmDialog(
    ask: RecordAsk,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(ask.event.beginEpochSec).atZone(zone)
    val end = Instant.ofEpochSecond(ask.event.endEpochSec).atZone(zone)
    val whenText = "${start.format(DayFormat)}  ${start.format(ClockFormat)} – ${end.format(ClockFormat)}"
    val title = ask.event.title.ifBlank { ask.channelName }
    val message = stringResource(
        if (ask.place == RecordingTarget.RECEIVER) R.string.record_confirm_receiver else R.string.record_confirm_tv,
        title,
        whenText,
    )
    TvConfirmDialog(
        title = stringResource(R.string.epg_record),
        message = message,
        confirmLabel = stringResource(R.string.epg_record),
        dismissLabel = stringResource(R.string.cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
