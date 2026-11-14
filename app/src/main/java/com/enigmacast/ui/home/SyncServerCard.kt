@file:OptIn(ExperimentalLayoutApi::class)

package com.enigmacast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.enigmacast.ui.theme.EnigmaType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import com.enigmacast.domain.sync.SyncRunRules
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
    private val tvBackup: com.enigmacast.data.enigmasync.SyncServerBackup,
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
    /** Text resource of the step the busy pairing or install is at (shown with a spinner). */
    var step by mutableStateOf<Int?>(null)
        private set
    /** Pairing did not finish within [SyncRunRules.PAIR_TIMEOUT_MS]. */
    var pairTimedOut by mutableStateOf(false)
        private set

    /** The sync run in progress (items so far), for the spinner in the paired view. */
    val syncProgress = engine.progress

    private fun showStep(res: Int) {
        viewModelScope.launch { if (busy) step = res }
    }

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
        if (probing || busy || linkedHost.isNotBlank() || address.isBlank()) return
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
        pairTimedOut = false
        installFailure = null
        step = R.string.sync_pair_step_cert
        // Set when the card gave up waiting: a join that answers later must not save a pairing.
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            EnigmaLog.service("sync: koppelen start host=${server.host}")
            // Only the pairing itself is awaited (certificate, join, profiles: a few requests).
            // The first full sync runs afterwards in the background with its own progress.
            val work = async(Dispatchers.IO) {
                runCatching {
                    val pin = client.capturePin(server.host, 5, 8)
                    if (pin != server.pin) error("${server.host}:8740 presenteert een ander certificaat dan bij het zoeken")
                    EnigmaLog.service("sync: koppelen certificaat ok")
                    showStep(R.string.sync_pair_step_join)
                    val paired = JSONObject(client.join(server.host, pin, "EnigmaCast"))
                    val role = paired.getString("role")
                    EnigmaLog.service("sync: koppelen join ok rol=$role")
                    if (abandoned.get()) error("abandoned")
                    client.save(server.host, pin, paired.getString("token"), paired.getString("deviceId"), role)
                    showStep(R.string.sync_pair_step_profiles)
                    val count = engine.importProfiles(strict = true)
                    EnigmaLog.service("sync: koppelen profielen gekopieerd $count")
                    false
                }
            }
            val result = kotlinx.coroutines.withTimeoutOrNull(SyncRunRules.PAIR_TIMEOUT_MS) { work.await() } ?: run {
                abandoned.set(true)
                work.cancel()
                // Saved already (only the profile copy was slow): it is paired; the sync copies them.
                if (client.config()?.host == server.host) Result.success(true) else Result.failure(PairTimeout())
            }
            busy = false
            step = null
            val seconds = (System.currentTimeMillis() - started) / 1000
            result.fold(
                onSuccess = { importLater ->
                    EnigmaLog.service("sync: koppelen klaar in ${seconds}s, eerste sync op de achtergrond")
                    client.config()?.let {
                        linkedHost = it.host
                        linkedRole = it.role
                    }
                    found = null
                    reinstallForm = false
                    justPaired = true
                    engine.syncInBackground("koppelen", importFirst = importLater)
                },
                onFailure = {
                    EnigmaLog.service("sync: koppelen mislukt na ${seconds}s: ${it.javaClass.simpleName}")
                    if (it is PairTimeout) pairTimedOut = true else pairError = it.message ?: it.javaClass.simpleName
                },
            )
        }
    }

    private class PairTimeout : Exception("timeout")

    fun install() {
        if (busy || host.isBlank() || password.isBlank()) return
        busy = true
        status = ""
        installFailure = null
        pairError = null
        pairTimedOut = false
        step = R.string.sync_update_step_connect
        val typed = password
        password = ""
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = installer.install(host.trim(), user.trim(), typed) { installStep -> showStep(updateStepText(installStep)) }
                    // A reinstall may come with a new certificate: no cached client or paging answer survives.
                    engine.serverChanged(host.trim())
                    showStep(R.string.sync_pair_step_join)
                    val paired = JSONObject(client.join(host.trim(), report.pin, "EnigmaCast"))
                    client.save(host.trim(), report.pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    showStep(R.string.sync_pair_step_profiles)
                    if (report.pairingCode.isBlank()) {
                        // Existing data was kept: take the server's profiles, do not add this TV's.
                        engine.importProfiles()
                    } else {
                        profiles.state.value.profiles.forEach { profile ->
                            val body = """{"uuid":${json(profile.uuid)},"name":${json(profile.name)},"avatar":${profile.avatar},"updatedMs":${System.currentTimeMillis()},"changeId":${json(profile.uuid + "-import")}}"""
                            runCatching { client.request(client.config()!!, "POST", "/api/v1/profiles", body.toByteArray()) }
                        }
                    }
                    report
                }
            }
            busy = false
            step = null
            if (result.isSuccess) engine.syncInBackground("installatie")
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


    // ------------------------------------------------------------ server management (every TV)

    /** One device the sync server knows. */
    data class ServerDevice(val id: String, val name: String, val role: String, val revoked: Boolean, val self: Boolean)

    var manageOpen by mutableStateOf(false)
    var manageBusy by mutableStateOf(false)
        private set
    /** Text resource for the last management result or problem (null = nothing to say). */
    var manageMessage by mutableStateOf<Int?>(null)
        private set
    var manageDetail by mutableStateOf("")
        private set
    var serverDevices by mutableStateOf<List<ServerDevice>>(emptyList())
        private set
    var serverStatus by mutableStateOf("")
        private set
    var backups by mutableStateOf<List<String>>(emptyList())
        private set

    /** 403 = this server only lets its first device manage (servers without the open-admin change). */
    private fun manageFailure(error: Throwable) {
        val text = error.message.orEmpty()
        manageMessage = when {
            text.contains("sync 403") -> R.string.sync_manage_needs_server
            text.contains("sync 404") -> R.string.sync_manage_not_supported
            else -> R.string.sync_manage_failed
        }
        manageDetail = if (manageMessage == R.string.sync_manage_failed) text else ""
    }

    private fun manage(work: suspend (com.enigmacast.data.enigmasync.SyncServerConfig) -> Unit) {
        val config = client.config() ?: return
        if (manageBusy) return
        manageBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { work(config) } }
            manageBusy = false
            result.onFailure { manageFailure(it) }
        }
    }

    fun openManage() {
        manageOpen = true
        refreshManage()
    }

    fun refreshManage() = manage { config ->
        manageMessage = null
        manageDetail = ""
        val info = JSONObject(client.request(config, "GET", "/api/v1/status"))
        serverStatus = "API ${info.optString("api")}, admin ${info.optInt("adminDevices")}"
        val rows = JSONObject(client.request(config, "GET", "/api/v1/devices")).getJSONArray("devices")
        serverDevices = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            ServerDevice(id, row.optString("name"), row.optString("role"), row.optInt("revoked") != 0, id == config.deviceId)
        }
        // Listing backups needs the newer server; an older one answers 404 and only Backup works.
        backups = runCatching {
            val list = JSONObject(client.request(config, "GET", "/api/v1/backups")).getJSONArray("backups")
            (0 until list.length()).map { list.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun revokeDevice(id: String) = manage { config ->
        client.request(config, "POST", "/api/v1/devices/revoke", """{"deviceId":${json(id)}}""".toByteArray())
        manageMessage = R.string.sync_manage_revoked
        serverDevices = serverDevices.map { if (it.id == id) it.copy(revoked = true) else it }
    }

    fun makeBackup() = manage { config ->
        val path = JSONObject(client.request(config, "POST", "/api/v1/backups")).optString("path")
        manageMessage = R.string.sync_manage_backup_done
        manageDetail = path
        if (path.isNotBlank() && path !in backups) backups = listOf(path) + backups
    }

    fun restoreBackup(folder: String) = manage { config ->
        client.request(config, "POST", "/api/v1/backups/restore", """{"folder":${json(folder)}}""".toByteArray())
        manageMessage = R.string.sync_manage_restored
        manageDetail = folder
        engine.syncInBackground("herstel backup")
    }


    // ------------------------------------------------------------ backup to this TV (over SSH)

    var tvBackupOpen by mutableStateOf(false)
    var tvBackupPassword by mutableStateOf("")
    var tvBackupBusy by mutableStateOf(false)
        private set
    /** Text resource of the last backup result (with [tvBackupDetail]); null = nothing to say. */
    var tvBackupMessage by mutableStateOf<Int?>(null)
        private set
    var tvBackupDetail by mutableStateOf("")
        private set
    var tvBackups by mutableStateOf<List<com.enigmacast.data.enigmasync.TvSyncBackup>>(emptyList())
        private set

    private fun backupHost(): String = linkedHost.ifBlank { host }.trim()

    fun openTvBackup() {
        tvBackupOpen = !tvBackupOpen
        if (tvBackupOpen) tvBackups = tvBackup.list()
    }

    private fun tvBackupWork(work: suspend () -> Pair<Int, String>) {
        if (tvBackupBusy) return
        tvBackupBusy = true
        tvBackupMessage = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { work() } }
            tvBackupBusy = false
            tvBackups = tvBackup.list()
            result.fold(
                onSuccess = { (res, detail) -> tvBackupMessage = res; tvBackupDetail = detail },
                onFailure = { tvBackupMessage = R.string.sync_tv_backup_failed; tvBackupDetail = it.message ?: it.javaClass.simpleName },
            )
        }
    }

    fun backupToTv() {
        val address = backupHost()
        val typed = tvBackupPassword
        if (address.isBlank() || typed.isBlank()) return
        tvBackupWork {
            val made = tvBackup.backup(address, user.trim().ifBlank { "root" }, typed)
            R.string.sync_tv_backup_done to made.file.name
        }
    }

    /**
     * Puts [file] on the server now installed on the receiver, then pairs this TV with it again:
     * the restored certificate is the old one, so the pin saved for the fresh install is stale.
     */
    fun restoreFromTv(file: java.io.File) {
        val address = backupHost()
        val typed = tvBackupPassword
        if (address.isBlank() || typed.isBlank()) return
        tvBackupWork {
            tvBackup.restore(address, user.trim().ifBlank { "root" }, typed, file)
            engine.serverChanged(address)
            var paired: JSONObject? = null
            var pin = ""
            for (attempt in 0 until 10) {
                kotlinx.coroutines.delay(1_500)
                val ok = runCatching {
                    pin = client.capturePin(address, 5, 8)
                    paired = JSONObject(client.join(address, pin, "EnigmaCast"))
                }.isSuccess
                if (ok) break
            }
            val joined = paired ?: error("sync server did not answer after the restore")
            client.save(address, pin, joined.getString("token"), joined.getString("deviceId"), joined.getString("role"))
            withContext(Dispatchers.Main) {
                linkedHost = address
                linkedRole = joined.getString("role")
            }
            runCatching { engine.importProfiles() }
            engine.syncInBackground("herstel van tv-backup")
            R.string.sync_tv_restore_done to file.name
        }
    }

    fun exportTvBackup(file: java.io.File) = tvBackupWork {
        R.string.sync_tv_backup_exported to tvBackup.export(file).absolutePath
    }

    fun deleteTvBackup(file: java.io.File) {
        tvBackup.delete(file)
        tvBackups = tvBackup.list()
    }

    /** Resets the device list with the recovery code, then pairs this device again. */
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
                    newCode
                }
            }
            busy = false
            result.fold(
                onSuccess = {
                    engine.syncInBackground("beheer overnemen")
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
                val linkedUpdating = updateState is SyncServerUpdater.State.Checking || updateState is SyncServerUpdater.State.Updating
                if (!updates.dialog) {
                    syncUpdateStatus(updateState)?.let { if (linkedUpdating) SyncBusyRow(it) else InfoRow(stringResource(R.string.sync_update_title), it) }
                }
                val progress by model.syncProgress.collectAsState()
                progress?.let { SyncBusyRow(stringResource(R.string.sync_progress, it.items, it.profile, it.profiles)) }
                val linkedFocus = remember { FocusRequester() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(
                        stringResource(R.string.sync_update_button),
                        { updates.checkAndOffer() },
                        modifier = Modifier.focusRequester(linkedFocus),
                        enabled = updateState !is SyncServerUpdater.State.Checking && updateState !is SyncServerUpdater.State.Updating, compact = true,
                    )
                    TvButton(stringResource(R.string.sync_server_disconnect), { model.disconnect() }, enabled = !linkedUpdating, compact = true)
                    TvButton(stringResource(R.string.sync_manage_button), { if (model.manageOpen) model.manageOpen = false else model.openManage() }, enabled = !linkedUpdating, compact = true)
                }
                if (model.manageOpen) SyncManageSection(model)
                TvButton(stringResource(R.string.sync_tv_backup_button), { model.openTvBackup() }, enabled = !linkedUpdating, compact = true)
                if (model.tvBackupOpen) SyncTvBackupSection(model)
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
        model.probing -> SyncBusyRow(stringResource(R.string.sync_server_searching, model.host.trim()))
        server != null -> {
            InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_found, server.host, SyncServerOffer.label(server.version)))
            Text(stringResource(R.string.sync_server_found_hint), color = EnigmaColors.Muted, fontSize = EnigmaType.SettingsDescription, lineHeight = EnigmaType.SettingsDescriptionLine)
        }
        model.searched.isNotBlank() -> InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_not_found, model.searched))
    }
    // Pairing or installing: spinner with the step it is at.
    if (model.busy) SyncBusyRow(stringResource(model.step ?: R.string.sync_server_pairing))
    if (model.pairTimedOut) InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_pair_timeout))
    model.pairError?.let { InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_pair_failed, it)) }
    model.installFailure?.let { failure -> syncUpdateStatus(failure)?.let { InfoRow(stringResource(R.string.sync_server_status), it) } }
    val check = lastCheck?.takeIf { server != null && it.host == server.host && !it.paired }
    val updating = updateState is SyncServerUpdater.State.Checking || updateState is SyncServerUpdater.State.Updating
    if (server != null && !updates.dialog && (updating || check != null)) {
        syncUpdateStatus(updateState)?.let { if (updating) SyncBusyRow(it) else InfoRow(stringResource(R.string.sync_update_title), it) }
    }
    val actions = SyncServerOffer.actions(server != null, server?.version, check?.latest)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (SyncServerOffer.Action.PAIR in actions) {
            TvButton(stringResource(R.string.sync_server_pair_existing), { model.pairExisting() }, enabled = !model.busy && !updating, compact = true)
        }
        if (SyncServerOffer.Action.UPDATE in actions && server != null) {
            TvButton(stringResource(R.string.sync_update_button), { updates.checkAndOffer(server.host) }, enabled = !model.busy && !updating, compact = true)
        }
        if (SyncServerOffer.Action.REINSTALL in actions) {
            TvButton(stringResource(R.string.sync_server_reinstall), { confirmReinstall = true }, enabled = !model.busy && !updating, compact = true)
        }
        TvButton(stringResource(R.string.sync_server_search_again), { model.search() }, enabled = !model.probing && !model.busy && !updating, compact = true)
    }
    if (server == null || model.reinstallForm) {
        if (server == null) {
            Text(stringResource(R.string.sync_server_install_hint), color = EnigmaColors.Muted, fontSize = EnigmaType.SettingsDescription, lineHeight = EnigmaType.SettingsDescriptionLine)
        }
        TvTextField(stringResource(R.string.sync_server_host), model.host, { model.host = it }, kind = TvFieldKind.HOST)
        TvTextField(stringResource(R.string.sync_server_user), model.user, { model.user = it })
        TvTextField(stringResource(R.string.sync_server_password), model.password, { model.password = it }, visualTransformation = PasswordVisualTransformation())
        TvTextField(stringResource(R.string.sync_server_recovery), model.recoveryCode, { model.recoveryCode = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(stringResource(R.string.sync_server_install), { model.install() }, enabled = !model.busy && !model.probing && !updating, compact = true)
            TvButton(stringResource(R.string.sync_server_claim), { model.claim() }, enabled = !model.busy && !updating && model.recoveryCode.isNotBlank(), compact = true)
        }
    }
    TvButton(stringResource(R.string.sync_tv_backup_button), { model.openTvBackup() }, enabled = !model.busy && !updating, compact = true)
    if (model.tvBackupOpen) SyncTvBackupSection(model)
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

/** Device list, revoke, backup and restore. Every paired TV may use it (servers with the open-admin change). */
@Composable
private fun SyncManageSection(model: SyncServerViewModel) {
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.sync_manage_title), color = EnigmaColors.Text, fontSize = EnigmaType.SettingsTitle)
    if (model.manageBusy) SyncBusyRow(stringResource(R.string.sync_manage_busy))
    model.manageMessage?.let { InfoRow(stringResource(R.string.sync_server_status), stringResource(it, model.manageDetail)) }
    if (model.serverStatus.isNotBlank()) InfoRow(stringResource(R.string.sync_manage_server), model.serverStatus)
    model.serverDevices.forEach { device ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val label = buildString {
                append(device.name.ifBlank { device.id.take(8) })
                append(" · ").append(device.role)
                if (device.self) append(" · ").append(stringResource(R.string.sync_manage_this_device))
                if (device.revoked) append(" · ").append(stringResource(R.string.sync_manage_revoked_tag))
            }
            Text(label, color = if (device.revoked) EnigmaColors.Subtle else EnigmaColors.Muted, fontSize = EnigmaType.SettingsTitle, modifier = Modifier.weight(1f))
            if (!device.self && !device.revoked) {
                TvButton(stringResource(R.string.sync_manage_revoke), { model.revokeDevice(device.id) }, enabled = !model.manageBusy, compact = true)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvButton(stringResource(R.string.sync_manage_refresh), { model.refreshManage() }, enabled = !model.manageBusy, compact = true)
        TvButton(stringResource(R.string.sync_manage_backup), { model.makeBackup() }, enabled = !model.manageBusy, compact = true)
    }
    model.backups.take(5).forEach { folder ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(folder.substringAfterLast('/'), color = EnigmaColors.Muted, fontSize = EnigmaType.SettingsValue, modifier = Modifier.weight(1f))
            TvButton(stringResource(R.string.sync_manage_restore), { confirmRestore = folder }, enabled = !model.manageBusy, compact = true)
        }
    }
    confirmRestore?.let { folder ->
        TvConfirmDialog(
            title = stringResource(R.string.sync_manage_restore),
            message = stringResource(R.string.sync_manage_restore_message, folder.substringAfterLast('/')),
            confirmLabel = stringResource(R.string.sync_manage_restore),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmRestore = null
                model.restoreBackup(folder)
            },
            onDismiss = { confirmRestore = null },
            destructive = true,
        )
    }
}

/**
 * Backup of the whole sync server (database, key, certificate, dashboard login) to this TV, and
 * restore onto a newly installed server. Uses the receiver's SSH login like the installer.
 */
@Composable
private fun SyncTvBackupSection(model: SyncServerViewModel) {
    var confirmRestore by remember { mutableStateOf<java.io.File?>(null) }
    val format = remember { java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale.getDefault()) }
    Text(stringResource(R.string.sync_tv_backup_title), color = EnigmaColors.Text, fontSize = EnigmaType.SettingsTitle)
    Text(stringResource(R.string.sync_tv_backup_hint), color = EnigmaColors.Muted, fontSize = EnigmaType.SettingsDescription, lineHeight = EnigmaType.SettingsDescriptionLine)
    TvTextField(stringResource(R.string.sync_server_user), model.user, { model.user = it })
    TvTextField(
        stringResource(R.string.sync_server_password),
        model.tvBackupPassword,
        { model.tvBackupPassword = it },
        visualTransformation = PasswordVisualTransformation(),
    )
    if (model.tvBackupBusy) SyncBusyRow(stringResource(R.string.sync_tv_backup_busy))
    model.tvBackupMessage?.let { InfoRow(stringResource(R.string.sync_server_status), stringResource(it, model.tvBackupDetail)) }
    TvButton(
        stringResource(R.string.sync_tv_backup_make),
        { model.backupToTv() },
        enabled = !model.tvBackupBusy && model.tvBackupPassword.isNotBlank(), compact = true,
    )
    if (model.tvBackups.isEmpty()) {
        Text(stringResource(R.string.sync_tv_backup_none), color = EnigmaColors.Subtle, fontSize = EnigmaType.SettingsDescription)
    }
    model.tvBackups.take(5).forEach { item ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "${format.format(java.util.Date(item.createdMs))} · ${item.sizeBytes / 1024} KB",
                color = EnigmaColors.Muted,
                fontSize = EnigmaType.SettingsDescription,
                modifier = Modifier.weight(1f),
            )
            TvButton(
                stringResource(R.string.sync_tv_backup_restore),
                { confirmRestore = item.file },
                enabled = !model.tvBackupBusy && model.tvBackupPassword.isNotBlank(), compact = true,
            )
            TvButton(stringResource(R.string.sync_tv_backup_export), { model.exportTvBackup(item.file) }, enabled = !model.tvBackupBusy, compact = true)
            TvButton(stringResource(R.string.sync_tv_backup_delete), { model.deleteTvBackup(item.file) }, enabled = !model.tvBackupBusy, compact = true)
        }
    }
    confirmRestore?.let { file ->
        TvConfirmDialog(
            title = stringResource(R.string.sync_tv_backup_restore),
            message = stringResource(R.string.sync_tv_restore_message, file.name),
            confirmLabel = stringResource(R.string.sync_tv_backup_restore),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmRestore = null
                model.restoreFromTv(file)
            },
            onDismiss = { confirmRestore = null },
            destructive = true,
        )
    }
}
