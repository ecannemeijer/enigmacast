package com.enigmacast.ui.power

import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverDetails
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.model.SleepTimerStatus
import com.enigmacast.domain.power.ReceiverPowerFlow
import com.enigmacast.domain.power.ReceiverPowerState

data class PowerFeedback(
    val message: String,
    val isError: Boolean,
    val id: Long = 0,
)

/** Dutch texts for the Receiver section, kept apart so they can be unit tested. */
internal object PowerCopy {
    fun label(action: PowerAction): String = when (action) {
        PowerAction.STANDBY -> "Stand-by"
        PowerAction.WAKE_UP -> "Aanzetten"
        PowerAction.TOGGLE_STANDBY -> "Stand-by wisselen"
        PowerAction.REBOOT -> "Herstarten"
        PowerAction.RESTART_GUI -> "GUI herstarten"
        PowerAction.DEEP_STANDBY -> "Diep slapen"
    }

    /** Anything that takes the receiver off the network is confirmed first. */
    fun needsConfirmation(action: PowerAction): Boolean = action.dropsConnection

    fun confirmTitle(action: PowerAction): String = when (action) {
        PowerAction.REBOOT -> "Ontvanger herstarten?"
        PowerAction.RESTART_GUI -> "Enigma2 herstarten?"
        PowerAction.DEEP_STANDBY -> "Ontvanger uitschakelen?"
        else -> label(action)
    }

    fun confirmMessage(action: PowerAction): String = when (action) {
        PowerAction.REBOOT ->
            "De ontvanger start helemaal opnieuw op; dat duurt meestal één tot twee minuten. " +
                "De stream stopt. Loopt er een opname, dan vraagt de ontvanger op de tv nog om bevestiging."
        PowerAction.RESTART_GUI ->
            "Alleen de Enigma2-interface start opnieuw, binnen een halve minuut. De stream stopt even."
        PowerAction.DEEP_STANDBY ->
            "De ontvanger gaat helemaal uit. Aanzetten kan daarna alleen met de afstandsbediening " +
                "of de aan/uit-knop van de ontvanger, niet vanuit EnigmaCast."
        else -> ""
    }

    fun confirmLabel(action: PowerAction): String = when (action) {
        PowerAction.DEEP_STANDBY -> "Uitschakelen"
        else -> label(action)
    }

    fun result(action: PowerAction, outcome: ReceiverPowerFlow.Outcome): PowerFeedback = when (outcome) {
        is ReceiverPowerFlow.Outcome.Failed -> PowerFeedback(
            "${label(action)} mislukt: ${outcome.error.message ?: "geen verbinding"}",
            isError = true,
        )
        ReceiverPowerFlow.Outcome.Sent -> PowerFeedback(
            when (action) {
                PowerAction.REBOOT -> "Ontvanger herstart…"
                PowerAction.RESTART_GUI -> "Enigma2 herstart…"
                PowerAction.DEEP_STANDBY -> "Ontvanger schakelt uit"
                else -> "Commando verstuurd"
            },
            isError = false,
        )
        is ReceiverPowerFlow.Outcome.Confirmed -> PowerFeedback(
            if (outcome.inStandby) "Ontvanger staat in stand-by" else "Ontvanger staat aan",
            isError = false,
        )
        is ReceiverPowerFlow.Outcome.Unconfirmed -> PowerFeedback(
            when (action) {
                PowerAction.STANDBY -> "Stand-by verstuurd, maar nog niet bevestigd"
                PowerAction.WAKE_UP -> "Ontvanger reageert nog niet"
                else -> "Commando verstuurd, status onbekend"
            },
            isError = true,
        )
    }

    fun stateLabel(state: ReceiverPowerState, restarting: PowerAction?): String = when {
        restarting == PowerAction.DEEP_STANDBY -> "Uitgeschakeld"
        restarting != null -> "Herstart…"
        else -> when (state) {
            ReceiverPowerState.ON -> "Aan"
            ReceiverPowerState.STANDBY -> "Stand-by"
            ReceiverPowerState.UNREACHABLE -> "Niet bereikbaar"
            ReceiverPowerState.UNKNOWN -> "Status onbekend"
        }
    }

    /** Sleep timer presets in minutes; 0 switches the timer off. */
    val sleepPresets = listOf(0, 15, 30, 60, 90, 120)

    fun sleepPresetLabel(minutes: Int): String = if (minutes == 0) "Uit" else minutes.toString()

    fun sleepPresetIndex(status: SleepTimerStatus?): Int {
        if (status == null) return -1
        if (!status.enabled) return 0
        return sleepPresets.indexOf(status.minutes)
    }

    fun sleepActionLabel(action: SleepTimerAction): String = when (action) {
        SleepTimerAction.STANDBY -> "stand-by"
        SleepTimerAction.SHUTDOWN -> "uitschakelen"
    }

    fun sleepSummary(status: SleepTimerStatus?, error: String): String = when {
        error.isNotBlank() -> error
        status == null -> "Slaaptimer laden…"
        !status.enabled -> "Uit. Kies na hoeveel minuten de ontvanger uit moet."
        status.minutes > 0 -> "Actief: na ${status.minutes} min ${sleepActionLabel(status.action)}."
        else -> "Actief: daarna ${sleepActionLabel(status.action)}."
    }

    fun sleepResult(minutes: Int, action: SleepTimerAction): PowerFeedback = if (minutes == 0) {
        PowerFeedback("Slaaptimer uitgezet", isError = false)
    } else {
        PowerFeedback("Slaaptimer: over $minutes min ${sleepActionLabel(action)}", isError = false)
    }

    fun detailRows(details: ReceiverDetails): List<Pair<String, String>> {
        val tuners = details.tuners.take(4).joinToString("\n") +
            if (details.tuners.size > 4) "\n+${details.tuners.size - 4} meer" else ""
        return listOf(
            "Model" to listOf(details.brand, details.model).filter { it.isNotBlank() }.joinToString(" "),
            "Image" to listOf(details.imageDistro, details.imageVersion).filter { it.isNotBlank() }.joinToString(" "),
            "Enigma2" to details.enigmaVersion,
            "OpenWebif" to details.webifVersion,
            "Kernel" to details.kernelVersion,
            "Chipset" to details.chipset,
            "Uptime" to details.uptime,
            "Tuners" to tuners,
        ).filter { it.second.isNotBlank() }
    }
}
