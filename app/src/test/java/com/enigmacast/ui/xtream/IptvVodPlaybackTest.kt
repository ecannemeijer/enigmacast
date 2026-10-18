package com.enigmacast.ui.xtream

import com.enigmacast.domain.player.VodOsdKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvVodPlaybackTest {
    @Test fun resumeStoredLikeRecordings() {
        assertNull(IptvResume.toStore(30_000, 6_000_000))          // too early
        assertEquals(600_000L, IptvResume.toStore(600_000, 6_000_000))
        assertNull(IptvResume.toStore(5_950_000, 6_000_000))       // near the end: watched
        assertEquals(600_000L, IptvResume.toStore(600_000, 0))     // unknown length: keep
        assertNull(IptvResume.offer(0))
        assertEquals(90_000L, IptvResume.offer(90_000))
    }

    @Test fun holdingGoesFaster() {
        assertEquals(10_000L, VodOsdKeys.scrubStep(0))
        assertEquals(30_000L, VodOsdKeys.scrubStep(10))
        assertEquals(60_000L, VodOsdKeys.scrubStep(25))
    }
}
