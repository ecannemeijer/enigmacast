package com.enigmacast.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the 0f7e213 stream loop (transcoded start on the LAN, fallback that never took effect). */
class QualitySessionTest {
    private val key = "box|192.168.1.209:80"
    private val autoHome = QualityInputs(StreamProfile.AUTO, TranscodePreset.MEDIUM, transcodingSupported = true, remote = false)
    private val autoRemote = autoHome.copy(remote = true)

    @Test
    fun autoOnTheLanChoosesTheOriginalEvenWhenTheReceiverCanTranscode() {
        val session = QualitySession()
        assertEquals(StreamLevel.DIRECT, session.choose(key, autoHome))
        assertFalse(session.onStartFailed())
    }

    @Test
    fun aLevelReachedAwayIsNotCarriedIntoAHomeStream() {
        val session = QualitySession()
        assertEquals(StreamLevel.MEDIUM, session.choose(key, autoRemote))
        session.onPlaying()
        session.afterStalls(listOf(0L, 1L), 2L)
        assertEquals(StreamLevel.LOW, session.level)
        assertEquals(StreamLevel.DIRECT, session.choose(key, autoHome))
    }

    @Test
    fun aFailedTranscodedStartFallsBackOnceAndThenStaysOriginal() {
        val session = QualitySession()
        assertEquals(StreamLevel.MEDIUM, session.choose(key, autoRemote))
        assertTrue(session.onStartFailed())
        assertEquals(QualityReason.FALLBACK, session.reason)
        // The same inputs as before the failure: this used to keep the transcoded level and loop.
        assertEquals(StreamLevel.DIRECT, session.choose(key, autoRemote))
        assertFalse(session.onStartFailed())
        assertEquals(StreamLevel.DIRECT, session.choose(key, autoRemote))
        assertFalse(session.onStartFailed())
        assertTrue(session.transcoderFailed(key))
    }

    @Test
    fun anOriginalStreamThatFailsNeverAsksForAQualityRestart() {
        val session = QualitySession()
        session.choose(key, autoRemote.copy(profile = StreamProfile.DIRECT))
        assertFalse(session.onStartFailed())
    }

    @Test
    fun aTranscodedStreamThatPlayedIsNotBlamedOnTheTranscoder() {
        val session = QualitySession()
        session.choose(key, autoRemote)
        session.onPlaying()
        assertFalse(session.onStartFailed())
    }

    @Test
    fun aManualTranscodedChoiceIsDroppedAfterTheTranscoderFailed() {
        val session = QualitySession()
        session.setManual(StreamLevel.LOW)
        assertEquals(StreamLevel.LOW, session.choose(key, autoHome))
        assertTrue(session.onStartFailed())
        assertEquals(StreamLevel.DIRECT, session.choose(key, autoHome))
    }

    @Test
    fun withoutTranscodingSupportEveryProfileIsTheOriginal() {
        val session = QualitySession()
        val unsupported = autoRemote.copy(transcodingSupported = false)
        StreamProfile.entries.forEach { profile ->
            assertEquals(StreamLevel.DIRECT, session.choose(key, unsupported.copy(profile = profile)))
        }
    }
}
