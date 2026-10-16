package com.enigmacast.ui.connection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.ui.theme.ChoiceChip
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.edgeLeft
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty

private enum class ReceiverSetupStep { Choose, Detected, Manual }

/**
 * Autodetect or Manual, for the first start and for Settings › Receiver.
 * Address and login fields stay hidden until one of the two is chosen.
 * After Autodetect only the login remains; Manual shows the address fields too.
 */
@Composable
fun ReceiverSetup(
    viewModel: ConnectionViewModel,
    autodetectFocus: FocusRequester,
    showConnect: Boolean,
    onConnected: () -> Unit = {},
    onSaved: () -> Unit = {},
    onSettings: (() -> Unit)? = null,
    /** Wizard only: a detected receiver that does not ask for a login is saved and the step advances. */
    autoConnectOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(ReceiverSetupStep.Choose) }
    AutodetectDialogHost(
        viewModel = viewModel,
        returnFocus = autodetectFocus,
        onChosen = { receiver ->
            if (autoConnectOpen && !receiver.loginRequired) {
                viewModel.username = ""
                viewModel.password = ""
                viewModel.connect(onConnected) { step = ReceiverSetupStep.Detected }
                if (!viewModel.busy) step = ReceiverSetupStep.Detected
            } else {
                step = ReceiverSetupStep.Detected
            }
        },
    )
    BackHandler(enabled = step != ReceiverSetupStep.Choose) {
        step = ReceiverSetupStep.Choose
        runCatching { autodetectFocus.requestFocus() }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (viewModel.hasSavedReceiver && viewModel.host.isNotBlank()) {
            Text(
                stringResource(R.string.receiver_current, "${viewModel.name}  ·  ${viewModel.host}"),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChoiceChip(
                label = stringResource(R.string.action_autodetect),
                selected = step == ReceiverSetupStep.Detected,
                onClick = viewModel::startAutodetect,
                modifier = Modifier.focusRequester(autodetectFocus).edgeLeft(),
            )
            ChoiceChip(
                label = stringResource(R.string.action_manual),
                selected = step == ReceiverSetupStep.Manual,
                onClick = { step = ReceiverSetupStep.Manual },
            )
            if (onSettings != null) {
                CompactButton(stringResource(R.string.settings), onSettings)
            }
        }
        when (step) {
            ReceiverSetupStep.Choose -> Unit
            ReceiverSetupStep.Detected -> {
                Text(
                    stringResource(
                        R.string.receiver_found,
                        viewModel.name.ifBlank { viewModel.host },
                        viewModel.host,
                        viewModel.httpPort,
                        if (viewModel.useHttps) "HTTPS" else "HTTP",
                    ),
                    color = EnigmaColors.Text,
                    fontSize = 14.sp,
                )
                HttpWarning(viewModel)
                LoginFields(viewModel)
                SetupActions(viewModel, showConnect, onConnected, onSaved)
            }
            ReceiverSetupStep.Manual -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SetupField(stringResource(R.string.field_receiver_name), viewModel.name, { viewModel.name = it }, Modifier.weight(1f), followEdge = true)
                    SetupField(stringResource(R.string.field_host), viewModel.host, { viewModel.host = it }, Modifier.weight(1f), TvFieldKind.HOST)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SetupField(stringResource(R.string.field_http_port), viewModel.httpPort, { viewModel.httpPort = it }, Modifier.weight(1f), TvFieldKind.NUMBER, followEdge = true)
                    SetupField(stringResource(R.string.field_stream_port), viewModel.streamPort, { viewModel.streamPort = it }, Modifier.weight(1f), TvFieldKind.NUMBER)
                }
                LoginFields(viewModel)
                ToggleRow(
                    title = "HTTPS",
                    description = stringResource(R.string.https_desc),
                    checked = viewModel.useHttps,
                    onCheckedChange = { viewModel.useHttps = it },
                )
                HttpWarning(viewModel)
                SetupActions(viewModel, showConnect, onConnected, onSaved)
            }
        }
    }
}

@Composable
private fun LoginFields(viewModel: ConnectionViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SetupField(stringResource(R.string.field_username), viewModel.username, { viewModel.username = it }, Modifier.weight(1f), followEdge = true)
        SetupField(
            stringResource(R.string.field_password),
            viewModel.password,
            { viewModel.password = it },
            Modifier.weight(1f),
            TvFieldKind.PASSWORD,
        )
    }
}

@Composable
private fun SetupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: TvFieldKind = TvFieldKind.TEXT,
    followEdge: Boolean = false,
) {
    TvTextField(label, value, onValueChange, modifier, kind = kind, followEdge = followEdge)
}

@Composable
private fun HttpWarning(viewModel: ConnectionViewModel) {
    if (viewModel.useHttps) return
    val internet = viewModel.host.isNotBlank() && !ReceiverRouting.isLocalAddress(viewModel.host)
    Text(
        stringResource(if (internet) R.string.conn_http_internet_warning else R.string.conn_http_warning),
        color = if (internet) EnigmaColors.Danger else EnigmaColors.Accent,
        fontSize = 13.sp,
    )
}

@Composable
private fun SetupActions(
    viewModel: ConnectionViewModel,
    showConnect: Boolean,
    onConnected: () -> Unit,
    onSaved: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (showConnect) {
            CompactButton(stringResource(R.string.action_test_connection), viewModel::test, modifier = Modifier.edgeLeft())
            CompactButton(stringResource(R.string.action_save), { viewModel.save(onSaved) })
        } else {
            CompactButton(stringResource(R.string.action_save), { viewModel.save(onSaved) }, modifier = Modifier.edgeLeft())
        }
        if (showConnect) {
            CompactButton(stringResource(R.string.action_connect), { viewModel.connect(onConnected) })
        }
    }
    if (viewModel.status.isNotEmpty()) {
        Text(viewModel.status.asString(), color = EnigmaColors.Muted, fontSize = 13.sp)
    }
}
