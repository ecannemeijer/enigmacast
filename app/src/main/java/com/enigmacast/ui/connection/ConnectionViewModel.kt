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
import com.enigmacast.data.discovery.AutodetectResult
import com.enigmacast.data.discovery.DiscoveredHost
import com.enigmacast.data.discovery.DiscoveredReceiver
import com.enigmacast.data.discovery.withoutSaved
import com.enigmacast.data.discovery.ReceiverAutodetect
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
import com.enigmacast.live.StreamQualityController
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val autodetect: ReceiverAutodetect,
    private val tunnel: WireGuardTunnel,
    private val router: ReceiverRouter,
    private val quality: StreamQualityController,
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
    private var receiverId: String = ""
    /** The receiver currently shown in the form. A different active id reloads the fields. */
    private var loadedId: String? = null
    /** True while adding a box, so a settings update does not refill the blank form. */
    private var adding = false

    /** True once a receiver has been saved, so the setup screen can name the current one. */
    var hasSavedReceiver by mutableStateOf(false)
        private set

    /** Settings › Playback › Stream type: the result of the last save or port detection. */
    var streamTypeStatus by mutableStateOf(UiText.Empty)
        private set
    var detecting by mutableStateOf(false)
        private set
    private var portSaveJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                if (adding) return@collect
                val active = settings.activeReceiver()
                if (active == null) {
                    if (loadedId != null || hasSavedReceiver) clearForm()
                    return@collect
                }
                if (active.id == loadedId) return@collect
                applyReceiver(active)
            }
        }
        viewModelScope.launch { tunnel.status.collect { tunnelStatus = it } }
    }

    /** Blank form for a new box. Saving creates it and makes it the active one. */
    fun beginNew() {
        adding = true
        clearForm()
    }

    /** Show an existing box in the form so its address and login can be changed. */
    fun editReceiver(config: ReceiverConfig) {
        adding = false
        applyReceiver(config)
    }

    /** Leave the blank form and show the active box again. */
    fun abandonNewReceiver() {
        if (!adding) return
        adding = false
        loadedId = null
        viewModelScope.launch {
            val active = settingsRepository.current().activeReceiver()
            if (active == null) clearForm() else applyReceiver(active)
        }
    }

    private fun applyReceiver(config: ReceiverConfig) {
        receiverId = config.id
        loadedId = config.id
        hasSavedReceiver = true
        name = config.name
        host = config.host
        httpPort = config.httpPort.toString()
        streamPort = config.streamingPort.toString()
        useHttps = config.useHttps
        username = config.username
        password = config.password
        allowInsecureCertificates = config.allowInsecureCertificates
        remoteHost = config.remoteHost
        remoteHttpPort = config.remoteHttpPort.portText()
        remoteStreamPort = config.remoteStreamingPort.portText()
        remoteUseHttps = config.remoteUseHttps || config.remoteHost.isBlank()
        accessMode = config.accessMode
        streamProfile = config.streamProfile
        transcodePreset = config.transcodePreset
        transcodingPort = config.transcodingPort.toString()
        remoteTranscodingPort = config.remoteTranscodingPort.portText()
        wireGuard = config.wireGuard
        wireGuardText = ""
        wireGuardRemoved = false
        wireGuardSaved = config.wireGuardConfig.isNotBlank()
        saved = config
        status = UiText.Empty
        if (config.wireGuard) refreshVpnConsent()
    }

    private fun clearForm() {
        receiverId = ""
        loadedId = null
        hasSavedReceiver = false
        name = AppLanguageStore.wrap(context).getString(R.string.default_receiver_name)
        host = ""
        httpPort = "80"
        streamPort = "8001"
        useHttps = false
        username = ""
        password = ""
        allowInsecureCertificates = false
        remoteHost = ""
        remoteHttpPort = ""
        remoteStreamPort = ""
        remoteUseHttps = true
        accessMode = AccessMode.AUTO
        streamProfile = StreamProfile.AUTO
        transcodePreset = TranscodePreset.MEDIUM
        transcodingPort = "8002"
        remoteTranscodingPort = ""
        wireGuard = false
        wireGuardText = ""
        wireGuardRemoved = false
        wireGuardSaved = false
        saved = ReceiverConfig()
        status = UiText.Empty
    }

    /** What the config in use (new, else saved) contains, for hints and warnings. */
    val wireGuardSummary: WireGuardSummary
        get() = WireGuardText.summarize(wireGuardConfigInUse())

    fun consentIntent(): Intent? = tunnel.consentIntent()

    /** Normal, Transcoded or Automatic, saved at once. Transcoded and Automatic ask the receiver for its transcoding port. */
    fun chooseStreamProfile(profile: StreamProfile) {
        if (streamProfile == profile) return
        streamProfile = profile
        streamTypeStatus = UiText.Empty
        saveStreamType()
        if (profile != StreamProfile.DIRECT) detectTranscodingPort()
    }

    fun chooseTranscodePreset(preset: TranscodePreset) {
        if (transcodePreset == preset) return
        transcodePreset = preset
        saveStreamType()
    }

    /** The port field; a valid port is saved half a second after the last key. */
    fun editTranscodingPort(text: String) {
        transcodingPort = text.filter(Char::isDigit).take(5)
        portSaveJob?.cancel()
        val port = transcodingPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            streamTypeStatus = UiText.res(R.string.stream_type_port_invalid)
            return
        }
        streamTypeStatus = UiText.Empty
        portSaveJob = viewModelScope.launch {
            delay(PORT_SAVE_DELAY_MS)
            saveStreamType()
        }
    }

    /**
     * Asks OpenWebif's phone playlist where the receiver transcodes and puts that port in
     * the field: 8002 (or 8001) from the TranscodingSetup plugin on Broadcom boxes, the
     * normal stream port 8001 on other encoder boxes.
     */
    fun detectTranscodingPort() {
        if (detecting) return
        detecting = true
        streamTypeStatus = UiText.res(R.string.stream_type_detecting)
        viewModelScope.launch {
            val config = settingsRepository.current().activeReceiver()
            val detection = try {
                config?.let { api.transcodeDetection(ReceiverRouting.effective(it, ReceiverRoute.HOME)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EnigmaLog.connection("transcoding port detection failed: ${error.javaClass.simpleName}")
                streamTypeStatus = error.uiMessage(UiText.res(R.string.stream_type_detect_failed))
                detecting = false
                return@launch
            }
            detecting = false
            streamTypeStatus = when {
                detection == null -> UiText.res(R.string.stream_type_detect_failed)
                !detection.transcodes -> UiText.res(R.string.stream_type_no_transcoding)
                else -> {
                    transcodingPort = detection.port.toString()
                    saveStreamType()
                    UiText.res(R.string.stream_type_detected, detection.port)
                }
            }
            EnigmaLog.connection("transcoding port detected=${detection?.port} transcodes=${detection?.transcodes}")
        }
    }

    /** Writes only the stream type fields into the saved receiver, so other unsaved edits stay unsaved. */
    private fun saveStreamType() {
        val port = transcodingPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: saved.transcodingPort
        val profile = streamProfile
        val preset = transcodePreset
        viewModelScope.launch {
            settingsRepository.update { settings ->
                settings.copy(
                    receivers = settings.receivers.map { receiver ->
                        if (receiver.id != receiverId) receiver
                        else receiver.copy(streamProfile = profile, transcodePreset = preset, transcodingPort = port)
                    },
                )
            }
            saved = saved.copy(streamProfile = profile, transcodePreset = preset, transcodingPort = port)
            quality.settingsChanged()
            EnigmaLog.connection("stream type ${profile.name.lowercase()} preset=${preset.name.lowercase()} transcodingPort=$port")
        }
    }

    /** The tunnel switch; only switching it on looks at the VPN permission (nothing starts until saved and needed). */
    fun setWireGuardEnabled(on: Boolean) {
        wireGuard = on
        if (on) refreshVpnConsent()
    }

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
        val stored = settingsRepository.upsertReceiver(config)
        rememberStored(stored)
        quality.settingsChanged()
        status = UiText.res(R.string.conn_saved, stored.name)
        onSaved()
    }

    fun connect(onConnected: () -> Unit) = launchAction { config ->
        val result = api.testConnection(config)
        status = result.message
        if (result.success) {
            rememberStored(settingsRepository.upsertReceiver(config))
            onConnected()
        }
    }

    private fun rememberStored(stored: ReceiverConfig) {
        receiverId = stored.id
        loadedId = stored.id
        adding = false
        saved = stored
        hasSavedReceiver = true
    }

    /** The AutoDetect dialog; [AutodetectState.Hidden] when it is closed. */
    var autodetectState by mutableStateOf<AutodetectState>(AutodetectState.Hidden)
        private set
    private var autodetectJob: Job? = null

    /** AutoDetect button: open the dialog and search (again). The form is not changed until a receiver is chosen. */
    fun startAutodetect() {
        autodetectJob?.cancel()
        autodetectState = AutodetectState.Searching
        val formHost = host.trim().takeIf { it.isNotBlank() }?.let { DiscoveredHost(it, httpPort.trim().toIntOrNull() ?: 80) }
        autodetectJob = viewModelScope.launch {
            val result = try {
                autodetect.find(formHost)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                EnigmaLog.discoveryError("autodetect failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
                AutodetectResult(emptyList(), emptyList(), 0, 0)
            }
            val fresh = result.receivers.withoutSaved(settingsRepository.current().receivers)
            autodetectState = when {
                fresh.isNotEmpty() -> AutodetectState.Results(fresh)
                result.receivers.isNotEmpty() -> AutodetectState.AlreadyAdded
                result.subnets.isEmpty() -> AutodetectState.NoNetwork
                else -> AutodetectState.NothingFound(result.subnets.joinToString(), result.scannedAddresses)
            }
        }
    }

    /** Cancel / Back while searching, or Close: stop the search and close the dialog. */
    fun closeAutodetect() {
        autodetectJob?.cancel()
        autodetectJob = null
        autodetectState = AutodetectState.Hidden
    }

    /** OK on a result: fill host, OpenWebif port and HTTPS; the user still presses Save. */
    fun chooseDetected(receiver: DiscoveredReceiver) {
        host = receiver.host
        httpPort = receiver.httpPort.toString()
        useHttps = receiver.useHttps
        val defaultName = AppLanguageStore.wrap(context).getString(R.string.default_receiver_name)
        if ((name.isBlank() || name == defaultName) && receiver.model.isNotBlank()) {
            name = receiver.model
        }
        status = UiText.res(R.string.autodetect_chosen, receiver.label)
        closeAutodetect()
    }

    /** True when [receiver] is the address in the form now (marked in the result list). */
    fun isSelected(receiver: DiscoveredReceiver): Boolean =
        host.trim() == receiver.host && httpPort.trim() == receiver.httpPort.toString() && useHttps == receiver.useHttps

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
        const val PORT_SAVE_DELAY_MS = 500L
    }
}
