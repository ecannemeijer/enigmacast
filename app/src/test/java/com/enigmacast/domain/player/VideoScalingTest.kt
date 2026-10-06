package com.enigmacast.domain.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoScalingTest {
    private fun scale(mode: PictureMode, w: Int, h: Int, ratio: Float = 1f) = VideoScaling.scale(mode, w, h, ratio, 1920, 1080)

    @Test
    fun autoKeepsHdFullScreen() {
        assertEquals(VideoScale(1f, 1f), scale(PictureMode.AUTO, 1920, 1080))
    }

    @Test
    fun autoPillarboxesFourByThree() {
        val result = scale(PictureMode.AUTO, 720, 576, 16f / 15f) // 4:3 PAL
        assertEquals(0.75f, result.x, 0.01f)
        assertEquals(1f, result.y, 0.0001f)
    }

    @Test
    fun autoUsesThePixelShapeOfAnamorphicSd() {
        val result = scale(PictureMode.AUTO, 720, 576, 64f / 45f) // 16:9 PAL
        assertEquals(1f, result.x, 0.01f)
        assertEquals(1f, result.y, 0.01f)
    }

    @Test
    fun forcedShapesIgnoreTheStream() {
        assertEquals(0.75f, scale(PictureMode.NORMAL, 1920, 1080).x, 0.0001f)
        assertEquals(VideoScale(1f, 1f), scale(PictureMode.WIDE, 720, 576, 16f / 15f))
    }

    @Test
    fun zoomFillsAndCropsWhileStretchFillsExactly() {
        val zoom = scale(PictureMode.ZOOM, 720, 576, 16f / 15f)
        assertEquals(1f, zoom.x, 0.0001f)
        assertEquals(1.333f, zoom.y, 0.01f)
        val cinema = scale(PictureMode.ZOOM, 1920, 800)
        assertEquals(1.35f, cinema.x, 0.01f)
        assertEquals(VideoScale(1f, 1f), scale(PictureMode.STRETCH, 720, 576, 16f / 15f))
    }

    @Test
    fun unknownSizesLeaveThePictureAlone() {
        assertEquals(VideoScale(1f, 1f), scale(PictureMode.AUTO, 0, 0))
        assertEquals(VideoScale(1f, 1f), VideoScaling.scale(PictureMode.ZOOM, 720, 576, 1f, 0, 0))
    }
}
