package com.enigmacast.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.ui.connection.AutodetectState
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.connection.ReceiverSetup
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.edgeLeft
import com.enigmacast.ui.theme.requestWhenReady

private enum class ReceiverForm { Add, Edit }

/**
 * Settings › Receivers. OK on a row makes that box the one in use.
 * Add and Edit open the autodetect or manual form in this same page.
 */
@Composable
fun ReceiversSettingsPanel(
    receivers: List<ReceiverConfig>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    /** Left from the form returns to the Receivers item, not the main menu. */
    menuAnchor: FocusRequester,
    /**
     * True while the address form is open. The dashboard then ignores focus that
     * lands on the main menu, which would otherwise leave Settings.
     */
    onFormActive: (Boolean) -> Unit,
    /** Where to put focus again when it escapes the form. */
    formFocus: FocusRequester,
    /** OK on the Receivers menu item lands here. */
    contentFocus: FocusRequester,
    connection: ConnectionViewModel = hiltViewModel(),
) {
    var form by remember { mutableStateOf<ReceiverForm?>(null) }
    val currentForm = rememberUpdatedState(form)
    var returning by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ReceiverConfig?>(null) }
    val addFocus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        onDispose {
            onFormActive(false)
            if (currentForm.value == ReceiverForm.Add) connection.abandonNewReceiver()
        }
    }
    LaunchedEffect(form, returning) {
        if (form == null && returning) {
            addFocus.requestWhenReady()
            returning = false
            onFormActive(false)
        }
    }
    fun closeForm() {
        if (form == ReceiverForm.Add) connection.abandonNewReceiver()
        returning = true
        form = null
    }
    if (form != null) {
        BackHandler { closeForm() }
        LaunchedEffect(connection.autodetectState is AutodetectState.Hidden) {
            if (connection.autodetectState is AutodetectState.Hidden) formFocus.requestWhenReady()
        }
        ReceiverSetup(
            viewModel = connection,
            autodetectFocus = formFocus,
            showConnect = false,
            onSaved = { closeForm() },
            modifier = Modifier.focusProperties { left = menuAnchor },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.receivers_intro),
            color = EnigmaColors.Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        CompactButton(
            label = stringResource(R.string.receivers_add),
            onClick = {
                onFormActive(true)
                connection.beginNew()
                form = ReceiverForm.Add
            },
            modifier = Modifier.focusRequester(addFocus).focusRequester(contentFocus).edgeLeft(),
        )
        if (receivers.isEmpty()) {
            Text(stringResource(R.string.receivers_empty), color = EnigmaColors.Muted, fontSize = 14.sp)
        }
        receivers.forEach { receiver ->
            val inUse = receiver.id == activeId
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CompactListRow(
                    onClick = { onSelect(receiver.id) },
                    selected = inUse,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) { _ ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(receiver.name.ifBlank { receiver.host }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (receiver.name.isNotBlank()) {
                            Text(receiver.host, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    if (inUse) {
                        Text(
                            stringResource(R.string.receivers_in_use),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                CompactButton(
                    label = stringResource(R.string.receivers_edit),
                    onClick = {
                        onFormActive(true)
                        connection.editReceiver(receiver)
                        form = ReceiverForm.Edit
                    },
                )
                CompactButton(
                    label = stringResource(R.string.receivers_delete),
                    onClick = { pendingDelete = receiver },
                )
            }
        }
    }
    pendingDelete?.let { receiver ->
        val label = receiver.name.ifBlank { receiver.host }
        TvConfirmDialog(
            title = stringResource(R.string.receivers_delete_title),
            message = stringResource(R.string.receivers_delete_message, label),
            confirmLabel = stringResource(R.string.receivers_delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                pendingDelete = null
                onDelete(receiver.id)
            },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}
