package com.enigmacast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.enigmasync.SyncServerUpdater
import com.enigmacast.data.enigmasync.SyncServerUpdater.State
import com.enigmacast.data.enigmasync.UpdateFailure
import com.enigmacast.data.enigmasync.UpdateStep
import com.enigmacast.domain.sync.SyncServerVersions
import com.enigmacast.ui.theme.DialogButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.skinSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** The sync server update: version check, the one-time prompt and the SSH login dialog. */
@HiltViewModel
class SyncServerUpdateViewModel @Inject constructor(
    private val updater: SyncServerUpdater,
    private val hotel: com.enigmacast.data.hotel.HotelStore,
) : ViewModel() {
    val state = updater.state
    val last = updater.last

    /** The startup question (Later / Update); null when there is nothing to ask. */
    var prompt by mutableStateOf<SyncServerUpdater.Check?>(null)
        private set

    /** The login dialog is open. */
    var dialog by mutableStateOf(false)
        private set
    var user by mutableStateOf("root")
    var password by mutableStateOf("")

    val hotelMode: Boolean get() = hotel.current.enabled

    /** Once per app start (so also after an app update), in the background. */
    fun startupCheck() {
        if (startupDone) return
        startupDone = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(STARTUP_DELAY_MS)
            prompt = runCatching { updater.startupPrompt(hotel.current.enabled) }.getOrNull()
        }
    }

    /**
     * "Update sync server" in Settings: check first; the dialog opens only when there is something to install.
     * [host]: a server found at the receiver address while this TV is not paired.
     */
    fun checkAndOffer(host: String? = null) {
        if (state.value is State.Checking || state.value is State.Updating) return
        viewModelScope.launch {
            val check = runCatching { updater.check(host) }.getOrNull() ?: return@launch
            if (check.verdict != SyncServerVersions.Verdict.UP_TO_DATE && check.latest != null) dialog = true
        }
    }

    fun later() {
        updater.postpone(prompt?.latest)
        prompt = null
    }

    fun acceptPrompt() {
        prompt = null
        dialog = true
    }

    fun closeDialog() {
        if (state.value is State.Updating) return
        dialog = false
        password = ""
        updater.reset()
    }

    fun update() {
        if (password.isBlank() || state.value is State.Updating) return
        val typed = password
        password = ""
        viewModelScope.launch { updater.update(user.trim(), typed) }
    }

    private companion object {
        const val STARTUP_DELAY_MS = 20_000L
        @Volatile
        var startupDone = false
    }
}

/** The one-time prompt and the update dialog, over the whole app. [allowed]: no other dialog, no wizard. */
@Composable
fun SyncServerUpdatePrompt(allowed: Boolean, model: SyncServerUpdateViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { model.startupCheck() }
    val prompt = model.prompt
    if (allowed && prompt != null && !model.hotelMode) {
        TvConfirmDialog(
            title = stringResource(R.string.sync_update_title),
            message = availableMessage(prompt),
            confirmLabel = stringResource(R.string.sync_update_now),
            dismissLabel = stringResource(R.string.sync_update_later),
            onConfirm = model::acceptPrompt,
            onDismiss = model::later,
        )
    }
    if (model.dialog) SyncServerUpdateDialog(model)
}

@Composable
private fun availableMessage(check: SyncServerUpdater.Check): String {
    val base = stringResource(R.string.sync_update_available, check.runningText, check.latest ?: "?")
    return if (check.running.withoutPaging) base + " " + stringResource(R.string.sync_update_paging_hint) else base
}

/** Text of an update or install step ("Backing up…", "Installing…", "Restarting…", "Checking…"). */
fun updateStepText(step: UpdateStep): Int = when (step) {
    UpdateStep.CONNECT -> R.string.sync_update_step_connect
    UpdateStep.LOCATE -> R.string.sync_update_step_locate
    UpdateStep.DOWNLOAD -> R.string.sync_update_step_download
    UpdateStep.BACKUP -> R.string.sync_update_step_backup
    UpdateStep.INSTALL -> R.string.sync_update_step_install
    UpdateStep.RESTART -> R.string.sync_update_step_restart
    UpdateStep.VERIFY -> R.string.sync_update_step_verify
}

/** A running step: spinner and its text. Not focusable, so D-pad focus stays where it was. */
@Composable
fun SyncBusyRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = EnigmaColors.Accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(22.dp),
        )
        Text(text, color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = EnigmaType.Strong, lineHeight = 20.sp)
    }
}

@Composable
fun syncUpdateStatus(state: State): String? = when (state) {
    State.Idle -> null
    State.Checking -> stringResource(R.string.sync_update_checking)
    is State.Checked -> when (state.check.verdict) {
        SyncServerVersions.Verdict.UP_TO_DATE -> stringResource(R.string.sync_update_current, state.check.runningText)
        SyncServerVersions.Verdict.OUTDATED -> stringResource(R.string.sync_update_available, state.check.runningText, state.check.latest ?: "?")
        SyncServerVersions.Verdict.UNKNOWN -> stringResource(R.string.sync_update_unknown, state.check.runningText)
    }
    is State.CheckFailed -> stringResource(R.string.sync_update_check_failed)
    is State.Updating -> stringResource(updateStepText(state.step))
    is State.Done -> if (state.backup.isEmpty()) {
        stringResource(R.string.sync_update_current, state.version)
    } else {
        stringResource(R.string.sync_update_done, state.version, state.backup)
    }
    is State.Failed -> stringResource(
        R.string.sync_update_failed,
        when (state.reason) {
            UpdateFailure.NOT_INSTALLED -> stringResource(R.string.sync_update_err_not_installed)
            UpdateFailure.NO_DATA -> stringResource(R.string.sync_update_err_no_data)
            UpdateFailure.PIN_MISMATCH -> stringResource(R.string.sync_update_err_pin)
            UpdateFailure.NO_RELEASE -> stringResource(R.string.sync_update_err_release)
            UpdateFailure.DATA_DIR_MOVES -> stringResource(R.string.sync_update_err_dir, state.detail)
            UpdateFailure.BACKUP -> stringResource(R.string.sync_update_err_backup)
            UpdateFailure.VERIFY -> stringResource(R.string.sync_update_err_verify, state.detail)
            null -> state.detail.ifBlank { "SSH" }
        },
    )
}

/**
 * SSH login and progress. Focus starts on the password field; while the update runs no button
 * is focusable and Back does nothing, so the receiver is never left halfway by a stray key.
 */
@Composable
fun SyncServerUpdateDialog(model: SyncServerUpdateViewModel) {
    val state by model.state.collectAsState()
    val last by model.last.collectAsState()
    val running = state is State.Updating
    val finished = state is State.Done
    Dialog(onDismissRequest = { model.closeDialog() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val passwordFocus = remember { FocusRequester() }
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(running, finished) {
            if (running) return@LaunchedEffect
            runCatching { if (finished) closeFocus.requestFocus() else passwordFocus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .skinSurface(EnigmaColors.Selected, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.sync_update_title), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
            last?.takeIf { !finished }?.let {
                Text(availableMessage(it), color = EnigmaColors.Muted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            syncUpdateStatus(state)?.takeIf { state !is State.Checked }?.let {
                if (running || state is State.Checking) {
                    SyncBusyRow(it)
                } else {
                    Text(it, color = if (state is State.Failed) EnigmaColors.Text else EnigmaColors.Muted, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
            if (!finished) {
                Text(stringResource(R.string.sync_update_login_hint), color = EnigmaColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                TvTextField(stringResource(R.string.sync_server_user), model.user, { model.user = it }, browsable = !running)
                TvTextField(
                    stringResource(R.string.sync_server_password),
                    model.password,
                    { model.password = it },
                    kind = TvFieldKind.PASSWORD,
                    visualTransformation = PasswordVisualTransformation(),
                    fieldFocus = passwordFocus,
                    browsable = !running,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (!finished) {
                    DialogButton(
                        label = stringResource(R.string.sync_update_now),
                        onClick = { model.update() },
                        destructive = false,
                        enabled = !running && model.password.isNotBlank(),
                        compact = false,
                        modifier = Modifier,
                    )
                }
                DialogButton(
                    label = stringResource(if (finished) R.string.close else R.string.cancel),
                    onClick = { model.closeDialog() },
                    destructive = false,
                    enabled = !running,
                    compact = false,
                    modifier = Modifier.focusRequester(closeFocus),
                )
            }
        }
    }
}
