package com.enigmacast.ui.xtream

import com.enigmacast.domain.player.VodOsdKeys
import com.enigmacast.domain.torrent.ResumeSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvVodPlaybackTest {
    @Test fun resumeKeptFromTheFirstSecond() {
        assertEquals(ResumeSave.KEEP, IptvResume.action(1_000, 6_000_000))       // 1 s: in Continue watching
        assertEquals(ResumeSave.KEEP, IptvResume.action(30_000, 6_000_000))
        assertEquals(ResumeSave.KEEP, IptvResume.action(600_000, 6_000_000))
        assertEquals(ResumeSave.CLEAR, IptvResume.action(5_950_000, 6_000_000))  // near the end: watched
        assertEquals(ResumeSave.KEEP, IptvResume.action(600_000, 0))             // unknown length: keep
        // A stop at 0 (or a stream that never started) leaves the saved row alone.
        assertEquals(ResumeSave.NONE, IptvResume.action(0, 6_000_000))
        assertEquals(ResumeSave.NONE, IptvResume.action(400, 0))
        assertNull(IptvResume.offer(0))
        assertNull(IptvResume.offer(30_000))                                     // kept, but no question
        assertEquals(90_000L, IptvResume.offer(90_000))
    }

    @Test fun holdingGoesFaster() {
        assertEquals(10_000L, VodOsdKeys.scrubStep(0))
        assertEquals(30_000L, VodOsdKeys.scrubStep(10))
        assertEquals(60_000L, VodOsdKeys.scrubStep(25))
    }
}
