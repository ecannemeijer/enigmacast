package com.enigmacast.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {
    private val autoHome = QualityInputs(StreamProfile.AUTO, TranscodePreset.MEDIUM, transcodingSupported = true, internet = false, metered = false)
    private val autoRemote = autoHome.copy(internet = true)

    @Test
    fun directAndUnsupportedAlwaysUseTheOriginal() {
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(autoRemote.copy(profile = StreamProfile.DIRECT)))
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(autoRemote.copy(transcodingSupported = false)))
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(autoRemote.copy(profile = StreamProfile.TRANSCODED, transcodingSupported = false)))
        assertFalse(StreamQualityPolicy.adaptive(autoRemote.copy(transcodingSupported = false)))
    }

    @Test
    fun transcodedUsesThePreset() {
        val inputs = autoHome.copy(profile = StreamProfile.TRANSCODED, preset = TranscodePreset.LOW)
        assertEquals(StreamLevel.LOW, StreamQualityPolicy.initial(inputs))
        assertNull(StreamQualityPolicy.afterStalls(inputs, StreamLevel.LOW, listOf(0L, 1L, 2L), 3L))
    }

    @Test
    fun autoIsOriginalAtHomeAndTranscodedRemotely() {
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(autoHome))
        assertEquals(StreamLevel.MEDIUM, StreamQualityPolicy.initial(autoRemote))
        assertEquals(StreamLevel.MEDIUM, StreamQualityPolicy.initial(autoHome.copy(metered = true)))
        assertEquals(StreamLevel.HIGH, StreamQualityPolicy.ceiling(autoRemote))
        assertEquals(StreamLevel.LOW, StreamQualityPolicy.initial(autoRemote, lastGood = StreamLevel.LOW))
        assertEquals(StreamLevel.HIGH, StreamQualityPolicy.initial(autoRemote, lastGood = StreamLevel.DIRECT))
    }

    @Test
    fun twoStallsWithinAMinuteStepDown() {
        val now = 100_000L
        assertNull(StreamQualityPolicy.afterStalls(autoHome, StreamLevel.DIRECT, listOf(now - 5_000), now))
        assertNull(StreamQualityPolicy.afterStalls(autoHome, StreamLevel.DIRECT, listOf(now - 90_000, now - 1_000), now))
        assertEquals(StreamLevel.HIGH, StreamQualityPolicy.afterStalls(autoHome, StreamLevel.DIRECT, listOf(now - 30_000, now - 1_000), now))
        assertEquals(StreamLevel.LOW, StreamQualityPolicy.afterStalls(autoRemote, StreamLevel.MEDIUM, listOf(now, now), now))
        assertNull(StreamQualityPolicy.afterStalls(autoRemote, StreamLevel.LOW, listOf(now, now), now))
    }

    @Test
    fun aLongStableStretchStepsUpToTheCeilingOnly() {
        val stable = StreamQualityPolicy.STABLE_TO_STEP_UP_MS
        assertNull(StreamQualityPolicy.afterStable(autoRemote, StreamLevel.LOW, stable - 1))
        assertEquals(StreamLevel.MEDIUM, StreamQualityPolicy.afterStable(autoRemote, StreamLevel.LOW, stable))
        assertNull(StreamQualityPolicy.afterStable(autoRemote, StreamLevel.HIGH, stable))
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.afterStable(autoHome, StreamLevel.HIGH, stable))
    }

    @Test
    fun transcodedUrlsFollowTheImageStyle() {
        assertEquals('&', TranscodeUrls.separator("openpli"))
        assertEquals('?', TranscodeUrls.separator("openatv"))
        assertEquals(
            "http://box:8002/1%3A0%3A19%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A?bitrate=1200000&width=1024&height=576",
            TranscodeUrls.build("box", 8002, "1%3A0%3A19%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A", TranscodePreset.MEDIUM, '&'),
        )
        assertTrue(TranscodeUrls.build("box", 8001, "ref", TranscodePreset.HIGH, '?').endsWith("ref?bitrate=2500000?width=1280?height=720"))
    }
}
