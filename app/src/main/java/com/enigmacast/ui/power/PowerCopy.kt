package com.enigmacast.ui.power

import com.enigmacast.R
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverDetails
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.model.SleepTimerStatus
import com.enigmacast.domain.power.ReceiverPowerFlow
import com.enigmacast.domain.power.ReceiverPowerState
import com.enigmacast.util.UiText
import com.enigmacast.util.isNotEmpty
import com.enigmacast.util.uiMessage

data class PowerFeedback(
    val message: UiText,
    val isError: Boolean,
    val id: Long = 0,
)

/** Texts for the Receiver section, kept apart so they can be unit tested. */
internal object PowerCopy {
    fun label(action: PowerAction): UiText = UiText.res(
        when (action) {
            PowerAction.STANDBY -> R.string.power_action_standby
            PowerAction.WAKE_UP -> R.string.power_action_wake
            PowerAction.TOGGLE_STANDBY -> R.string.power_action_toggle
            PowerAction.REBOOT -> R.string.power_action_reboot
            PowerAction.RESTART_GUI -> R.string.power_action_restart_gui
            PowerAction.DEEP_STANDBY -> R.string.power_action_deep_standby
        },
    )

    /** Anything that takes the receiver off the network is confirmed first. */
    fun needsConfirmation(action: PowerAction): Boolean = action.dropsConnection

    fun confirmTitle(action: PowerAction): UiText = when (action) {
        PowerAction.REBOOT -> UiText.res(R.string.power_confirm_reboot_title)
        PowerAction.RESTART_GUI -> UiText.res(R.string.power_confirm_gui_title)
        PowerAction.DEEP_STANDBY -> UiText.res(R.string.power_confirm_deep_title)
        else -> label(action)
    }

    fun confirmMessage(action: PowerAction): UiText = when (action) {
        PowerAction.REBOOT -> UiText.res(R.string.power_confirm_reboot_message)
        PowerAction.RESTART_GUI -> UiText.res(R.string.power_confirm_gui_message)
        PowerAction.DEEP_STANDBY -> UiText.res(R.string.power_confirm_deep_message)
        else -> UiText.Empty
    }

    fun confirmLabel(action: PowerAction): UiText = when (action) {
        PowerAction.DEEP_STANDBY -> UiText.res(R.string.power_confirm_deep_button)
        else -> label(action)
    }

    fun result(action: PowerAction, outcome: ReceiverPowerFlow.Outcome): PowerFeedback = when (outcome) {
        is ReceiverPowerFlow.Outcome.Failed -> PowerFeedback(
            UiText.res(R.string.power_failed, label(action), outcome.error.uiMessage(UiText.res(R.string.power_no_connection))),
            isError = true,
        )
        ReceiverPowerFlow.Outcome.Sent -> PowerFeedback(
            UiText.res(
                when (action) {
                    PowerAction.REBOOT -> R.string.power_sent_reboot
                    PowerAction.RESTART_GUI -> R.string.power_sent_gui
                    PowerAction.DEEP_STANDBY -> R.string.power_sent_deep
                    else -> R.string.power_sent
                },
            ),
            isError = false,
        )
        is ReceiverPowerFlow.Outcome.Confirmed -> PowerFeedback(
            UiText.res(if (outcome.inStandby) R.string.power_confirmed_standby else R.string.power_confirmed_on),
            isError = false,
        )
        is ReceiverPowerFlow.Outcome.Unconfirmed -> PowerFeedback(
            UiText.res(
                when (action) {
                    PowerAction.STANDBY -> R.string.power_unconfirmed_standby
                    PowerAction.WAKE_UP -> R.string.power_not_responding
                    else -> R.string.power_unconfirmed
                },
            ),
            isError = true,
        )
    }

    fun stateLabel(state: ReceiverPowerState, restarting: PowerAction?): UiText = UiText.res(
        when {
            restarting == PowerAction.DEEP_STANDBY -> R.string.power_state_off
            restarting != null -> R.string.power_state_restarting
            else -> when (state) {
                ReceiverPowerState.ON -> R.string.power_state_on
                ReceiverPowerState.STANDBY -> R.string.power_state_standby
                ReceiverPowerState.UNREACHABLE -> R.string.power_state_unreachable
                ReceiverPowerState.UNKNOWN -> R.string.power_state_unknown
            }
        },
    )

    /** Sleep timer presets in minutes; 0 switches the timer off. */
    val sleepPresets = listOf(0, 15, 30, 60, 90, 120)

    fun sleepPresetLabel(minutes: Int): UiText =
        if (minutes == 0) UiText.res(R.string.sleep_off) else UiText.Raw(minutes.toString())

    fun sleepPresetIndex(status: SleepTimerStatus?): Int {
        if (status == null) return -1
        if (!status.enabled) return 0
        return sleepPresets.indexOf(status.minutes)
    }

    /** Lower case, used inside sentences ("after 30 min switch off"). */
    fun sleepActionLabel(action: SleepTimerAction): UiText = UiText.res(
        when (action) {
            SleepTimerAction.STANDBY -> R.string.sleep_action_standby
            SleepTimerAction.SHUTDOWN -> R.string.sleep_action_shutdown
        },
    )

    /** Capitalised, used as a choice. */
    fun sleepActionTitle(action: SleepTimerAction): UiText = UiText.res(
        when (action) {
            SleepTimerAction.STANDBY -> R.string.sleep_action_standby_title
            SleepTimerAction.SHUTDOWN -> R.string.sleep_action_shutdown_title
        },
    )

    fun sleepSummary(status: SleepTimerStatus?, error: UiText): UiText = when {
        error.isNotEmpty() -> error
        status == null -> UiText.res(R.string.sleep_loading)
        !status.enabled -> UiText.res(R.string.sleep_summary_off)
        status.minutes > 0 -> UiText.res(R.string.sleep_summary_active_minutes, status.minutes, sleepActionLabel(status.action))
        else -> UiText.res(R.string.sleep_summary_active, sleepActionLabel(status.action))
    }

    fun sleepResult(minutes: Int, action: SleepTimerAction): PowerFeedback = if (minutes == 0) {
        PowerFeedback(UiText.res(R.string.sleep_result_off), isError = false)
    } else {
        PowerFeedback(UiText.res(R.string.sleep_result_set, minutes, sleepActionLabel(action)), isError = false)
    }

    /**
     * Receiver facts. The labels are technical names that read the same in English and
     * Dutch (Model, Image, Enigma2, …), so they are not translated.
     */
    fun detailRows(details: ReceiverDetails): List<Pair<String, UiText>> {
        val shown = UiText.Raw(details.tuners.take(4).joinToString("\n"))
        val extra = details.tuners.size - 4
        val tuners = if (extra > 0) UiText.Joined(listOf(shown, UiText.plural(R.plurals.power_details_more, extra, extra))) else shown
        return listOf(
            "Model" to raw(details.brand, details.model),
            "Image" to raw(details.imageDistro, details.imageVersion),
            "Enigma2" to raw(details.enigmaVersion),
            "OpenWebif" to raw(details.webifVersion),
            "Kernel" to raw(details.kernelVersion),
            "Chipset" to raw(details.chipset),
            "Uptime" to raw(details.uptime),
            "Tuners" to tuners,
        ).filter { it.second.isNotEmpty() }
    }

    private fun raw(vararg parts: String): UiText = UiText.Raw(parts.filter { it.isNotBlank() }.joinToString(" "))
}
