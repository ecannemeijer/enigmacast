package com.enigmacast.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.enigmacast.ui.activity.FullscreenVideo

/**
 * Put on a fullscreen video surface: reports [player]'s frame rate to [AutoFrameRate] while the
 * surface covers the window. [dvb] streams (Enigma2) without a known rate count as 50 Hz.
 * Audio-only (radio) never reports a rate, so the display stays as it is.
 */
@Composable
fun Modifier.autoFrameRate(player: Player, dvb: Boolean): Modifier {
    val owner = remember { Any() }
    val view = LocalView.current
    val fullscreen = remember { mutableStateOf(false) }
    val fps = remember { mutableFloatStateOf(-1f) }
    val isDvb by rememberUpdatedState(dvb)
    val push = { AutoFrameRate.report(owner, fullscreen.value, fps.floatValue, isDvb) }
    DisposableEffect(player) {
        fun read() {
            val hasVideo = player.videoSize.width > 0 && player.videoSize.height > 0
            fps.floatValue = if (!hasVideo) {
                -1f
            } else {
                player.currentTracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).frameRate } }
                    ?.takeIf { it > 0f } ?: 0f
            }
            // 0 = video without a known rate (DVB falls back to 50 Hz); -1 = no video yet.
            if (fps.floatValue < 0f) AutoFrameRate.report(owner, false, -1f, isDvb) else push()
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = read()
            override fun onVideoSizeChanged(videoSize: VideoSize) = read()
            override fun onRenderedFirstFrame() = read()
        }
        player.addListener(listener)
        read()
        onDispose {
            player.removeListener(listener)
            AutoFrameRate.leave(owner)
        }
    }
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val covers = FullscreenVideo.covers(bounds.width, bounds.height, view.width, view.height)
        if (covers != fullscreen.value) {
            fullscreen.value = covers
            if (fps.floatValue >= 0f) push() else AutoFrameRate.report(owner, false, -1f, isDvb)
        }
    }
}
