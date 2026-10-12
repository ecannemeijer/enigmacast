package com.enigmacast.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.power.ReceiverPowerState
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.InfoRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty

private class PrimaryPower(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val onClick: () -> Unit,
)

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
    onRetreatTo: (FocusRequester) -> Unit,
    viewModel: ReceiverPowerViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.powerState
    val busy = viewModel.busyAction
    val busyText = stringResource(R.string.busy)
    fun hint(action: PowerAction, idle: String) = if (busy == action) busyText else idle
    val subtitle = listOf(viewModel.receiverName, viewModel.receiverHost)
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
    LaunchedEffect(Unit) {
        viewModel.loadDetails()
    }
    Column(modifier) {
        PanelHeader(stringResource(R.string.receiver_title), subtitle)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard(
                    title = stringResource(R.string.power_card_title),
                    trailing = {
                        StatusPill(viewModel.stateLabel.asString(), powerStateColor(state, viewModel.restarting))
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
                        // One tile whose content follows the state, so focus stays on it when the state flips.
                        val primary = when (state) {
                            ReceiverPowerState.STANDBY -> PrimaryPower(
                                icon = EnigmaIcons.Power,
                                label = stringResource(R.string.power_action_wake),
                                description = hint(PowerAction.WAKE_UP, stringResource(R.string.power_wake_hint)),
                                onClick = { viewModel.request(PowerAction.WAKE_UP) },
                            )
                            ReceiverPowerState.UNREACHABLE -> PrimaryPower(
                                icon = Icons.Filled.Refresh,
                                label = stringResource(R.string.power_retry),
                                description = stringResource(R.string.power_retry_hint),
                                onClick = viewModel::refreshNow,
                            )
                            ReceiverPowerState.ON, ReceiverPowerState.UNKNOWN -> PrimaryPower(
                                icon = EnigmaIcons.Bedtime,
                                label = stringResource(R.string.power_action_standby),
                                description = hint(PowerAction.STANDBY, stringResource(R.string.power_standby_hint)),
                                onClick = { viewModel.request(PowerAction.STANDBY) },
                            )
                        }
                        ActionTile(
                            icon = primary.icon,
                            label = primary.label,
                            description = primary.description,
                            tone = TileTone.Primary,
                            onClick = primary.onClick,
                            modifier = first,
                        )
                        ActionTile(
                            icon = Icons.Filled.Refresh,
                            label = stringResource(R.string.power_action_restart_gui),
                            description = hint(PowerAction.RESTART_GUI, stringResource(R.string.power_gui_hint)),
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
                            label = stringResource(R.string.power_action_reboot),
                            description = hint(PowerAction.REBOOT, stringResource(R.string.power_reboot_hint)),
                            onClick = { viewModel.request(PowerAction.REBOOT) },
                            modifier = Modifier
                                .weight(1f)
                                .focusProperties { left = menuFocus },
                        )
                        ActionTile(
                            icon = EnigmaIcons.Power,
                            label = stringResource(R.string.power_action_deep_standby),
                            description = hint(PowerAction.DEEP_STANDBY, stringResource(R.string.power_deep_hint)),
                            tone = TileTone.Danger,
                            onClick = { viewModel.request(PowerAction.DEEP_STANDBY) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                SleepTimerCard(viewModel, menuFocus)
            }
            }
            ReceiverDetailsCard(viewModel, Modifier.width(280.dp))
        }
    }
    viewModel.pendingConfirmation?.let { action ->
        TvConfirmDialog(
            title = PowerCopy.confirmTitle(action).asString(),
            message = PowerCopy.confirmMessage(action).asString(),
            confirmLabel = PowerCopy.confirmLabel(action).asString(),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::cancelPending,
            destructive = true,
        )
    }
}

@Composable
private fun SleepTimerCard(viewModel: ReceiverPowerViewModel, menuFocus: FocusRequester) {
    val status = viewModel.sleepTimer
    SettingsCard(
        title = stringResource(R.string.sleep_title),
        trailing = {
            if (status?.enabled == true) StatusPill(stringResource(R.string.active), EnigmaColors.Accent)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            Icon(
                EnigmaIcons.Timer,
                contentDescription = null,
                tint = EnigmaColors.Muted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (viewModel.sleepBusy) stringResource(R.string.busy) else PowerCopy.sleepSummary(status, viewModel.sleepTimerError).asString(),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
            )
        }
        ChoiceRow(
            title = stringResource(R.string.sleep_minutes),
            options = PowerCopy.sleepPresets.map { PowerCopy.sleepPresetLabel(it).asString() },
            selectedIndex = PowerCopy.sleepPresetIndex(status),
            onSelect = { index -> viewModel.setSleepTimer(PowerCopy.sleepPresets[index]) },
            firstOptionModifier = Modifier.focusProperties { left = menuFocus },
        )
        ChoiceRow(
            title = stringResource(R.string.sleep_then),
            options = SleepTimerAction.entries.map { PowerCopy.sleepActionTitle(it).asString() },
            selectedIndex = SleepTimerAction.entries.indexOf(viewModel.sleepAction),
            onSelect = { index -> viewModel.chooseSleepAction(SleepTimerAction.entries[index]) },
            firstOptionModifier = Modifier.focusProperties { left = menuFocus },
        )
    }
}

@Composable
private fun ReceiverDetailsCard(viewModel: ReceiverPowerViewModel, modifier: Modifier = Modifier) {
    val details = viewModel.details
    SettingsCard(title = stringResource(R.string.info), modifier = modifier) {
        when {
            details != null -> PowerCopy.detailRows(details).forEach { (label, value) ->
                InfoRow(label, value.asString())
            }
            viewModel.detailsError.isNotEmpty() -> Text(
                viewModel.detailsError.asString(),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            else -> Text(
                stringResource(R.string.info_loading),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}
