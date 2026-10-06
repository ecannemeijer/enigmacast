package com.enigmacast.ui.connection

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.discovery.NsdReceiverDiscovery
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.validation.ReceiverConfigValidator
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val discovery: NsdReceiverDiscovery,
) : ViewModel() {
    var name by mutableStateOf(AppLanguageStore.wrap(context).getString(R.string.default_receiver_name))
    var host by mutableStateOf("")
    var httpPort by mutableStateOf("80")
    var streamPort by mutableStateOf("8001")
    var useHttps by mutableStateOf(false)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var allowInsecureCertificates by mutableStateOf(false)
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
            }
        }
    }

    fun test() = launchAction { config ->
        val result = api.testConnection(config)
        status = result.message
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
