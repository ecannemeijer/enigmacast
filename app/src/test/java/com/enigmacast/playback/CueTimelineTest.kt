package com.enigmacast.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CueTimelineTest {
    private fun e(start: Long, end: Long, text: String) = CueTimeline.Entry(start, end, listOf(text))

    @Test
    fun findsTheCueAtThePosition() {
        val t = CueTimeline(listOf(e(3_000_000, 4_000_000, "b"), e(1_000_000, 2_000_000, "a")))
        assertEquals(emptyList<String>(), t.at(0))
        assertEquals(listOf("a"), t.at(1_000_000))
        assertEquals(emptyList<String>(), t.at(2_000_000))
        assertEquals(listOf("b"), t.at(3_500_000))
        assertEquals(emptyList<String>(), t.at(9_000_000))
    }

    @Test
    fun overlappingLinesShowTogether() {
        val t = CueTimeline(listOf(e(1_000_000, 5_000_000, "top"), e(2_000_000, 3_000_000, "bottom")))
        assertEquals(listOf("top", "bottom"), t.at(2_500_000))
        assertEquals(listOf("top"), t.at(4_000_000))
    }

    @Test
    fun openEndedCuesLastUntilTheNext() {
        val closed = CueTimeline.close(listOf(e(1_000_000, Long.MAX_VALUE, "a"), e(4_000_000, Long.MAX_VALUE, "b")))
        assertEquals(4_000_000L, closed[0].endUs)
        assertEquals(9_000_000L, closed[1].endUs)
    }

    @Test
    fun theSubtitleDelayShowsLinesLater() {
        val t = CueTimeline(listOf(e(1_000_000, 2_000_000, "a")))
        assertEquals(1_500_000L, SubtitleSync.shiftPositionUs(2_000_000, 500))
        assertEquals(listOf("a"), t.at(SubtitleSync.shiftPositionUs(2_000_000, 500)))
    }
}
