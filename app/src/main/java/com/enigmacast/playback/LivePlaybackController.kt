package com.enigmacast.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.domain.model.PlaybackSettings
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(UnstableApi::class)
class LiveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(8) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        return (500L * loadErrorInfo.errorCount).coerceAtMost(5_000L)
    }
}

/**
 * One ExoPlayer for the remote screen. A zap replaces the media item and does not
 * build a new player. The stream client is separate from the OpenWebif client so a
 * long-lived MPEG-TS response cannot exhaust the OSD connection pool.
 */
@OptIn(UnstableApi::class)
class LivePlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: OkHttpClientFactory,
) {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var settings = PlaybackSettings()
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var failures = 0
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        ensurePlayer()
    }

    fun exoPlayer(): ExoPlayer = ensurePlayer()

    fun updateSettings(settings: PlaybackSettings) {
        if (this.settings == settings) return
        this.settings = settings
        val url = currentUrl
        if (player != null) {
            releasePlayer()
            ensurePlayer()
            if (url != null) play(url, force = true)
        }
    }

    fun play(url: String, force: Boolean = false) {
        val exo = ensurePlayer()
        if (!force && url == currentUrl && exo.playerError == null && exo.playbackState != Player.STATE_IDLE) {
            return
        }
        currentUrl = url
        val item = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(1_000)
                    .setMinOffsetMs(500)
                    .setMaxOffsetMs(5_000)
                    .build(),
            )
            .build()
        exo.setMediaItem(item, true)
        exo.prepare()
        exo.playWhenReady = true
        EnigmaLog.player("switching live stream")
    }

    fun reconnect() {
        val url = currentUrl ?: return
        if (!settings.autoReconnect && failures > 0) return
        play(url, force = true)
    }

    fun release() {
        reconnectJob?.cancel()
        releasePlayer()
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        return buildPlayer().also { player = it }
    }

    private fun buildPlayer(): ExoPlayer {
        val loadControl = if (settings.lowLatency) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    settings.minBufferMs,
                    settings.maxBufferMs,
                    settings.bufferForPlaybackMs,
                    settings.bufferForRebufferMs,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl()
        }
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(clientFactory.streamClient()),
            DefaultExtractorsFactory(),
        ).setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy())
        val exo = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        exo.addListener(listener)
        return exo
    }

    private fun publish() {
        val exo = player ?: return
        val video = exo.videoFormat
        val audio = exo.audioFormat
        val liveOffset = exo.currentLiveOffset
        _state.value = PlayerState(
            playbackState = when (exo.playbackState) {
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "IDLE"
            },
            videoWidth = video?.width ?: exo.videoSize.width,
            videoHeight = video?.height ?: exo.videoSize.height,
            frameRate = video?.frameRate ?: 0f,
            videoCodec = video?.sampleMimeType.orEmpty(),
            audioCodec = audio?.sampleMimeType.orEmpty(),
            bufferMs = exo.totalBufferedDuration,
            liveOffsetMs = if (liveOffset == C.TIME_UNSET) -1 else liveOffset,
            error = exo.playerError?.errorCodeName,
        )
    }

    private fun scheduleReconnect() {
        if (!settings.autoReconnect) return
        val owner = scope ?: return
        failures += 1
        reconnectJob?.cancel()
        reconnectJob = owner.launch {
            delay((1_000L * failures).coerceAtMost(8_000L))
            reconnect()
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) failures = 0
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            EnigmaLog.player("error ${error.errorCodeName}")
            publish()
            scheduleReconnect()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            publish()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            publish()
        }
    }
}
