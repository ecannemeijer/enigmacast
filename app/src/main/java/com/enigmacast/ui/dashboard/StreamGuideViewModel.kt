package com.enigmacast.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamGuideViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val serviceSource: ServiceStateSource,
    private val playback: LivePlaybackController,
    private val endpoints: OpenWebifEndpoints,
) : ViewModel() {
    var guide by mutableStateOf<ChannelGuide?>(null)
        private set
    var channels by mutableStateOf<List<BouquetEntry>>(emptyList())
        private set
    var bouquetName by mutableStateOf("")
        private set
    var tunedReference by mutableStateOf("")
        private set
    var displayName by mutableStateOf("")
        private set
    var status by mutableStateOf("")
        private set
    private var tuneJob: Job? = null
    private var lastPressedReference = ""
    private var previewJob: Job? = null
    private var shownReference = ""
    private var guideToken = 0
    private var bouquetLoaded = false

    fun refresh() {
        viewModelScope.launch {
            if (browsingAwayFromTuned()) {
                reloadShownGuide()
            } else {
                loadGuide()
            }
            if (!bouquetLoaded) loadBouquet()
        }
    }

    fun refreshIfServiceChanged() {
        if (browsingAwayFromTuned() || previewJob?.isActive == true || tuneJob?.isActive == true) return
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            val reference = runCatching { serviceSource.currentService(receiver).reference }.getOrNull()
            if (reference.isNullOrBlank()) return@launch
            if (!BouquetChannels.sameService(reference, guide?.serviceReference.orEmpty())) {
                loadGuide()
            }
            val known = channels.any { BouquetChannels.sameService(it.reference, reference) }
            if (bouquetLoaded && !known) loadBouquet()
        }
    }

    fun preview(channel: BouquetEntry) {
        if (!BouquetChannels.sameService(channel.reference, lastPressedReference)) {
            lastPressedReference = ""
        }
        if (BouquetChannels.sameService(channel.reference, shownReference)) return
        displayName = channel.name
        shownReference = channel.reference
        val token = ++guideToken
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(180)
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            runCatching { serviceSource.channelGuide(receiver, channel.reference, channel.name) }
                .onSuccess { loaded ->
                    if (token != guideToken) return@onSuccess
                    guide = loaded
                    displayName = loaded.channelName.ifBlank { channel.name }
                    status = ""
                }
                .onFailure { error ->
                    if (token != guideToken) return@onFailure
                    status = error.message ?: "EPG niet beschikbaar"
                }
        }
    }

    fun tune(channel: BouquetEntry, onStarted: () -> Unit, onFullscreen: () -> Unit) {
        if (lastPressedReference.isNotBlank() && BouquetChannels.sameService(channel.reference, lastPressedReference)) {
            onFullscreen()
            return
        }
        lastPressedReference = channel.reference
        tuneJob?.cancel()
        previewJob?.cancel()
        val token = ++guideToken
        tuneJob = viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver()
            if (receiver == null) {
                status = "Geen ontvanger"
                return@launch
            }
            val previous = tunedReference
            tunedReference = channel.reference
            runCatching { serviceSource.zap(receiver, channel.reference) }
                .onFailure { error ->
                    tunedReference = previous
                    status = error.message ?: "Kanaal starten mislukt"
                    return@launch
                }
            startPlayback(receiver, channel.reference)
            onStarted()
            runCatching { serviceSource.channelGuide(receiver, channel.reference, channel.name) }
                .onSuccess { loaded ->
                    if (token != guideToken) return@onSuccess
                    guide = loaded
                    val reference = loaded.serviceReference.ifBlank { channel.reference }
                    tunedReference = reference
                    shownReference = reference
                    displayName = loaded.channelName.ifBlank { channel.name }
                    status = ""
                }
                .onFailure { error ->
                    status = error.message ?: "EPG niet beschikbaar"
                }
        }
    }

    private fun startPlayback(receiver: ReceiverConfig, reference: String) {
        playback.play(endpoints.stream(receiver, reference), force = true)
    }

    private suspend fun loadGuide() {
        val receiver = settingsRepository.current().activeReceiver()
        if (receiver == null) {
            status = "Geen ontvanger"
            return
        }
        val token = ++guideToken
        runCatching { serviceSource.channelGuide(receiver) }
            .onSuccess { loaded ->
                if (token != guideToken) return@onSuccess
                guide = loaded
                displayName = loaded.channelName
                if (tuneJob?.isActive != true) {
                    tunedReference = loaded.serviceReference
                    shownReference = loaded.serviceReference
                    if (loaded.serviceReference.isNotBlank()) {
                        startPlayback(receiver, loaded.serviceReference)
                    }
                }
                status = ""
            }
            .onFailure { error ->
                status = error.message ?: "EPG niet beschikbaar"
            }
    }

    private fun browsingAwayFromTuned(): Boolean {
        return shownReference.isNotBlank() &&
            tunedReference.isNotBlank() &&
            !BouquetChannels.sameService(shownReference, tunedReference)
    }

    private suspend fun reloadShownGuide() {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        val reference = shownReference
        val name = displayName
        if (reference.isBlank()) return
        val token = ++guideToken
        runCatching { serviceSource.channelGuide(receiver, reference, name) }
            .onSuccess { loaded ->
                if (token != guideToken) return@onSuccess
                guide = loaded
                displayName = loaded.channelName.ifBlank { name }
            }
    }

    private suspend fun loadBouquet() {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        runCatching { serviceSource.currentBouquetChannels(receiver) }
            .onSuccess { loaded ->
                bouquetName = loaded.bouquetName
                channels = loaded.channels
                bouquetLoaded = true
                EnigmaLog.service("bouquet ${loaded.bouquetName} channels=${loaded.channels.size}")
            }
            .onFailure { error ->
                if (status.isBlank()) status = error.message ?: "Kanalen niet beschikbaar"
            }
    }
}
