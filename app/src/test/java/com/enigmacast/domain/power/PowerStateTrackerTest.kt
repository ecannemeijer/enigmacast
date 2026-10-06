package com.enigmacast.domain.power

import com.enigmacast.domain.model.PowerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class PowerStateTrackerTest {
    private var now = 0L
    private val tracker = PowerStateTracker(clock = { now }, graceMs = 60_000)

    @Test
    fun firstAnswerSetsStateWithoutEvent() {
        assertNull(tracker.onPoll(false))
        assertEquals(ReceiverPowerState.ON, tracker.state)
        assertEquals(false, tracker.knownStandby)
    }

    @Test
    fun wakingUpOutsideTheAppIsReported() {
        tracker.onPoll(true)
        assertEquals(PowerEvent.WokeUp, tracker.onPoll(false))
        assertEquals(ReceiverPowerState.ON, tracker.state)
    }

    @Test
    fun standbyFromTheRemoteOrSleepTimerIsReported() {
        tracker.onPoll(false)
        assertEquals(PowerEvent.WentToStandby, tracker.onPoll(true))
        assertNull(tracker.onPoll(true))
    }

    @Test
    fun standbyAtStartIsNotReportedAsAChange() {
        assertNull(tracker.onPoll(true))
        assertEquals(ReceiverPowerState.STANDBY, tracker.state)
    }

    @Test
    fun comingBackAfterANetworkDropIsReported() {
        tracker.onPoll(false)
        assertNull(tracker.onPoll(null))
        assertEquals(ReceiverPowerState.UNREACHABLE, tracker.state)
        assertEquals(PowerEvent.WokeUp, tracker.onPoll(false))
    }

    @Test
    fun confirmedStandbyAndWakeProduceEvents() {
        tracker.onPoll(false)
        assertEquals(
            PowerEvent.WentToStandby,
            tracker.onCommandResult(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Confirmed(inStandby = true)),
        )
        assertEquals(ReceiverPowerState.STANDBY, tracker.state)
        assertEquals(
            PowerEvent.WokeUp,
            tracker.onCommandResult(PowerAction.WAKE_UP, ReceiverPowerFlow.Outcome.Confirmed(inStandby = false)),
        )
        assertEquals(ReceiverPowerState.ON, tracker.state)
    }

    @Test
    fun failedCommandKeepsTheState() {
        tracker.onPoll(false)
        assertNull(tracker.onCommandResult(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Failed(IOException("x"))))
        assertEquals(ReceiverPowerState.ON, tracker.state)
    }

    @Test
    fun rebootWaitsUntilTheReceiverWasOfflineBeforeReportingItBack() {
        tracker.onPoll(false)
        assertEquals(
            PowerEvent.GoingOffline(PowerAction.REBOOT),
            tracker.onCommandResult(PowerAction.REBOOT, ReceiverPowerFlow.Outcome.Sent),
        )
        assertEquals(PowerAction.REBOOT, tracker.restarting)
        now += 5_000
        assertNull(tracker.onPoll(false))
        assertEquals(PowerAction.REBOOT, tracker.restarting)
        assertNull(tracker.onPoll(null))
        assertEquals(ReceiverPowerState.UNREACHABLE, tracker.state)
        assertEquals(PowerEvent.WokeUp, tracker.onPoll(false))
        assertNull(tracker.restarting)
        assertEquals(ReceiverPowerState.ON, tracker.state)
    }

    @Test
    fun restartThatNeverDropsIsAcceptedAfterTheGracePeriod() {
        tracker.onCommandResult(PowerAction.RESTART_GUI, ReceiverPowerFlow.Outcome.Sent)
        now += 30_000
        assertNull(tracker.onPoll(false))
        now += 31_000
        assertEquals(PowerEvent.WokeUp, tracker.onPoll(false))
        assertNull(tracker.restarting)
    }

    @Test
    fun restartIntoStandbyEndsTheRestart() {
        tracker.onCommandResult(PowerAction.REBOOT, ReceiverPowerFlow.Outcome.Sent)
        assertEquals(PowerEvent.WentToStandby, tracker.onPoll(true))
        assertNull(tracker.restarting)
        assertEquals(ReceiverPowerState.STANDBY, tracker.state)
    }

    @Test
    fun unconfirmedCommandUsesTheLastAnswer() {
        tracker.onPoll(false)
        assertNull(tracker.onCommandResult(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Unconfirmed(false)))
        assertEquals(ReceiverPowerState.ON, tracker.state)
        assertNull(tracker.onCommandResult(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Unconfirmed(null)))
        assertEquals(ReceiverPowerState.ON, tracker.state)
    }
}
