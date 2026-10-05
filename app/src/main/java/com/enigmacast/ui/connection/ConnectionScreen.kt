package com.enigmacast.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import com.enigmacast.ui.theme.TvButton

@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = Color(0xFF3A4458),
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Color(0xFFB7C0D0),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 72.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("EnigmaCast", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            "Connect an Enigma2 receiver. The Android TV shows the live stream and the real receiver OSD.",
            color = Color(0xFFB7C0D0),
            fontSize = 18.sp,
        )
        if (!viewModel.useHttps) {
            Text(
                "HTTP is not encrypted. Use it only on a trusted local network.",
                color = Color(0xFFE8B84A),
                fontSize = 16.sp,
            )
        }
        LabeledField("Receiver name", viewModel.name, { viewModel.name = it }, fieldColors)
        LabeledField("IP address or hostname", viewModel.host, { viewModel.host = it }, fieldColors)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledField("OpenWebif port", viewModel.httpPort, { viewModel.httpPort = it }, fieldColors, Modifier.weight(1f))
            LabeledField("Streaming port", viewModel.streamPort, { viewModel.streamPort = it }, fieldColors, Modifier.weight(1f))
        }
        LabeledField("Username", viewModel.username, { viewModel.username = it }, fieldColors)
        LabeledField(
            label = "Password",
            value = viewModel.password,
            onValueChange = { viewModel.password = it },
            colors = fieldColors,
            password = true,
        )
        ToggleRow("HTTPS", viewModel.useHttps) { viewModel.useHttps = it }
        ToggleRow("Allow self-signed certificate", viewModel.allowInsecureCertificates) {
            viewModel.allowInsecureCertificates = it
        }
        if (viewModel.discoveredHosts.isNotEmpty()) {
            Text("Discovered hosts", color = Color.White, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                viewModel.discoveredHosts.take(6).forEach { found ->
                    TvButton(text = found, onClick = { viewModel.selectHost(found) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("Search", viewModel::discover, enabled = !viewModel.busy)
            TvButton("Test connection", viewModel::test, enabled = !viewModel.busy)
            TvButton("Save", { viewModel.save() }, enabled = !viewModel.busy)
            TvButton("Connect", { viewModel.connect(onConnected) }, enabled = !viewModel.busy)
            TvButton("Settings", onSettings)
        }
        if (viewModel.status.isNotBlank()) {
            Text(viewModel.status, color = Color.White, fontSize = 18.sp)
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    colors: androidx.compose.material3.TextFieldColors,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = colors,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f, fill = false))
        Switch(checked = checked, onCheckedChange = onChecked        )
    }
}
