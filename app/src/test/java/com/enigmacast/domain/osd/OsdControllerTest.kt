package com.enigmacast.domain.osd

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.OsdSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OsdControllerTest {
    @Test
    fun unchangedGrabDoesNotReplaceTheFrame() = runTest {
        val source = FakeOsdSource()
        val controller = OsdController(
            source = source,
            analyzer = OsdAnalyzer { bytes ->
                OsdAnalysis(OsdHasher.crc32(bytes), 100, 100, isEmpty = false)
            },
            configProvider = { ReceiverConfig(host = "192.168.1.20", name = "Box") },
            osdConfigProvider = { OsdConfig(idlePollMs = 1_000, hiddenPollMs = 1_000, slowPollMs = 1_000) },
            now = { 0L },
        )
        val job = controller.start(this)
        advanceTimeBy(1)
        val first = controller.update.value
        requireNotNull(first)
        source.bytes = byteArrayOf(1, 2, 3)
        advanceTimeBy(1_100)
        assertEquals(first.hash, controller.update.value?.hash)
        source.bytes = byteArrayOf(9, 9, 9)
        advanceTimeBy(1_100)
        assertNotEquals(first.hash, controller.update.value?.hash)
        job.cancel()
    }

    private class FakeOsdSource : OsdSource {
        var bytes = byteArrayOf(1, 2, 3)
        override suspend fun grab(
            config: ReceiverConfig,
            format: GrabFormat,
            mode: GrabMode,
        ): GrabbedImage {
            return GrabbedImage(bytes.copyOf(), "image/png", 200, 12)
        }
    }
}
