package com.enigmacast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.domain.sync.SyncServerOffer
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.enigmasync.EnigmaSyncClient
import com.enigmacast.data.enigmasync.EnigmaSyncEngine
import com.enigmacast.data.enigmasync.SyncServerInstaller
import com.enigmacast.data.enigmasync.SyncServerProbe
import com.enigmacast.data.enigmasync.UpdateError
import com.enigmacast.util.EnigmaLog
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.ui.theme.InfoRow
import com.enigmacast.data.enigmasync.SyncServerUpdater
import com.enigmacast.domain.sync.SyncServerVersions
import com.enigmacast.ui.theme.EnigmaColors
import androidx.tv.material3.Text
import androidx.compose.ui.unit.sp
import com.enigmacast.ui.theme.SettingsCard
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@HiltViewModel
class SyncServerViewModel @Inject constructor(
    private val installer: SyncServerInstaller,
    private val client: EnigmaSyncClient,
    private val engine: EnigmaSyncEngine,
    private val profiles: ProfileStore,
    private val settings: SettingsRepository,
    private val updater: SyncServerUpdater,
) : ViewModel() {
    var host by mutableStateOf("")
    var user by mutableStateOf("root")
    var password by mutableStateOf("")
    var recoveryCode by mutableStateOf("")
    var freshRecovery by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var linkedHost by mutableStateOf("")
    var linkedRole by mutableStateOf("")
    /** After an install: the web dashboard and its one-time password (moved here from the setup wizard). */
    var dashboardUrl by mutableStateOf("")
    var dashboardPassword by mutableStateOf("")

    /** Not paired: looking for a sync server on [host] port 8740. */
    var probing by mutableStateOf(false)
        private set
    /** Not paired: the sync server that answered on the receiver, or null (none / not searched yet). */
    var found by mutableStateOf<SyncServerProbe?>(null)
        private set
    /** Host the last search looked at ("" = not searched yet). */
    var searched by mutableStateOf("")
        private set
    /** "Reinstall" was confirmed: show the install form although a server runs. */
    var reinstallForm by mutableStateOf(false)
    /** The install stopped to protect existing data (shown with the update texts). */
    var installFailure by mutableStateOf<SyncServerUpdater.State.Failed?>(null)
        private set
    /** Why "Pair with existing server" failed (shown with the pairing text). */
    var pairError by mutableStateOf<String?>(null)
        private set
    /** Just paired from this card: focus moves to the first button of the paired view. */
    var justPaired by mutableStateOf(false)

    /** The last sync run's outcome (shown as "sync incomplete" while profiles or parts failed). */
    val health = engine.health

    init {
        viewModelScope.launch { runCatching { engine.loadHealth() } }
        viewModelScope.launch {
            val receiver = settings.current().activeReceiver()
            if (host.isBlank()) host = receiver?.host.orEmpty()
            val config = client.config()
            if (config != null) {
                linkedHost = config.host
                linkedRole = config.role
            } else {
                search()
            }
        }
    }

    /**
     * Not paired: asks port 8740 on the receiver (no login, also answers on 0.2.x servers) whether a
     * sync server runs. Found → pair with it; nothing → the install form.
     */
    fun search(checkRelease: Boolean = true) {
        val address = host.trim()
        if (probing || linkedHost.isNotBlank() || address.isBlank()) return
        probing = true
        installFailure = null
        pairError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.probe(address, 4, 6) }.getOrNull() }
            found = result
            searched = address
            probing = false
            reinstallForm = false
            // Version check against the latest release in the background, for the Update button.
            if (result != null && checkRelease) launch { runCatching { updater.check(address) } }
        }
    }

    /**
     * Joins the running server the same way the setup wizard and the other TVs do: trust the
     * certificate it presents, join (member when an admin exists), then copy its profiles here.
     * Nothing on the receiver changes and no local profile is pushed to the server.
     */
    fun pairExisting() {
        val server = found ?: return
        if (busy) return
        busy = true
        status = ""
        pairError = null
        installFailure = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pin = client.capturePin(server.host)
                    if (pin != server.pin) error("${server.host}:8740 presenteert een ander certificaat dan bij het zoeken")
                    val paired = JSONObject(client.join(server.host, pin, "EnigmaCast"))
                    client.save(server.host, pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    engine.importProfiles()
                    runCatching { engine.sync(foreground = true) }
                    Unit
                }
            }
            busy = false
            result.fold(
                onSuccess = {
                    client.config()?.let {
                        linkedHost = it.host
                        linkedRole = it.role
                    }
                    found = null
                    reinstallForm = false
                    justPaired = true
                },
                onFailure = {
                    EnigmaLog.service("syncserver koppelen mislukt: ${it.javaClass.simpleName}")
                    pairError = it.message ?: it.javaClass.simpleName
                },
            )
        }
    }

    fun install() {
        if (busy || host.isBlank() || password.isBlank()) return
        busy = true
        status = ""
        installFailure = null
        val typed = password
        password = ""
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = installer.install(host.trim(), user.trim(), typed)
                    val paired = JSONObject(client.join(host.trim(), report.pin, "EnigmaCast"))
                    client.save(host.trim(), report.pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    if (report.pairingCode.isBlank()) {
                        // Existing data was kept: take the server's profiles, do not add this TV's.
                        engine.importProfiles()
                    } else {
                        profiles.state.value.profiles.forEach { profile ->
                            val body = """{"uuid":${json(profile.uuid)},"name":${json(profile.name)},"avatar":${profile.avatar},"updatedMs":${System.currentTimeMillis()},"changeId":${json(profile.uuid + "-import")}}"""
                            runCatching { client.request(client.config()!!, "POST", "/api/v1/profiles", body.toByteArray()) }
                        }
                    }
                    engine.sync(foreground = true)
                    report
                }
            }
            busy = false
            val error = result.exceptionOrNull()
            installFailure = (error as? UpdateError)?.let { SyncServerUpdater.State.Failed(it.reason, it.detail) }
            status = result.fold(
                onSuccess = { "Installed on ${it.model}. ${it.python}. Data ${it.dataDir}." },
                onFailure = { if (installFailure != null) "" else it.message ?: "Install failed" },
            )
            result.getOrNull()?.let { report ->
                dashboardUrl = report.dashboardUrl
                dashboardPassword = report.dashboardPassword
                client.config()?.let {
                    linkedHost = it.host
                    linkedRole = it.role
                }
            }
        }
    }

    /** Frees the single admin slot with the recovery code, then pairs this device as that admin. */
    fun disconnect() {
        client.clear()
        linkedHost = ""
        linkedRole = ""
        freshRecovery = null
        search()
    }

    fun claim() {
        if (busy || host.isBlank() || recoveryCode.isBlank()) return
        busy = true
        status = "Bezig met beheer overnemen…"
        freshRecovery = null
        val typedRecovery = recoveryCode.trim()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val address = host.trim()
                    val pin = client.storedPin(address) ?: client.capturePin(address)
                    val recovered = JSONObject(client.recover(address, pin, typedRecovery))
                    val newCode = recovered.getString("recoveryCode")
                    val paired = try {
                        JSONObject(client.pair(address, pin, recovered.getString("pairingCode"), "EnigmaCast"))
                    } catch (error: Throwable) {
                        throw IllegalStateException("${error.message}. Nieuwe herstelcode (bewaren): $newCode", error)
                    }
                    if (paired.getString("role") != "admin") error("receiver did not grant admin")
                    client.save(address, pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    profiles.state.value.profiles.forEach { profile ->
                        val body = """{"uuid":${json(profile.uuid)},"name":${json(profile.name)},"avatar":${profile.avatar},"updatedMs":${System.currentTimeMillis()},"changeId":${json(profile.uuid + "-claim")}}"""
                        runCatching { client.request(client.config()!!, "POST", "/api/v1/profiles", body.toByteArray()) }
                    }
                    runCatching { engine.sync(foreground = true) }
                    newCode
                }
            }
            busy = false
            result.fold(
                onSuccess = {
                    freshRecovery = it
                    password = ""
                    recoveryCode = ""
                    status = ""
                    linkedHost = host.trim()
                    linkedRole = "admin"
                },
                onFailure = { status = it.message ?: "Claim failed" },
            )
        }
    }

    private fun json(value: String) = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
}

@Composable
fun SyncServerCard(
    model: SyncServerViewModel = hiltViewModel(),
    updates: SyncServerUpdateViewModel = hiltViewModel(),
) {
    SettingsCard(stringResource(R.string.sync_server_title)) {
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.freshRecovery?.let { InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_claimed, it)) }
            if (model.status.isNotBlank()) InfoRow(stringResource(R.string.sync_server_status), model.status)
            if (model.dashboardUrl.isNotBlank()) {
                InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.setup_sync_dashboard_ready, model.dashboardUrl))
            }
            if (model.dashboardPassword.isNotBlank()) {
                InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.setup_sync_dashboard_password, model.dashboardPassword))
            }
            if (model.linkedHost.isNotBlank()) {
                InfoRow(
                    stringResource(R.string.sync_server_status),
                    stringResource(R.string.sync_server_connected, model.linkedHost, model.linkedRole),
                )
                val health by model.health.collectAsState()
                if (health.incomplete) {
                    InfoRow(
                        stringResource(R.string.sync_server_last_sync),
                        if (health.failedProfiles > 0) {
                            stringResource(R.string.sync_server_incomplete_profiles, health.failedProfiles, health.totalProfiles)
                        } else {
                            stringResource(R.string.sync_server_incomplete)
                        },
                    )
                }
                val updateState by updates.state.collectAsState()
                val lastCheck by updates.last.collectAsState()
                // Hotel mode: guests are never asked, so the owner sees it here behind the PIN.
                lastCheck?.takeIf { updates.hotelMode && it.verdict == SyncServerVersions.Verdict.OUTDATED }?.let {
                    InfoRow(stringResource(R.string.sync_update_title), stringResource(R.string.sync_update_hotel_notice, it.runningText, it.latest ?: "?"))
                }
                if (!updates.dialog) syncUpdateStatus(updateState)?.let { InfoRow(stringResource(R.string.sync_update_title), it) }
                val linkedFocus = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(
                        stringResource(R.string.sync_update_button),
                        { updates.checkAndOffer() },
                        modifier = Modifier.focusRequester(linkedFocus),
                        enabled = updateState !is SyncServerUpdater.State.Checking && updateState !is SyncServerUpdater.State.Updating,
                    )
                    TvButton(stringResource(R.string.sync_server_disconnect), { model.disconnect() })
                }
                // Paired from this card: the Pair button is gone, keep D-pad focus inside the card.
                LaunchedEffect(model.justPaired) {
                    if (model.justPaired) {
                        runCatching { linkedFocus.requestFocus() }
                        model.justPaired = false
                    }
                }
                if (updates.dialog) SyncServerUpdateDialog(updates)
                return@Column
            }
            NotPairedSection(model, updates)
        }
    }
}

/**
 * Not paired: a server already running on the receiver is paired with (or updated), like the other
 * TVs; installing again is only behind a warning. No server: the install form.
 */
@Composable
private fun NotPairedSection(model: SyncServerViewModel, updates: SyncServerUpdateViewModel) {
    val updateState by updates.state.collectAsState()
    val lastCheck by updates.last.collectAsState()
    val server = model.found
    var confirmReinstall by remember { mutableStateOf(false) }
    // After the update dialog closes, ask the server again so the found version is current.
    var dialogWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(updates.dialog) {
        if (dialogWasOpen && !updates.dialog) model.search(checkRelease = false)
        dialogWasOpen = updates.dialog
    }
    when {
        model.probing -> InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_searching, model.host.trim()))
        server != null -> {
            InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_found, server.host, SyncServerOffer.label(server.version)))
            Text(stringResource(R.string.sync_server_found_hint), color = EnigmaColors.Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        model.searched.isNotBlank() -> InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_not_found, model.searched))
    }
    if (model.busy && server != null && !model.reinstallForm) {
        InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_pairing))
    }
    model.pairError?.let { InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_pair_failed, it)) }
    model.installFailure?.let { failure -> syncUpdateStatus(failure)?.let { InfoRow(stringResource(R.string.sync_server_status), it) } }
    val check = lastCheck?.takeIf { server != null && it.host == server.host && !it.paired }
    if (server != null && !updates.dialog && (updateState is SyncServerUpdater.State.Checking || check != null)) {
        syncUpdateStatus(updateState)?.let { InfoRow(stringResource(R.string.sync_update_title), it) }
    }
    val actions = SyncServerOffer.actions(server != null, server?.version, check?.latest)
    val updating = updateState is SyncServerUpdater.State.Checking || updateState is SyncServerUpdater.State.Updating
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (SyncServerOffer.Action.PAIR in actions) {
            TvButton(stringResource(R.string.sync_server_pair_existing), { model.pairExisting() }, enabled = !model.busy && !updating)
        }
        if (SyncServerOffer.Action.UPDATE in actions && server != null) {
            TvButton(stringResource(R.string.sync_update_button), { updates.checkAndOffer(server.host) }, enabled = !model.busy && !updating)
        }
        if (SyncServerOffer.Action.REINSTALL in actions) {
            TvButton(stringResource(R.string.sync_server_reinstall), { confirmReinstall = true }, enabled = !model.busy && !updating)
        }
        TvButton(stringResource(R.string.sync_server_search_again), { model.search() }, enabled = !model.probing && !model.busy && !updating)
    }
    if (server == null || model.reinstallForm) {
        if (server == null) {
            Text(stringResource(R.string.sync_server_install_hint), color = EnigmaColors.Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        TvTextField(stringResource(R.string.sync_server_host), model.host, { model.host = it }, kind = TvFieldKind.HOST)
        TvTextField(stringResource(R.string.sync_server_user), model.user, { model.user = it })
        TvTextField(stringResource(R.string.sync_server_password), model.password, { model.password = it }, visualTransformation = PasswordVisualTransformation())
        TvTextField(stringResource(R.string.sync_server_recovery), model.recoveryCode, { model.recoveryCode = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(stringResource(R.string.sync_server_install), { model.install() }, enabled = !model.busy && !model.probing)
            TvButton(stringResource(R.string.sync_server_claim), { model.claim() }, enabled = !model.busy && model.recoveryCode.isNotBlank())
        }
    }
    if (confirmReinstall && server != null) {
        TvConfirmDialog(
            title = stringResource(R.string.sync_server_reinstall_title),
            message = stringResource(R.string.sync_server_reinstall_message, server.host),
            confirmLabel = stringResource(R.string.sync_server_reinstall_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmReinstall = false
                model.reinstallForm = true
            },
            onDismiss = { confirmReinstall = false },
            destructive = true,
        )
    }
    if (updates.dialog) SyncServerUpdateDialog(updates)

}
