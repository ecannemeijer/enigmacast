package com.enigmacast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.model.RecordingTarget
import com.enigmacast.domain.network.TranscodePreset
import com.enigmacast.playback.AudioSync
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

    fun setStreamMode(mode: StreamMode) = update { it.copy(streamMode = mode) }
    fun setRecordingTarget(target: RecordingTarget) = update { it.copy(playback = it.playback.copy(recordingTarget = target)) }
    fun setRecordingPreset(preset: TranscodePreset?) = update { it.copy(playback = it.playback.copy(recordingPreset = preset)) }
    fun setLowLatency(enabled: Boolean) = update { it.copy(playback = it.playback.copy(lowLatency = enabled)) }
    fun setAutoReconnect(enabled: Boolean) = update { it.copy(playback = it.playback.copy(autoReconnect = enabled)) }
    fun setHdmiPassthrough(enabled: Boolean) = update { it.copy(playback = it.playback.copy(hdmiPassthrough = enabled)) }
    fun stepAudioDelay(passthrough: Boolean, direction: Int) = update { current ->
        val playback = current.playback
        current.copy(
            playback = if (passthrough) {
                playback.copy(audioDelayPassthroughMs = AudioSync.step(playback.audioDelayPassthroughMs, direction))
            } else {
                playback.copy(audioDelayPcmMs = AudioSync.step(playback.audioDelayPcmMs, direction))
            },
        )
    }
    fun resetAudioDelay(passthrough: Boolean) = update { current ->
        current.copy(
            playback = if (passthrough) {
                current.playback.copy(audioDelayPassthroughMs = 0)
            } else {
                current.playback.copy(audioDelayPcmMs = 0)
            },
        )
    }
    fun setSubtitlesEnabled(enabled: Boolean) = update { it.copy(playback = it.playback.copy(subtitlesEnabled = enabled)) }
    fun setOsdFormat(format: String) = update { it.copy(osd = it.osd.copy(format = format)) }
    fun setFastPolling(enabled: Boolean) = update { it.copy(osd = it.osd.copy(fastPolling = enabled)) }
    fun setDebugOverlay(enabled: Boolean) = update { it.copy(debug = it.debug.copy(overlay = enabled)) }
    fun setVerboseLogging(enabled: Boolean) = update { it.copy(debug = it.debug.copy(verboseLogging = enabled)) }
    fun setDebugBorders(enabled: Boolean) = update { it.copy(osd = it.osd.copy(debugBorders = enabled)) }
    fun setNavigationSounds(enabled: Boolean) = update { it.copy(navigationSounds = enabled) }
    fun setOsdInfoFields(fields: OsdInfoFields) = update { it.copy(osdInfo = fields) }
    fun setInsecureCertificates(enabled: Boolean) = update { current ->
        val active = current.activeReceiver() ?: return@update current
        val updated = active.copy(allowInsecureCertificates = enabled)
        current.copy(receivers = current.receivers.map { if (it.id == updated.id) updated else it })
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
