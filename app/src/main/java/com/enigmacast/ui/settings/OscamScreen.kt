package com.enigmacast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.oscam.OscamReader
import com.enigmacast.domain.oscam.OscamUser
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.util.asString

private val debugLevels = listOf(
    0 to R.string.oscam_debug_off,
    1 to R.string.oscam_debug_errors,
    128 to R.string.oscam_debug_dvbapi,
    1024 to R.string.oscam_debug_ecm,
    65535 to R.string.oscam_debug_all,
)

@Composable
fun OscamScreen(
    onBack: () -> Unit,
    viewModel: OscamViewModel = hiltViewModel(),
) {
    val state = viewModel.state
    val logScroll = rememberScrollState()
    LaunchedEffect(state.log) {
        if (!state.logPaused) logScroll.scrollTo(logScroll.maxValue)
    }
    val controlsEnabled = !state.busy && !state.stopped && !state.noReceiver
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EnigmaColors.Canvas)
            .padding(horizontal = 36.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader(
                stringResource(R.string.oscam),
                stringResource(R.string.oscam_hint),
                modifier = Modifier.weight(1f),
            )
            if (state.version.isNotBlank()) {
                StatusPill(serverCaption(state.version, state.uptime), EnigmaColors.Cyan)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(
                EnigmaIcons.Sync,
                stringResource(R.string.oscam_connect),
                viewModel::connect,
                modifier = Modifier.weight(1f),
                enabled = !state.noReceiver && !state.busy,
            )
            ActionTile(
                EnigmaIcons.Restart,
                stringResource(R.string.oscam_restart),
                viewModel::askRestart,
                modifier = Modifier.weight(1f),
                tone = TileTone.Primary,
                enabled = controlsEnabled,
            )
            ActionTile(
                EnigmaIcons.Power,
                stringResource(R.string.oscam_stop),
                viewModel::askStop,
                modifier = Modifier.weight(1f),
                tone = TileTone.Danger,
                enabled = controlsEnabled,
            )
            ActionTile(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.back),
                onBack,
                modifier = Modifier.weight(0.8f),
            )
        }
        if (state.noReceiver) {
            Text(stringResource(R.string.no_receiver), color = EnigmaColors.Text, fontSize = 16.sp)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TvTextField(
                label = stringResource(R.string.oscam_port),
                value = state.port,
                onValueChange = viewModel::setPort,
                kind = TvFieldKind.NUMBER,
                modifier = Modifier.weight(0.6f),
            )
            TvTextField(
                label = stringResource(R.string.field_user),
                value = state.username,
                onValueChange = viewModel::setUsername,
                modifier = Modifier.weight(1f),
            )
            TvTextField(
                label = stringResource(R.string.oscam_password),
                value = state.password,
                onValueChange = viewModel::setPassword,
                kind = TvFieldKind.PASSWORD,
                modifier = Modifier.weight(1f),
            )
        }
        state.message?.let { Text(it.asString(), color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsCard(stringResource(R.string.oscam_readers)) {
                    if (state.readers.isEmpty()) {
                        Text(stringResource(R.string.oscam_no_readers), color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    } else {
                        state.readers.forEach { reader ->
                            ReaderRow(reader, enabled = controlsEnabled, onRestart = { viewModel.askRestartReader(reader.name) })
                        }
                    }
                }
                SettingsCard(stringResource(R.string.oscam_users)) {
                    if (state.users.isEmpty()) {
                        Text(stringResource(R.string.oscam_no_users), color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    } else {
                        state.users.forEach { user -> UserRow(user) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(
                        stringResource(R.string.oscam_reset_readers),
                        viewModel::askResetReaderStats,
                        Modifier.weight(1f),
                        enabled = controlsEnabled,
                    )
                    CompactButton(
                        stringResource(R.string.oscam_reset_users),
                        viewModel::askResetUserStats,
                        Modifier.weight(1f),
                        enabled = controlsEnabled,
                    )
                }
            }
            SettingsCard(stringResource(R.string.oscam_log), modifier = Modifier.weight(0.58f).fillMaxHeight(), trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactButton(
                        stringResource(if (state.logPaused) R.string.oscam_log_resume else R.string.oscam_log_pause),
                        viewModel::toggleLogPause,
                    )
                    CompactButton(stringResource(R.string.oscam_log_clear), viewModel::clearLog)
                }
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    debugLevels.forEach { (level, label) ->
                        CompactButton(
                            stringResource(label),
                            { viewModel.setDebug(level) },
                            selected = state.debug == level,
                            enabled = controlsEnabled,
                        )
                    }
                }
                Text(
                    text = state.log.ifEmpty { stringResource(R.string.oscam_empty_log) },
                    color = if (state.log.isEmpty()) EnigmaColors.Muted else EnigmaColors.Text,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(logScroll)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
    when (state.confirm) {
        OscamConfirm.RESTART -> TvConfirmDialog(
            title = stringResource(R.string.oscam_restart_title),
            message = stringResource(R.string.oscam_restart_message),
            confirmLabel = stringResource(R.string.oscam_restart),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissConfirm,
            focusConfirm = false,
        )
        OscamConfirm.STOP -> TvConfirmDialog(
            title = stringResource(R.string.oscam_stop_title),
            message = stringResource(R.string.oscam_stop_message),
            confirmLabel = stringResource(R.string.oscam_stop),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissConfirm,
            destructive = true,
        )
        OscamConfirm.RESTART_READER -> TvConfirmDialog(
            title = stringResource(R.string.oscam_restart_reader_title, state.pendingReader.orEmpty()),
            message = stringResource(R.string.oscam_restart_reader_message),
            confirmLabel = stringResource(R.string.oscam_restart_reader),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissConfirm,
            focusConfirm = false,
        )
        OscamConfirm.RESET_READER_STATS -> TvConfirmDialog(
            title = stringResource(R.string.oscam_reset_readers_title),
            message = stringResource(R.string.oscam_reset_readers_message),
            confirmLabel = stringResource(R.string.oscam_reset_readers),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissConfirm,
            focusConfirm = false,
        )
        OscamConfirm.RESET_USER_STATS -> TvConfirmDialog(
            title = stringResource(R.string.oscam_reset_users_title),
            message = stringResource(R.string.oscam_reset_users_message),
            confirmLabel = stringResource(R.string.oscam_reset_users),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissConfirm,
            focusConfirm = false,
        )
        null -> Unit
    }
}

@Composable
private fun ReaderRow(reader: OscamReader, enabled: Boolean, onRestart: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(reader.name, color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = readerDetail(reader)
            if (detail.isNotBlank()) {
                Text(detail, color = EnigmaColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (reader.connection.isNotBlank()) StatusPill(reader.connection, connectionColor(reader.connection))
        CompactButton(stringResource(R.string.oscam_restart_reader), onRestart, enabled = enabled)
    }
}

@Composable
private fun UserRow(user: OscamUser) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(user.name, color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(
            user.channel.takeIf { it.isNotBlank() },
            user.answeredBy.takeIf { it.isNotBlank() },
            user.ecmMs.takeIf { it.isNotBlank() }?.let { "$it ms" },
        ).joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(detail, color = EnigmaColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CompactButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) EnigmaColors.Accent else EnigmaColors.Raised,
            contentColor = EnigmaColors.Text,
            focusedContainerColor = EnigmaColors.Focus,
            focusedContentColor = EnigmaColors.OnFocus,
        ),
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun serverCaption(version: String, uptime: String): String {
    val short = version.substringBefore("-").ifBlank { version }
    return if (uptime.isBlank()) short else "$short · $uptime"
}

private fun readerDetail(reader: OscamReader): String {
    val protocol = reader.protocol.substringBefore(" (").trim()
    return listOfNotNull(
        protocol.takeIf { it.isNotBlank() },
        reader.channel.takeIf { it.isNotBlank() },
        reader.ecmMs.takeIf { it.isNotBlank() }?.let { "$it ms" },
    ).joinToString(" · ")
}

private fun connectionColor(status: String): Color {
    val ok = status.equals("CARDOK", true) || status.equals("CONNECTED", true) || status.equals("OK", true)
    return if (ok) EnigmaColors.Cyan else EnigmaColors.Danger
}
