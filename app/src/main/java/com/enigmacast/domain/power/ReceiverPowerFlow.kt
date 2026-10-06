package com.enigmacast.domain.power

import com.enigmacast.domain.model.PowerAction
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Sends one OpenWebif power command and, where a state change is expected, polls
 * `/api/powerstate` until Enigma2 reports it. The reply to `newstate` carries the
 * standby flag from before the command, so it is never trusted as the result.
 *
 * Reboot, GUI restart and deep standby take the receiver off the network; for those
 * the command is reported as sent and nothing is polled.
 */
internal object ReceiverPowerFlow {
    sealed interface Outcome {
        /** Enigma2 reported the expected standby state. */
        data class Confirmed(val inStandby: Boolean) : Outcome

        /** The receiver accepted a command that restarts or shuts it down. */
        data object Sent : Outcome

        /** The command was accepted but the expected state was not seen in time. */
        data class Unconfirmed(val lastKnownStandby: Boolean?) : Outcome

        data class Failed(val error: Throwable) : Outcome
    }

    /** The standby flag the receiver should report after [action], or null when no state is expected. */
    fun expectedStandby(action: PowerAction, wasInStandby: Boolean?): Boolean? = when (action) {
        PowerAction.STANDBY -> true
        PowerAction.WAKE_UP -> false
        PowerAction.TOGGLE_STANDBY -> wasInStandby?.not()
        PowerAction.DEEP_STANDBY, PowerAction.REBOOT, PowerAction.RESTART_GUI -> null
    }

    suspend fun run(
        action: PowerAction,
        wasInStandby: Boolean?,
        send: suspend () -> Unit,
        readStandby: suspend () -> Boolean,
        attempts: Int = 15,
        pollMs: Long = 1_000L,
        log: (String) -> Unit = { EnigmaLog.connection(it) },
    ): Outcome {
        log("power ${action.name} requested")
        try {
            send()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("power ${action.name} failed")
            return Outcome.Failed(error)
        }
        if (action.dropsConnection) {
            log("power ${action.name} sent")
            return Outcome.Sent
        }
        val expected = expectedStandby(action, wasInStandby)
        val outcome = waitForStandby(expected, readStandby, attempts, pollMs)
        log("power ${action.name} -> $outcome")
        return outcome
    }

    /**
     * Polls [readStandby] up to [attempts] times. With [expected] null the first answer wins.
     * Read errors are ignored; the receiver often drops one request while switching state.
     */
    suspend fun waitForStandby(
        expected: Boolean?,
        readStandby: suspend () -> Boolean,
        attempts: Int = 15,
        pollMs: Long = 1_000L,
    ): Outcome {
        var last: Boolean? = null
        repeat(attempts) {
            delay(pollMs)
            val state = try {
                readStandby()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (state != null) {
                last = state
                if (expected == null || state == expected) return Outcome.Confirmed(state)
            }
        }
        return Outcome.Unconfirmed(last)
    }
}
