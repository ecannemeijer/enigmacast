package com.enigmacast.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {
    private val autoHome = QualityInputs(StreamProfile.AUTO, TranscodePreset.MEDIUM, transcodingSupported = true, remote = false)
    private val autoRemote = autoHome.copy(remote = true)

    @Test
    fun directAndUnsupportedAlwaysUseTheOriginal() {
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(autoRemote.copy(profile = StreamProfile.DIRECT)))
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(autoRemote.copy(transcodingSupported = false)))
        assertFalse(StreamQualityPolicy.adaptive(autoRemote.copy(transcodingSupported = false)))
    }

    @Test
    fun transcodedIsForcedAtHomeEvenWhenTheReceiverDoesNotReportTranscoding() {
        val transcodedHome = autoHome.copy(profile = StreamProfile.TRANSCODED, transcodingSupported = false)
        assertEquals(StreamLevel.MEDIUM, StreamQualityPolicy.initial(transcodedHome))
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(transcodedHome.copy(transcoderFailed = true)))
        assertEquals(StreamLevel.DIRECT, StreamQualityPolicy.initial(autoRemote.copy(transcoderFailed = true)))
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
        assertFalse(StreamQualityPolicy.adaptive(autoHome))
        assertEquals(StreamLevel.HIGH, StreamQualityPolicy.ceiling(autoRemote))
        assertEquals(StreamLevel.LOW, StreamQualityPolicy.initial(autoRemote, lastGood = StreamLevel.LOW))
        assertEquals(StreamLevel.HIGH, StreamQualityPolicy.initial(autoRemote, lastGood = StreamLevel.DIRECT))
    }

    @Test
    fun twoStallsWithinAMinuteStepDown() {
        val now = 100_000L
        assertNull(StreamQualityPolicy.afterStalls(autoHome, StreamLevel.DIRECT, listOf(now - 5_000), now))
        assertNull(StreamQualityPolicy.afterStalls(autoHome, StreamLevel.DIRECT, listOf(now - 90_000, now - 1_000), now))
        // At home Auto never steps down into transcoding, however much it stalls.
        assertNull(StreamQualityPolicy.afterStalls(autoHome, StreamLevel.DIRECT, listOf(now - 30_000, now - 1_000), now))
        assertEquals(StreamLevel.LOW, StreamQualityPolicy.afterStalls(autoRemote, StreamLevel.MEDIUM, listOf(now - 30_000, now - 1_000), now))
        assertEquals(StreamLevel.LOW, StreamQualityPolicy.afterStalls(autoRemote, StreamLevel.MEDIUM, listOf(now, now), now))
        assertNull(StreamQualityPolicy.afterStalls(autoRemote, StreamLevel.LOW, listOf(now, now), now))
    }

    @Test
    fun aLongStableStretchStepsUpToTheCeilingOnly() {
        val stable = StreamQualityPolicy.STABLE_TO_STEP_UP_MS
        assertNull(StreamQualityPolicy.afterStable(autoRemote, StreamLevel.LOW, stable - 1))
        assertEquals(StreamLevel.MEDIUM, StreamQualityPolicy.afterStable(autoRemote, StreamLevel.LOW, stable))
        assertNull(StreamQualityPolicy.afterStable(autoRemote, StreamLevel.HIGH, stable))
        assertNull(StreamQualityPolicy.afterStable(autoHome, StreamLevel.HIGH, stable))
    }

    @Test
    fun transcodedUrlsUseOneQuestionMarkAndAmpersands() {
        assertEquals(
            "http://box:8002/1%3A0%3A19%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A?bitrate=1200000&width=1024&height=576",
            TranscodeUrls.build("box", 8002, "1%3A0%3A19%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A", TranscodePreset.MEDIUM),
        )
        TranscodePreset.entries.forEach { preset ->
            val url = TranscodeUrls.build("box", 8001, "ref", preset)
            assertEquals(1, url.count { it == '?' })
            assertTrue(url.endsWith("ref?bitrate=${preset.bitrateKbps * 1000}&width=${preset.width}&height=${preset.height}"))
        }
    }

    @Test
    fun detectsThePortFromOpenWebifsPhonePlaylist() {
        // Broadcom box with the TranscodingSetup plugin on 8002 (OpenATV style, repeated '?').
        val broadcom = "#EXTM3U \n#EXTVLCOPT:http-reconnect=true \nhttp://192.168.1.209:8002/1:0:19:1B6C:C88:3:EB0000:0:0:0:?bitrate=1000000?width=1280?height=720?aspectratio=2?interlaced=0\n"
        assertEquals(TranscodeDetection(8002, transcodes = true), TranscodeUrls.detect(broadcom))
        // HiSilicon box: the encoder sits behind the normal stream port; with stream authentication on.
        val hisilicon = "#EXTM3U\n#EXTVLCOPT:http-reconnect=true\nhttp://root:secret@192.168.1.209:8001/1:0:19:1B6C:C88:3:EB0000:0:0:0:?bitrate=1000000?width=1280?height=720\n"
        assertEquals(TranscodeDetection(8001, transcodes = true), TranscodeUrls.detect(hisilicon))
        val pli = "#EXTM3U\nhttp://192.168.1.209:8001/1:0:19:1B6C:C88:3:EB0000:0:0:0:?bitrate=1000000&width=1280&height=720\n"
        assertEquals(TranscodeDetection(8001, transcodes = true), TranscodeUrls.detect(pli))
        // No encoder: OpenWebif writes the plain stream URL.
        assertEquals(TranscodeDetection(8001, transcodes = false), TranscodeUrls.detect("#EXTM3U\nhttp://box:8001/1:0:1:0:0:0:0:0:0:0:\n"))
        assertEquals(null, TranscodeUrls.detect("<html>login</html>"))
    }
}
