package com.enigmacast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.ui.theme.TvButton

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
            .verticalScroll(rememberScrollState())
            .padding(72.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 36.sp)
        Text(
            receiver?.let { "${it.name}  ${it.host}" } ?: "No receiver",
            color = Color(0xFFB7C0D0),
            fontSize = 20.sp,
        )
        TvButton("Edit receiver", onEditReceiver)
        Toggle("Fast playback", settings.playback.lowLatency, viewModel::setLowLatency)
        Toggle("HDMI audio passthrough", settings.playback.hdmiPassthrough, viewModel::setHdmiPassthrough)
        Toggle("Auto reconnect", settings.playback.autoReconnect, viewModel::setAutoReconnect)
        Toggle("Fast OSD polling", settings.osd.fastPolling, viewModel::setFastPolling)
        Toggle("OSD debug border", settings.osd.debugBorders, viewModel::setDebugBorders)
        Toggle("Debug overlay", settings.debug.overlay, viewModel::setDebugOverlay)
        Toggle("Verbose logging", settings.debug.verboseLogging, viewModel::setVerboseLogging)
        Toggle(
            "Allow self-signed certificate",
            receiver?.allowInsecureCertificates == true,
            viewModel::setInsecureCertificates,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(
                text = if (settings.osd.format == "jpg") "OSD format: JPG" else "OSD format: PNG",
                onClick = {
                    viewModel.setOsdFormat(if (settings.osd.format == "jpg") "png" else "jpg")
                },
            )
            TvButton("OSD diagnostics", onDiagnostics)
            TvButton("Back", onBack)
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
