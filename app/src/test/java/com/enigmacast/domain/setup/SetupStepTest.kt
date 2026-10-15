package com.enigmacast.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStepTest {
    @Test fun order() {
        assertEquals(SetupStep.ACCOUNT, SetupStep.next(SetupStep.WELCOME))
        assertEquals(SetupStep.RECEIVER, SetupStep.next(SetupStep.ACCOUNT))
        assertEquals(SetupStep.SYNC_LINK, SetupStep.next(SetupStep.RECEIVER))
        assertEquals(SetupStep.PROFILE, SetupStep.next(SetupStep.SYNC_LINK))
        assertEquals(SetupStep.DONE, SetupStep.next(SetupStep.DONE))
        assertNull(SetupStep.previous(SetupStep.WELCOME))
        assertEquals(SetupStep.SYNC, SetupStep.previous(SetupStep.SOCCER))
    }

    @Test fun onlyOptionalStepsCanBeSkipped() {
        assertEquals(setOf(SetupStep.PROFILE, SetupStep.BOUQUETS, SetupStep.SOCCER), SetupStep.entries.filter { it.optional }.toSet())
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
}
