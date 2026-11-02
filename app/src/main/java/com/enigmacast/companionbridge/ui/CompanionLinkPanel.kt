package com.enigmacast.companionbridge.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Dialog
import com.enigmacast.companionbridge.CompanionDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import com.enigmacast.domain.profile.photo.QrCode
import com.enigmacast.ui.profile.QrImage
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
    var popup by remember { mutableStateOf(false) }
    var knownDevices by remember { mutableStateOf(emptySet<String>()) }
    val codeView = remember { BringIntoViewRequester() }
    Column(
        Modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompanionDownloadCard()
        if (popup) {
            PairingPopup(state = state, knownDevices = knownDevices, onDismiss = { popup = false })
        }
        SettingsCard(stringResource(R.string.companion_title)) {
            ToggleRow(
                title = stringResource(R.string.companion_enable),
                checked = state.enabled,
                onCheckedChange = { on ->
                    model.setEnabled(on)
                    if (on) {
                        knownDevices = state.devices.map { it.id }.toSet()
                        popup = true
                    }
                },
                modifier = Modifier.focusRequester(contentFocus),
                description = stringResource(R.string.companion_enable_desc),
            )
            if (state.running) {
                val code = state.pairingCode
                if (code != null) {
                    PairingCode(code = code, expiresAt = state.pairingExpiresAt, modifier = Modifier.bringIntoViewRequester(codeView))
                    LaunchedEffect(code) {
                        delay(150)
                        codeView.bringIntoView()
                    }
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
            }
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

/** Stable link: GitHub always serves the newest Companion release under "latest". */
internal const val COMPANION_APK_URL =
    "https://github.com/ecannemeijer/enigmacast-companion/releases/latest/download/enigmacast-companion.apk"

/** QR code to download the Companion app; not focusable, so D-pad focus is unchanged. */
@Composable
private fun CompanionDownloadCard() {
    val qr = remember { runCatching { QrCode.encode(COMPANION_APK_URL.toByteArray()) }.getOrNull() } ?: return
    SettingsCard(stringResource(R.string.companion_steps_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            QrImage(qr, Modifier.size(130.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.companion_step_install), color = EnigmaColors.Text, fontSize = 14.sp)
                Text(stringResource(R.string.companion_step_enable, stringResource(R.string.companion_enable)), color = EnigmaColors.Text, fontSize = 14.sp)
                Text(stringResource(R.string.companion_step_code), color = EnigmaColors.Text, fontSize = 14.sp)
                Text(stringResource(R.string.companion_app_qr_update), color = EnigmaColors.Subtle, fontSize = 12.sp)
            }
        }
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
private fun PairingCode(code: String, expiresAt: Long, modifier: Modifier = Modifier, big: Boolean = false) {
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
        fontSize = if (big) 64.sp else 52.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 12.sp,
        modifier = modifier.padding(top = 10.dp),
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

/** The first phone that paired after the popup opened (not revoked, not known before). */
internal fun newlyPaired(known: Set<String>, devices: List<CompanionDevice>): CompanionDevice? =
    devices.firstOrNull { !it.revoked && it.id !in known }

/**
 * Popup after "allow pairing" is turned on: the code in large digits. Back or OK closes it;
 * when a phone pairs it shows "Paired with ..." and closes by itself.
 */
@Composable
private fun PairingPopup(state: CompanionBridgeState, knownDevices: Set<String>, onDismiss: () -> Unit) {
    val paired = newlyPaired(knownDevices, state.devices)
    val okFocus = remember { FocusRequester() }
    LaunchedEffect(paired?.id) {
        if (paired != null) {
            delay(2_500)
            onDismiss()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(EnigmaColors.Card, RoundedCornerShape(16.dp))
                .padding(28.dp)
                .widthIn(min = 420.dp)
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.companion_popup_title), color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            val code = state.pairingCode
            when {
                paired != null -> Text(
                    stringResource(R.string.companion_paired_with, paired.name),
                    color = EnigmaColors.Success,
                    fontSize = 18.sp,
                )
                state.running && code != null -> PairingCode(code = code, expiresAt = state.pairingExpiresAt, big = true)
                else -> Text(stringResource(R.string.companion_starting), color = EnigmaColors.Muted, fontSize = 14.sp)
            }
            Box(Modifier.widthIn(max = 240.dp)) {
                ValueRow(
                    title = stringResource(R.string.ok),
                    value = "",
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(okFocus),
                )
            }
        }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { okFocus.requestFocus() }
        }
    }
}
