package com.enigmacast.playback

import com.enigmacast.playback.ZapTimeline.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapTimelineTest {
    private var now = 0L
    private val timeline = ZapTimeline { now }

    @Test
    fun oneLineWithTheMillisecondsOfEachStep() {
        assertNull(timeline.begin("zap"))
        now = 300; assertNull(timeline.mark(Step.OWIF))
        now = 302; timeline.mark(Step.PREPARE)
        now = 400; timeline.mark(Step.CONNECT)
        now = 410; timeline.mark(Step.BYTES)
        now = 600; timeline.mark(Step.TRACKS, video = true)
        now = 900; assertNull(timeline.mark(Step.READY))
        timeline.note("buffer", "fast-start")
        now = 950
        val line = timeline.mark(Step.FRAME)
        assertEquals(
            "zap: reason=zap owif=300 prepare=2 connect=98 bytes=10 tracks=190 ready=300 frame=50 total=950 ms buffer=fast-start",
            line,
        )
        assertFalse(timeline.running())
    }

    @Test
    fun aStepIsTimedOnceAndMissingStepsShowADash() {
        timeline.begin("retry")
        now = 100; timeline.mark(Step.PREPARE)
        now = 500; timeline.mark(Step.PREPARE)
        now = 700
        assertEquals(
            "zap: reason=retry owif=- prepare=100 connect=- bytes=- tracks=- ready=- frame=600 total=700 ms",
            timeline.mark(Step.FRAME),
        )
    }

    @Test
    fun radioFinishesWhenReadyWithoutAFrame() {
        timeline.begin("zap")
        now = 50; timeline.mark(Step.TRACKS, video = false)
        now = 80
        val line = timeline.mark(Step.READY)!!
        assertTrue(line, line.endsWith("total=80 ms video=none"))
    }

    @Test
    fun aZapReplacedOrStoppedBeforeItsPictureIsReported() {
        timeline.begin("zap")
        now = 200; timeline.mark(Step.OWIF)
        now = 250
        val replaced = timeline.begin("zap")!!
        assertTrue(replaced, replaced.endsWith("total=250 ms unfinished=superseded"))
        now = 300
        assertTrue(timeline.abandon("stopped")!!.endsWith("unfinished=stopped"))
        assertNull(timeline.abandon("stopped"))
        assertNull(timeline.mark(Step.FRAME))
    }

    @Test
    fun nothingIsMarkedOutsideAZap() {
        assertNull(timeline.mark(Step.FRAME))
        timeline.note("wait", "0ms")
        assertFalse(timeline.running())
    }
}
