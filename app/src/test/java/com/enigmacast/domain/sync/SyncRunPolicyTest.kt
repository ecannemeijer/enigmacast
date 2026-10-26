package com.enigmacast.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRunPolicyTest {
    @Test
    fun pauseDoublesAfterEachIncompleteRunUpToHalfAnHour() {
        assertEquals(0L, SyncRunPolicy.pauseMs(0))
        assertEquals(60_000L, SyncRunPolicy.pauseMs(1))
        assertEquals(120_000L, SyncRunPolicy.pauseMs(2))
        assertEquals(240_000L, SyncRunPolicy.pauseMs(3))
        assertEquals(SyncRunPolicy.MAX_PAUSE_MS, SyncRunPolicy.pauseMs(10))
        assertEquals(SyncRunPolicy.MAX_PAUSE_MS, SyncRunPolicy.pauseMs(100))
    }

    @Test
    fun backgroundPullsWaitButForegroundAlwaysPulls() {
        assertTrue(SyncRunPolicy.pullAllowed(1_000, 0, 0, foreground = false))
        assertFalse(SyncRunPolicy.pullAllowed(30_000, 0, 1, foreground = false))
        assertTrue(SyncRunPolicy.pullAllowed(60_000, 0, 1, foreground = false))
        assertTrue(SyncRunPolicy.pullAllowed(30_000, 0, 5, foreground = true))
    }
}
