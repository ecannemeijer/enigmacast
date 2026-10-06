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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.domain.model.PlaybackSettings
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.tuner.StreamFailure
import com.enigmacast.domain.tuner.StreamHealth
import com.enigmacast.domain.tuner.StreamStartCheck
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
class LiveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(8) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // A refused login or an unknown service does not get better by asking again.
        val code = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code == 401 || code == 403 || code == 404) return C.TIME_UNSET
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
    private var checkStart = false
    private var startedAtMs = 0L
    private var firstFrame = false
    private var lastFailure = StreamFailure.NONE
    private var watchdog: Job? = null
    private val bytesLoaded = AtomicLong()
    private val hold = PlaybackHold()
    private val _held = MutableStateFlow(false)

    /** True while live playback is paused for an app update download (see [holdForUpdate]). */
    val held: StateFlow<Boolean> = _held.asStateFlow()
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Sent as `Authorization` on stream requests; read on the loader thread. */
    @Volatile
    private var streamAuthorization: String? = null
    private val _health = MutableStateFlow(StreamHealth.IDLE)

    /**
     * How a stream started with `checkStart = true` is doing. Enigma2 accepts the
     * connection and only then looks for a tuner, so "no tuner" shows up as a stream
     * that ends, or stays silent, before any picture.
     */
    val health: StateFlow<StreamHealth> = _health.asStateFlow()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()
    private val _subtitlesAvailable = MutableStateFlow(false)
    val subtitlesAvailable: StateFlow<Boolean> = _subtitlesAvailable.asStateFlow()
    private val _tracks = MutableStateFlow(TrackMenu())

    /** Audio and subtitle tracks of the stream that plays, for the quick options bar. */
    val tracks: StateFlow<TrackMenu> = _tracks.asStateFlow()
    private val _stats = MutableStateFlow(StreamStats())

    /** Measured bitrate, dropped frames and stalls of the stream that plays. */
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()
    private val meter = ThroughputMeter()
    private val totalBytes = AtomicLong()
    private var droppedFrames = 0
    private var stalls = 0
    private var wasReady = false
    private val audioSync = AudioSyncOffsets()

    /** Whether the current audio goes out undecoded over HDMI; null before the first track. */
    val audioPassthroughActive: StateFlow<Boolean?> = audioSync.passthroughActive

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        ensurePlayer()
    }

    fun exoPlayer(): ExoPlayer = ensurePlayer()

    fun updateSettings(settings: PlaybackSettings) {
        audioSync.set(settings.audioDelayPcmMs, settings.audioDelayPassthroughMs)
        if (this.settings == settings) return
        val inPlace = this.settings.withoutInPlaceOptions() == settings.withoutInPlaceOptions()
        this.settings = settings
        if (inPlace) {
            // The sink reads a new delay on its next position update, and subtitles switch
            // through the track selection; neither needs a new player.
            EnigmaLog.player("audio delay pcm=${settings.audioDelayPcmMs}ms hdmi=${settings.audioDelayPassthroughMs}ms subtitles=${settings.subtitlesEnabled}")
            player?.let { exo ->
                selectSubtitles(exo)
                publish()
            }
            return
        }
        val url = currentUrl
        if (player != null) {
            releasePlayer()
            ensurePlayer()
            if (url != null) play(url, force = true, authorization = streamAuthorization, checkStart = checkStart)
        }
    }

    fun stop() {
        playbackEnabled = false
        currentUrl = null
        reconnectJob?.cancel()
        stopWatchdog(StreamHealth.IDLE)
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
        stopWatchdog(StreamHealth.IDLE)
        val exo = player ?: return
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        EnigmaLog.player("live stream released for zap")
    }

    fun allowPlayback() {
        playbackEnabled = true
    }

    /**
     * Stops the live stream so an app update downloads at full speed, in either viewing
     * mode and for every view of this player (full screen, mini player, audio-sync preview).
     * Until [releaseHold], [play], reconnects and settings rebuilds do not start anything;
     * the latest requested stream is remembered instead.
     */
    fun holdForUpdate() {
        if (!hold.hold(currentUrl?.let { StreamRequest(it, streamAuthorization, checkStart) })) return
        _held.value = true
        reconnectJob?.cancel()
        reconnectJob = null
        currentUrl = null
        failures = 0
        stopWatchdog(StreamHealth.IDLE)
        player?.let { exo ->
            exo.playWhenReady = false
            exo.stop()
            exo.clearMediaItems()
        }
        _cues.value = emptyList()
        EnigmaLog.player("live stream stopped for app update")
    }

    /** Ends [holdForUpdate]; with [resume] the stream that was playing (or last asked for) starts again. */
    fun releaseHold(resume: Boolean) {
        if (!hold.held) return
        val request = hold.release(resume)
        _held.value = false
        EnigmaLog.player("app update hold released, resume=${request != null}")
        if (request != null) {
            play(request.url, force = true, authorization = request.authorization, checkStart = request.checkStart)
        }
    }

    /**
     * Starts [url]. [authorization] goes along as the `Authorization` header (Enigma2
     * stream authentication). With [checkStart] the start is watched: a stream the
     * receiver refuses ends up in [health] and is not retried endlessly.
     */
    fun play(url: String, force: Boolean = false, authorization: String? = null, checkStart: Boolean = false) {
        if (!playbackEnabled) return
        if (!hold.admit(StreamRequest(url, authorization, checkStart))) return
        val exo = ensurePlayer()
        if (!force && url == currentUrl && exo.playerError == null && exo.playbackState != Player.STATE_IDLE) {
            return
        }
        currentUrl = url
        streamAuthorization = authorization
        this.checkStart = checkStart
        startWatchdog()
        resetStats()
        // Track overrides belong to the previous stream; the preferred language stays.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
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

    /** Plays audio track [id] (from [tracks]) and prefers its language on the next channels too. */
    fun selectAudio(id: String) {
        val exo = player ?: return
        val (group, track) = TrackMenus.find(exo.currentTracks, id, C.TRACK_TYPE_AUDIO) ?: return
        val language = group.getTrackFormat(track).language
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .setPreferredAudioLanguage(language)
            .build()
        EnigmaLog.player("audio track $id ${language.orEmpty()}")
    }

    /**
     * Shows subtitle track [id] (from [tracks]), or none for null. The caller saves the
     * on/off choice in the settings; it is applied here at once so nothing restarts.
     */
    fun chooseSubtitles(id: String?) {
        settings = settings.copy(subtitlesEnabled = id != null)
        val exo = player ?: return
        if (id == null) {
            selectSubtitles(exo)
            publish()
            return
        }
        val (group, track) = TrackMenus.find(exo.currentTracks, id, C.TRACK_TYPE_TEXT) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
        EnigmaLog.player("subtitle track $id")
    }

    /** Starts the current stream again from scratch (quick options "Restart stream"). */
    fun restart(): Boolean {
        val url = currentUrl ?: return false
        failures = 0
        play(url, force = true, authorization = streamAuthorization, checkStart = checkStart)
        return true
    }

    fun reconnect() {
        val url = currentUrl ?: return
        if (!settings.autoReconnect && failures > 0) return
        play(url, force = true, authorization = streamAuthorization, checkStart = checkStart)
    }

    fun release() {
        reconnectJob?.cancel()
        stopWatchdog(StreamHealth.IDLE)
        releasePlayer()
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        bytesLoaded.set(0)
        firstFrame = false
        lastFailure = StreamFailure.NONE
        startedAtMs = System.currentTimeMillis()
        if (!checkStart) {
            _health.value = StreamHealth.IDLE
            return
        }
        _health.value = StreamHealth.STARTING
        watchdog = ownScope.launch {
            while (true) {
                if (checkHealth()) break
                delay(500)
            }
        }
    }

    private fun stopWatchdog(health: StreamHealth) {
        watchdog?.cancel()
        watchdog = null
        _health.value = health
    }

    /** Returns true when the start has a final answer. */
    private fun checkHealth(): Boolean {
        val exo = player ?: return true
        val tracks = exo.currentTracks
        val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        val health = StreamStartCheck.evaluate(
            elapsedMs = System.currentTimeMillis() - startedAtMs,
            bytes = bytesLoaded.get(),
            firstFrame = firstFrame,
            hasVideoTrack = hasVideo,
            audioPlaying = exo.isPlaying,
            failure = lastFailure,
        )
        if (health != _health.value) EnigmaLog.player("stream check $health (${bytesLoaded.get()} bytes)")
        _health.value = health
        return when (health) {
            StreamHealth.PLAYING -> {
                watchdog = null
                true
            }
            StreamHealth.NO_TUNER, StreamHealth.AUTH_REQUIRED, StreamHealth.UNREACHABLE -> {
                // Hang up so the receiver frees whatever it had set up, and do not retry.
                reconnectJob?.cancel()
                reconnectJob = null
                currentUrl = null
                exo.playWhenReady = false
                exo.stop()
                exo.clearMediaItems()
                watchdog = null
                true
            }
            StreamHealth.FAILED -> {
                watchdog = null
                true
            }
            else -> false
        }
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
        val renderers = LiveRenderersFactory(context, settings.hdmiPassthrough, audioSync)
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
        val http = OkHttpDataSource.Factory(clientFactory.streamClient())
            .setTransferListener(byteCounter)
        val withAuthorization: DataSource.Factory = ResolvingDataSource.Factory(http) { spec: DataSpec ->
            val header = streamAuthorization
            if (header == null) spec else spec.withAdditionalHeaders(mapOf("Authorization" to header))
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(withAuthorization, extractors)
            .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy())
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
        exo.addAnalyticsListener(loadErrors)
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
            audioLabel = TrackLabels.audio(audio),
            audioTrackCount = exo.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
            subtitleLabel = TrackLabels.subtitle(exo.currentTracks, settings.subtitlesEnabled),
            subtitleTrackCount = exo.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT },
            pixelRatio = exo.videoSize.pixelWidthHeightRatio,
        )
        _tracks.value = TrackMenus.build(exo.currentTracks, settings.subtitlesEnabled)
    }

    private fun resetStats() {
        meter.reset()
        droppedFrames = 0
        stalls = 0
        wasReady = false
        _stats.value = StreamStats()
    }

    /** Once a second: bitrate from the bytes that came in, plus the frame and stall counters. */
    private fun startStatsSampler() {
        ownScope.launch {
            var lastBytes = totalBytes.get()
            var lastAt = System.currentTimeMillis()
            while (true) {
                delay(STATS_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val bytes = totalBytes.get()
                if (currentUrl != null && player?.playWhenReady == true) {
                    meter.add(bytes - lastBytes, now - lastAt)
                    _stats.value = StreamStats(
                        bitrateKbps = meter.kbps,
                        droppedFrames = droppedFrames,
                        stalls = stalls,
                        bufferMs = player?.totalBufferedDuration ?: 0L,
                    )
                }
                lastBytes = bytes
                lastAt = now
            }
        }
    }

    init {
        startStatsSampler()
    }

    private val byteCounter = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            bytesLoaded.addAndGet(bytesTransferred.toLong())
            totalBytes.addAndGet(bytesTransferred.toLong())
        }
    }

    /** Load errors that the player retries quietly still tell the start check what went wrong. */
    private val loadErrors = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            // Media3 marks errors it will not retry as "canceled"; they still say why the start failed.
            lastFailure = StreamFailures.classify(error)
            EnigmaLog.player("load error ${error.javaClass.simpleName} → $lastFailure")
            if (checkStart && watchdog != null) checkHealth()
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@LivePlaybackController.droppedFrames += droppedFrames
        }
    }

    private fun scheduleReconnect() {
        if (!settings.autoReconnect) return
        // A refused start is final; the screen offers to try again.
        if (checkStart && _health.value in FINAL_REFUSALS) return
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
        override fun onPlayerError(error: PlaybackException) {
            EnigmaLog.player("error ${error.errorCodeName}")
            if (checkStart) {
                lastFailure = StreamFailures.classify(error)
                if (watchdog != null) checkHealth()
            }
            publish()
            scheduleReconnect()
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
            if (checkStart && watchdog != null) checkHealth()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                failures = 0
                wasReady = true
            }
            // Running out of data after the picture had started is a stall.
            if (playbackState == Player.STATE_BUFFERING && wasReady && player?.playWhenReady == true) stalls += 1
            if (playbackState == Player.STATE_ENDED && checkStart && watchdog != null) {
                lastFailure = StreamFailure.ENDED
                checkHealth()
            }
            publish()
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

private val FINAL_REFUSALS = setOf(StreamHealth.NO_TUNER, StreamHealth.AUTH_REQUIRED, StreamHealth.UNREACHABLE)

@OptIn(UnstableApi::class)
private class LiveRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val audioSync: AudioSyncOffsets,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val detected = AudioTrackAudioOutputProvider.Builder(context).build()
        val output = if (passthrough) detected else PcmAudioOutputProvider(detected)
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioOutputProvider(output)
            .build()
        return AudioSyncSink(sink, audioSync)
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

/** Settings that can change while a stream plays, without building a new player. */
private fun PlaybackSettings.withoutInPlaceOptions() = copy(audioDelayPcmMs = 0, audioDelayPassthroughMs = 0, subtitlesEnabled = true)

private const val STATS_INTERVAL_MS = 1_000L
