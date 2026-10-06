package com.enigmacast.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.power.ReceiverPowerState
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.TvConfirmDialog

fun powerStateColor(state: ReceiverPowerState, restarting: PowerAction?): Color = when {
    restarting != null -> EnigmaColors.Accent
    state == ReceiverPowerState.ON -> EnigmaColors.Success
    state == ReceiverPowerState.STANDBY -> EnigmaColors.Accent
    state == ReceiverPowerState.UNREACHABLE -> EnigmaColors.Danger
    else -> EnigmaColors.Subtle
}

/**
 * Receiver section of the dashboard. [pageFocus] is the first tile, entered with
 * D-pad right from the side menu; D-pad left from the first column returns to [menuFocus].
 */
@Composable
fun ReceiverPowerPanel(
    pageFocus: FocusRequester,
    menuFocus: FocusRequester,
    viewModel: ReceiverPowerViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.powerState
    val busy = viewModel.busyAction
    fun hint(action: PowerAction, idle: String) = if (busy == action) "Bezig…" else idle
    val subtitle = listOf(viewModel.receiverName, viewModel.receiverHost)
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
    Column(modifier) {
        PanelHeader("Ontvanger", subtitle)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(
                title = "Aan / uit",
                trailing = {
                    StatusPill(viewModel.stateLabel, powerStateColor(state, viewModel.restarting))
                },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    val first = Modifier
                        .weight(1f)
                        .focusRequester(pageFocus)
                        .focusProperties { left = menuFocus }
                    when (state) {
                        ReceiverPowerState.STANDBY -> ActionTile(
                            icon = EnigmaIcons.Power,
                            label = "Aanzetten",
                            description = hint(PowerAction.WAKE_UP, "Uit stand-by halen"),
                            tone = TileTone.Primary,
                            onClick = { viewModel.request(PowerAction.WAKE_UP) },
                            modifier = first,
                        )
                        ReceiverPowerState.UNREACHABLE -> ActionTile(
                            icon = Icons.Filled.Refresh,
                            label = "Opnieuw proberen",
                            description = "Status opnieuw opvragen",
                            tone = TileTone.Primary,
                            onClick = viewModel::refreshNow,
                            modifier = first,
                        )
                        ReceiverPowerState.ON, ReceiverPowerState.UNKNOWN -> ActionTile(
                            icon = EnigmaIcons.Bedtime,
                            label = "Stand-by",
                            description = hint(PowerAction.STANDBY, "Beeld en tuner uit"),
                            tone = TileTone.Primary,
                            onClick = { viewModel.request(PowerAction.STANDBY) },
                            modifier = first,
                        )
                    }
                    ActionTile(
                        icon = Icons.Filled.Refresh,
                        label = "GUI herstarten",
                        description = hint(PowerAction.RESTART_GUI, "Alleen Enigma2"),
                        onClick = { viewModel.request(PowerAction.RESTART_GUI) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    ActionTile(
                        icon = EnigmaIcons.Restart,
                        label = "Herstarten",
                        description = hint(PowerAction.REBOOT, "Volledige herstart"),
                        onClick = { viewModel.request(PowerAction.REBOOT) },
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { left = menuFocus },
                    )
                    ActionTile(
                        icon = EnigmaIcons.Power,
                        label = "Diep slapen",
                        description = hint(PowerAction.DEEP_STANDBY, "Helemaal uit"),
                        tone = TileTone.Danger,
                        onClick = { viewModel.request(PowerAction.DEEP_STANDBY) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    viewModel.pendingConfirmation?.let { action ->
        TvConfirmDialog(
            title = PowerCopy.confirmTitle(action),
            message = PowerCopy.confirmMessage(action),
            confirmLabel = PowerCopy.confirmLabel(action),
            dismissLabel = "Annuleren",
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::cancelPending,
            destructive = true,
        )
    }
}
