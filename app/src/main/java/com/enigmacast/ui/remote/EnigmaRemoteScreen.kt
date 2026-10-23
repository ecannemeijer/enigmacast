package com.enigmacast.ui.remote

import com.enigmacast.ui.theme.glassSheen
import com.enigmacast.ui.theme.skinShape
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.SurfaceView
import com.enigmacast.ui.activity.reportFullscreenVideo
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import com.enigmacast.playback.VideoOutput
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.ConnectionState
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.osd.OsdScale
import com.enigmacast.domain.osd.OsdScaleMode
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.domain.player.VideoScaling
import com.enigmacast.ui.recording.RecordConfirmDialog
import com.enigmacast.ui.search.SearchPanel
import com.enigmacast.ui.search.SearchViewModel
import com.enigmacast.ui.recording.RecordingChip
import com.enigmacast.ui.theme.EnigmaColors

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EnigmaRemoteScreen(
    onLeaveToDashboard: () -> Unit,
    onLeaveToMyItems: (String) -> Unit,
    pendingSearchQuery: String? = null,
    pendingSearchPlay: Boolean = false,
    pendingSearchToken: Int = 0,
    onAssistantSearchConsumed: () -> Unit = {},
    viewModel: RemoteDisplayViewModel = hiltViewModel(),
    searchModel: SearchViewModel = hiltViewModel(),
) {
    DisposableEffect(Unit) {
        onDispose { viewModel.pip.stop() }
    }
    DisposableEffect(onLeaveToDashboard, onLeaveToMyItems) {
        viewModel.onLeaveToDashboard = onLeaveToDashboard
        viewModel.onLeaveToMyItems = onLeaveToMyItems
        onDispose {
            viewModel.onLeaveToDashboard = null
            viewModel.onLeaveToMyItems = null
        }
    }
    LaunchedEffect(pendingSearchToken) {
        val q = pendingSearchQuery ?: return@LaunchedEffect
        if (pendingSearchToken == 0) return@LaunchedEffect
        viewModel.openSearch()
        searchModel.applyExternalQuery(
            value = q,
            playFirst = pendingSearchPlay,
            onPlayed = viewModel::finishSearchPlayback,
            onBouquet = { viewModel.noteBouquet(it.name) },
        )
        onAssistantSearchConsumed()
    }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = ui.recentBar || viewModel.equivalentsState.open) {
        if (viewModel.equivalentsState.open) viewModel.closeEquivalent() else viewModel.closeRecentBar()
    }
    androidx.compose.runtime.DisposableEffect(ui.recentBar, viewModel.equivalentsState.open) {
        if (ui.recentBar || viewModel.equivalentsState.open) {
            PanelBackGuard.register {
                if (viewModel.equivalentsState.open) {
                    viewModel.closeEquivalent()
                    true
                } else if (ui.recentBar) {
                    viewModel.closeRecentBar()
                    true
                } else {
                    false
                }
            }
        }
        onDispose { PanelBackGuard.unregister() }
    }
    val scrim = EnigmaColors.scrim(ui.panelTransparency)
    val player = viewModel.player()
    val quickBarActions = remember(viewModel) {
        QuickBarActions(
            onSubtitles = viewModel::chooseSubtitles,
            onAudio = viewModel::chooseAudio,
            onPassthrough = viewModel::setPassthrough,
            onPictureMode = viewModel::choosePictureMode,
            onAudioDelay = viewModel::setAudioDelay,
            onSubtitleDelay = viewModel::setSubtitleDelay,
            onGuide = viewModel::openGuideFromBar,
            onRecent = viewModel::zapRecent,
            onSleep = viewModel::setSleepTimer,
            onRestart = viewModel::restartFromBar,
            onQuality = viewModel::chooseQuality,
            onRecord = viewModel::recordWatching,
            onSearch = viewModel::openSearch,
            onEquivalent = viewModel::openEquivalent,
            onRecentBar = viewModel::openRecentBar,
            onPip = viewModel::openPipPicker,
        )
    }
    val guideActions = remember(viewModel) {
        GuideActions(
            onOpenChannelEpg = viewModel::openChannelEpg,
            onChooseProgramme = viewModel::chooseProgramme,
            onAutoSwitch = viewModel::toggleAutoSwitch,
            onRecord = viewModel::chooseRecord,
            onDismissProgramme = viewModel::dismissProgramme,
        )
    }
    val pipOn by viewModel.pip.active.collectAsStateWithLifecycle()
    val pipSwap by viewModel.pip.swapped.collectAsStateWithLifecycle()
    val pipList by viewModel.pip.listOpen.collectAsStateWithLifecycle()
    val pipNames by viewModel.pip.names.collectAsStateWithLifecycle()
    val big = if (pipOn && pipSwap) viewModel.pip.player() else player
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        PipAudio(player, viewModel.pip)
        EnigmaVideoPlayer(
            player = big,
            frame = if (ui.playerState.filePlayback) null else ui.osdFrame,
            debugBorders = ui.osdDebugBorders,
            cues = ui.cues,
            picture = VideoShape(ui.pictureMode, ui.playerState.videoWidth, ui.playerState.videoHeight, ui.playerState.pixelRatio),
            osdAlpha = (100 - ui.panelTransparency.coerceIn(0, 100)) / 100f,
            modifier = Modifier.fillMaxSize(),
        )
        if (pipOn) {
            PipInset(
                player = if (pipSwap) player else viewModel.pip.player(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).size(width = 320.dp, height = 180.dp),
            )
        }
        if (pipList) {
            PipChannelList(pipNames, viewModel.pip::choose, viewModel.pip::closeList)
        }
        if (viewModel.showPipPicker) {
            PipPicker(
                loadCategories = viewModel.pip::categories,
                loadChannels = viewModel.pip::channels,
                onStart = { list, index -> viewModel.pip.startResolved(list, index) },
                onDismiss = { viewModel.showPipPicker = false },
            )
        }
        val radioChannel = ui.own.channel
        if (ui.ownTuner && !ui.playerState.filePlayback && radioChannel != null &&
            BouquetChannels.isRadioBouquet(ui.own.bouquetReference)
        ) {
            RadioNowPlaying(
                channel = radioChannel,
                guide = ui.own.guide,
                streamTitle = ui.playerState.streamTitle,
                streamArtist = ui.playerState.streamArtist,
                picon = viewModel::picon,
                cachedPicon = viewModel::cachedPicon,
                compact = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (ui.recordingChannel.isNotBlank() && !ui.playerState.filePlayback) {
            RecordingChip(
                channel = ui.recordingChannel,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 24.dp),
            )
        }
        if (ui.playerState.filePlayback) {
            // Recordings: the same Stremio-style VOD OSD as IPTV films and torrents.
            com.enigmacast.ui.player.VodPlayerOsd(
                controller = viewModel.fileOsd,
                model = com.enigmacast.ui.player.VodOsdModel(
                    title = ui.fileTitle.ifBlank { stringResource(R.string.nav_my_items) },
                    player = ui.playerState,
                    tracks = ui.tracks,
                    pictureMode = ui.pictureMode,
                    audioDelayMs = ui.audioDelayMs,
                    subtitleDelayMs = ui.subtitleDelayMs,
                    speed = ui.fileSpeed,
                    hdmiPassthrough = ui.hdmiPassthrough,
                    sleepEndsAtMs = ui.sleepEndsAtMs,
                ),
                actions = com.enigmacast.ui.player.VodOsdActions(
                    onTogglePause = viewModel::toggleFilePause,
                    onSeek = viewModel::seekFile,
                    onSubtitles = quickBarActions.onSubtitles,
                    onAudio = quickBarActions.onAudio,
                    onPictureMode = quickBarActions.onPictureMode,
                    onAudioDelay = quickBarActions.onAudioDelay,
                    onSubtitleDelay = quickBarActions.onSubtitleDelay,
                    onRestart = viewModel::restartFromBar,
                    onSpeed = viewModel::setFileSpeed,
                    onPassthrough = quickBarActions.onPassthrough,
                    onSleep = quickBarActions.onSleep,
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (ui.ownTuner) {
            OwnTunerOverlay(
                ui = ui,
                picon = viewModel::picon,
                cachedPicon = viewModel::cachedPicon,
                numberOf = viewModel::numberOf,
                onZap = viewModel::zapTo,
                onChooseBouquet = viewModel::chooseBouquet,
                quickBarActions = quickBarActions,
                guideActions = guideActions,
            )
        } else if (ui.quickBar) {
            QuickOptionsBar(
                ui = ui,
                actions = quickBarActions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
            )
        }
        if (ui.searchOpen) {
            SearchPanel(
                rowsFocusable = true,
                focusToken = ui.searchToken,
                onPlayed = viewModel::finishSearchPlayback,
                onBouquet = { viewModel.noteBouquet(it.name) },
                viewModel = searchModel,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(600.dp)
                    .background(scrim)
                    .glassSheen(androidx.compose.ui.graphics.RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 20.dp),
            )
        }
        if (ui.connectionState !is ConnectionState.Connected && ui.connectionState !is ConnectionState.Idle) {
            ConnectionProblemOverlay(ui.connectionState)
        }
        if (ui.debugOverlayEnabled) {
            DebugOverlay(ui)
        }
        ui.recordAsk?.let { ask ->
            RecordConfirmDialog(ask, onConfirm = viewModel::confirmRecord, onDismiss = viewModel::dismissRecord)
        }
        EquivalentChannelsPanel(
            state = viewModel.equivalentsState,
            loadLogo = { channel ->
                if (channel.source == com.enigmacast.domain.channel.ChannelSource.ENIGMA2) {
                    viewModel.picon(com.enigmacast.domain.model.BouquetEntry(channel.name, channel.reference))
                } else {
                    null
                }
            },
            onChoose = viewModel::chooseEquivalent,
            onDismiss = viewModel::closeEquivalent,
        )
        if (ui.recentBar) {
            RecentChannelsBar(
                channels = ui.recent,
                currentReference = ui.own.channel?.reference ?: ui.currentService?.reference.orEmpty(),
                focusToken = ui.recentBarToken,
                onChoose = viewModel::zapRecent,
                onClear = viewModel::clearRecent,
                onBackToOptions = { viewModel.closeRecentBar(backToOptions = true) },
                onClose = { viewModel.closeRecentBar(backToOptions = false) },
                loadLogo = { channel ->
                    viewModel.picon(com.enigmacast.domain.model.BouquetEntry(channel.name, channel.reference))
                },            )
        }
    }
}

/** Attaches [player] to a SurfaceView or a TextureView. */
private fun Player.bindVideo(view: View) {
    when (view) {
        is SurfaceView -> setVideoSurfaceView(view)
        is TextureView -> setVideoTextureView(view)
    }
}

private fun Player.unbindVideo(view: View) {
    when (view) {
        is SurfaceView -> clearVideoSurfaceView(view)
        is TextureView -> clearVideoTextureView(view)
    }
}

/**
 * A SurfaceView by default: frames stay on the hardware video overlay, as in TiviMate and Netflix.
 * A TextureView sends them through the GPU, which garbles the picture on Amlogic sticks.
 */
private fun videoView(context: android.content.Context, surface: Boolean): View =
    if (surface) SurfaceView(context) else TextureView(context)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EnigmaVideoTexture(player: Player, cues: List<Cue>, modifier: Modifier = Modifier) {
    val surface = VideoOutput.surfaceView
    key(surface) {
        AndroidView(
            modifier = modifier.reportFullscreenVideo(),
            factory = { context ->
                val video = videoView(context, surface).apply { isFocusable = false }
                val subtitles = CueOverlayView(context)
                FrameLayout(context).apply {
                    addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(subtitles, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    tag = MiniVideoLayer(video, subtitles, boundPlayer = null)
                }
            },
            update = { layout ->
                val layer = layout.tag as MiniVideoLayer
                if (layer.boundPlayer !== player) {
                    layer.boundPlayer?.unbindVideo(layer.video)
                    player.bindVideo(layer.video)
                    layer.boundPlayer = player
                }
                layer.subtitles.cues = cues
            },
            onRelease = { layout ->
                val layer = layout.tag as MiniVideoLayer
                layer.boundPlayer?.unbindVideo(layer.video)
                layer.boundPlayer = null
            },
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun EnigmaVideoPlayer(
    player: Player,
    frame: OsdFrame?,
    debugBorders: Boolean,
    cues: List<Cue>,
    picture: VideoShape,
    osdAlpha: Float = 1f,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    // Compose draws into the parent canvas. Android child views are composited above that
    // canvas, so the OSD is an ImageView in the same FrameLayout, above the video.
    // Subtitles sit above the video and under the tuner OSD.
    val surface = VideoOutput.surfaceView
    key(surface) {
    AndroidView(
        modifier = modifier.reportFullscreenVideo(),
        factory = { context ->
            val video = videoView(context, surface)
            val subtitles = CueOverlayView(context)
            val osd = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                isFocusable = false
                isClickable = false
            }
            FrameLayout(context).apply {
                addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                addView(subtitles, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(osd, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                val layer = VideoLayer(this, video, subtitles, osd, boundPlayer = null)
                tag = layer
                addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                    if (r - l != or - ol || b - t != ob - ot) post { layer.applyPicture() }
                }
            }
        },
        update = { layout ->
            val layer = layout.tag as VideoLayer
            if (layer.boundPlayer !== player) {
                layer.boundPlayer?.unbindVideo(layer.video)
                player.bindVideo(layer.video)
                layer.boundPlayer = player
            }
            layer.subtitles.cues = cues
            if (layer.picture != picture) {
                layer.picture = picture
                layer.applyPicture()
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
            layer.osd.imageAlpha = (osdAlpha.coerceIn(0f, 1f) * 255).toInt()
            layer.osd.setBackgroundColor(if (debugBorders) 0x66FF0000 else 0)
        },
        onRelease = { layout ->
            val layer = layout.tag as VideoLayer
            layer.boundPlayer?.unbindVideo(layer.video)
            layer.boundPlayer = null
            layer.osd.setImageBitmap(null)
        },
    )
    }
}

private class VideoLayer(
    val frame: FrameLayout,
    val video: View,
    val subtitles: CueOverlayView,
    val osd: ImageView,
    var boundPlayer: Player?,
    var shownHash: Long? = null,
    var picture: VideoShape? = null,
) {
    /** Scales the frame around the centre of the view for the chosen picture mode. */
    fun applyPicture() {
        val shape = picture ?: return
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return
        val scale = VideoScaling.scale(shape.mode, shape.videoWidth, shape.videoHeight, shape.pixelRatio, width, height)
        when (video) {
            is TextureView -> {
                val matrix = Matrix()
                matrix.setScale(scale.x, scale.y, width / 2f, height / 2f)
                video.setTransform(matrix)
                video.invalidate()
            }
            else -> {
                // A SurfaceView cannot take a matrix; it is sized instead, centred and clipped by the frame.
                val w = (width * scale.x).toInt().coerceAtLeast(1)
                val h = (height * scale.y).toInt().coerceAtLeast(1)
                val params = video.layoutParams as FrameLayout.LayoutParams
                if (params.width != w || params.height != h) {
                    params.width = w
                    params.height = h
                    params.gravity = Gravity.CENTER
                    video.layoutParams = params
                }
            }
        }
    }
}

/** What the picture mode needs to know about the video. */
data class VideoShape(val mode: PictureMode, val videoWidth: Int, val videoHeight: Int, val pixelRatio: Float)

private class MiniVideoLayer(
    val video: View,
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
