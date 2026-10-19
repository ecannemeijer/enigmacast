package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/** Load controls with a byte target that fits this device's heap (see [BufferPolicy]). */
@OptIn(UnstableApi::class)
object PlayerBuffers {
    fun targetBytes(kind: BufferPolicy.Kind): Int = BufferPolicy.targetBytes(Runtime.getRuntime().maxMemory(), kind)

    /** IPTV / Torrent films and series: Media3's buffer times, bytes capped to the heap. */
    fun vod(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setTargetBufferBytes(targetBytes(BufferPolicy.Kind.VOD))
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    /** The picture-in-picture inset: a small live stream next to the main player. */
    fun inset(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setTargetBufferBytes(targetBytes(BufferPolicy.Kind.LIVE))
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}
