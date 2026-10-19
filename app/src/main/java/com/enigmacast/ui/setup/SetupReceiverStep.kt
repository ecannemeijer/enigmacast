package com.enigmacast.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.ui.connection.AutodetectDialogHost
import com.enigmacast.ui.connection.ConnectFailure
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.theme.ChoiceChip
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.edgeLeft
import com.enigmacast.util.asString

enum class NoticeKind { SUCCESS, ERROR, INFO }

/** A tinted box with an icon: what happened, and what to do next. */
@Composable
fun SetupNotice(text: String, kind: NoticeKind, modifier: Modifier = Modifier) {
    val color = when (kind) {
        NoticeKind.SUCCESS -> EnigmaColors.Success
        NoticeKind.ERROR -> EnigmaColors.Danger
        NoticeKind.INFO -> EnigmaColors.Accent
    }
    val icon = when (kind) {
        NoticeKind.SUCCESS -> "✓"
        NoticeKind.ERROR -> "!"
        NoticeKind.INFO -> "i"
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .background(color.copy(alpha = 0.16f), shape)
            .border(1.dp, color, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(text, color = EnigmaColors.Text, fontSize = 17.sp, lineHeight = 24.sp)
    }
}

private enum class ReceiverMode { NONE, FOUND, MANUAL }

/**
 * The wizard's receiver step: searches by itself, one Connect button, login fields only when the
 * receiver asks for them (username filled in as root), and a manual form that only asks for the
 * address, with ports and HTTPS behind "Advanced". Settings › Receiver keeps its own full form.
 */
@Composable
fun WizardReceiverSetup(
    viewModel: ConnectionViewModel,
    focus: FocusRequester,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(ReceiverMode.NONE) }
    var loginNeeded by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // A first run searches straight away; a re-run from Settings keeps the receiver it has.
        if (!autoStarted && !viewModel.hasSavedReceiver) {
            autoStarted = true
            viewModel.startAutodetect()
        }
    }
    fun askLogin() {
        loginNeeded = true
        if (viewModel.username.isBlank()) viewModel.username = "root"
    }
    fun connect() {
        viewModel.connect(onConnected) { if (viewModel.failure == ConnectFailure.LOGIN) askLogin() }
    }
    AutodetectDialogHost(
        viewModel = viewModel,
        returnFocus = focus,
        wizard = true,
        onManual = { mode = ReceiverMode.MANUAL },
        onChosen = { receiver ->
            mode = ReceiverMode.FOUND
            if (receiver.loginRequired) {
                viewModel.password = ""
                askLogin()
            } else {
                loginNeeded = false
                viewModel.username = ""
                viewModel.password = ""
                connect()
            }
        },
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceChip(
                label = stringResource(R.string.autodetect_search_again),
                selected = mode == ReceiverMode.FOUND,
                onClick = viewModel::startAutodetect,
                modifier = Modifier.focusRequester(focus).edgeLeft(),
            )
            ChoiceChip(
                label = stringResource(R.string.setup_receiver_manual),
                selected = mode == ReceiverMode.MANUAL,
                onClick = { mode = ReceiverMode.MANUAL },
            )
        }
        if (mode == ReceiverMode.NONE) return@Column
        if (mode == ReceiverMode.FOUND) {
            Text(
                stringResource(R.string.setup_receiver_found, viewModel.name.ifBlank { viewModel.host }),
                color = EnigmaColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            WizardField(
                stringResource(R.string.setup_receiver_address),
                viewModel.host,
                { viewModel.host = it },
                TvFieldKind.HOST,
            )
        }
        if (loginNeeded) {
            Text(stringResource(R.string.setup_receiver_login_hint), color = EnigmaColors.Muted, fontSize = 17.sp, lineHeight = 24.sp)
            LoginRow(viewModel)
        }
        if (mode == ReceiverMode.MANUAL) {
            TvButton(
                stringResource(if (advanced) R.string.setup_advanced_hide else R.string.setup_advanced),
                { advanced = !advanced },
                compact = true,
            )
            if (advanced) {
                WizardField(stringResource(R.string.field_receiver_name), viewModel.name, { viewModel.name = it })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 760.dp)) {
                    TvTextField(stringResource(R.string.field_http_port), viewModel.httpPort, { viewModel.httpPort = it }, Modifier.weight(1f), kind = TvFieldKind.NUMBER, followEdge = true)
                    TvTextField(stringResource(R.string.field_stream_port), viewModel.streamPort, { viewModel.streamPort = it }, Modifier.weight(1f), kind = TvFieldKind.NUMBER)
                }
                if (!loginNeeded) LoginRow(viewModel)
                ToggleRow(
                    title = "HTTPS",
                    description = stringResource(R.string.https_desc),
                    checked = viewModel.useHttps,
                    onCheckedChange = { viewModel.useHttps = it },
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }
        }
        // Only an address on the internet without HTTPS is worth a warning here; home addresses are normal.
        if (!viewModel.useHttps && viewModel.host.isNotBlank() && !ReceiverRouting.isLocalAddress(viewModel.host)) {
            SetupNotice(stringResource(R.string.conn_http_internet_warning), NoticeKind.ERROR)
        }
        TvButton(
            stringResource(if (viewModel.busy) R.string.setup_receiver_connecting else R.string.action_connect),
            ::connect,
            enabled = !viewModel.busy,
        )
        val failure = viewModel.failure
        if (failure != null && !viewModel.busy) {
            val text = when (failure) {
                ConnectFailure.NO_ADDRESS -> stringResource(R.string.setup_receiver_error_address)
                ConnectFailure.INVALID -> viewModel.status.asString()
                ConnectFailure.LOGIN -> stringResource(R.string.setup_receiver_error_login)
                ConnectFailure.UNREACHABLE -> stringResource(R.string.setup_receiver_error_unreachable, viewModel.host.trim())
            }
            SetupNotice(text, NoticeKind.ERROR)
        }
    }
}

@Composable
private fun LoginRow(viewModel: ConnectionViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 760.dp)) {
        TvTextField(stringResource(R.string.field_username), viewModel.username, { viewModel.username = it }, Modifier.weight(1f), followEdge = true)
        TvTextField(stringResource(R.string.field_password), viewModel.password, { viewModel.password = it }, Modifier.weight(1f), kind = TvFieldKind.PASSWORD)
    }
}

@Composable
private fun WizardField(label: String, value: String, onValueChange: (String) -> Unit, kind: TvFieldKind = TvFieldKind.TEXT) {
    TvTextField(label, value, onValueChange, Modifier.widthIn(max = 520.dp).fillMaxWidth(), kind = kind, large = true, followEdge = true)
}
