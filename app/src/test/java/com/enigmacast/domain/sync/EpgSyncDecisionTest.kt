package com.enigmacast.domain.sync

import com.enigmacast.domain.sync.EpgSyncDecision.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSyncDecisionTest {
    private val h = 3600_000L
    private val now = 100 * h

    private fun d(force: Boolean = false, changed: Boolean = false, has: Boolean = true, perDay: Int = 2, success: Long = now - h, attempt: Long = now - h) =
        EpgSyncDecision.decide(force, changed, has, perDay, success, attempt, now)

    @Test fun startWithFreshRoomGuideDoesNotFetch() {
        assertEquals(Reason.NOT_DUE, d())
        assertFalse(EpgSyncDecision.runs(d()))
    }
    @Test fun dueAfterInterval() = assertEquals(Reason.DUE, d(success = now - 13 * h, attempt = now - 13 * h))
    @Test fun emptyRoomFetches() = assertTrue(EpgSyncDecision.runs(d(has = false)))
    @Test fun unfinishedDownloadWaitsInsteadOfRepeatingEveryStart() {
        assertEquals(Reason.RETRY_WAIT, d(success = now - 20 * h, attempt = now - 5 * 60_000))
        assertEquals(Reason.DUE, d(success = now - 20 * h, attempt = now - h))
    }
    @Test fun offOnlyWhenEmpty() {
        assertEquals(Reason.NOT_DUE, d(perDay = 0, success = now - 99 * h))
        assertEquals(Reason.EMPTY, d(perDay = 0, has = false, success = 0, attempt = 0))
    }
    @Test fun forcedAndChanged() {
        assertEquals(Reason.FORCED, d(force = true))
        assertEquals(Reason.CHANNELS_CHANGED, d(changed = true))
    }
}
