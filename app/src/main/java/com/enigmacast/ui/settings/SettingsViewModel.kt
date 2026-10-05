package com.enigmacast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    fun setLowLatency(enabled: Boolean) = update { it.copy(playback = it.playback.copy(lowLatency = enabled)) }
    fun setAutoReconnect(enabled: Boolean) = update { it.copy(playback = it.playback.copy(autoReconnect = enabled)) }
    fun setHdmiPassthrough(enabled: Boolean) = update { it.copy(playback = it.playback.copy(hdmiPassthrough = enabled)) }
    fun setOsdFormat(format: String) = update { it.copy(osd = it.osd.copy(format = format)) }
    fun setFastPolling(enabled: Boolean) = update { it.copy(osd = it.osd.copy(fastPolling = enabled)) }
    fun setDebugOverlay(enabled: Boolean) = update { it.copy(debug = it.debug.copy(overlay = enabled)) }
    fun setVerboseLogging(enabled: Boolean) = update { it.copy(debug = it.debug.copy(verboseLogging = enabled)) }
    fun setDebugBorders(enabled: Boolean) = update { it.copy(osd = it.osd.copy(debugBorders = enabled)) }
    fun setInsecureCertificates(enabled: Boolean) = update { current ->
        val active = current.activeReceiver() ?: return@update current
        val updated = active.copy(allowInsecureCertificates = enabled)
        current.copy(receivers = current.receivers.map { if (it.id == updated.id) updated else it })
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
