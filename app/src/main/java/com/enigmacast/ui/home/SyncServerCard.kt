package com.enigmacast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.ui.theme.InfoRow
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

    init {
        viewModelScope.launch {
            val receiver = settings.current().activeReceiver()
            if (host.isBlank()) host = receiver?.host.orEmpty()
            client.config()?.let {
                linkedHost = it.host
                linkedRole = it.role
            }
        }
    }

    fun install() {
        if (busy || host.isBlank() || password.isBlank()) return
        busy = true
        status = ""
        val typed = password
        password = ""
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = installer.install(host.trim(), user.trim(), typed)
                    val paired = JSONObject(client.join(host.trim(), report.pin, "EnigmaCast"))
                    client.save(host.trim(), report.pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    profiles.state.value.profiles.forEach { profile ->
                        val body = """{"uuid":${json(profile.uuid)},"name":${json(profile.name)},"avatar":${profile.avatar},"updatedMs":${System.currentTimeMillis()},"changeId":${json(profile.uuid + "-import")}}"""
                        runCatching { client.request(client.config()!!, "POST", "/api/v1/profiles", body.toByteArray()) }
                    }
                    engine.sync()
                    report
                }
            }
            busy = false
            status = result.fold(
                onSuccess = { "Installed on ${it.model}. ${it.python}. Data ${it.dataDir}." },
                onFailure = { it.message ?: "Install failed" },
            )
        }
    }

    /** Frees the single admin slot with the recovery code, then pairs this device as that admin. */
    fun disconnect() {
        client.clear()
        linkedHost = ""
        linkedRole = ""
        freshRecovery = null
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
                    runCatching { engine.sync() }
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
fun SyncServerCard(model: SyncServerViewModel = hiltViewModel()) {
    SettingsCard(stringResource(R.string.sync_server_title)) {
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.freshRecovery?.let { InfoRow(stringResource(R.string.sync_server_status), stringResource(R.string.sync_server_claimed, it)) }
            if (model.status.isNotBlank()) InfoRow(stringResource(R.string.sync_server_status), model.status)
            if (model.linkedHost.isNotBlank()) {
                InfoRow(
                    stringResource(R.string.sync_server_status),
                    stringResource(R.string.sync_server_connected, model.linkedHost, model.linkedRole),
                )
                TvButton(stringResource(R.string.sync_server_disconnect), { model.disconnect() })
                return@Column
            }
            TvTextField(stringResource(R.string.sync_server_host), model.host, { model.host = it }, kind = TvFieldKind.HOST)
            TvTextField(stringResource(R.string.sync_server_user), model.user, { model.user = it })
            TvTextField(stringResource(R.string.sync_server_password), model.password, { model.password = it }, visualTransformation = PasswordVisualTransformation())
            TvTextField(stringResource(R.string.sync_server_recovery), model.recoveryCode, { model.recoveryCode = it })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(stringResource(R.string.sync_server_install), { model.install() }, enabled = !model.busy)
                TvButton(stringResource(R.string.sync_server_claim), { model.claim() }, enabled = !model.busy && model.recoveryCode.isNotBlank())
            }
        }
    }
}
