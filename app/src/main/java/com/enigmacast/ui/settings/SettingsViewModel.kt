package com.enigmacast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.MotionQuality
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.ReceiverList
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
    private val switcher: com.enigmacast.live.ReceiverSwitcher,
    private val syncEngine: com.enigmacast.sync.SyncEngine,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    /** Settings › Receivers "Refresh channels & bouquets now": per receiver id. */
    sealed interface ChannelRefresh {
        data object Running : ChannelRefresh
        data class Done(val changed: Boolean, val bouquets: Int, val channels: Int) : ChannelRefresh
        data class Failed(val message: String) : ChannelRefresh
    }
    private val _channelRefresh = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ChannelRefresh>>(emptyMap())
    val channelRefresh: kotlinx.coroutines.flow.StateFlow<Map<String, ChannelRefresh>> = _channelRefresh

    fun refreshChannels(id: String) {
        val receiver = settings.value.receivers.firstOrNull { it.id == id } ?: return
        if (_channelRefresh.value[id] == ChannelRefresh.Running) return
        _channelRefresh.value = _channelRefresh.value + (id to ChannelRefresh.Running)
        viewModelScope.launch {
            val result = try {
                val outcome = syncEngine.refreshChannelsNow(receiver)
                // The guide and Home follow in the background for the box in use.
                if (outcome.replaced && id == settings.value.activeReceiver()?.id) {
                    com.enigmacast.sync.SyncScheduler.runNow(appContext, force = false)
                }
                ChannelRefresh.Done(outcome.replaced, outcome.bouquets, outcome.channels)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ChannelRefresh.Failed(e.message ?: e.javaClass.simpleName)
            }
            _channelRefresh.value = _channelRefresh.value + (id to result)
        }
    }

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
    fun setVideoDecoder(mode: com.enigmacast.domain.model.VideoDecoderMode) = update { it.copy(playback = it.playback.copy(videoDecoder = mode)) }
    fun setVideoCompatibility(mode: com.enigmacast.domain.model.VideoCompatibility) = update { it.copy(playback = it.playback.copy(videoCompatibility = mode)) }
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
    fun setSkin(id: com.enigmacast.domain.model.OsdSkinId) = update { it.copy(osdSkin = id) }
    fun setNavigationSounds(enabled: Boolean) = update { it.copy(navigationSounds = enabled) }
    fun setMotionQuality(quality: MotionQuality) = update { it.copy(motionQuality = quality) }
    fun setOsdInfoFields(fields: OsdInfoFields) = update { it.copy(osdInfo = fields) }
    fun setOsdTransparency(percent: Int) = update {
        it.copy(osd = it.osd.copy(transparencyPercent = percent.coerceIn(0, 100)))
    }
    /** Same path as the Red key: frees the old box's tuner, saves, full sync, Premium check on Free. */
    fun selectReceiver(id: String) = switcher.switchTo(id)
    fun removeReceiver(id: String) = update { ReceiverList.remove(it, id) }
    fun setInsecureCertificates(enabled: Boolean) = update { current ->
        val active = current.activeReceiver() ?: return@update current
        val updated = active.copy(allowInsecureCertificates = enabled)
        current.copy(receivers = current.receivers.map { if (it.id == updated.id) updated else it })
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
