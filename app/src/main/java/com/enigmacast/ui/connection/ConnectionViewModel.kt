package com.enigmacast.ui.connection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.discovery.NsdReceiverDiscovery
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.validation.ReceiverConfigValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val discovery: NsdReceiverDiscovery,
) : ViewModel() {
    var name by mutableStateOf("Living room")
    var host by mutableStateOf("")
    var httpPort by mutableStateOf("80")
    var streamPort by mutableStateOf("8001")
    var useHttps by mutableStateOf(false)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var allowInsecureCertificates by mutableStateOf(false)
    var status by mutableStateOf("")
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
            }
        }
    }

    fun test() = launchAction { config ->
        val result = api.testConnection(config)
        status = result.message
    }

    fun save(onSaved: () -> Unit = {}) = launchAction { config ->
        settingsRepository.upsertReceiver(config)
        status = "Saved ${config.name}"
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
        status = "Searching the network"
        viewModelScope.launch {
            val hosts = runCatching { discovery.discoverHosts() }.getOrDefault(emptyList())
            discoveredHosts = hosts
            status = if (hosts.isEmpty()) {
                "No receivers found. Enter the IP address manually."
            } else {
                "Found ${hosts.size} host(s). Select one, then test the connection."
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
            status = validation.errors.joinToString("\n")
            return
        }
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block(config)
            } catch (error: Exception) {
                status = error.message ?: "Request failed"
            } finally {
                busy = false
            }
        }
    }

    private fun currentConfig(): ReceiverConfig {
        return ReceiverConfig(
            id = receiverId,
            name = name.trim(),
            host = host.trim(),
            httpPort = httpPort.toIntOrNull() ?: 0,
            useHttps = useHttps,
            username = username,
            password = password,
            streamingPort = streamPort.toIntOrNull() ?: 0,
            allowInsecureCertificates = allowInsecureCertificates,
        )
    }
}
