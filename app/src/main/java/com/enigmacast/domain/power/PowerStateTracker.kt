package com.enigmacast.domain.power

import com.enigmacast.domain.model.PowerAction

enum class ReceiverPowerState {
    UNKNOWN,
    ON,
    STANDBY,
    UNREACHABLE,
}

/** What the rest of the dashboard should do after a power change. */
sealed interface PowerEvent {
    /** Standby was confirmed after a command, or the receiver went to standby on its own (remote, sleep timer). */
    data object WentToStandby : PowerEvent

    /** The receiver is on again after standby, a restart or being unreachable. */
    data object WokeUp : PowerEvent

    /** A reboot, GUI restart or deep standby was accepted; the receiver is going away. */
    data class GoingOffline(val action: PowerAction) : PowerEvent
}

/**
 * Keeps the last known power state from polls and commands.
 *
 * After a reboot, GUI restart or deep standby the receiver often still answers for a
 * few seconds (or waits for a confirmation on the TV when a recording runs). Until it has
 * been seen offline once, or [graceMs] has passed, an "on" answer is not taken as the
 * receiver being back, so the stream is not restarted against a box that is going down.
 */
internal class PowerStateTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceMs: Long = 90_000L,
) {
    var state: ReceiverPowerState = ReceiverPowerState.UNKNOWN
        private set
    var restarting: PowerAction? = null
        private set
    private var sawOffline = false
    private var sentAt = 0L

    /** Forget the previous box so the next poll is not read as that box waking or sleeping. */
    fun reset() {
        state = ReceiverPowerState.UNKNOWN
        restarting = null
        sawOffline = false
        sentAt = 0L
    }

    val knownStandby: Boolean?
        get() = when (state) {
            ReceiverPowerState.ON -> false
            ReceiverPowerState.STANDBY -> true
            ReceiverPowerState.UNKNOWN, ReceiverPowerState.UNREACHABLE -> null
        }

    /** [standby] is null when the receiver did not answer. */
    fun onPoll(standby: Boolean?): PowerEvent? {
        if (restarting != null) {
            when {
                standby == null -> {
                    sawOffline = true
                    state = ReceiverPowerState.UNREACHABLE
                }
                standby -> {
                    restarting = null
                    state = ReceiverPowerState.STANDBY
                    return PowerEvent.WentToStandby
                }
                sawOffline || clock() - sentAt > graceMs -> {
                    restarting = null
                    state = ReceiverPowerState.ON
                    return PowerEvent.WokeUp
                }
            }
            return null
        }
        val previous = state
        state = when (standby) {
            null -> ReceiverPowerState.UNREACHABLE
            true -> ReceiverPowerState.STANDBY
            false -> ReceiverPowerState.ON
        }
        val cameBack = previous == ReceiverPowerState.STANDBY || previous == ReceiverPowerState.UNREACHABLE
        return when {
            state == ReceiverPowerState.ON && cameBack -> PowerEvent.WokeUp
            state == ReceiverPowerState.STANDBY && previous == ReceiverPowerState.ON -> PowerEvent.WentToStandby
            else -> null
        }
    }

    fun onCommandResult(action: PowerAction, outcome: ReceiverPowerFlow.Outcome): PowerEvent? {
        return when (outcome) {
            is ReceiverPowerFlow.Outcome.Confirmed -> {
                restarting = null
                if (outcome.inStandby) {
                    state = ReceiverPowerState.STANDBY
                    PowerEvent.WentToStandby
                } else {
                    state = ReceiverPowerState.ON
                    PowerEvent.WokeUp
                }
            }
            ReceiverPowerFlow.Outcome.Sent -> {
                restarting = action
                sawOffline = false
                sentAt = clock()
                PowerEvent.GoingOffline(action)
            }
            is ReceiverPowerFlow.Outcome.Unconfirmed -> {
                outcome.lastKnownStandby?.let { onPoll(it) }
                null
            }
            is ReceiverPowerFlow.Outcome.Failed -> null
        }
    }
}
