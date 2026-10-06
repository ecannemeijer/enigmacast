package com.enigmacast.ui.power

import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.power.ReceiverPowerFlow
import com.enigmacast.domain.power.ReceiverPowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PowerCopyTest {
    @Test
    fun rebootRestartAndDeepStandbyAskFirst() {
        assertTrue(PowerCopy.needsConfirmation(PowerAction.REBOOT))
        assertTrue(PowerCopy.needsConfirmation(PowerAction.RESTART_GUI))
        assertTrue(PowerCopy.needsConfirmation(PowerAction.DEEP_STANDBY))
        assertFalse(PowerCopy.needsConfirmation(PowerAction.STANDBY))
        assertFalse(PowerCopy.needsConfirmation(PowerAction.WAKE_UP))
    }

    @Test
    fun everyConfirmedActionExplainsWhatHappens() {
        listOf(PowerAction.REBOOT, PowerAction.RESTART_GUI, PowerAction.DEEP_STANDBY).forEach { action ->
            assertTrue(PowerCopy.confirmMessage(action).isNotBlank())
            assertTrue(PowerCopy.confirmTitle(action).endsWith("?"))
        }
    }

    @Test
    fun resultsAreErrorsOnlyWhenSomethingWentWrong() {
        val failed = PowerCopy.result(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Failed(IOException("HTTP 401")))
        assertTrue(failed.isError)
        assertEquals("Stand-by mislukt: HTTP 401", failed.message)
        val standby = PowerCopy.result(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Confirmed(inStandby = true))
        assertFalse(standby.isError)
        assertEquals("Ontvanger staat in stand-by", standby.message)
        val reboot = PowerCopy.result(PowerAction.REBOOT, ReceiverPowerFlow.Outcome.Sent)
        assertFalse(reboot.isError)
        assertEquals("Ontvanger herstart…", reboot.message)
        assertTrue(PowerCopy.result(PowerAction.WAKE_UP, ReceiverPowerFlow.Outcome.Unconfirmed(true)).isError)
    }

    @Test
    fun stateLabelPrefersARunningRestart() {
        assertEquals("Aan", PowerCopy.stateLabel(ReceiverPowerState.ON, null))
        assertEquals("Stand-by", PowerCopy.stateLabel(ReceiverPowerState.STANDBY, null))
        assertEquals("Niet bereikbaar", PowerCopy.stateLabel(ReceiverPowerState.UNREACHABLE, null))
        assertEquals("Herstart…", PowerCopy.stateLabel(ReceiverPowerState.ON, PowerAction.REBOOT))
        assertEquals("Uitgeschakeld", PowerCopy.stateLabel(ReceiverPowerState.UNREACHABLE, PowerAction.DEEP_STANDBY))
    }
}
