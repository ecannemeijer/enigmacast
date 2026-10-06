package com.enigmacast.ui.connection

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.discovery.NsdReceiverDiscovery
import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.network.TunnelState
import com.enigmacast.data.network.TunnelStatus
import com.enigmacast.data.network.WireGuardTunnel
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.AccessMode
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.network.StreamProfile
import com.enigmacast.domain.network.TranscodePreset
import com.enigmacast.domain.network.WireGuardSummary
import com.enigmacast.domain.network.WireGuardText
import com.enigmacast.domain.validation.ReceiverConfigValidator
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val discovery: NsdReceiverDiscovery,
    private val tunnel: WireGuardTunnel,
    private val router: ReceiverRouter,
) : ViewModel() {
    var name by mutableStateOf(AppLanguageStore.wrap(context).getString(R.string.default_receiver_name))
    var host by mutableStateOf("")
    var httpPort by mutableStateOf("80")
    var streamPort by mutableStateOf("8001")
    var useHttps by mutableStateOf(false)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var allowInsecureCertificates by mutableStateOf(false)
    var remoteHost by mutableStateOf("")
    var remoteHttpPort by mutableStateOf("")
    var remoteStreamPort by mutableStateOf("")
    var remoteUseHttps by mutableStateOf(true)
    var accessMode by mutableStateOf(AccessMode.AUTO)
    var streamProfile by mutableStateOf(StreamProfile.AUTO)
    var transcodePreset by mutableStateOf(TranscodePreset.MEDIUM)
    var transcodingPort by mutableStateOf("8002")
    var remoteTranscodingPort by mutableStateOf("")
    var wireGuard by mutableStateOf(false)

    /** Newly pasted or imported config; empty keeps the saved one, which is never shown again. */
    var wireGuardText by mutableStateOf("")
    var wireGuardSaved by mutableStateOf(false)
        private set
    private var wireGuardRemoved = false
    var vpnAllowed by mutableStateOf(true)
        private set
    var tunnelStatus by mutableStateOf(TunnelStatus())
        private set
    private var saved = ReceiverConfig()
    var status by mutableStateOf(UiText.Empty)
    var busy by mutableStateOf(false)
    var discoveredHosts by mutableStateOf<List<String>>(emptyList())
    private var receiverId: String = ""

    init {
        viewModelScope.launch {
            settingsRepository.current().activeReceiver()?.let { saved ->
                receiverId = saved.id
                name = saved.name
                host = saved.host
                httpPort = saved.httpPort.toString()
                streamPort = saved.streamingPort.toString()
                useHttps = saved.useHttps
                username = saved.username
                password = saved.password
                allowInsecureCertificates = saved.allowInsecureCertificates
                remoteHost = saved.remoteHost
                remoteHttpPort = saved.remoteHttpPort.portText()
                remoteStreamPort = saved.remoteStreamingPort.portText()
                remoteUseHttps = saved.remoteUseHttps || saved.remoteHost.isBlank()
                accessMode = saved.accessMode
                streamProfile = saved.streamProfile
                transcodePreset = saved.transcodePreset
                transcodingPort = saved.transcodingPort.toString()
                remoteTranscodingPort = saved.remoteTranscodingPort.portText()
                wireGuard = saved.wireGuard
                wireGuardSaved = saved.wireGuardConfig.isNotBlank()
                this@ConnectionViewModel.saved = saved
            }
        }
        viewModelScope.launch { tunnel.status.collect { tunnelStatus = it } }
        refreshVpnConsent()
    }

    /** What the config in use (new, else saved) contains, for hints and warnings. */
    val wireGuardSummary: WireGuardSummary
        get() = WireGuardText.summarize(wireGuardConfigInUse())

    fun consentIntent(): Intent? = tunnel.consentIntent()

    fun refreshVpnConsent() {
        vpnAllowed = tunnel.consentIntent() == null
    }

    fun onVpnConsent(granted: Boolean) {
        refreshVpnConsent()
        status = UiText.res(if (granted && vpnAllowed) R.string.wg_consent_ok else R.string.wg_consent_denied)
    }

    fun onNoConsentDialog() {
        status = UiText.res(R.string.wg_no_consent_dialog)
    }

    fun onNoFilePicker() {
        status = UiText.res(R.string.wg_no_picker)
    }

    fun importWireGuard(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream -> readSmallText(stream) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                status = UiText.res(R.string.wg_import_failed)
            } else {
                wireGuardText = text.trim()
                wireGuardRemoved = false
                status = UiText.res(R.string.wg_imported)
            }
        }
    }

    fun removeWireGuard() {
        wireGuardText = ""
        wireGuardSaved = false
        wireGuardRemoved = true
        wireGuard = false
        status = UiText.res(R.string.wg_removed)
    }

    /** Brings the tunnel up the way the player would (tunnel route) and asks the receiver through it. */
    fun testTunnel() {
        if (!tunnel.enabledFor(currentConfig().copy(wireGuard = true))) {
            status = UiText.res(R.string.wg_issue_empty)
            return
        }
        refreshVpnConsent()
        if (!vpnAllowed) {
            status = UiText.res(R.string.wg_needs_consent)
            return
        }
        launchAction { config ->
            val viaTunnel = config.copy(wireGuard = true, accessMode = AccessMode.REMOTE)
            router.invalidate()
            val result = api.testConnection(viaTunnel)
            status = if (tunnel.status.value.state == TunnelState.UP) {
                UiText.Joined(listOf(UiText.res(R.string.wg_test_ok), result.message))
            } else {
                UiText.Joined(listOf(UiText.res(R.string.wg_test_not_up), tunnel.status.value.detail))
            }
        }
    }

    fun test() = launchAction { config ->
        val result = api.testConnection(config)
        status = result.message
    }

    /** Tests the remote profile directly, as if it were the home address. */
    fun testRemote() = launchAction { config ->
        if (!ReceiverRouting.remoteConfigured(config)) {
            status = UiText.res(R.string.conn_remote_missing)
            return@launchAction
        }
        val remote = ReceiverRouting.effective(config, ReceiverRoute.REMOTE).copy(remoteHost = "", accessMode = AccessMode.HOME)
        val result = api.testConnection(remote)
        status = UiText.Joined(listOf(UiText.res(R.string.conn_remote_result), result.message))
    }

    fun save(onSaved: () -> Unit = {}) = launchAction { config ->
        settingsRepository.upsertReceiver(config)
        status = UiText.res(R.string.conn_saved, config.name)
        onSaved()
    }

    fun connect(onConnected: () -> Unit) = launchAction { config ->
        val result = api.testConnection(config)
        status = result.message
        if (result.success) {
            settingsRepository.upsertReceiver(config)
            onConnected()
        }
    }

    fun discover() {
        if (busy) return
        busy = true
        status = UiText.res(R.string.conn_searching)
        viewModelScope.launch {
            val hosts = runCatching { discovery.discoverHosts() }.getOrDefault(emptyList())
            discoveredHosts = hosts
            status = if (hosts.isEmpty()) {
                UiText.res(R.string.conn_none_found)
            } else {
                UiText.plural(R.plurals.conn_found_hosts, hosts.size, hosts.size)
            }
            busy = false
        }
    }

    fun selectHost(value: String) {
        host = value
    }

    private fun launchAction(block: suspend (ReceiverConfig) -> Unit) {
        val config = currentConfig()
        val validation = ReceiverConfigValidator.validate(config)
        if (!validation.isValid) {
            status = UiText.Joined(validation.errors)
            return
        }
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block(config)
            } catch (error: Exception) {
                status = error.uiMessage(UiText.res(R.string.conn_request_failed))
            } finally {
                busy = false
            }
        }
    }

    private fun currentConfig(): ReceiverConfig {
        // Settings without a field here (timeouts, transcoding, tunnel) keep their saved values.
        return saved.copy(
            id = receiverId,
            name = name.trim(),
            host = host.trim(),
            httpPort = httpPort.toIntOrNull() ?: 0,
            useHttps = useHttps,
            username = username,
            password = password,
            streamingPort = streamPort.toIntOrNull() ?: 0,
            allowInsecureCertificates = allowInsecureCertificates,
            remoteHost = remoteHost.trim(),
            remoteHttpPort = remoteHttpPort.portValue(),
            remoteStreamingPort = remoteStreamPort.portValue(),
            remoteUseHttps = remoteUseHttps,
            accessMode = accessMode,
            streamProfile = streamProfile,
            transcodePreset = transcodePreset,
            transcodingPort = transcodingPort.trim().toIntOrNull() ?: 0,
            remoteTranscodingPort = remoteTranscodingPort.portValue(),
            wireGuard = wireGuard,
            wireGuardConfig = wireGuardConfigInUse(),
        )
    }

    private fun wireGuardConfigInUse(): String = when {
        wireGuardText.isNotBlank() -> wireGuardText.trim()
        wireGuardRemoved -> ""
        else -> saved.wireGuardConfig
    }

    private fun Int.portText(): String = if (this > 0) toString() else ""

    /** Empty means "same as at home" (0); anything unreadable fails validation (-1). */
    private fun String.portValue(): Int = if (isBlank()) 0 else trim().toIntOrNull() ?: -1

    /** The text of [stream], or null when it is larger than a WireGuard config can reasonably be. */
    private fun readSmallText(stream: InputStream): String? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (out.size() + read > MAX_CONFIG_BYTES) return null
            out.write(chunk, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 64 * 1024
    }
}
