package com.enigmacast.ui.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverDetails
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.model.SleepTimerStatus
import com.enigmacast.domain.power.PowerEvent
import com.enigmacast.domain.power.PowerStateTracker
import com.enigmacast.domain.power.ReceiverPowerFlow
import com.enigmacast.domain.power.ReceiverPowerState
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
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
    var details by mutableStateOf<ReceiverDetails?>(null)
        private set
    var detailsError by mutableStateOf(UiText.Empty)
        private set
    var sleepTimer by mutableStateOf<SleepTimerStatus?>(null)
        private set
    var sleepTimerError by mutableStateOf(UiText.Empty)
        private set
    var sleepAction by mutableStateOf(SleepTimerAction.STANDBY)
        private set
    var sleepBusy by mutableStateOf(false)
        private set
    private var feedbackId = 0L

    /** Faster while a restart is under way, so the dashboard notices the receiver coming back. */
    val pollIntervalMs: Long get() = if (restarting != null) 5_000L else 10_000L

    val stateLabel: UiText get() = PowerCopy.stateLabel(powerState, restarting)

    /** One light `/api/powerstate` request. Never throws except for cancellation. */
    suspend fun poll() {
        if (busyAction != null) return
        val receiver = try {
            settingsRepository.current().activeReceiver()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
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

    /** Receiver details and the sleep timer, loaded when the Receiver section opens. */
    fun loadDetails() {
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            try {
                details = infoSource.details(receiver)
                detailsError = UiText.Empty
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                detailsError = error.uiMessage(UiText.res(R.string.power_info_unavailable))
            }
            loadSleepTimer()
        }
    }

    fun setSleepTimer(minutes: Int) {
        if (sleepBusy) return
        sleepBusy = true
        viewModelScope.launch {
            try {
                val receiver = settingsRepository.current().activeReceiver() ?: return@launch
                val action = sleepAction
                val updated = infoSource.setSleepTimer(receiver, minutes, action, enabled = minutes > 0)
                sleepTimer = updated
                sleepTimerError = UiText.Empty
                showFeedback(PowerCopy.sleepResult(minutes, action))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFeedback(PowerFeedback(UiText.res(R.string.sleep_failed, error.uiMessage(UiText.res(R.string.power_no_connection))), isError = true))
            } finally {
                sleepBusy = false
            }
        }
    }

    /** Changing what happens afterwards re-applies a running timer so the choice takes effect. */
    fun chooseSleepAction(action: SleepTimerAction) {
        if (action == sleepAction) return
        sleepAction = action
        val running = sleepTimer?.takeIf { it.enabled && it.minutes > 0 } ?: return
        setSleepTimer(running.minutes)
    }

    private suspend fun loadSleepTimer() {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        try {
            val status = infoSource.sleepTimer(receiver)
            sleepTimer = status
            sleepAction = status.action
            sleepTimerError = UiText.Empty
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            sleepTimer = null
            sleepTimerError = error.uiMessage(UiText.res(R.string.sleep_unavailable))
        }
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
                    showFeedback(PowerFeedback(UiText.res(R.string.power_no_receiver), isError = true))
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
        if (busyAction == null) {
            if (event == PowerEvent.WokeUp && (wasRestarting || wasStandby)) {
                showFeedback(PowerFeedback(UiText.res(R.string.power_back_on), isError = false))
            } else if (event == PowerEvent.WentToStandby) {
                showFeedback(PowerFeedback(UiText.res(R.string.power_went_standby), isError = false))
            }
        }
        _events.tryEmit(event)
    }

    private fun showFeedback(value: PowerFeedback) {
        feedbackId += 1
        feedback = value.copy(id = feedbackId)
    }
}
