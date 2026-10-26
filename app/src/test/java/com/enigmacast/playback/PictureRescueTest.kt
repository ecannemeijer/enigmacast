package com.enigmacast.playback

import com.enigmacast.domain.osdinfo.Scrambling
import com.enigmacast.domain.tuner.StreamHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureRescueTest {
    private fun retry(
        health: StreamHealth = StreamHealth.NO_PICTURE,
        bytes: Long = 7_240_444,
        video: Boolean = true,
        scrambling: Scrambling = Scrambling.CLEAR,
        tried: Boolean = false,
        frame: Boolean = false,
    ) = PictureRescue.shouldRetry(health, bytes, 64 * 1024, video, scrambling, tried, frame)

    @Test
    fun noPictureWithDataIsRetriedOnce() {
        assertTrue(retry())
        assertTrue(retry(scrambling = Scrambling.UNKNOWN))
        assertFalse(retry(tried = true))
    }

    @Test
    fun otherCasesAreNotRetried() {
        assertFalse(retry(health = StreamHealth.STARTING))
        assertFalse(retry(health = StreamHealth.NO_TUNER))
        assertFalse(retry(bytes = 1_000))
        assertFalse(retry(video = false))
        assertFalse(retry(scrambling = Scrambling.SCRAMBLED))
        assertFalse(retry(frame = true))
    }

    @Test
    fun nextDecoderFirstThenTextureView() {
        assertEquals(RescueKind.NEXT_DECODER, PictureRescue.plan(videoDecoders = 2, surfaceView = true, knownGarbler = false))
        assertEquals(RescueKind.NEXT_DECODER, PictureRescue.plan(2, surfaceView = true, knownGarbler = true))
        assertEquals(RescueKind.TEXTURE_VIEW, PictureRescue.plan(1, surfaceView = true, knownGarbler = false))
        // Amlogic garbles a TextureView, and a TextureView user has nothing else to try.
        assertNull(PictureRescue.plan(1, surfaceView = true, knownGarbler = true))
        assertNull(PictureRescue.plan(1, surfaceView = false, knownGarbler = false))
        assertNull(PictureRescue.plan(0, surfaceView = false, knownGarbler = false))
    }

    @Test
    fun rotateMovesTheFirstChoiceLast() {
        assertEquals(listOf("c2.android.avc", "c2.goldfish.h264"), PictureRescue.rotate(listOf("c2.goldfish.h264", "c2.android.avc")))
        assertEquals(listOf("b", "c", "a"), PictureRescue.rotate(listOf("a", "b", "c")))
        assertEquals(listOf("a"), PictureRescue.rotate(listOf("a")))
        assertEquals(emptyList<String>(), PictureRescue.rotate(emptyList<String>()))
    }
}

class FrozenPlaybackTest {
    @Test
    fun readyWithoutProgressAndNotLoadingRecoversAfterTheDelay() {
        val frozen = FrozenPlayback(afterMs = 6_000)
        assertFalse(frozen.update(true, false, 200, 1_000, 10, true, 0))
        assertFalse(frozen.update(true, false, 200, 1_000, 10, true, 1_000))
        assertFalse(frozen.update(true, false, 200, 1_000, 10, true, 6_000))
        assertTrue(frozen.update(true, false, 200, 1_000, 10, true, 7_000))
    }

    @Test
    fun audioMovingButNoFramesCountsAsFrozenForVideo() {
        val frozen = FrozenPlayback(afterMs = 2_000)
        var position = 0L
        assertFalse(frozen.update(true, false, 100, position, 5, true, 0))
        for (t in 1..2) {
            position += 1_000
            assertFalse(frozen.update(true, false, 100, position, 5, true, t * 1_000L - 1_000 + 1_000))
        }
        position += 1_000
        assertTrue(frozen.update(true, false, 100, position, 5, true, 4_000))
    }

    @Test
    fun progressLoadingBufferOrBufferingStateKeepItCalm() {
        val frozen = FrozenPlayback(afterMs = 1_000)
        var t = 0L
        var position = 0L
        var frames = 0L
        frozen.update(true, false, 100, position, frames, true, t)
        repeat(5) {
            t += 1_000; position += 1_000; frames += 25
            assertFalse(frozen.update(true, false, 100, position, frames, true, t))
        }
        repeat(5) { t += 1_000; assertFalse(frozen.update(true, true, 100, position, frames, true, t)) }
        repeat(5) { t += 1_000; assertFalse(frozen.update(true, false, 5_000, position, frames, true, t)) }
        repeat(5) { t += 1_000; assertFalse(frozen.update(false, false, 100, position, frames, true, t)) }
    }

    @Test
    fun radioOnlyNeedsThePositionToMove() {
        val frozen = FrozenPlayback(afterMs = 1_000)
        var position = 0L
        frozen.update(true, false, 100, position, 0, false, 0)
        for (t in 1..5) {
            position += 1_000
            assertFalse(frozen.update(true, false, 100, position, 0, false, t * 1_000L))
        }
    }
}
