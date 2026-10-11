package com.enigmacast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enigmacast.R
import com.enigmacast.ui.osd.OsdInfoSettings
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.StepperRow
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.ToggleRow

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditReceiver: () -> Unit,
    onDiagnostics: () -> Unit,
    onSignalMeter: () -> Unit,
    onOscam: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val receiver = settings.activeReceiver()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EnigmaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PanelHeader(stringResource(R.string.settings), receiver?.let { "${it.name}  ·  ${it.host}" } ?: stringResource(R.string.no_receiver))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(EnigmaIcons.Tv, stringResource(R.string.edit_receiver), onEditReceiver, tone = TileTone.Primary)
            ActionTile(Icons.Filled.Search, stringResource(R.string.osd_diagnostics), onDiagnostics)
            ActionTile(EnigmaIcons.Speed, stringResource(R.string.signal_meter), onSignalMeter)
            ActionTile(EnigmaIcons.Lock, stringResource(R.string.oscam), onOscam)
            ActionTile(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack)
        }
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard(stringResource(R.string.playback)) {
                ToggleRow(stringResource(R.string.fast_playback), settings.playback.lowLatency, viewModel::setLowLatency)
                ToggleRow(stringResource(R.string.hdmi_audio_passthrough), settings.playback.hdmiPassthrough, viewModel::setHdmiPassthrough)
                ToggleRow(stringResource(R.string.dvb_subtitles), settings.playback.subtitlesEnabled, viewModel::setSubtitlesEnabled)
                ToggleRow(stringResource(R.string.auto_reconnect), settings.playback.autoReconnect, viewModel::setAutoReconnect)
            }
            SettingsCard(stringResource(R.string.osd)) {
                StepperRow(
                    title = stringResource(R.string.osd_transparency),
                    description = stringResource(R.string.osd_transparency_desc),
                    value = "${settings.osd.transparencyPercent}%",
                    decreaseLabel = "−10",
                    increaseLabel = "+10",
                    onDecrease = { viewModel.setOsdTransparency(settings.osd.transparencyPercent - 10) },
                    onIncrease = { viewModel.setOsdTransparency(settings.osd.transparencyPercent + 10) },
                )
                ChoiceRow(
                    title = stringResource(R.string.osd_format_long),
                    options = listOf("PNG", "JPG"),
                    selectedIndex = if (settings.osd.format == "jpg") 1 else 0,
                    onSelect = { viewModel.setOsdFormat(if (it == 1) "jpg" else "png") },
                )
                ToggleRow(stringResource(R.string.fast_osd_polling), settings.osd.fastPolling, viewModel::setFastPolling)
                ToggleRow(stringResource(R.string.osd_debug_border), settings.osd.debugBorders, viewModel::setDebugBorders)
            }
            OsdInfoSettings(settings.osdInfo, viewModel::setOsdInfoFields)
            SettingsCard(stringResource(R.string.connection)) {
                ToggleRow(
                    title = stringResource(R.string.allow_self_signed),
                    checked = receiver?.allowInsecureCertificates == true,
                    onCheckedChange = viewModel::setInsecureCertificates,
                )
            }
            SettingsCard(stringResource(R.string.diagnostics)) {
                ToggleRow(stringResource(R.string.debug_overlay), settings.debug.overlay, viewModel::setDebugOverlay)
                ToggleRow(stringResource(R.string.verbose_logging), settings.debug.verboseLogging, viewModel::setVerboseLogging)
            }
        }
    }
}
