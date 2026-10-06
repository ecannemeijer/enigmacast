package com.enigmacast.domain.power

import com.enigmacast.domain.model.PowerAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReceiverPowerFlowTest {
    @Test
    fun expectedStandbyFollowsTheAction() {
        assertEquals(true, ReceiverPowerFlow.expectedStandby(PowerAction.STANDBY, wasInStandby = false))
        assertEquals(false, ReceiverPowerFlow.expectedStandby(PowerAction.WAKE_UP, wasInStandby = true))
        assertEquals(true, ReceiverPowerFlow.expectedStandby(PowerAction.TOGGLE_STANDBY, wasInStandby = false))
        assertEquals(false, ReceiverPowerFlow.expectedStandby(PowerAction.TOGGLE_STANDBY, wasInStandby = true))
        assertNull(ReceiverPowerFlow.expectedStandby(PowerAction.TOGGLE_STANDBY, wasInStandby = null))
        assertNull(ReceiverPowerFlow.expectedStandby(PowerAction.REBOOT, wasInStandby = false))
    }

    @Test
    fun onlyRestartsAndDeepStandbyDropTheConnection() {
        assertTrue(PowerAction.REBOOT.dropsConnection)
        assertTrue(PowerAction.RESTART_GUI.dropsConnection)
        assertTrue(PowerAction.DEEP_STANDBY.dropsConnection)
        assertFalse(PowerAction.STANDBY.dropsConnection)
        assertFalse(PowerAction.WAKE_UP.dropsConnection)
        assertFalse(PowerAction.TOGGLE_STANDBY.dropsConnection)
    }

    @Test
    fun standbyIsConfirmedOnceTheReceiverReportsIt() = runTest {
        val reads = ArrayDeque(listOf(false, false, true))
        var sent = 0
        val outcome = ReceiverPowerFlow.run(
            action = PowerAction.STANDBY,
            wasInStandby = false,
            send = { sent += 1 },
            readStandby = { reads.removeFirst() },
            log = {},
        )
        assertEquals(1, sent)
        assertEquals(ReceiverPowerFlow.Outcome.Confirmed(inStandby = true), outcome)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun wakeIgnoresDroppedPollsWhileSwitching() = runTest {
        var calls = 0
        val outcome = ReceiverPowerFlow.run(
            action = PowerAction.WAKE_UP,
            wasInStandby = true,
            send = {},
            readStandby = {
                calls += 1
                if (calls == 1) throw IOException("busy")
                false
            },
            log = {},
        )
        assertEquals(ReceiverPowerFlow.Outcome.Confirmed(inStandby = false), outcome)
        assertEquals(2, calls)
    }

    @Test
    fun rebootIsReportedAsSentWithoutPolling() = runTest {
        var polled = false
        val outcome = ReceiverPowerFlow.run(
            action = PowerAction.REBOOT,
            wasInStandby = false,
            send = {},
            readStandby = {
                polled = true
                false
            },
            log = {},
        )
        assertEquals(ReceiverPowerFlow.Outcome.Sent, outcome)
        assertFalse(polled)
    }

    @Test
    fun sendFailureIsReturnedAndNothingIsPolled() = runTest {
        val error = IOException("HTTP 401")
        var polled = false
        val outcome = ReceiverPowerFlow.run(
            action = PowerAction.STANDBY,
            wasInStandby = false,
            send = { throw error },
            readStandby = {
                polled = true
                true
            },
            log = {},
        )
        assertEquals(ReceiverPowerFlow.Outcome.Failed(error), outcome)
        assertFalse(polled)
    }

    @Test
    fun unconfirmedAfterAllAttemptsKeepsTheLastAnswer() = runTest {
        var calls = 0
        val outcome = ReceiverPowerFlow.run(
            action = PowerAction.WAKE_UP,
            wasInStandby = true,
            send = {},
            readStandby = {
                calls += 1
                true
            },
            attempts = 4,
            log = {},
        )
        assertEquals(ReceiverPowerFlow.Outcome.Unconfirmed(lastKnownStandby = true), outcome)
        assertEquals(4, calls)
    }

    @Test
    fun waitWithoutExpectationTakesTheFirstAnswer() = runTest {
        val outcome = ReceiverPowerFlow.waitForStandby(
            expected = null,
            readStandby = { true },
            attempts = 3,
            pollMs = 10,
        )
        assertEquals(ReceiverPowerFlow.Outcome.Confirmed(inStandby = true), outcome)
    }
}
