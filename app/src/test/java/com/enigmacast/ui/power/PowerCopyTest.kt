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
            assertTrue(PowerCopy.confirmMessage(action).isNotEmpty())
            assertTrue(PowerCopy.confirmTitle(action) is UiText.Res)
            assertTrue(PowerCopy.confirmTitle(action) != PowerCopy.label(action))
        }
    }

    @Test
    fun resultsAreErrorsOnlyWhenSomethingWentWrong() {
        val failed = PowerCopy.result(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Failed(IOException("HTTP 401")))
        assertTrue(failed.isError)
        assertEquals(
            UiText.res(R.string.power_failed, UiText.res(R.string.power_action_standby), UiText.Raw("HTTP 401")),
            failed.message,
        )
        val standby = PowerCopy.result(PowerAction.STANDBY, ReceiverPowerFlow.Outcome.Confirmed(inStandby = true))
        assertFalse(standby.isError)
        assertEquals(UiText.res(R.string.power_confirmed_standby), standby.message)
        val reboot = PowerCopy.result(PowerAction.REBOOT, ReceiverPowerFlow.Outcome.Sent)
        assertFalse(reboot.isError)
        assertEquals(UiText.res(R.string.power_sent_reboot), reboot.message)
        assertTrue(PowerCopy.result(PowerAction.WAKE_UP, ReceiverPowerFlow.Outcome.Unconfirmed(true)).isError)
    }

    @Test
    fun stateLabelPrefersARunningRestart() {
        assertEquals(UiText.res(R.string.power_state_on), PowerCopy.stateLabel(ReceiverPowerState.ON, null))
        assertEquals(UiText.res(R.string.power_state_standby), PowerCopy.stateLabel(ReceiverPowerState.STANDBY, null))
        assertEquals(UiText.res(R.string.power_state_unreachable), PowerCopy.stateLabel(ReceiverPowerState.UNREACHABLE, null))
        assertEquals(UiText.res(R.string.power_state_restarting), PowerCopy.stateLabel(ReceiverPowerState.ON, PowerAction.REBOOT))
        assertEquals(UiText.res(R.string.power_state_off), PowerCopy.stateLabel(ReceiverPowerState.UNREACHABLE, PowerAction.DEEP_STANDBY))
    }

    @Test
    fun sleepPresetFollowsTheReceiver() {
        assertEquals(-1, PowerCopy.sleepPresetIndex(null))
        assertEquals(0, PowerCopy.sleepPresetIndex(SleepTimerStatus(false, 60, SleepTimerAction.STANDBY)))
        assertEquals(3, PowerCopy.sleepPresetIndex(SleepTimerStatus(true, 60, SleepTimerAction.STANDBY)))
        assertEquals(-1, PowerCopy.sleepPresetIndex(SleepTimerStatus(true, 45, SleepTimerAction.STANDBY)))
        assertEquals(UiText.res(R.string.sleep_off), PowerCopy.sleepPresetLabel(0))
        assertEquals(UiText.Raw("30"), PowerCopy.sleepPresetLabel(30))
    }

    @Test
    fun sleepSummaryShowsErrorsFirst() {
        assertEquals(UiText.Raw("Not supported"), PowerCopy.sleepSummary(null, UiText.Raw("Not supported")))
        assertEquals(
            UiText.res(R.string.sleep_summary_active_minutes, 30, UiText.res(R.string.sleep_action_shutdown)),
            PowerCopy.sleepSummary(SleepTimerStatus(true, 30, SleepTimerAction.SHUTDOWN), UiText.Empty),
        )
        assertEquals(UiText.res(R.string.sleep_result_off), PowerCopy.sleepResult(0, SleepTimerAction.STANDBY).message)
        assertEquals(
            UiText.res(R.string.sleep_result_set, 90, UiText.res(R.string.sleep_action_standby)),
            PowerCopy.sleepResult(90, SleepTimerAction.STANDBY).message,
        )
    }

    @Test
    fun detailRowsSkipEmptyFieldsAndShortenLongTunerLists() {
        val rows = PowerCopy.detailRows(
            ReceiverDetails(
                brand = "Vu+",
                model = "Duo 4K",
                imageDistro = "OpenATV",
                imageVersion = "7.5",
                tuners = (1..6).map { "Tuner $it" },
            ),
        ).toMap()
        assertEquals(UiText.Raw("Vu+ Duo 4K"), rows["Model"])
        assertEquals(UiText.Raw("OpenATV 7.5"), rows["Image"])
        assertFalse(rows.containsKey("Kernel"))
        assertEquals(
            UiText.Joined(listOf(UiText.Raw("Tuner 1\nTuner 2\nTuner 3\nTuner 4"), UiText.plural(R.plurals.power_details_more, 2, 2))),
            rows["Tuners"],
        )
    }
}
