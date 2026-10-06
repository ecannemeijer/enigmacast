package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStartCheckTest {
    private fun check(
        elapsedMs: Long = 1_000,
        bytes: Long = 0,
        firstFrame: Boolean = false,
        hasVideo: Boolean = true,
        audioPlaying: Boolean = false,
        failure: StreamFailure = StreamFailure.NONE,
    ) = StreamStartCheck.evaluate(elapsedMs, bytes, firstFrame, hasVideo, audioPlaying, failure)

    @Test
    fun receiverClosingAnEmptyStreamMeansNoTuner() {
        assertEquals(StreamHealth.NO_TUNER, check(failure = StreamFailure.PARSE, bytes = 0))
        assertEquals(StreamHealth.NO_TUNER, check(failure = StreamFailure.ENDED, bytes = 24))
    }

    @Test
    fun silenceForTooLongMeansNoTuner() {
        assertEquals(StreamHealth.STARTING, check(elapsedMs = 3_000))
        assertEquals(StreamHealth.NO_TUNER, check(elapsedMs = StreamStartCheck.NO_DATA_TIMEOUT_MS, bytes = 1_000))
    }

    @Test
    fun firstFrameOrPlayingRadioMeansPlaying() {
        assertEquals(StreamHealth.PLAYING, check(firstFrame = true, bytes = 500_000))
        assertEquals(StreamHealth.PLAYING, check(hasVideo = false, audioPlaying = true, bytes = 100_000))
    }

    @Test
    fun loginAndConnectionProblemsAreReportedAsSuch() {
        assertEquals(StreamHealth.AUTH_REQUIRED, check(failure = StreamFailure.HTTP_401))
        assertEquals(StreamHealth.UNREACHABLE, check(failure = StreamFailure.CONNECT))
        assertEquals(StreamHealth.FAILED, check(failure = StreamFailure.HTTP_OTHER))
    }

    @Test
    fun dataWithoutPictureIsReportedAfterAWhile() {
        val bytes = 2_000_000L
        assertEquals(StreamHealth.STARTING, check(elapsedMs = 9_000, bytes = bytes))
        assertEquals(StreamHealth.NO_PICTURE, check(elapsedMs = StreamStartCheck.NO_PICTURE_TIMEOUT_MS, bytes = bytes))
        assertEquals(StreamHealth.FAILED, check(failure = StreamFailure.ENDED, bytes = bytes))
    }
}
