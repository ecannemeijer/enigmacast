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
import com.enigmacast.domain.service.LiveZapFlow
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamGuideViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val serviceSource: ServiceStateSource,
    private val infoSource: ReceiverInfoSource,
    private val playback: LivePlaybackController,
    private val endpoints: OpenWebifEndpoints,
) : ViewModel() {
    var guide by mutableStateOf<ChannelGuide?>(null)
        private set
    var channels by mutableStateOf<List<BouquetEntry>>(emptyList())
        private set
    var bouquetName by mutableStateOf("")
        private set
    var bouquets by mutableStateOf<List<BouquetEntry>>(emptyList())
        private set
    var selectedBouquetReference by mutableStateOf("")
        private set
    var tunedReference by mutableStateOf("")
        private set
    var displayName by mutableStateOf("")
        private set
    var status by mutableStateOf("")
        private set
    var askWake by mutableStateOf(false)
        private set
    var waking by mutableStateOf(false)
        private set
    private var tuneJob: Job? = null
    private var lastPressedReference = ""
    private var previewJob: Job? = null
    private var shownReference = ""
    private var guideToken = 0
    private var bouquetLoaded = false
    private var bouquetPinned = false
    private var bouquetJob: Job? = null
    private var bouquetToken = 0
    private var powerCheckStarted = false
    private var powerKnown = false
    private var leaveOff = false

    fun refresh() {
        val shouldCheckPower = !powerCheckStarted
        if (shouldCheckPower) powerCheckStarted = true
        viewModelScope.launch {
            if (shouldCheckPower) {
                val receiver = settingsRepository.current().activeReceiver()
                val standby = receiver?.let { box ->
                    runCatching { infoSource.powerState(box).inStandby }.getOrNull()
                }
                if (standby == true) {
                    askWake = true
                    status = "Ontvanger staat uit"
                    return@launch
                }
                powerKnown = true
            }
            if (askWake || leaveOff || waking || !powerKnown) return@launch
            if (browsingAwayFromTuned()) {
                reloadShownGuide()
            } else {
                loadGuide()
            }
            if (!bouquetLoaded) loadBouquet()
        }
    }

    fun confirmWake() {
        if (waking) return
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver()
            if (receiver == null) {
                askWake = false
                return@launch
            }
            waking = true
            val woke = runCatching { infoSource.wake(receiver) }.isSuccess
            if (!woke) {
                waking = false
                status = "Aanzetten mislukt"
                return@launch
            }
            var awake = false
            for (attempt in 1..15) {
                delay(1_000)
                awake = runCatching { !infoSource.powerState(receiver).inStandby }.getOrDefault(false)
                if (awake) break
            }
            waking = false
            if (!awake) {
                status = "Ontvanger reageert nog niet"
                return@launch
            }
            askWake = false
            leaveOff = false
            powerKnown = true
            status = ""
            loadGuide()
            if (!bouquetLoaded) loadBouquet()
        }
    }

    fun declineWake() {
        if (waking) return
        askWake = false
        leaveOff = true
        powerKnown = true
        status = "Ontvanger staat uit"
    }

    fun refreshIfServiceChanged() {
        if (!powerKnown || askWake || leaveOff || waking) return
        if (browsingAwayFromTuned() || previewJob?.isActive == true || tuneJob?.isActive == true || bouquetJob?.isActive == true) return
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            val reference = runCatching { serviceSource.currentService(receiver).reference }.getOrNull()
            if (reference.isNullOrBlank()) return@launch
            if (!BouquetChannels.sameService(reference, guide?.serviceReference.orEmpty())) {
                loadGuide()
            }
            val known = channels.any { BouquetChannels.sameService(it.reference, reference) }
            if (bouquetLoaded && !known && !bouquetPinned) loadBouquet()
        }
    }

    fun preview(channel: BouquetEntry) {
        if (!BouquetChannels.sameService(channel.reference, lastPressedReference)) {
            lastPressedReference = ""
        }
        if (BouquetChannels.sameService(channel.reference, shownReference)) return
        if (tuneJob?.isActive == true) return
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
        val token = ++guideToken
        tuneJob?.cancel()
        previewJob?.cancel()
        val previous = tunedReference
        tunedReference = channel.reference
        tuneJob = viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver()
            if (receiver == null) {
                if (token != guideToken) {
                    EnigmaLog.service("stale tune ignored")
                    return@launch
                }
                status = "Geen ontvanger"
                return@launch
            }
            val streamUrl = endpoints.stream(receiver, channel.reference)
            val outcome = LiveZapFlow.run(
                token = token,
                isCurrent = { it == guideToken },
                reference = channel.reference,
                streamUrl = streamUrl,
                releaseStream = { playback.stopForZap() },
                zap = { serviceSource.zap(receiver, channel.reference) },
                currentReference = { serviceSource.currentService(receiver).reference },
                startStream = { playback.play(streamUrl, force = true) },
            )
            if (token != guideToken || outcome is LiveZapFlow.Outcome.Stale) return@launch
            when (outcome) {
                LiveZapFlow.Outcome.Stale -> return@launch
                LiveZapFlow.Outcome.Started -> {
                    onStarted()
                    bouquetPinned = false
                    try {
                        val loaded = serviceSource.channelGuide(receiver, channel.reference, channel.name)
                        if (token != guideToken) return@launch
                        guide = loaded
                        val reference = loaded.serviceReference.ifBlank { channel.reference }
                        tunedReference = reference
                        shownReference = reference
                        displayName = loaded.channelName.ifBlank { channel.name }
                        status = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (token != guideToken) return@launch
                        status = error.message ?: "EPG niet beschikbaar"
                    }
                }
                is LiveZapFlow.Outcome.ZapFailed -> {
                    tunedReference = previous
                    status = outcome.error.message ?: "Kanaal starten mislukt"
                }
                LiveZapFlow.Outcome.TimedOut -> {
                    status = "Zender niet bevestigd"
                }
            }
        }
    }

    private fun startPlayback(receiver: ReceiverConfig, reference: String) {
        playback.play(endpoints.stream(receiver, reference), force = true)
    }

    private suspend fun loadGuide() {
        if (tuneJob?.isActive == true) return
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
        if (tuneJob?.isActive == true) return
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

    fun previewBouquet(bouquet: BouquetEntry) {
        if (bouquet.reference == selectedBouquetReference && bouquetLoaded) return
        bouquetPinned = true
        lastPressedReference = ""
        selectedBouquetReference = bouquet.reference
        val token = ++bouquetToken
        bouquetJob?.cancel()
        bouquetJob = viewModelScope.launch {
            delay(500)
            if (token != bouquetToken) return@launch
            showBouquet(bouquet, token)
        }
    }

    private suspend fun showBouquet(bouquet: BouquetEntry, token: Int) {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        runCatching { serviceSource.channelsInBouquet(receiver, bouquet.reference) }
            .onSuccess { loaded ->
                if (token != bouquetToken) return@onSuccess
                bouquetName = bouquet.name
                channels = loaded
                bouquetLoaded = true
                status = ""
                EnigmaLog.service("bouquet ${bouquet.name} channels=${loaded.size}")
            }
            .onFailure { error ->
                if (token != bouquetToken) return@onFailure
                status = error.message ?: "Kanalen niet beschikbaar"
            }
    }

    private suspend fun loadBouquet() {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        if (!bouquetPinned) {
            runCatching { serviceSource.televisionBouquets(receiver) }
                .onSuccess { bouquets = it }
        }
        runCatching { serviceSource.currentBouquetChannels(receiver) }
            .onSuccess { loaded ->
                if (bouquetPinned) return@onSuccess
                bouquetName = loaded.bouquetName
                channels = loaded.channels
                selectedBouquetReference = loaded.bouquetReference
                bouquetLoaded = true
                EnigmaLog.service("bouquet ${loaded.bouquetName} channels=${loaded.channels.size}")
            }
            .onFailure { error ->
                if (status.isBlank()) status = error.message ?: "Kanalen niet beschikbaar"
            }
    }
}
