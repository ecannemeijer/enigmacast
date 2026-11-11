package com.enigmacast.domain.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvConnectionLimitTest {
    @Test fun unknownMaxNeverWarns() {
        assertFalse(IptvConnectionLimit.wouldExceed(null, used = 2, extra = 1))
        assertFalse(IptvConnectionLimit.wouldExceed(0, used = 2, extra = 1))
    }

    @Test fun warnsWhenANewCaptureWouldPassTheMax() {
        assertFalse(IptvConnectionLimit.wouldExceed(2, used = 1, extra = 1))
        assertTrue(IptvConnectionLimit.wouldExceed(2, used = 2, extra = 1))
        assertTrue(IptvConnectionLimit.wouldExceed(1, used = 1, extra = 1))
    }

    @Test fun prefersProviderActiveCons() {
        assertEquals(3, IptvConnectionLimit.used(activeCons = 3, watching = 1, recording = 1))
        assertEquals(2, IptvConnectionLimit.used(activeCons = null, watching = 1, recording = 1))
        assertEquals(0, IptvConnectionLimit.used(activeCons = null, watching = 0, recording = 0))
    }
}
