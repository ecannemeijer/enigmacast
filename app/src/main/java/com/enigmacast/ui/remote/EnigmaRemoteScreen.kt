package com.enigmacast.ui.remote

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.TextureView
import android.view.View
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.enigmacast.R
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
        EnigmaVideoPlayer(player, ui.osdFrame, ui.osdDebugBorders, ui.cues)
        if (ui.ownTuner) {
            OwnTunerOverlay(
                ui = ui,
                picon = viewModel::picon,
                cachedPicon = viewModel::cachedPicon,
                numberOf = viewModel::numberOf,
                onZap = viewModel::zapTo,
                onChooseBouquet = viewModel::chooseBouquet,
            )
        }
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
fun EnigmaVideoTexture(player: Player, cues: List<Cue>, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val texture = TextureView(context).apply { isFocusable = false }
            val subtitles = CueOverlayView(context)
            FrameLayout(context).apply {
                addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(subtitles, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                tag = MiniVideoLayer(texture, subtitles, boundPlayer = null)
            }
        },
        update = { layout ->
            val layer = layout.tag as MiniVideoLayer
            if (layer.boundPlayer !== player) {
                layer.boundPlayer?.clearVideoTextureView(layer.texture)
                player.setVideoTextureView(layer.texture)
                layer.boundPlayer = player
            }
            layer.subtitles.cues = cues
        },
        onRelease = { layout ->
            val layer = layout.tag as MiniVideoLayer
            layer.boundPlayer?.clearVideoTextureView(layer.texture)
            layer.boundPlayer = null
        },
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun EnigmaVideoPlayer(player: Player, frame: OsdFrame?, debugBorders: Boolean, cues: List<Cue>) {
    // Compose draws into the parent canvas. An Android child such as TextureView is
    // composited above that canvas, so an OSD Image in Compose stays hidden behind the video.
    // Subtitles sit above the video and under the tuner OSD.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val texture = TextureView(context)
            val subtitles = CueOverlayView(context)
            val osd = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                isFocusable = false
                isClickable = false
            }
            FrameLayout(context).apply {
                addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(subtitles, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(osd, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                tag = VideoLayer(texture, subtitles, osd, boundPlayer = null)
            }
        },
        update = { layout ->
            val layer = layout.tag as VideoLayer
            if (layer.boundPlayer !== player) {
                layer.boundPlayer?.clearVideoTextureView(layer.texture)
                player.setVideoTextureView(layer.texture)
                layer.boundPlayer = player
            }
            layer.subtitles.cues = cues
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
    val subtitles: CueOverlayView,
    val osd: ImageView,
    var boundPlayer: Player?,
    var shownHash: Long? = null,
)

private class MiniVideoLayer(
    val texture: TextureView,
    val subtitles: CueOverlayView,
    var boundPlayer: Player?,
)

private class CueOverlayView(context: android.content.Context) : View(context) {
    var cues: List<Cue> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
    }

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        for (cue in cues) {
            val bitmap = cue.bitmap
            if (bitmap != null && !bitmap.isRecycled) {
                drawBitmapCue(canvas, cue, bitmap, viewWidth, viewHeight)
            } else if (!cue.text.isNullOrEmpty()) {
                canvas.drawText(cue.text.toString(), viewWidth / 2f, viewHeight - 48f, textPaint)
            }
        }
    }

    private fun drawBitmapCue(canvas: Canvas, cue: Cue, bitmap: android.graphics.Bitmap, viewWidth: Float, viewHeight: Float) {
        val unset = Cue.DIMEN_UNSET
        val cueWidth = if (cue.size != unset) cue.size * viewWidth else bitmap.width.toFloat()
        val cueHeight = if (cue.bitmapHeight != unset) {
            cue.bitmapHeight * viewHeight
        } else {
            bitmap.height * (cueWidth / bitmap.width.coerceAtLeast(1))
        }
        var x = if (cue.position != unset) cue.position * viewWidth else (viewWidth - cueWidth) / 2f
        var y = if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != unset) {
            cue.line * viewHeight
        } else {
            viewHeight - cueHeight
        }
        if (cue.positionAnchor == Cue.ANCHOR_TYPE_MIDDLE) x -= cueWidth / 2f
        if (cue.positionAnchor == Cue.ANCHOR_TYPE_END) x -= cueWidth
        if (cue.lineAnchor == Cue.ANCHOR_TYPE_MIDDLE) y -= cueHeight / 2f
        if (cue.lineAnchor == Cue.ANCHOR_TYPE_END) y -= cueHeight
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + cueWidth, y + cueHeight), null)
    }
}

@Composable
private fun ConnectionProblemOverlay(state: ConnectionState) {
    val message = when (state) {
        is ConnectionState.Failed -> if (state.reason.isBlank()) {
            stringResource(R.string.conn_receiver_offline_plain)
        } else {
            stringResource(R.string.conn_receiver_offline, state.reason)
        }
        is ConnectionState.Reconnecting -> stringResource(R.string.conn_reconnecting)
        ConnectionState.NotConfigured -> stringResource(R.string.conn_no_receiver_configured)
        ConnectionState.Connecting -> stringResource(R.string.conn_connecting)
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
