package com.enigmacast.ui.recording

import com.enigmacast.ui.theme.focusRequesterIf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.tuner.PlannedRecording
import com.enigmacast.domain.tuner.TunerConflict
import com.enigmacast.live.TunerConflictController
import com.enigmacast.ui.theme.DialogButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.requestWhenReady
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TunerConflictViewModel @Inject constructor(private val controller: TunerConflictController) : ViewModel() {
    val conflict = controller.conflict
    fun close() = controller.close()
    fun watch(recording: PlannedRecording) { viewModelScope.launch { controller.watch(recording) } }
    fun cancel(conflict: TunerConflict, recording: PlannedRecording) { viewModelScope.launch { controller.cancel(conflict, recording) } }
}

/**
 * Shown wherever a channel found no free tuner because recordings hold them (Live TV, zap,
 * channel list, Watch from Movies / Series / Search / Home): each recording with Watch and
 * Cancel recording, then Close. Cancel asks first; afterwards the requested channel starts.
 */
@Composable
fun TunerConflictHost(model: TunerConflictViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val conflict by model.conflict.collectAsStateWithLifecycle()
    val current = conflict ?: return
    var confirm by remember(current) { mutableStateOf<PlannedRecording?>(null) }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    confirm?.let { rec ->
        TvChoiceDialog(
            title = stringResource(R.string.home_cancel_recording_title, rec.title.ifBlank { rec.channelName }),
            message = stringResource(R.string.conflict_cancel_then, current.target.name),
            choices = listOf(stringResource(R.string.home_cancel_recording), stringResource(R.string.close)),
            stacked = true,
            onChoose = { i -> confirm = null; if (i == 0) model.cancel(current, rec) },
            onDismiss = { confirm = null },
        )
        return
    }
    Dialog(onDismissRequest = model::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(current) { first.requestWhenReady() }
        Column(
            Modifier.widthIn(min = 260.dp, max = 460.dp).width(IntrinsicSize.Max)
                .background(EnigmaColors.Selected, RoundedCornerShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.conflict_title, current.target.name), color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            current.recordings.forEachIndexed { i, rec ->
                Text(
                    stringResource(R.string.conflict_running, rec.channelName, rec.title.ifBlank { rec.channelName }, clock.format(Date(rec.untilEpochSec * 1000))),
                    color = EnigmaColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2,
                    modifier = Modifier.padding(top = if (i > 0) 4.dp else 0.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DialogButton(
                        label = stringResource(R.string.conflict_watch, rec.channelName),
                        onClick = { model.watch(rec) }, destructive = false, enabled = true, compact = true,
                        modifier = Modifier.weight(1f).focusRequesterIf(i == 0, first),
                    )
                    DialogButton(
                        label = stringResource(R.string.home_cancel_recording),
                        onClick = { confirm = rec }, destructive = false, enabled = true, compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            DialogButton(stringResource(R.string.close), model::close, destructive = false, enabled = true, compact = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
