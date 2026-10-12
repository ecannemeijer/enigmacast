package com.enigmacast.ui.recording

import androidx.compose.runtime.Composable
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
    onConfirm: (RecordingTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(ask.event.beginEpochSec).atZone(zone)
    val end = Instant.ofEpochSecond(ask.event.endEpochSec).atZone(zone)
    val whenText = "${start.format(DayFormat)}  ${start.format(ClockFormat)} – ${end.format(ClockFormat)}"
    val title = ask.event.title.ifBlank { ask.channelName }
    TvChoiceDialog(
        title = stringResource(R.string.epg_record),
        message = stringResource(R.string.record_choose, title, whenText),
        choices = listOf(stringResource(R.string.record_on_device), stringResource(R.string.record_on_box), stringResource(R.string.cancel)),
        onChoose = { i ->
            when (i) {
                0 -> onConfirm(RecordingTarget.TV)
                1 -> onConfirm(RecordingTarget.RECEIVER)
                else -> onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}
