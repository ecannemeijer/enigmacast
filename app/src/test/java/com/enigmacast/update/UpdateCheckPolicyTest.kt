package com.enigmacast.update

import com.enigmacast.update.UpdateCheckPolicy.Reason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {
    private val min = 60_000L

    @Test fun theFirstCheckAlwaysRuns() {
        assertTrue(UpdateCheckPolicy.shouldCheck(Reason.START, 0, null))
        assertTrue(UpdateCheckPolicy.shouldCheck(Reason.FOREGROUND, 5, null))
    }

    @Test fun foregroundChecksAtMostEveryHalfHour() {
        assertFalse(UpdateCheckPolicy.shouldCheck(Reason.FOREGROUND, 29 * min, 0))
        assertTrue(UpdateCheckPolicy.shouldCheck(Reason.FOREGROUND, 30 * min, 0))
        assertTrue(UpdateCheckPolicy.shouldCheck(Reason.FOREGROUND, 5 * 60 * min, 0))
    }

    @Test fun periodicCheckRunsAfterFourHoursButNotRightAfterAForegroundOne() {
        assertTrue(UpdateCheckPolicy.shouldCheck(Reason.PERIODIC, UpdateCheckPolicy.PERIODIC_MS, 0))
        assertFalse(UpdateCheckPolicy.shouldCheck(Reason.PERIODIC, UpdateCheckPolicy.PERIODIC_MS, UpdateCheckPolicy.PERIODIC_MS - min))
    }

    private fun prompt(
        local: Int = 123,
        remote: Int = 124,
        hotel: Boolean = false,
        showing: Boolean = false,
        busy: Boolean = false,
        dismissed: Int? = null,
        dismissedAt: Long? = null,
        now: Long = 100 * min,
    ) = UpdateCheckPolicy.shouldPrompt(local, remote, hotel, showing, busy, dismissed, dismissedAt, now)

    @Test fun asksOnlyForANewerBuild() {
        assertTrue(prompt())
        assertFalse(prompt(remote = 123))
        assertFalse(prompt(remote = 122))
    }

    @Test fun neverInHotelModeOverAPromptOrWhileDownloading() {
        assertFalse(prompt(hotel = true))
        assertFalse(prompt(showing = true))
        assertFalse(prompt(busy = true))
    }

    @Test fun aClosedVersionWaitsHalfAnHour() {
        assertFalse(prompt(dismissed = 124, dismissedAt = 90 * min, now = 100 * min))
        assertTrue(prompt(dismissed = 124, dismissedAt = 70 * min, now = 100 * min))
    }

    @Test fun anEvenNewerVersionIsAskedRightAway() {
        assertTrue(prompt(remote = 125, dismissed = 124, dismissedAt = 99 * min, now = 100 * min))
    }
}
