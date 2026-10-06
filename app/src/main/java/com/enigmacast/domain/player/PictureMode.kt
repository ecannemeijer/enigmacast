package com.enigmacast.domain.player

import kotlinx.serialization.Serializable

/**
 * How the picture fills the screen. Chosen per channel in the quick options bar; a
 * channel without a choice uses [AUTO].
 */
@Serializable
enum class PictureMode {
    /** Whole picture, in the shape the stream says (including anamorphic SD), black bars where needed. */
    AUTO,

    /** Shown as 16:9, whatever the stream says. */
    WIDE,

    /** Shown as 4:3, whatever the stream says. */
    NORMAL,

    /** Fills the screen in the right shape; the edges that stick out are cut off. */
    ZOOM,

    /** Fills the screen exactly; the picture is stretched or squeezed. */
    STRETCH,
}

/** Horizontal and vertical scale of the video relative to the view it is drawn in. */
data class VideoScale(val x: Float, val y: Float)

/**
 * The video surface draws each frame stretched to the whole view. These factors shrink
 * or enlarge it around the centre so it gets the shape [PictureMode] asks for.
 */
object VideoScaling {
    fun scale(
        mode: PictureMode,
        videoWidth: Int,
        videoHeight: Int,
        pixelRatio: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): VideoScale {
        if (mode == PictureMode.STRETCH || videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return VideoScale(1f, 1f)
        }
        val ratio = if (pixelRatio > 0f && pixelRatio.isFinite()) pixelRatio else 1f
        val pictureAspect = when (mode) {
            PictureMode.WIDE -> 16f / 9f
            PictureMode.NORMAL -> 4f / 3f
            else -> videoWidth * ratio / videoHeight
        }
        val viewAspect = viewWidth.toFloat() / viewHeight
        val fill = mode == PictureMode.ZOOM
        return if ((pictureAspect > viewAspect) != fill) {
            // Full width, bars top and bottom (or, when filling, full height and cut sides).
            VideoScale(1f, viewAspect / pictureAspect)
        } else {
            VideoScale(pictureAspect / viewAspect, 1f)
        }
    }
}
