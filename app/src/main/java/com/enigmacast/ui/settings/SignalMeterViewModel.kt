package com.enigmacast.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.osdinfo.ServiceOrigin
import com.enigmacast.domain.osdinfo.SignalReading
import com.enigmacast.domain.source.OsdInfoSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.tuner.TunerSatellite
import com.enigmacast.domain.tuner.TunerSatellites
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignalMeterUi(
    val loading: Boolean = true,
    val satellites: List<TunerSatellite> = emptyList(),
    val selectedIndex: Int? = null,
    val reading: SignalReading? = null,
    val tunedChannel: String? = null,
    val message: UiText? = null,
    val busy: Boolean = false,
    val noReceiver: Boolean = false,
)

@HiltViewModel
class SignalMeterViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val router: ReceiverRouter,
    private val services: ServiceStateSource,
    private val osd: OsdInfoSource,
    private val playback: LivePlaybackController,
) : ViewModel() {
    var state by mutableStateOf(SignalMeterUi())
        private set

    private var active = false
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var returnReference: String? = null
    private var zapConfig: ReceiverConfig? = null
    private var zapSent = false
    private var streamStopped = false

    fun onEnter() {
        active = true
        zapSent = false
        returnReference = null
        zapConfig = null
        load()
    }

    /** Zaps back to the channel that was on before the first measurement. */
    fun onLeave() {
        if (!active) return
        active = false
        loadJob?.cancel()
        pollJob?.cancel()
        val reference = returnReference
        val config = zapConfig
        val restore = zapSent && !reference.isNullOrBlank() && config != null
        returnReference = null
        zapConfig = null
        zapSent = false
        if (streamStopped) {
            playback.allowPlayback()
            streamStopped = false
        }
        if (!restore) return
        viewModelScope.launch {
            runCatching { services.zap(config, reference) }
        }
    }

    fun select(index: Int) {
        val satellite = state.satellites.getOrNull(index) ?: return
        if (state.busy) return
        loadJob?.cancel()
        pollJob?.cancel()
        state = state.copy(selectedIndex = index, reading = null, tunedChannel = null)
        if (satellite.serviceReference == null) {
            state = state.copy(message = UiText.res(R.string.signal_no_channel), busy = false)
            return
        }
        loadJob = viewModelScope.launch {
            val config = receiver() ?: return@launch
            state = state.copy(busy = true, message = UiText.res(R.string.signal_tuning, satellite.label))
            try {
                if (returnReference == null) {
                    returnReference = runCatching { services.currentService(config).reference }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    zapConfig = config
                }
                zapSent = true
                releaseStream()
                services.zap(config, satellite.serviceReference)
                if (!active) return@launch
                state = state.copy(busy = false, tunedChannel = satellite.channelName, message = null)
                startPoll()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (!active) return@launch
                state = state.copy(busy = false, message = error.uiMessage(UiText.res(R.string.signal_zap_failed)))
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        pollJob?.cancel()
        state = SignalMeterUi(loading = true)
        loadJob = viewModelScope.launch {
            val config = receiver()
            if (config == null) {
                state = SignalMeterUi(loading = false, noReceiver = true)
                return@launch
            }
            val configured = runCatching { osd.enigmaSettings(config) }
                .getOrNull()
                ?.let { TunerSatellites.positions(it) }
                .orEmpty()
            val channels = try {
                val needed = configured.map { it.tenths }.toSet()
                loadChannels(config, needed)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (configured.isEmpty()) {
                    state = SignalMeterUi(loading = false, message = error.uiMessage(UiText.res(R.string.signal_load_failed)))
                    return@launch
                }
                emptyList()
            }
            if (!active) return@launch
            val satellites = TunerSatellites.merge(configured, channels)
            state = SignalMeterUi(
                loading = false,
                satellites = satellites,
                message = if (satellites.isEmpty()) UiText.res(R.string.signal_no_satellites) else null,
            )
        }
    }

    private suspend fun loadChannels(config: ReceiverConfig, needed: Set<Int>): List<BouquetEntry> {
        val found = mutableListOf<BouquetEntry>()
        val seen = mutableSetOf<Int>()
        for (bouquet in services.televisionBouquets(config)) {
            if (!active) return found
            val channels = runCatching { services.channelsInBouquet(config, bouquet.reference) }.getOrDefault(emptyList())
            for (channel in channels) {
                val orbital = ServiceOrigin.orbital(channel.reference)?.tenths
                found += channel
                if (orbital != null) seen += orbital
            }
            if (needed.isNotEmpty() && needed.all { it in seen }) break
        }
        return found
    }

    /** The open stream holds a tuner, so the dish cannot move until that socket is closed. */
    private suspend fun releaseStream() {
        if (streamStopped) return
        playback.stop()
        streamStopped = true
        delay(STREAM_RELEASE_MS)
    }

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && active) {
                val config = receiver() ?: break
                val reading = runCatching { osd.signal(config) }.getOrNull()
                if (active) state = state.copy(reading = reading)
                delay(POLL_MS)
            }
        }
    }

    private suspend fun receiver(): ReceiverConfig? {
        val activeReceiver = settingsRepository.current().activeReceiver() ?: return null
        return router.effective(activeReceiver)
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val STREAM_RELEASE_MS = 400L
    }
}
