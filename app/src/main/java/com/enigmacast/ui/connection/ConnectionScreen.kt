package com.enigmacast.ui.connection

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import com.enigmacast.R
import com.enigmacast.data.network.TunnelState
import com.enigmacast.domain.network.AccessMode
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.network.StreamProfile
import com.enigmacast.domain.network.TranscodePreset
import com.enigmacast.domain.network.WireGuardIssue
import com.enigmacast.domain.network.WireGuardText
import com.enigmacast.ui.theme.ChoiceChip
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty

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
            stringResource(R.string.conn_intro),
            color = Color(0xFFB7C0D0),
            fontSize = 18.sp,
        )
        if (!viewModel.useHttps) {
            val internet = viewModel.host.isNotBlank() && !ReceiverRouting.isLocalAddress(viewModel.host)
            Text(
                stringResource(if (internet) R.string.conn_http_internet_warning else R.string.conn_http_warning),
                color = if (internet) Color(0xFFFF7A70) else Color(0xFFE8B84A),
                fontSize = 16.sp,
            )
        }
        LabeledField(stringResource(R.string.field_receiver_name), viewModel.name, { viewModel.name = it }, fieldColors)
        LabeledField(stringResource(R.string.field_host), viewModel.host, { viewModel.host = it }, fieldColors)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledField(stringResource(R.string.field_http_port), viewModel.httpPort, { viewModel.httpPort = it }, fieldColors, Modifier.weight(1f))
            LabeledField(stringResource(R.string.field_stream_port), viewModel.streamPort, { viewModel.streamPort = it }, fieldColors, Modifier.weight(1f))
        }
        LabeledField(stringResource(R.string.field_username), viewModel.username, { viewModel.username = it }, fieldColors)
        LabeledField(
            label = stringResource(R.string.field_password),
            value = viewModel.password,
            onValueChange = { viewModel.password = it },
            colors = fieldColors,
            password = true,
        )
        ToggleRow("HTTPS", viewModel.useHttps) { viewModel.useHttps = it }
        ToggleRow(stringResource(R.string.allow_self_signed), viewModel.allowInsecureCertificates) {
            viewModel.allowInsecureCertificates = it
        }
        QualitySection(viewModel, fieldColors)
        RemoteSection(viewModel, fieldColors)
        WireGuardSection(viewModel, fieldColors)
        AccessModeSection(viewModel)
        if (viewModel.discoveredHosts.isNotEmpty()) {
            Text(stringResource(R.string.discovered_hosts), color = Color.White, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                viewModel.discoveredHosts.take(6).forEach { found ->
                    TvButton(text = found, onClick = { viewModel.selectHost(found) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(stringResource(R.string.action_search), viewModel::discover, enabled = !viewModel.busy)
            TvButton(stringResource(R.string.action_test_connection), viewModel::test, enabled = !viewModel.busy)
            if (viewModel.remoteHost.isNotBlank()) {
                TvButton(stringResource(R.string.action_test_remote), viewModel::testRemote, enabled = !viewModel.busy)
            }
            TvButton(stringResource(R.string.action_save), { viewModel.save() }, enabled = !viewModel.busy)
            TvButton(stringResource(R.string.action_connect), { viewModel.connect(onConnected) }, enabled = !viewModel.busy)
            TvButton(stringResource(R.string.settings), onSettings)
        }
        if (viewModel.status.isNotEmpty()) {
            Text(viewModel.status.asString(), color = Color.White, fontSize = 18.sp)
        }
    }
}

/** Direct, transcoded or automatic streams, with the transcoder's preset and port. */
@Composable
private fun QualitySection(viewModel: ConnectionViewModel, fieldColors: androidx.compose.material3.TextFieldColors) {
    Text(stringResource(R.string.conn_quality_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            StreamProfile.AUTO to R.string.profile_auto,
            StreamProfile.DIRECT to R.string.profile_direct,
            StreamProfile.TRANSCODED to R.string.profile_transcoded,
        ).forEach { (profile, label) ->
            ChoiceChip(stringResource(label), selected = viewModel.streamProfile == profile, onClick = { viewModel.streamProfile = profile })
        }
    }
    Text(
        stringResource(
            when (viewModel.streamProfile) {
                StreamProfile.AUTO -> R.string.profile_auto_hint
                StreamProfile.DIRECT -> R.string.profile_direct_hint
                StreamProfile.TRANSCODED -> R.string.profile_transcoded_hint
            },
        ),
        color = Color(0xFFB7C0D0),
        fontSize = 15.sp,
    )
    if (viewModel.streamProfile == StreamProfile.TRANSCODED) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TranscodePreset.entries.forEach { preset ->
                ChoiceChip(
                    stringResource(R.string.quality_transcoded, preset.height, preset.bitrateKbps / 1000f),
                    selected = viewModel.transcodePreset == preset,
                    onClick = { viewModel.transcodePreset = preset },
                )
            }
        }
    }
    if (viewModel.streamProfile != StreamProfile.DIRECT) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledField(
                stringResource(R.string.field_transcoding_port),
                viewModel.transcodingPort,
                { viewModel.transcodingPort = it },
                fieldColors,
                Modifier.weight(1f),
            )
            if (viewModel.remoteHost.isNotBlank()) {
                LabeledField(
                    stringResource(R.string.field_remote_transcoding_port),
                    viewModel.remoteTranscodingPort,
                    { viewModel.remoteTranscodingPort = it },
                    fieldColors,
                    Modifier.weight(1f),
                )
            }
        }
        Text(stringResource(R.string.conn_transcoding_hint), color = Color(0xFFB7C0D0), fontSize = 15.sp)
    }
}

/** Remote profile: DynDNS name or public address, forwarded ports and when to use it. */
@Composable
private fun RemoteSection(viewModel: ConnectionViewModel, fieldColors: androidx.compose.material3.TextFieldColors) {
    Text(stringResource(R.string.conn_remote_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.conn_remote_intro), color = Color(0xFFB7C0D0), fontSize = 16.sp)
    LabeledField(stringResource(R.string.field_remote_host), viewModel.remoteHost, { viewModel.remoteHost = it }, fieldColors)
    if (viewModel.remoteHost.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LabeledField(
            stringResource(R.string.field_remote_http_port),
            viewModel.remoteHttpPort,
            { viewModel.remoteHttpPort = it },
            fieldColors,
            Modifier.weight(1f),
        )
        LabeledField(
            stringResource(R.string.field_remote_stream_port),
            viewModel.remoteStreamPort,
            { viewModel.remoteStreamPort = it },
            fieldColors,
            Modifier.weight(1f),
        )
    }
    ToggleRow(stringResource(R.string.remote_https), viewModel.remoteUseHttps) { viewModel.remoteUseHttps = it }
    if (!viewModel.remoteUseHttps) {
        Text(stringResource(R.string.conn_remote_http_warning), color = Color(0xFFFF7A70), fontSize = 16.sp)
    }
    Text(stringResource(R.string.conn_remote_stream_note), color = Color(0xFFE8B84A), fontSize = 15.sp)
}

/** When to use the remote profile or tunnel; only shown when there is one. */
@Composable
private fun AccessModeSection(viewModel: ConnectionViewModel) {
    if (viewModel.remoteHost.isBlank() && !viewModel.wireGuard) return
    Text(stringResource(R.string.conn_access_mode), color = Color.White, fontSize = 18.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            AccessMode.AUTO to R.string.access_auto,
            AccessMode.HOME to R.string.access_home,
            AccessMode.REMOTE to R.string.access_remote,
        ).forEach { (mode, label) ->
            ChoiceChip(stringResource(label), selected = viewModel.accessMode == mode, onClick = { viewModel.accessMode = mode })
        }
    }
    Text(stringResource(R.string.conn_access_hint), color = Color(0xFFB7C0D0), fontSize = 15.sp)
}

/** The experimental in-app WireGuard tunnel: config import, VPN permission and a test. */
@Composable
private fun WireGuardSection(viewModel: ConnectionViewModel, fieldColors: androidx.compose.material3.TextFieldColors) {
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVpnConsent(result.resultCode == Activity.RESULT_OK)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importWireGuard(uri)
    }
    Text(stringResource(R.string.conn_wg_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.conn_wg_intro), color = Color(0xFFB7C0D0), fontSize = 16.sp)
    ToggleRow(stringResource(R.string.wg_toggle), viewModel.wireGuard, viewModel::setWireGuardEnabled)
    if (!viewModel.wireGuard) return
    if (viewModel.wireGuardSaved && viewModel.wireGuardText.isBlank()) {
        Text(stringResource(R.string.conn_wg_saved), color = Color(0xFF8FD18F), fontSize = 15.sp)
    }
    OutlinedTextField(
        value = viewModel.wireGuardText,
        onValueChange = { viewModel.wireGuardText = it },
        label = { Text(stringResource(R.string.field_wg_config)) },
        singleLine = false,
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace),
        visualTransformation = HideWireGuardKeys,
        colors = fieldColors,
    )
    val summary = viewModel.wireGuardSummary
    if (summary.valid) {
        Text(
            stringResource(R.string.wg_summary, summary.endpoint, summary.allowedIps.joinToString(", ")),
            color = Color(0xFFB7C0D0),
            fontSize = 15.sp,
        )
        if (summary.fullTunnel) Text(stringResource(R.string.wg_full_tunnel), color = Color(0xFFE8B84A), fontSize = 15.sp)
        if (WireGuardText.covers(summary.allowedIps, viewModel.host) == false) {
            Text(stringResource(R.string.wg_home_not_covered, viewModel.host.trim()), color = Color(0xFFFF7A70), fontSize = 15.sp)
        }
    } else {
        summary.issues.forEach { issue ->
            Text(stringResource(issue.label()), color = Color(0xFFE8B84A), fontSize = 15.sp)
        }
    }
    Text(stringResource(viewModel.tunnelStatus.state.label()), color = Color.White, fontSize = 16.sp)
    if (viewModel.tunnelStatus.detail.isNotEmpty()) {
        Text(viewModel.tunnelStatus.detail.asString(), color = Color(0xFFB7C0D0), fontSize = 15.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton(stringResource(R.string.action_wg_import), {
            try {
                fileLauncher.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                viewModel.onNoFilePicker()
            }
        })
        if (!viewModel.vpnAllowed) {
            TvButton(stringResource(R.string.action_wg_allow), {
                val intent = viewModel.consentIntent()
                if (intent == null) {
                    viewModel.refreshVpnConsent()
                } else {
                    try {
                        consentLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        viewModel.onNoConsentDialog()
                    }
                }
            })
        }
        TvButton(stringResource(R.string.action_wg_test), viewModel::testTunnel, enabled = !viewModel.busy)
        if (viewModel.wireGuardSaved || viewModel.wireGuardText.isNotBlank()) {
            TvButton(stringResource(R.string.action_wg_remove), viewModel::removeWireGuard)
        }
    }
}

internal fun WireGuardIssue.label(): Int = when (this) {
    WireGuardIssue.EMPTY -> R.string.wg_issue_empty
    WireGuardIssue.NO_INTERFACE -> R.string.wg_issue_no_interface
    WireGuardIssue.NO_PRIVATE_KEY -> R.string.wg_issue_no_private_key
    WireGuardIssue.NO_ADDRESS -> R.string.wg_issue_no_address
    WireGuardIssue.NO_PEER -> R.string.wg_issue_no_peer
    WireGuardIssue.NO_PUBLIC_KEY -> R.string.wg_issue_no_public_key
    WireGuardIssue.NO_ENDPOINT -> R.string.wg_issue_no_endpoint
    WireGuardIssue.NO_ALLOWED_IPS -> R.string.wg_issue_no_allowed_ips
}

internal fun TunnelState.label(): Int = when (this) {
    TunnelState.OFF -> R.string.wg_status_off
    TunnelState.CONNECTING -> R.string.wg_status_connecting
    TunnelState.UP -> R.string.wg_status_up
    TunnelState.NEEDS_CONSENT -> R.string.wg_status_consent
    TunnelState.FAILED -> R.string.wg_status_failed
}

/** Shows private and preshared keys as dots; same length, so cursor positions stay the same. */
internal object HideWireGuardKeys : VisualTransformation {
    private val keyLine = Regex("(?im)^(\\s*(?:PrivateKey|PresharedKey)\\s*=\\s*)(\\S+)")

    override fun filter(text: AnnotatedString): TransformedText {
        val masked = keyLine.replace(text.text) { match -> match.groupValues[1] + "•".repeat(match.groupValues[2].length) }
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
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
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
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
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
