package com.enigmacast.domain.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvRecordTimesTest {
    @Test fun futureProgrammeAppliesMargins() {
        val (begin, end) = IptvRecordTimes.window(1_000, 1_600, marginBeforeSec = 60, marginAfterSec = 120, nowSec = 500)
        assertEquals(940L, begin)
        assertEquals(1_720L, end)
    }

    @Test fun currentProgrammeStartsNowAndKeepsTheEndMargin() {
        val (begin, end) = IptvRecordTimes.window(1_000, 1_600, marginBeforeSec = 60, marginAfterSec = 120, nowSec = 1_100)
        assertEquals(1_100L, begin)
        assertEquals(1_720L, end)
    }

    @Test fun windowIsAtLeastOneMinute() {
        val (begin, end) = IptvRecordTimes.window(1_000, 1_010, 0, 0, nowSec = 1_000)
        assertEquals(1_000L, begin)
        assertEquals(1_060L, end)
    }
}
