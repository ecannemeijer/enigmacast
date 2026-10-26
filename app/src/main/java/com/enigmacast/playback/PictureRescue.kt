package com.enigmacast.playback

import com.enigmacast.domain.osdinfo.Scrambling
import com.enigmacast.domain.tuner.StreamHealth

/** What a one-time retry changes when a stream delivers data but never shows a frame. */
enum class RescueKind { NEXT_DECODER, TEXTURE_VIEW }

/**
 * Data arrives and a video track is there, but no frame came out of the decoder (NO_PICTURE).
 * That is often the decoder or the surface rather than the stream, so the player gets one more
 * try per channel: the next decoder in the list (e.g. the software one), or a TextureView when
 * there is only one decoder. Scrambled streams are left alone: no decoder can show those.
 */
object PictureRescue {
    fun shouldRetry(
        health: StreamHealth,
        bytesLoaded: Long,
        minBytes: Long,
        hasVideoTrack: Boolean,
        scrambling: Scrambling,
        alreadyTried: Boolean,
        firstFrame: Boolean,
    ): Boolean = health == StreamHealth.NO_PICTURE &&
        !alreadyTried &&
        !firstFrame &&
        hasVideoTrack &&
        scrambling != Scrambling.SCRAMBLED &&
        bytesLoaded >= minBytes

    /** Null when there is nothing else to try. */
    fun plan(videoDecoders: Int, surfaceView: Boolean, knownGarbler: Boolean): RescueKind? = when {
        videoDecoders > 1 -> RescueKind.NEXT_DECODER
        surfaceView && !knownGarbler -> RescueKind.TEXTURE_VIEW
        else -> null
    }

    /** The first choice moves to the end, so the next decoder is tried first. */
    fun <T> rotate(order: List<T>): List<T> = if (order.size < 2) order else order.drop(1) + order.first()
}

/**
 * READY and playing, but nothing moves: the position (or, with video, the rendered frame count)
 * does not advance, the load control stopped loading and less than [BufferPolicy.STARVED_MS] is
 * buffered (its byte limit is full of data it cannot play). ExoPlayer does not leave that state
 * by itself; [StarvedBuffer] only covers BUFFERING.
 */
class FrozenPlayback(private val afterMs: Long) {
    private var since = -1L
    private var lastPosition = Long.MIN_VALUE
    private var lastFrames = -1L

    fun update(
        ready: Boolean,
        loading: Boolean,
        bufferedMs: Long,
        positionMs: Long,
        renderedFrames: Long,
        expectVideo: Boolean,
        nowMs: Long,
    ): Boolean {
        val positionMoved = lastPosition != Long.MIN_VALUE && positionMs != lastPosition
        val framesMoved = lastFrames >= 0 && renderedFrames > lastFrames
        val first = lastPosition == Long.MIN_VALUE
        lastPosition = positionMs
        lastFrames = renderedFrames
        val progress = positionMoved && (!expectVideo || framesMoved)
        val frozen = ready && !loading && bufferedMs < BufferPolicy.STARVED_MS && !first && !progress
        if (!frozen) {
            since = -1L
            return false
        }
        if (since < 0) since = nowMs
        if (nowMs - since < afterMs) return false
        since = -1L
        return true
    }

    fun reset() {
        since = -1L
        lastPosition = Long.MIN_VALUE
        lastFrames = -1L
    }
}
