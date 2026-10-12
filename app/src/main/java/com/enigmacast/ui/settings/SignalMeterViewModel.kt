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
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.osdinfo.SignalReading
import com.enigmacast.domain.power.ReceiverPowerFlow
import com.enigmacast.domain.source.OsdInfoSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.tuner.Lamedb
import com.enigmacast.domain.tuner.MeterSatellite
import com.enigmacast.domain.tuner.SignalReferences
import com.enigmacast.domain.tuner.TunerSatellites
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MeterTunerOption(
    val index: Int,
    val label: String,
)

data class SignalMeterUi(
    val loading: Boolean = true,
    val tuners: List<MeterTunerOption> = emptyList(),
    val tunerIndex: Int? = null,
    val satellites: List<MeterSatellite> = emptyList(),
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
    private val info: ReceiverInfoSource,
    private val playback: LivePlaybackController,
) : ViewModel() {
    var state by mutableStateOf(SignalMeterUi())
        private set

    private var active = false
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var zapConfig: ReceiverConfig? = null
    /** A measurement zap took a tuner; leaving must give it back. */
    private var tunerHeld = false
    private var streamStopped = false
    /** Frontend the measurement is read from. Choosing a tuner sets this. */
    private var meterTuner: Int? = null
    /** Satellites configured on each tuner, so switching tuner does not read settings again. */
    private var satellitesByTuner: Map<Int, List<MeterSatellite>> = emptyMap()
    /** Receiver `lamedb`, kept so each satellite does not download the channel database again. */
    private var channelDb: String? = null

    fun onEnter() {
        active = true
        tunerHeld = false
        zapConfig = null
        meterTuner = null
        // The open stream holds a tuner for as long as this screen is up. Drop it now,
        // before a measurement, and keep playback off until we leave.
        if (!streamStopped) {
            playback.stop()
            streamStopped = true
        }
        load()
    }

    /**
     * Leaves the measurement. Standby stops the service the meter tuned, so every tuner
     * that zap took is free again for the next channel.
     */
    fun onLeave() {
        if (!active) return
        active = false
        loadJob?.cancel()
        pollJob?.cancel()
        val config = zapConfig
        val release = tunerHeld && config != null
        val resume = streamStopped
        zapConfig = null
        tunerHeld = false
        streamStopped = false
        meterTuner = null
        if (!release) {
            if (resume) playback.allowPlayback()
            return
        }
        // Playback stays off until the measurement tuner is released, so the next
        // channel does not open a second stream against a tuner this zap still holds.
        viewModelScope.launch {
            withContext(NonCancellable) {
                runCatching { releaseTuners(config) }
            }
            if (resume) playback.allowPlayback()
        }
    }

    override fun onCleared() {
        onLeave()
    }

    fun selectTuner(index: Int) {
        if (state.busy || index == state.tunerIndex) return
        val satellites = satellitesByTuner[index].orEmpty()
        pollJob?.cancel()
        meterTuner = null
        state = state.copy(
            tunerIndex = index,
            satellites = satellites,
            selectedIndex = null,
            reading = null,
            tunedChannel = null,
            message = if (satellites.isEmpty()) UiText.res(R.string.signal_no_satellites) else null,
        )
    }

    fun select(index: Int) {
        val satellite = state.satellites.getOrNull(index) ?: return
        val tuner = state.tunerIndex ?: return
        if (state.busy) return
        loadJob?.cancel()
        pollJob?.cancel()
        meterTuner = null
        state = state.copy(selectedIndex = index, reading = null, tunedChannel = null)
        loadJob = viewModelScope.launch {
            val config = receiver() ?: return@launch
            state = state.copy(busy = true, message = UiText.res(R.string.signal_tuning, satellite.label))
            val database = try {
                channelDb ?: osd.lamedb(config)?.also { channelDb = it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (!active) return@launch
                state = state.copy(busy = false, message = error.uiMessage(UiText.res(R.string.signal_load_failed)))
                return@launch
            }
            if (database.isNullOrBlank()) {
                if (!active) return@launch
                state = state.copy(busy = false, message = UiText.res(R.string.signal_load_failed))
                return@launch
            }
            val tuned = Lamedb.serviceOn(database, satellite.transponders)
            if (tuned == null) {
                if (!active) return@launch
                state = state.copy(busy = false, message = UiText.res(R.string.signal_no_channel))
                return@launch
            }
            val (transponder, service) = tuned
            try {
                zapConfig = config
                tunerHeld = true
                meterTuner = tuner
                releaseStream()
                services.zap(config, service.reference)
                if (!active) return@launch
                val tunedText = if (service.name.isBlank()) transponder.caption else "${service.name} · ${transponder.caption}"
                state = state.copy(busy = false, tunedChannel = tunedText, message = null)
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
            if (!active) return@launch
            val settings = runCatching { osd.enigmaSettings(config) }.getOrNull()
            val references = SignalReferences.satellites()
            val configured = settings?.let { TunerSatellites.tuners(it) }.orEmpty()
            val reported = runCatching { info.details(config).tuners }.getOrDefault(emptyList())
            satellitesByTuner = configured.associate { tuner ->
                tuner.index to tuner.positions.mapNotNull { position ->
                    references.firstOrNull { satellite ->
                        satellite.transponders.any { it.orbitalTenths == position.tenths }
                    }
                }.distinctBy { it.label }
            }
            val options = tunerOptions(configured.map { it.index }, reported)
            state = SignalMeterUi(
                loading = false,
                tuners = options,
                message = if (options.isEmpty()) UiText.res(R.string.signal_no_satellites) else null,
            )
        }
    }

    /**
     * Tuner buttons come from the receiver setup. Tuners that `/api/about` reports are added
     * when the setup has no entry for them, so each frontend on the box can be picked.
     */
    private fun tunerOptions(configured: List<Int>, reported: List<String>): List<MeterTunerOption> {
        val indices = linkedSetOf<Int>()
        indices += configured
        reported.forEachIndexed { index, label ->
            val satellite = label.contains("DVB-S", ignoreCase = true) || label.contains("SAT", ignoreCase = true)
            if (satellite || reported.none { it.contains("DVB-S", ignoreCase = true) }) indices += index
        }
        return indices.map { index ->
            val reportedLabel = reported.getOrNull(index)?.substringBefore(":")?.trim().orEmpty()
            MeterTunerOption(index, reportedLabel.ifBlank { "Tuner ${index + 1}" })
        }
    }

    /**
     * Standby drops the live service that holds the tuner after a measurement zap.
     * A box that is already in standby ignores another standby command, so it is woken
     * first; otherwise that service keeps the tuner.
     */
    private suspend fun releaseTuners(config: ReceiverConfig) {
        playback.stop()
        val already = runCatching { info.powerState(config).inStandby }.getOrNull()
        if (already == true) {
            ReceiverPowerFlow.run(
                action = PowerAction.WAKE_UP,
                wasInStandby = true,
                send = { info.setPowerState(config, PowerAction.WAKE_UP) },
                readStandby = { info.powerState(config).inStandby },
                attempts = 4,
                pollMs = 400L,
            )
        }
        ReceiverPowerFlow.run(
            action = PowerAction.STANDBY,
            wasInStandby = false,
            send = { info.setPowerState(config, PowerAction.STANDBY) },
            readStandby = { info.powerState(config).inStandby },
            attempts = 4,
            pollMs = 400L,
        )
    }

    /** The open stream holds a tuner, so the dish cannot move until that socket is closed. */
    private suspend fun releaseStream() {
        if (!streamStopped) {
            playback.stop()
            streamStopped = true
        }
        delay(STREAM_RELEASE_MS)
    }

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && active) {
                val config = receiver() ?: break
                val reading = readMeter(config)
                if (reading != null && active) state = state.copy(reading = reading)
                delay(POLL_MS)
            }
        }
    }

    /** The frontend of the tuner the user picked. `tunersignal` fills in when that frontend file has no level. */
    private suspend fun readMeter(config: ReceiverConfig): SignalReading? {
        val tuner = meterTuner ?: return null
        val frontend = runCatching { osd.streamTunerSignal(config, tuner) }.getOrNull()
        if (frontend != null && frontend.usable()) return frontend
        val current = runCatching { osd.signal(config) }.getOrNull()
        if (current != null && current.usable() && (current.tunerNumber == null || current.tunerNumber == tuner)) {
            return current
        }
        return frontend
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

private fun SignalReading.usable(): Boolean {
    val snr = snrPercent ?: 0
    val agc = agcPercent ?: 0
    val db = snrDb ?: 0f
    return snr > 0 || agc > 0 || db > 0.05f
}
