package com.enigmacast.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStepTest {
    @Test fun order() {
        assertEquals(SetupStep.RECEIVER, SetupStep.next(SetupStep.WELCOME))
        assertEquals(SetupStep.PROFILE, SetupStep.next(SetupStep.RECEIVER))
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
}
