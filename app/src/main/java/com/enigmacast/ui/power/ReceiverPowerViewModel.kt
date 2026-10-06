package com.enigmacast.ui.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.power.PowerEvent
import com.enigmacast.domain.power.PowerStateTracker
import com.enigmacast.domain.power.ReceiverPowerFlow
import com.enigmacast.domain.power.ReceiverPowerState
import com.enigmacast.domain.source.ReceiverInfoSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Standby, wake, reboot, GUI restart and deep standby for the active receiver.
 * The dashboard polls [poll] while it is visible and reacts to [events].
 */
@HiltViewModel
class ReceiverPowerViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val infoSource: ReceiverInfoSource,
) : ViewModel() {
    private val tracker = PowerStateTracker()
    private val _events = MutableSharedFlow<PowerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PowerEvent> = _events.asSharedFlow()

    var powerState by mutableStateOf(ReceiverPowerState.UNKNOWN)
        private set
    var restarting by mutableStateOf<PowerAction?>(null)
        private set
    var receiverName by mutableStateOf("")
        private set
    var receiverHost by mutableStateOf("")
        private set
    var busyAction by mutableStateOf<PowerAction?>(null)
        private set
    var pendingConfirmation by mutableStateOf<PowerAction?>(null)
        private set
    var feedback by mutableStateOf<PowerFeedback?>(null)
        private set
    private var feedbackId = 0L

    /** Faster while a restart is under way, so the dashboard notices the receiver coming back. */
    val pollIntervalMs: Long get() = if (restarting != null) 5_000L else 10_000L

    val stateLabel: String get() = PowerCopy.stateLabel(powerState, restarting)

    suspend fun poll() {
        if (busyAction != null) return
        val receiver = settingsRepository.current().activeReceiver() ?: return
        receiverName = receiver.name
        receiverHost = receiver.host
        val standby = try {
            infoSource.powerState(receiver).inStandby
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (busyAction != null) return
        publish(tracker.onPoll(standby))
    }

    fun refreshNow() {
        viewModelScope.launch { poll() }
    }

    fun request(action: PowerAction) {
        if (busyAction != null) return
        if (PowerCopy.needsConfirmation(action)) {
            pendingConfirmation = action
        } else {
            perform(action)
        }
    }

    fun confirmPending() {
        val action = pendingConfirmation ?: return
        pendingConfirmation = null
        perform(action)
    }

    fun cancelPending() {
        pendingConfirmation = null
    }

    fun dismissFeedback(id: Long) {
        if (feedback?.id == id) feedback = null
    }

    private fun perform(action: PowerAction) {
        if (busyAction != null) return
        busyAction = action
        viewModelScope.launch {
            try {
                val receiver = settingsRepository.current().activeReceiver()
                if (receiver == null) {
                    showFeedback(PowerFeedback("Geen ontvanger ingesteld", isError = true))
                    return@launch
                }
                val outcome = ReceiverPowerFlow.run(
                    action = action,
                    wasInStandby = tracker.knownStandby,
                    send = { infoSource.setPowerState(receiver, action) },
                    readStandby = { infoSource.powerState(receiver).inStandby },
                )
                val event = tracker.onCommandResult(action, outcome)
                showFeedback(PowerCopy.result(action, outcome))
                publish(event)
            } finally {
                busyAction = null
            }
        }
    }

    private fun publish(event: PowerEvent?) {
        val wasRestarting = restarting != null
        val wasStandby = powerState == ReceiverPowerState.STANDBY
        powerState = tracker.state
        restarting = tracker.restarting
        if (event == null) return
        // A command reports its own result; a short network blip is not worth a message.
        if (event == PowerEvent.WokeUp && busyAction == null && (wasRestarting || wasStandby)) {
            showFeedback(PowerFeedback("Ontvanger staat weer aan", isError = false))
        }
        _events.tryEmit(event)
    }

    private fun showFeedback(value: PowerFeedback) {
        feedbackId += 1
        feedback = value.copy(id = feedbackId)
    }
}
