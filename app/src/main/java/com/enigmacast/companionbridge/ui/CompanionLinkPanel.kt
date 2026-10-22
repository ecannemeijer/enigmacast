package com.enigmacast.companionbridge.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.companionbridge.CompanionBridgeState
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.ValueRow
import kotlinx.coroutines.delay

/**
 * Settings › Phone & remote. Toont de koppelcode, de certificaatpin en de gekoppelde
 * telefoons; de brug start en stopt met de Activity (zie CompanionBridge).
 */
@Composable
fun CompanionLinkPanel(
    contentFocus: FocusRequester,
    model: CompanionViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    Column(
        Modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard(stringResource(R.string.companion_title)) {
            ToggleRow(
                title = stringResource(R.string.companion_enable),
                checked = state.enabled,
                onCheckedChange = model::setEnabled,
                modifier = Modifier.focusRequester(contentFocus),
                description = stringResource(R.string.companion_enable_desc),
            )
            ValueRow(
                title = stringResource(R.string.companion_status),
                value = statusText(state),
                onClick = {},
                description = if (state.clients > 0) {
                    stringResource(R.string.companion_clients, state.clients)
                } else {
                    null
                },
            )
            if (state.running) {
                TvTextField(
                    label = stringResource(R.string.companion_test_field),
                    value = state.testFormText,
                    onValueChange = model::setTestFormText,
                    kind = TvFieldKind.TEXT,
                )
                Text(
                    stringResource(R.string.companion_test_field_desc),
                    color = EnigmaColors.Subtle,
                    fontSize = 11.sp,
                )
            }
            if (state.running) {
                val code = state.pairingCode
                if (code != null) {
                    PairingCode(code = code, expiresAt = state.pairingExpiresAt)
                    ValueRow(
                        title = stringResource(R.string.companion_new_code),
                        value = "",
                        onClick = model::newCode,
                        description = stringResource(R.string.companion_new_code_desc),
                    )
                } else {
                    ValueRow(
                        title = stringResource(R.string.companion_new_code),
                        value = "",
                        onClick = model::newCode,
                        description = stringResource(R.string.companion_code_expired),
                    )
                }
                PinBlock(state.pin)
            }
            state.error?.let { message ->
                Text(
                    stringResource(R.string.companion_error, message),
                    color = EnigmaColors.Danger,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        SettingsCard(stringResource(R.string.companion_devices)) {
            if (state.devices.isEmpty()) {
                Text(
                    stringResource(R.string.companion_no_devices),
                    color = EnigmaColors.Muted,
                    fontSize = 13.sp,
                )
            } else {
                state.devices.forEach { device ->
                    ValueRow(
                        title = device.name,
                        value = if (device.revoked) {
                            stringResource(R.string.companion_revoked)
                        } else {
                            stringResource(R.string.companion_revoke)
                        },
                        onClick = { if (!device.revoked) model.revoke(device.id) },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.companion_hint),
            color = EnigmaColors.Subtle,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun statusText(state: CompanionBridgeState): String = when {
    state.error != null -> stringResource(R.string.companion_error_short)
    state.running -> stringResource(R.string.companion_running, state.port)
    state.enabled -> stringResource(R.string.companion_starting)
    else -> stringResource(R.string.off)
}

@Composable
private fun PairingCode(code: String, expiresAt: Long) {
    var remaining by remember(code) { mutableIntStateOf(0) }
    LaunchedEffect(code, expiresAt) {
        while (true) {
            remaining = ((expiresAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            delay(500)
        }
    }
    Text(
        code,
        color = EnigmaColors.Text,
        fontSize = 44.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 10.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
    Text(
        stringResource(R.string.companion_code_desc),
        color = EnigmaColors.Muted,
        fontSize = 12.sp,
    )
    Text(
        stringResource(R.string.companion_seconds, remaining),
        color = if (remaining == 0) EnigmaColors.Danger else EnigmaColors.Subtle,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun PinBlock(pin: String) {
    if (pin.isBlank()) return
    Text(
        stringResource(R.string.companion_pin),
        color = EnigmaColors.Muted,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
    Text(
        pin,
        color = EnigmaColors.Subtle,
        fontSize = 10.sp,
        maxLines = 2,
    )
    Text(
        stringResource(R.string.companion_pin_desc),
        color = EnigmaColors.Subtle,
        fontSize = 10.sp,
    )
}
