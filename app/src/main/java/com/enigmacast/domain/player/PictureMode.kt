package com.enigmacast.domain.player

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** Within this relative difference, treat picture and view as the same shape (avoids a 1–2 px bar). */
    private const val NEAR_MATCH = 0.02f

    /**
     * Pixel aspect when the stream omitted SAR (Media3 reports 1.0). Classic SD coded sizes
     * are almost never square: 720×576 → 4:3 with PAR 16:15, 720×480 → 4:3 with PAR 8:9.
     * Anamorphic 16:9 SD carries SAR in the bitstream; when Media3 reports it we keep that.
     */
    fun effectivePixelRatio(videoWidth: Int, videoHeight: Int, pixelRatio: Float): Float {
        val reported = if (pixelRatio > 0f && pixelRatio.isFinite()) pixelRatio else 1f
        if (abs(reported - 1f) > 0.02f) return reported
        return when {
            videoHeight in 570..576 && videoWidth in 700..728 -> 16f / 15f
            videoHeight in 480..486 && videoWidth in 700..728 -> 8f / 9f
            else -> reported
        }
    }

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
        val ratio = effectivePixelRatio(videoWidth, videoHeight, pixelRatio)
        val pictureAspect = when (mode) {
            PictureMode.WIDE -> 16f / 9f
            PictureMode.NORMAL -> 4f / 3f
            else -> videoWidth * ratio / videoHeight
        }
        val viewAspect = viewWidth.toFloat() / viewHeight
        if (abs(pictureAspect - viewAspect) / viewAspect <= NEAR_MATCH) {
            return VideoScale(1f, 1f)
        }
        val fill = mode == PictureMode.ZOOM
        return if ((pictureAspect > viewAspect) != fill) {
            // Full width, bars top and bottom (or, when filling, full height and cut sides).
            VideoScale(1f, viewAspect / pictureAspect)
        } else {
            VideoScale(pictureAspect / viewAspect, 1f)
        }
    }

    /** SurfaceView layout sizes: round so a near-full scale does not leave a 1 px black edge. */
    fun surfaceSize(viewWidth: Int, viewHeight: Int, scale: VideoScale): Pair<Int, Int> {
        val w = (viewWidth * scale.x).roundToInt().coerceAtLeast(1).coerceAtMost(viewWidth)
        val h = (viewHeight * scale.y).roundToInt().coerceAtLeast(1).coerceAtMost(viewHeight)
        // ZOOM may enlarge past the view; keep the computed size for cropping by the frame.
        val zoomW = (viewWidth * scale.x).roundToInt().coerceAtLeast(1)
        val zoomH = (viewHeight * scale.y).roundToInt().coerceAtLeast(1)
        return if (scale.x > 1f || scale.y > 1f) zoomW to zoomH else w to h
    }
}
