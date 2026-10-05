package com.enigmacast.ui.remote

import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.enigmacast.domain.model.ConnectionState
import com.enigmacast.domain.osd.OsdScale
import com.enigmacast.domain.osd.OsdScaleMode

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EnigmaRemoteScreen(
    onLeaveToDashboard: () -> Unit,
    viewModel: RemoteDisplayViewModel = hiltViewModel(),
) {
    DisposableEffect(onLeaveToDashboard) {
        viewModel.onLeaveToDashboard = onLeaveToDashboard
        onDispose { viewModel.onLeaveToDashboard = null }
    }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val player = viewModel.player()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        EnigmaVideoPlayer(player, ui.osdFrame, ui.osdDebugBorders)
        if (ui.connectionState !is ConnectionState.Connected && ui.connectionState !is ConnectionState.Idle) {
            ConnectionProblemOverlay(ui.connectionState)
        }
        if (ui.debugOverlayEnabled) {
            DebugOverlay(ui)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EnigmaVideoTexture(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply { isFocusable = false }
        },
        update = { texture ->
            val bound = texture.tag as? Player
            if (bound !== player) {
                bound?.clearVideoTextureView(texture)
                player.setVideoTextureView(texture)
                texture.tag = player
            }
        },
        onRelease = { texture ->
            (texture.tag as? Player)?.clearVideoTextureView(texture)
            texture.tag = null
        },
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun EnigmaVideoPlayer(player: Player, frame: OsdFrame?, debugBorders: Boolean) {
    // Compose draws into the parent canvas. An Android child such as TextureView is
    // composited above that canvas, so an OSD Image in Compose stays hidden behind the video.
    // The OSD ImageView is the next child of the same FrameLayout and is therefore on top.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val texture = TextureView(context)
            val osd = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                isFocusable = false
                isClickable = false
            }
            FrameLayout(context).apply {
                addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(osd, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                tag = VideoLayer(texture, osd, boundPlayer = null)
            }
        },
        update = { layout ->
            val layer = layout.tag as VideoLayer
            if (layer.boundPlayer !== player) {
                layer.boundPlayer?.clearVideoTextureView(layer.texture)
                player.setVideoTextureView(layer.texture)
                layer.boundPlayer = player
            }
            val scale = OsdScale.choose(
                frame?.width ?: 0,
                frame?.height ?: 0,
                layout.width,
                layout.height,
            )
            layer.osd.scaleType = if (scale == OsdScaleMode.FILL_BOUNDS) {
                ImageView.ScaleType.FIT_XY
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
            if (layer.shownHash != frame?.hash) {
                layer.shownHash = frame?.hash
                layer.osd.setImageBitmap(frame?.bitmap)
            }
            layer.osd.setBackgroundColor(if (debugBorders) 0x66FF0000 else 0)
        },
        onRelease = { layout ->
            val layer = layout.tag as VideoLayer
            layer.boundPlayer?.clearVideoTextureView(layer.texture)
            layer.boundPlayer = null
            layer.osd.setImageBitmap(null)
        },
    )
}

private class VideoLayer(
    val texture: TextureView,
    val osd: ImageView,
    var boundPlayer: Player?,
    var shownHash: Long? = null,
)

@Composable
private fun ConnectionProblemOverlay(state: ConnectionState) {
    val message = when (state) {
        is ConnectionState.Failed -> state.reason
        is ConnectionState.Reconnecting -> state.reason
        ConnectionState.Connecting -> "Connecting"
        else -> ""
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 22.sp,
            modifier = Modifier
                .padding(32.dp)
                .background(Color(0xCC101820))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun DebugOverlay(ui: RemoteDisplayUiState) {
    val info = ui.debugInfo
    Column(
        modifier = Modifier
            .padding(24.dp)
            .background(Color(0xB0101820))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DebugLine("Receiver", info.host)
        DebugLine("Service", info.serviceName)
        DebugLine("Ref", info.serviceReference)
        DebugLine("Video", info.video)
        DebugLine("Audio", info.audio)
        DebugLine("Media3", info.media3State)
        DebugLine("Latency", info.latency)
        DebugLine("OSD", info.osdState)
        DebugLine("OSD latency", "${info.osdRequestLatencyMs} ms")
        DebugLine("OSD fps", "%.1f".format(info.osdFps))
        DebugLine("Network", info.network)
        DebugLine("Last key", info.lastKey)
        ui.playerState.error?.let { DebugLine("Player error", it) }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text("$label: $value", color = Color.White, fontSize = 16.sp)
}
