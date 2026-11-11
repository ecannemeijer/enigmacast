package com.enigmacast.domain.xtream

import com.enigmacast.domain.model.IptvRefreshInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvRefreshTest {
    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun defaultDayIsDueAfterTwentyFourHours() {
        assertFalse(IptvRefresh.isDue(IptvRefreshInterval.EVERY_24H, now - 23 * hour, now))
        assertTrue(IptvRefresh.isDue(IptvRefreshInterval.EVERY_24H, now - 24 * hour, now))
    }

    @Test
    fun sixAndFortyEightHourWindows() {
        assertFalse(IptvRefresh.isDue(IptvRefreshInterval.EVERY_6H, now - 5 * hour, now))
        assertTrue(IptvRefresh.isDue(IptvRefreshInterval.EVERY_6H, now - 6 * hour, now))
        assertFalse(IptvRefresh.isDue(IptvRefreshInterval.EVERY_48H, now - 47 * hour, now))
        assertTrue(IptvRefresh.isDue(IptvRefreshInterval.EVERY_48H, now - 48 * hour, now))
    }

    @Test
    fun manualOnlyNeverRunsOnATimer() {
        assertFalse(IptvRefresh.isDue(IptvRefreshInterval.MANUAL, 0L, now))
        assertFalse(IptvRefresh.isDue(IptvRefreshInterval.MANUAL, now - 100 * hour, now))
    }

    @Test
    fun armedOrCatchupIndexAlwaysRuns() {
        assertTrue(IptvRefresh.isDue(IptvRefreshInterval.MANUAL, now, now, armed = true))
        assertTrue(IptvRefresh.isDue(IptvRefreshInterval.EVERY_24H, now, now, indexCatchup = true))
    }
}
