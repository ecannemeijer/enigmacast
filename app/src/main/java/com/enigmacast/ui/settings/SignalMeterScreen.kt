package com.enigmacast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.osdinfo.SignalReading
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.asString
import java.util.Locale

@Composable
fun SignalMeterScreen(
    onBack: () -> Unit,
    viewModel: SignalMeterViewModel = hiltViewModel(),
) {
    DisposableEffect(Unit) {
        viewModel.onEnter()
        onDispose { viewModel.onLeave() }
    }
    val state = viewModel.state
    val backFocus = remember { FocusRequester() }
    val firstSatelliteFocus = remember { FocusRequester() }
    var openedFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.noReceiver, state.satellites.size) {
        if (openedFocus || state.loading) return@LaunchedEffect
        if (state.noReceiver || state.satellites.isEmpty()) backFocus.requestWhenReady()
        else firstSatelliteFocus.requestWhenReady()
        openedFocus = true
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EnigmaColors.Canvas)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PanelHeader(stringResource(R.string.signal_meter), stringResource(R.string.signal_meter_hint))
        ActionTile(
            Icons.AutoMirrored.Filled.ArrowBack,
            stringResource(R.string.back),
            onBack,
            modifier = Modifier.focusRequester(backFocus),
        )
        when {
            state.noReceiver -> Text(stringResource(R.string.no_receiver), color = EnigmaColors.Text, fontSize = 18.sp)
            state.loading -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = EnigmaColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
                Text(
                    stringResource(R.string.signal_loading),
                    color = EnigmaColors.Muted,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.satellites.forEachIndexed { index, satellite ->
                        val selected = index == state.selectedIndex
                        Button(
                            onClick = { viewModel.select(index) },
                            enabled = !state.busy,
                            modifier = if (index == 0) Modifier.focusRequester(firstSatelliteFocus) else Modifier,
                            colors = ButtonDefaults.colors(
                                containerColor = if (selected) EnigmaColors.Accent else EnigmaColors.Raised,
                                contentColor = EnigmaColors.Text,
                                focusedContainerColor = EnigmaColors.Focus,
                                focusedContentColor = EnigmaColors.OnFocus,
                            ),
                        ) {
                            Text(satellite.label, fontSize = 16.sp)
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.tunedChannel?.let { channel ->
                        Text(stringResource(R.string.signal_channel, channel), color = EnigmaColors.Text, fontSize = 18.sp)
                    }
                    Meter(state.reading)
                    if (state.busy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = EnigmaColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                            state.message?.let { Text(it.asString(), color = EnigmaColors.Muted, fontSize = 16.sp) }
                        }
                    } else {
                        state.message?.takeIf { state.satellites.isNotEmpty() }?.let {
                            Text(it.asString(), color = EnigmaColors.Muted, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
        if (!state.loading && !state.noReceiver) {
            state.message?.takeIf { state.satellites.isEmpty() }?.let {
                Text(it.asString(), color = EnigmaColors.Muted, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Meter(reading: SignalReading?) {
    val locked = reading != null && reading.locked()
    MeterBar(stringResource(R.string.osd_signal_strength), reading?.agcPercent)
    val quality = reading?.snrPercent
    val db = reading?.snrDb?.let { String.format(Locale.US, "%.1f", it) }
    MeterBar(
        label = stringResource(R.string.osd_signal_quality),
        percent = quality,
        extra = db?.let { stringResource(R.string.signal_db, it) },
    )
    reading?.ber?.let { ber ->
        Text(stringResource(R.string.signal_ber, ber), color = EnigmaColors.Text, fontSize = 16.sp)
    }
    reading?.tunerNumber?.let { tuner ->
        Text(stringResource(R.string.signal_tuner, tuner + 1), color = EnigmaColors.Muted, fontSize = 16.sp)
    }
    if (reading != null && !locked) {
        Text(stringResource(R.string.signal_no_lock), color = EnigmaColors.Danger, fontSize = 16.sp)
    }
}

@Composable
private fun MeterBar(label: String, percent: Int?, extra: String? = null) {
    val value = percent?.coerceIn(0, 100)
    val caption = listOfNotNull(value?.let { "$it%" }, extra).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, color = EnigmaColors.Text, fontSize = 16.sp, modifier = Modifier.widthIn(min = 120.dp))
            Text(caption.ifEmpty { "—" }, color = EnigmaColors.Muted, fontSize = 16.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(EnigmaColors.Raised),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((value ?: 0) / 100f)
                    .height(14.dp)
                    .background(if (value == null) EnigmaColors.Raised else EnigmaColors.Accent),
            )
        }
    }
}

private fun SignalReading.locked(): Boolean {
    val snr = snrPercent ?: 0
    val agc = agcPercent ?: 0
    val db = snrDb ?: 0f
    return snr > 0 || agc > 0 || db > 0.05f
}
