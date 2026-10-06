package com.enigmacast.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
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
import javax.inject.Singleton

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
@Singleton
class LivePlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: OkHttpClientFactory,
) {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var playbackEnabled = true
    private var settings = PlaybackSettings()
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var failures = 0
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()
    private val _subtitlesAvailable = MutableStateFlow(false)
    val subtitlesAvailable: StateFlow<Boolean> = _subtitlesAvailable.asStateFlow()

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

    fun stop() {
        playbackEnabled = false
        currentUrl = null
        reconnectJob?.cancel()
        releasePlayer()
        _cues.value = emptyList()
        _subtitlesAvailable.value = false
        _state.value = PlayerState()
    }

    /**
     * Drops the live MPEG-TS source before a zap so the receiver can free the tuner.
     * The same ExoPlayer is kept; [release] is for leaving the screen.
     */
    fun stopForZap() {
        reconnectJob?.cancel()
        reconnectJob = null
        currentUrl = null
        failures = 0
        val exo = player ?: return
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        EnigmaLog.player("live stream released for zap")
    }

    fun allowPlayback() {
        playbackEnabled = true
    }

    fun play(url: String, force: Boolean = false) {
        if (!playbackEnabled) return
        val exo = ensurePlayer()
        if (!force && url == currentUrl && exo.playerError == null && exo.playbackState != Player.STATE_IDLE) {
            return
        }
        currentUrl = url
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .setLiveConfiguration(liveConfiguration())
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

    private fun liveConfiguration(): MediaItem.LiveConfiguration {
        val builder = MediaItem.LiveConfiguration.Builder()
        return if (settings.lowLatency) {
            builder.setTargetOffsetMs(1_000).setMinOffsetMs(500).setMaxOffsetMs(5_000).build()
        } else {
            builder.setTargetOffsetMs(4_000).setMinOffsetMs(2_000).setMaxOffsetMs(15_000).build()
        }
    }

    private fun loadControl(): DefaultLoadControl {
        val minBuffer: Int
        val maxBuffer: Int
        val playback: Int
        val rebuffer: Int
        if (settings.lowLatency) {
            minBuffer = settings.minBufferMs
            maxBuffer = settings.maxBufferMs
            playback = settings.bufferForPlaybackMs
            rebuffer = settings.bufferForRebufferMs
        } else {
            minBuffer = 2_000
            maxBuffer = 15_000
            playback = 2_000
            rebuffer = 2_000
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playback, rebuffer)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    @OptIn(ExperimentalApi::class)
    private fun buildPlayer(): ExoPlayer {
        val renderers = LiveRenderersFactory(context, settings.hdmiPassthrough)
            .setEnableDecoderFallback(true)
            .setVideoRendererEarlySchedulingThresholdUs(TEXTURE_VIEW_EARLY_SCHEDULING_US)
        val trackSelector = DefaultTrackSelector(context)
        val preferredAudio = if (settings.hdmiPassthrough) {
            arrayOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG)
        } else {
            arrayOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)
        }
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioMimeTypes(*preferredAudio)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesEnabled)
            .setSelectUndeterminedTextLanguage(settings.subtitlesEnabled)
            .build()
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(clientFactory.streamClient()),
            extractors,
        ).setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy())
        val exo = ExoPlayer.Builder(context)
            .setLoadControl(loadControl())
            .setRenderersFactory(renderers)
            .setTrackSelector(trackSelector)
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
        EnigmaLog.player(if (settings.hdmiPassthrough) "audio HDMI passthrough" else "audio stereo downmix")
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

    private fun selectSubtitles(exo: ExoPlayer) {
        val enabled = settings.subtitlesEnabled
        val available = exo.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && group.length > 0
        }
        _subtitlesAvailable.value = available
        val parameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        if (!enabled) {
            exo.trackSelectionParameters = parameters.clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            _cues.value = emptyList()
            return
        }
        exo.trackSelectionParameters = parameters.build()
        if (!available) return
        val alreadySelected = exo.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && group.isSelected
        }
        if (alreadySelected) return
        val group = exo.currentTracks.groups.firstOrNull { candidate ->
            candidate.type == C.TRACK_TYPE_TEXT && candidate.isTrackSupported(0)
        } ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
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

        override fun onTracksChanged(tracks: Tracks) {
            publish()
            selectSubtitles(player ?: return)
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = if (settings.subtitlesEnabled) cueGroup.cues else emptyList()
        }
    }
}

/**
 * Video is drawn on a TextureView. A TextureView shows a frame as soon as the decoder
 * hands it over and ignores the release time Media3 asks for. Media3 hands frames over
 * up to 50 ms early by default, so the picture ran ahead of the sound by a few tens of
 * milliseconds. 20 ms is about one refresh at 50 Hz, the rate DVB channels use.
 */
private const val TEXTURE_VIEW_EARLY_SCHEDULING_US = 20_000L

@OptIn(UnstableApi::class)
private class LiveRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val detected = AudioTrackAudioOutputProvider.Builder(context).build()
        val output = if (passthrough) detected else PcmAudioOutputProvider(detected)
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioOutputProvider(output)
            .build()
    }
}

@OptIn(UnstableApi::class)
private class PcmAudioOutputProvider(
    delegate: AudioOutputProvider,
) : ForwardingAudioOutputProvider(delegate) {
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val mime = formatConfig.format.sampleMimeType
        if (mime != null && mime != MimeTypes.AUDIO_RAW) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        return super.getFormatSupport(formatConfig)
    }
}
