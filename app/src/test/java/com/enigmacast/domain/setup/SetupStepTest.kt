package com.enigmacast.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStepTest {
    private val plain = SetupConditions()

    @Test fun standardPathHasFiveSteps() {
        assertEquals(
            listOf(SetupStep.WELCOME, SetupStep.RECEIVER, SetupStep.VIEWING, SetupStep.ACCOUNT, SetupStep.DONE),
            SetupFlow.steps(plain),
        )
    }

    @Test fun syncAndIptvOnlyWhenOffered() {
        val sync = SetupConditions(syncOffered = true)
        assertEquals(
            listOf(SetupStep.WELCOME, SetupStep.RECEIVER, SetupStep.SYNC_LINK, SetupStep.VIEWING, SetupStep.ACCOUNT, SetupStep.DONE),
            SetupFlow.steps(sync),
        )
        val both = SetupConditions(syncOffered = true, iptvOffered = true, paired = true)
        assertEquals(SetupStep.SYNC_LINK, SetupFlow.next(SetupStep.RECEIVER, both))
        assertEquals(SetupStep.IPTV_OFFER, SetupFlow.next(SetupStep.SYNC_LINK, both))
        assertEquals(SetupStep.VIEWING, SetupFlow.next(SetupStep.IPTV_OFFER, both))
        // Paired before the wizard: no sync step, but the IPTV offer still shows.
        val prePaired = SetupConditions(iptvOffered = true, paired = true)
        assertEquals(SetupStep.IPTV_OFFER, SetupFlow.next(SetupStep.RECEIVER, prePaired))
        assertEquals(SetupStep.RECEIVER, SetupFlow.previous(SetupStep.IPTV_OFFER, prePaired))
    }

    @Test fun nextAndPreviousSkipOmittedSteps() {
        assertEquals(SetupStep.RECEIVER, SetupFlow.next(SetupStep.WELCOME, plain))
        assertEquals(SetupStep.VIEWING, SetupFlow.next(SetupStep.RECEIVER, plain))
        assertEquals(SetupStep.ACCOUNT, SetupFlow.next(SetupStep.VIEWING, plain))
        assertEquals(SetupStep.DONE, SetupFlow.next(SetupStep.ACCOUNT, plain))
        assertEquals(SetupStep.DONE, SetupFlow.next(SetupStep.DONE, plain))
        assertNull(SetupFlow.previous(SetupStep.WELCOME, plain))
        assertEquals(SetupStep.RECEIVER, SetupFlow.previous(SetupStep.VIEWING, plain))
        assertEquals(SetupStep.VIEWING, SetupFlow.previous(SetupStep.ACCOUNT, plain))
    }

    @Test fun leavingAStepThatJustDisappearedGoesOn() {
        // The sync search ended without a server while its step was on screen.
        assertEquals(SetupStep.VIEWING, SetupFlow.next(SetupStep.SYNC_LINK, plain))
        assertEquals(SetupStep.RECEIVER, SetupFlow.previous(SetupStep.SYNC_LINK, plain))
        assertEquals(SetupStep.VIEWING, SetupFlow.next(SetupStep.IPTV_OFFER, plain))
    }

    @Test fun extrasComeFromAndReturnToDone() {
        assertEquals(listOf(SetupStep.PROFILE, SetupStep.BOUQUETS), SetupFlow.extras(SetupConditions(hasReceiver = true)))
        assertEquals(listOf(SetupStep.BOUQUETS), SetupFlow.extras(SetupConditions(paired = true, hasReceiver = true)))
        assertEquals(listOf(SetupStep.PROFILE), SetupFlow.extras(plain))
        SetupStep.entries.filter { it.extra }.forEach {
            assertEquals(SetupStep.DONE, SetupFlow.next(it, plain))
            assertEquals(SetupStep.DONE, SetupFlow.previous(it, plain))
            assertNull(SetupFlow.position(it, plain))
            assertFalse(it in SetupFlow.steps(plain))
        }
    }

    @Test fun progressCountsOnlyShownSteps() {
        assertEquals(1 to 5, SetupFlow.position(SetupStep.WELCOME, plain))
        assertEquals(3 to 5, SetupFlow.position(SetupStep.VIEWING, plain))
        assertEquals(5 to 5, SetupFlow.position(SetupStep.DONE, plain))
        val sync = SetupConditions(syncOffered = true, iptvOffered = true)
        assertEquals(3 to 7, SetupFlow.position(SetupStep.SYNC_LINK, sync))
        assertEquals(5 to 7, SetupFlow.position(SetupStep.VIEWING, sync))
        // A step that just dropped out keeps a sensible number until the wizard moves on.
        assertEquals(3 to 5, SetupFlow.position(SetupStep.SYNC_LINK, plain))
    }

    @Test fun skippableSteps() {
        assertEquals(
            setOf(SetupStep.SYNC_LINK, SetupStep.IPTV_OFFER, SetupStep.ACCOUNT, SetupStep.PROFILE, SetupStep.BOUQUETS),
            SetupStep.entries.filter { it.optional }.toSet(),
        )
        assertFalse(SetupStep.RECEIVER.optional)
        assertFalse(SetupStep.WELCOME.optional)
    }

    @Test fun onlyFreshInstallsSeeTheWizard() {
        assertTrue(SetupStep.showOnStart(0))
        assertFalse(SetupStep.showOnStart(1))
    }

    @Test fun startGate() {
        assertEquals(Start.SETUP, SetupStep.startScreen(receiverCount = 0, completed = false))
        assertEquals(Start.CONNECTION, SetupStep.startScreen(receiverCount = 0, completed = true))
        assertEquals(Start.DASHBOARD, SetupStep.startScreen(receiverCount = 1, completed = false))
        assertEquals(Start.DASHBOARD, SetupStep.startScreen(receiverCount = 2, completed = true))
        assertFalse(SetupStep.showOnStart(0, completed = true))
    }

    @Test fun updatePromptWaitsForSetup() {
        assertFalse(SetupStep.allowsUpdatePrompt(receiverCount = 0, completed = false, onSetupScreen = true))
        assertFalse(SetupStep.allowsUpdatePrompt(receiverCount = 1, completed = false, onSetupScreen = true))
        assertFalse(SetupStep.allowsUpdatePrompt(receiverCount = 1, completed = true, onSetupScreen = true))
        assertFalse(SetupStep.allowsUpdatePrompt(receiverCount = 0, completed = false, onSetupScreen = false))
        assertTrue(SetupStep.allowsUpdatePrompt(receiverCount = 1, completed = false, onSetupScreen = false))
        assertTrue(SetupStep.allowsUpdatePrompt(receiverCount = 0, completed = true, onSetupScreen = false))
    }
}
