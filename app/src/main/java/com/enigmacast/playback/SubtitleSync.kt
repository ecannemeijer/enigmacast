package com.enigmacast.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * Subtitle delay in milliseconds. A positive value starts the subtitles later than the
 * picture; a negative value starts them earlier.
 */
internal object SubtitleSync {
    const val MIN_MS = -5_000
    const val MAX_MS = 5_000
    const val STEP_MS = 100

    fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)

    /**
     * The text renderer looks cues up at the playback position. Asking it for an earlier
     * position holds the line back, so a positive delay shows the subtitles later.
     */
    fun shiftPositionUs(positionUs: Long, delayMs: Int): Long {
        if (delayMs == 0 || positionUs < 0L) return positionUs
        return (positionUs - delayMs * 1_000L).coerceAtLeast(0L)
    }

    fun label(ms: Int): String = when {
        ms > 0 -> "+$ms ms"
        else -> "$ms ms"
    }
}

/**
 * The subtitle delay the running player reads. It lives outside the player so a change
 * applies on the next frame, without rebuilding the player or restarting the stream.
 */
class SubtitleSyncOffset {
    @Volatile
    var ms: Int = 0
        private set

    fun set(ms: Int) {
        this.ms = SubtitleSync.clamp(ms)
    }
}

/**
 * [TextRenderer] is final, so this forwards one and asks it for cues at
 * [SubtitleSync.shiftPositionUs]. The player still treats the real renderer as the
 * delegate for every other call.
 */
@OptIn(UnstableApi::class)
internal class OffsetTextRenderer(
    private val inner: TextRenderer,
    private val offset: SubtitleSyncOffset,
) : Renderer by inner {
    constructor(output: TextOutput, outputLooper: Looper, offset: SubtitleSyncOffset) :
        this(TextRenderer(output, outputLooper), offset)

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        inner.render(SubtitleSync.shiftPositionUs(positionUs, offset.ms), elapsedRealtimeUs)
    }
}
