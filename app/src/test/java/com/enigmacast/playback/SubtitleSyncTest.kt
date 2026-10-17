package com.enigmacast.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSyncTest {
    @Test
    fun positiveDelayLooksUpAnEarlierPositionSoLinesStartLater() {
        assertEquals(1_800_000L, SubtitleSync.shiftPositionUs(2_000_000L, 200))
    }

    @Test
    fun negativeDelayLooksAheadSoLinesStartEarlier() {
        assertEquals(2_200_000L, SubtitleSync.shiftPositionUs(2_000_000L, -200))
    }

    @Test
    fun shiftNeverGoesBeforeTheStart() {
        assertEquals(0L, SubtitleSync.shiftPositionUs(50_000L, 200))
        assertEquals(50_000L, SubtitleSync.shiftPositionUs(50_000L, 0))
    }

    @Test
    fun clampKeepsTheOfferedRange() {
        assertEquals(0, SubtitleSync.clamp(0))
        assertEquals(SubtitleSync.MAX_MS, SubtitleSync.clamp(9_000))
        assertEquals(SubtitleSync.MIN_MS, SubtitleSync.clamp(-9_000))
    }
}
