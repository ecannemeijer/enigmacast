package com.enigmacast.ui.power

import com.enigmacast.domain.model.PowerAction
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
}
