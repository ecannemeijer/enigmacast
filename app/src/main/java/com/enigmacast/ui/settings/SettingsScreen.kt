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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.ToggleRow

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditReceiver: () -> Unit,
    onDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val receiver = settings.activeReceiver()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EnigmaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 72.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PanelHeader("Settings", receiver?.let { "${it.name}  ·  ${it.host}" } ?: "No receiver")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(EnigmaIcons.Tv, "Edit receiver", onEditReceiver, tone = TileTone.Primary)
            ActionTile(Icons.Filled.Search, "OSD diagnostics", onDiagnostics)
            ActionTile(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        }
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard("Playback") {
                ToggleRow("Fast playback", settings.playback.lowLatency, viewModel::setLowLatency)
                ToggleRow("HDMI audio passthrough", settings.playback.hdmiPassthrough, viewModel::setHdmiPassthrough)
                ToggleRow("DVB subtitles", settings.playback.subtitlesEnabled, viewModel::setSubtitlesEnabled)
                ToggleRow("Auto reconnect", settings.playback.autoReconnect, viewModel::setAutoReconnect)
            }
            SettingsCard("OSD") {
                ChoiceRow(
                    title = "OSD format",
                    options = listOf("PNG", "JPG"),
                    selectedIndex = if (settings.osd.format == "jpg") 1 else 0,
                    onSelect = { viewModel.setOsdFormat(if (it == 1) "jpg" else "png") },
                )
                ToggleRow("Fast OSD polling", settings.osd.fastPolling, viewModel::setFastPolling)
                ToggleRow("OSD debug border", settings.osd.debugBorders, viewModel::setDebugBorders)
            }
            SettingsCard("Connection") {
                ToggleRow(
                    title = "Allow self-signed certificate",
                    checked = receiver?.allowInsecureCertificates == true,
                    onCheckedChange = viewModel::setInsecureCertificates,
                )
            }
            SettingsCard("Diagnostics") {
                ToggleRow("Debug overlay", settings.debug.overlay, viewModel::setDebugOverlay)
                ToggleRow("Verbose logging", settings.debug.verboseLogging, viewModel::setVerboseLogging)
            }
        }
    }
}
