package com.enigmacast.ui.dashboard

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.network.AccessMode
import com.enigmacast.domain.network.WireGuardText
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.connection.HideWireGuardKeys
import com.enigmacast.ui.connection.label
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.ChoiceChip
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty

/**
 * Settings › Remote access for the active receiver: when to use the home address, the remote
 * profile (DynDNS name, forwarded ports, HTTPS) and the built-in WireGuard tunnel (switch,
 * paste or import the config, VPN permission, test). Everything is saved with the receiver.
 * With the tunnel switched off nothing of WireGuard runs: no VPN, no changed routes.
 */
@Composable
internal fun RemoteAccessSettingsPanel(
    contentFocus: FocusRequester,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVpnConsent(result.resultCode == Activity.RESULT_OK)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importWireGuard(uri)
    }
    Text(
        stringResource(R.string.remote_settings_intro, viewModel.name.trim(), viewModel.host.trim()),
        color = EnigmaColors.Muted,
        fontSize = 13.sp,
    )
    SettingsCard(stringResource(R.string.conn_access_mode)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            listOf(
                AccessMode.AUTO to R.string.access_auto,
                AccessMode.HOME to R.string.access_home,
                AccessMode.REMOTE to R.string.access_remote,
            ).forEachIndexed { index, (mode, label) ->
                ChoiceChip(
                    stringResource(label),
                    selected = viewModel.accessMode == mode,
                    onClick = { viewModel.accessMode = mode },
                    modifier = if (index == 0) Modifier.focusRequester(contentFocus) else Modifier,
                )
            }
        }
        Hint(stringResource(R.string.conn_access_hint))
    }
    SettingsCard(stringResource(R.string.remote_profile_title)) {
        Hint(stringResource(R.string.conn_remote_intro))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            CompactField(stringResource(R.string.field_remote_host), viewModel.remoteHost, { viewModel.remoteHost = it }, kind = TvFieldKind.HOST, modifier = Modifier.weight(2f))
            CompactField(stringResource(R.string.field_remote_http_port), viewModel.remoteHttpPort, { viewModel.remoteHttpPort = it }, Modifier.weight(1f), TvFieldKind.NUMBER)
            CompactField(stringResource(R.string.field_remote_stream_port), viewModel.remoteStreamPort, { viewModel.remoteStreamPort = it }, Modifier.weight(1f), TvFieldKind.NUMBER)
        }
        if (viewModel.remoteHost.isNotBlank()) {
            ToggleRow(
                title = stringResource(R.string.remote_https),
                checked = viewModel.remoteUseHttps,
                onCheckedChange = { viewModel.remoteUseHttps = it },
            )
            if (!viewModel.remoteUseHttps) Hint(stringResource(R.string.conn_remote_http_warning), EnigmaColors.Danger)
            Hint(stringResource(R.string.conn_remote_stream_note), EnigmaColors.Accent)
        }
    }
    SettingsCard(stringResource(R.string.conn_wg_title)) {
        Hint(stringResource(R.string.conn_wg_intro))
        ToggleRow(
            title = stringResource(R.string.wg_toggle),
            checked = viewModel.wireGuard,
            onCheckedChange = viewModel::setWireGuardEnabled,
        )
        if (!viewModel.wireGuard) {
            Hint(stringResource(R.string.wg_off_note))
            return@SettingsCard
        }
        if (viewModel.wireGuardSaved && viewModel.wireGuardText.isBlank()) {
            Hint(stringResource(R.string.conn_wg_saved), Color(0xFF8FD18F))
        }
        Hint(stringResource(R.string.wg_paste_hint))
        WireGuardConfigField(viewModel.wireGuardText) { viewModel.wireGuardText = it }
        val summary = viewModel.wireGuardSummary
        if (summary.valid) {
            Hint(stringResource(R.string.wg_summary, summary.endpoint, summary.allowedIps.joinToString(", ")))
            if (summary.fullTunnel) Hint(stringResource(R.string.wg_full_tunnel), EnigmaColors.Accent)
            if (WireGuardText.covers(summary.allowedIps, viewModel.host) == false) {
                Hint(stringResource(R.string.wg_home_not_covered, viewModel.host.trim()), EnigmaColors.Danger)
            }
        } else {
            summary.issues.forEach { issue -> Hint(stringResource(issue.label()), EnigmaColors.Accent) }
        }
        Hint(stringResource(viewModel.tunnelStatus.state.label()), EnigmaColors.Text)
        if (viewModel.tunnelStatus.detail.isNotEmpty()) Hint(viewModel.tunnelStatus.detail.asString())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ActionTile(
            icon = Icons.Filled.Check,
            label = stringResource(R.string.action_save),
            onClick = { viewModel.save() },
            enabled = !viewModel.busy,
            tone = TileTone.Primary,
        )
        if (viewModel.remoteHost.isNotBlank()) {
            TvButton(stringResource(R.string.action_test_remote), viewModel::testRemote, enabled = !viewModel.busy)
        }
    }
    if (viewModel.status.isNotEmpty()) {
        Text(viewModel.status.asString(), color = EnigmaColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun Hint(text: String, color: Color = EnigmaColors.Muted) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 6.dp))
}

/** The pasted WireGuard config; private and preshared keys are shown as dots. */
@Composable
private fun WireGuardConfigField(value: String, onValueChange: (String) -> Unit) {
    TvTextField(
        label = stringResource(R.string.field_wg_config),
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.padding(horizontal = 6.dp),
        kind = TvFieldKind.MULTILINE,
        visualTransformation = HideWireGuardKeys,
        monospace = true,
    )
}
