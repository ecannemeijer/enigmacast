package com.enigmacast.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.enigmacast.data.network.OkHttpClientFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import com.enigmacast.domain.osdinfo.TsStreamFacts
import com.enigmacast.domain.model.PlaybackSettings
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.tuner.RestartBudget
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
class LiveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(8) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // A refused login or an unknown service does not get better by asking again.
        val code = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code == 401 || code == 403 || code == 404) return C.TIME_UNSET
        // An unreadable format (e.g. an HLS playlist opened as TS) stays unreadable; let the
        // controller decide instead of retrying the same load forever.
        if (loadErrorInfo.exception is ParserException) return C.TIME_UNSET
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
    private val owner: PlaybackOwner,
) {
    init {
        // IPTV started: this player stops, but may play again when asked (not disabled).
        owner.register(PlaybackOwner.Owner.ENIGMA2) { yieldToOther() }
        if (com.enigmacast.BuildConfig.DEBUG) owner.log = { EnigmaLog.player(it) }
    }

    /** Stops for the other player without blocking later [play] calls. */
    fun yieldToOther() {
        val enabled = playbackEnabled
        stop()
        playbackEnabled = enabled
    }

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    /** A recording file, not a live tuner stream. It must not be restarted when it ends. */
    private var filePlayback = false
    /** Length of the recording when the file itself does not say how long it is. */
    private var fileDurationMs = 0L
    private var fileClock: Job? = null
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

    /** The stream that plays is an internet / IPTV URL, not the receiver's 8001/8002 stream. */
    private var internet = false
    /** False until the new item reports its own metadata, so the previous song is not shown. */
    private var metadataReady = false
    private var internetMime: String? = null
    private var hlsTried = false
    private var internetAttempts = 0

    /**
     * Enigma2 MPEG-TS is read by [TimeshiftPump] into [store], and ExoPlayer reads that
     * buffer. Pausing the player then freezes the picture without letting the tuner socket go.
     */
    @Volatile
    private var store: TimeshiftStore? = null
    private var pump: TimeshiftPump? = null
    private var timeshiftGeneration = 0
    @Volatile
    private var timeshiftPaused = false
    private val _timeshiftFull = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the pause buffer is full and playback has jumped back to live. */
    val timeshiftFull: SharedFlow<Unit> = _timeshiftFull.asSharedFlow()

    /** True while an internet / IPTV URL is the current stream (quality logic ignores it). */
    val playingInternet: Boolean get() = internet && currentUrl != null
    private val bytesLoaded = AtomicLong()
    private val hold = PlaybackHold()
    private val _held = MutableStateFlow(false)

    /** True while live playback is paused for an app update download (see [holdForUpdate]). */
    val held: StateFlow<Boolean> = _held.asStateFlow()
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Deleting an old pause buffer (up to its full size on disk) never runs on Main. */
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /**
     * The live picture stayed down: unreachable at once, or still failed after the reconnects.
     * One signal per play, so a fallback that also fails does not switch straight back.
     */
    private val _gaveUp = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val gaveUp: SharedFlow<Unit> = _gaveUp.asSharedFlow()
    private var gaveUpSent = false
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
    private val _streamFacts = MutableStateFlow(TsStreamFacts())

    /**
     * What the receiver's MPEG-TS says about its own service (CAIDs, teletext, scrambled or
     * not); empty for internet streams. Updated from the loader thread.
     */
    val streamFacts: StateFlow<TsStreamFacts> = _streamFacts.asStateFlow()
    private val tsInspector = TsInspector { facts -> _streamFacts.value = facts }

    /** Read on the loader thread: only the receiver's own TS streams are inspected. */
    @Volatile
    private var inspectStream = false

    /** Measured bitrate, dropped frames and stalls of the stream that plays. */
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()
    private val meter = ThroughputMeter()
    private val totalBytes = AtomicLong()
    private var droppedFrames = 0
    private var stalls = 0
    private var wasReady = false
    private val audioSync = AudioSyncOffsets()
    private val subtitleSync = SubtitleSyncOffset()

    /** Whether the current audio goes out undecoded over HDMI; null before the first track. */
    val audioPassthroughActive: StateFlow<Boolean?> = audioSync.passthroughActive

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        ensurePlayer()
    }

    fun exoPlayer(): ExoPlayer = ensurePlayer()

    fun updateSettings(settings: PlaybackSettings) {
        audioSync.set(settings.audioDelayPcmMs, settings.audioDelayPassthroughMs)
        subtitleSync.set(settings.subtitleDelayMs)
        if (this.settings == settings) return
        val inPlace = this.settings.withoutInPlaceOptions() == settings.withoutInPlaceOptions()
        this.settings = settings
        if (inPlace) {
            // The sink reads a new delay on its next position update, and subtitles switch
            // through the track selection; neither needs a new player.
            EnigmaLog.player("audio delay pcm=${settings.audioDelayPcmMs}ms hdmi=${settings.audioDelayPassthroughMs}ms subtitles=${settings.subtitlesEnabled} subtitleDelay=${settings.subtitleDelayMs}ms")
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
            if (url != null) play(url, force = true, authorization = streamAuthorization, checkStart = checkStart, internet = internet)
        }
    }

    fun stop() {
        playbackEnabled = false
        endFilePlayback()
        currentUrl = null
        reconnectJob?.cancel()
        stopWatchdog(StreamHealth.IDLE)
        player?.let { exo ->
            exo.playWhenReady = false
            exo.stop()
        }
        retireTimeshift()
        clientFactory.cancelStream()
        releasePlayer()
        tsInspector.reset()
        _cues.value = emptyList()
        _subtitlesAvailable.value = false
        _state.value = PlayerState()
    }

    /** True while a live URL is loaded, so a zap must wait for that socket to close. */
    fun hasOpenStream(): Boolean = currentUrl != null

    /**
     * Drops the live MPEG-TS source before a zap so the receiver can free the tuner.
     * The same ExoPlayer is kept; [release] is for leaving the screen.
     * Returns a ticket for [playAfterRelease]. Until that play, player retries cannot
     * open the Enigma2 stream again and keep the tuner.
     */
    fun stopForZap(): Int {
        endFilePlayback()
        reconnectJob?.cancel()
        reconnectJob = null
        currentUrl = null
        failures = 0
        internet = false
        stopWatchdog(StreamHealth.IDLE)
        tsInspector.reset()
        // Stop the player before hanging up. Its read is on the timeshift buffer, and the
        // pump is what holds the tuner socket until that socket is cancelled.
        val exo = player
        exo?.playWhenReady = false
        exo?.stop()
        exo?.clearMediaItems()
        retireTimeshift()
        val ticket = clientFactory.holdStreamClosed()
        EnigmaLog.player("live stream released for zap")
        return ticket
    }

    /** The hold from the latest [stopForZap], or 0 when none was taken. */
    fun streamHoldTicket(): Int = clientFactory.streamHoldTicket()

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
        if (!hold.hold(currentUrl?.let { StreamRequest(it, streamAuthorization, checkStart, internet) })) return
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
        retireTimeshift()
        clientFactory.cancelStream()
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
            play(request.url, force = true, authorization = request.authorization, checkStart = request.checkStart, internet = request.internet)
        }
    }

    /**
     * Starts [url]. [authorization] goes along as the `Authorization` header (Enigma2
     * stream authentication). With [checkStart] the start is watched: a stream the
     * receiver refuses ends up in [health] and is not retried endlessly.
     */
    fun play(
        url: String,
        force: Boolean = false,
        authorization: String? = null,
        checkStart: Boolean = false,
        internet: Boolean = false,
        keepFailure: Boolean = false,
    ) {
        if (!playbackEnabled) return
        if (!keepFailure) gaveUpSent = false
        // The IPTV page plays in front: a background Enigma2 start (reconnect, mini player) must not
        // take the decoder and the output from it (black IPTV picture with Enigma2 sound).
        if (!owner.mayStart(PlaybackOwner.Owner.ENIGMA2)) { EnigmaLog.player("enigma2 start refused: IPTV in front"); return }
        if (!hold.admit(StreamRequest(url, authorization, checkStart, internet))) return
        owner.claim(PlaybackOwner.Owner.ENIGMA2)
        val exo = ensurePlayer()
        if (!force && url == currentUrl && exo.playerError == null && exo.playbackState != Player.STATE_IDLE) {
            return
        }
        // A new request (not an automatic retry) gets a fresh retry budget and format guess.
        internetAttempts = 0
        hlsTried = false
        internetMime = null
        endFilePlayback()
        start(url, authorization, checkStart, internet)
    }

    /**
     * Starts [url] only when [ticket] is still the latest [stopForZap].
     * A newer zap owns the closed socket and this call does not open a second one.
     */
    fun playAfterRelease(
        ticket: Int,
        url: String,
        force: Boolean = false,
        authorization: String? = null,
        checkStart: Boolean = false,
        internet: Boolean = false,
    ) {
        if (clientFactory.streamHoldTicket() != ticket) return
        if (!clientFactory.releaseStream(ticket)) return
        play(url, force, authorization, checkStart, internet)
    }

    private fun start(url: String, authorization: String?, checkStart: Boolean, internet: Boolean) {
        val exo = ensurePlayer()
        reconnectJob?.cancel()
        reconnectJob = null
        // Release the previous loader before the buffer is swapped, so it cannot open a
        // second Enigma2 socket while the new pump is taking the tuner. Retire first so
        // cancelling the socket is not reported as a stream that failed.
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        retireTimeshift()
        // The zap hold blocks player retries. Open the gate only now, then drop any socket
        // that is still listed, and only then ask the receiver for the new stream.
        clientFactory.releaseStream(clientFactory.streamHoldTicket())
        clientFactory.cancelStream()
        currentUrl = url
        metadataReady = false
        streamAuthorization = authorization
        this.checkStart = checkStart
        this.internet = internet
        inspectStream = !internet
        tsInspector.reset()
        startWatchdog()
        if (internet) _health.value = StreamHealth.STARTING
        resetStats()
        // Track overrides belong to the previous stream; the preferred language stays.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        val builder = MediaItem.Builder()
        val mime: String?
        if (internet) {
            // HLS / DASH / TS by URL; unknown → Media3 sniffs it (and HLS is tried once after a parse error).
            mime = internetMime ?: InternetMedia.mimeTypeFor(url)
            builder.setUri(url)
            if (mime != null) builder.setMimeType(mime)
            // No forced offset: live HLS starts at the playlist's live edge (its own hold-back).
        } else {
            // No live offset: that would pull a paused stream back to the live edge.
            beginTimeshift(url, authorization)
            mime = MimeTypes.VIDEO_MP2T
            builder.setUri(TIMESHIFT_URI).setMimeType(mime)
        }
        exo.setMediaItem(builder.build(), true)
        exo.prepare()
        exo.playWhenReady = true
        EnigmaLog.player(
            "switching live stream ${EnigmaLog.sanitizeUrl(url)} auth=${if (authorization == null) "none" else "basic"}" +
                if (internet) " kind=internet type=${mime ?: "auto"}" else " timeshift",
        )
    }

    /**
     * Pauses or resumes the Enigma2 stream in place. Returns false when this playback
     * is a file, an internet stream, or nothing is loaded; the caller can do something else.
     */
    fun toggleTimeshiftPause(): Boolean {
        val exo = player ?: return false
        if (filePlayback || internet || store == null || currentUrl == null) return false
        if (exo.playerError != null || exo.playbackState == Player.STATE_IDLE) return false
        if (exo.playWhenReady) {
            timeshiftPaused = true
            exo.pause()
        } else {
            timeshiftPaused = false
            exo.play()
        }
        publish()
        return true
    }

    /** True while a recording file is what the player shows, so a live zap does not replace it. */
    fun isFilePlayback(): Boolean = filePlayback

    /**
     * Plays a finished or in-progress recording as a file, not as a live channel.
     * Does not restart when the file ends, and does not take the viewer's tuner slot.
     */
    /** Plays a recording file; [startMs] > 0 resumes there (Media3 seeks once the TS duration is known). */
    fun playRecording(url: String, authorization: String?, durationMs: Long = 0L, startMs: Long = 0L) {
        if (!playbackEnabled) return
        if (!owner.mayStart(PlaybackOwner.Owner.ENIGMA2)) { EnigmaLog.player("enigma2 recording start refused: IPTV in front"); return }
        owner.claim(PlaybackOwner.Owner.ENIGMA2)
        val exo = ensurePlayer()
        filePlayback = true
        fileDurationMs = durationMs.coerceAtLeast(0L)
        reconnectJob?.cancel()
        reconnectJob = null
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        retireTimeshift()
        clientFactory.cancelStream()
        currentUrl = url
        streamAuthorization = authorization
        checkStart = false
        internet = false
        inspectStream = false
        stopWatchdog(StreamHealth.IDLE)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build()
        exo.setPlaybackSpeed(1f)
        if (startMs > 0) exo.setMediaItem(item, startMs) else exo.setMediaItem(item, true)
        exo.prepare()
        exo.playWhenReady = true
        EnigmaLog.player("playing recording ${EnigmaLog.sanitizeUrl(url)} from ${startMs / 1000}s")
        seekCheckLogged = false
        watchFileClock()
    }

    /** Jumps within the recording and keeps playing. */
    fun seekFile(positionMs: Long) {
        val exo = player ?: return
        if (!filePlayback) return
        val end = exo.duration.takeIf { it > 0 } ?: fileDurationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        exo.seekTo(positionMs.coerceIn(0, end))
        exo.playWhenReady = true
        publish()
    }

    /** Leaves the recording so a channel can play again. */
    fun stopFile() {
        if (!filePlayback) return
        endFilePlayback()
        reconnectJob?.cancel()
        reconnectJob = null
        currentUrl = null
        val exo = player ?: return
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        publish()
    }

    private fun endFilePlayback() {
        player?.setPlaybackSpeed(1f)
        filePlayback = false
        fileDurationMs = 0L
        fileClock?.cancel()
        fileClock = null
    }

    /** Recording speed (OSD settings); a new file starts at normal speed. */
    fun setFileSpeed(speed: Float) {
        val exo = player ?: return
        if (!filePlayback) return
        exo.setPlaybackSpeed(speed)
    }

    fun toggleFilePause() {
        val exo = player ?: return
        if (!filePlayback) return
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0)
            exo.playWhenReady = true
        } else {
            exo.playWhenReady = !exo.playWhenReady
        }
        publish()
    }

    private var seekCheckLogged = false

    private fun watchFileClock() {
        fileClock?.cancel()
        fileClock = ownScope.launch {
            while (filePlayback) {
                player?.let { exo ->
                    if (!seekCheckLogged && exo.playbackState == Player.STATE_READY) {
                        seekCheckLogged = true
                        EnigmaLog.player("recording seekable=${exo.isCurrentMediaItemSeekable} duration=${exo.duration}")
                    }
                }
                publish()
                delay(250)
            }
        }
    }

    /** An internet stream failed: try HLS once, retry within the cap, or stop and say why. */
    private fun onInternetFailure() {
        val url = currentUrl ?: return
        val exo = player ?: return
        val mime = internetMime ?: InternetMedia.mimeTypeFor(url)
        when (val action = InternetRetry.next(lastFailure, internetAttempts, mime, hlsTried, settings.autoReconnect)) {
            InternetRetryAction.TryHls -> {
                hlsTried = true
                internetMime = MimeTypes.APPLICATION_M3U8
                EnigmaLog.player("internet stream could not be read as a plain stream; trying HLS")
                start(url, streamAuthorization, checkStart, internet = true)
            }
            is InternetRetryAction.Retry -> {
                internetAttempts += 1
                EnigmaLog.player("internet stream ${lastFailure.name.lowercase()}: try $internetAttempts/${RestartBudget.MAX_ATTEMPTS} in ${action.delayMs} ms")
                reconnectJob?.cancel()
                reconnectJob = (scope ?: ownScope).launch {
                    delay(action.delayMs)
                    if (currentUrl == url) start(url, streamAuthorization, checkStart, internet = true)
                }
            }
            is InternetRetryAction.GiveUp -> {
                EnigmaLog.player("internet stream stopped after $internetAttempts tries: ${action.health.name.lowercase()}")
                reconnectJob?.cancel()
                reconnectJob = null
                exo.playWhenReady = false
                exo.stop()
                _health.value = action.health
                publish()
                noteGaveUp()
            }
        }
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
        EnigmaLog.subs("live choose ${id ?: "off"} at pos=${exo.currentPosition}ms dur=${exo.duration}ms seekable=${exo.isCurrentMediaItemSeekable}")
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
        play(url, force = true, authorization = streamAuthorization, checkStart = checkStart, internet = internet)
        return true
    }

    fun reconnect() {
        // ConnectivityManager delivers onAvailable on its own thread. ExoPlayer only
        // accepts calls from the main thread, so the restart is posted there.
        ownScope.launch {
            val url = currentUrl ?: return@launch
            if (!settings.autoReconnect && failures > 0) return@launch
            start(url, streamAuthorization, checkStart, internet)
        }
    }

    fun release() {
        reconnectJob?.cancel()
        stopWatchdog(StreamHealth.IDLE)
        player?.let { exo ->
            exo.playWhenReady = false
            exo.stop()
        }
        retireTimeshift()
        clientFactory.cancelStream()
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
                if (health == StreamHealth.UNREACHABLE) noteGaveUp()
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

    /**
     * Drops the pause buffer and ignores callbacks from the pump that was just stopped.
     * The player should already be stopped, so its loader is not opening a new socket.
     */
    private fun retireTimeshift() {
        timeshiftGeneration += 1
        timeshiftPaused = false
        val ending = pump
        val endingStore = store
        pump = null
        store = null
        ending?.stop()
        if (endingStore != null) {
            // Closed now (wakes a waiting reader); the chunk files go in the background.
            endingStore.close()
            diskScope.launch { endingStore.delete() }
        }
    }

    /** Receiver MPEG-TS: one pump owns the tuner socket and ExoPlayer reads the buffer. */
    private fun beginTimeshift(url: String, authorization: String?) {
        retireTimeshift()
        val generation = timeshiftGeneration
        val root = File(context.cacheDir, "timeshift")
        val created = TimeshiftStore(File(root, generation.toString()))
        store = created
        diskScope.launch {
            root.listFiles()?.forEach { child ->
                if (child.name != generation.toString()) child.deleteRecursively()
            }
        }
        val started = TimeshiftPump(
            calls = clientFactory.streamCalls(),
            store = created,
            url = url,
            authorization = authorization,
            onFull = { onTimeshiftFull(generation) },
            onEnded = { onTimeshiftEnded(generation) },
        )
        pump = started
        started.start()
    }

    private fun onTimeshiftFull(generation: Int) {
        ownScope.launch {
            if (generation != timeshiftGeneration) return@launch
            _timeshiftFull.tryEmit(Unit)
            val url = currentUrl ?: return@launch
            EnigmaLog.player("timeshift full, back to live")
            start(url, streamAuthorization, checkStart, internet)
        }
    }

    /** A dropped socket while paused never reaches the player, so reconnect from here. */
    private fun onTimeshiftEnded(generation: Int) {
        ownScope.launch {
            if (generation != timeshiftGeneration || !timeshiftPaused) return@launch
            EnigmaLog.player("timeshift stream ended while paused")
            scheduleReconnect()
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
        // A byte target that fits the heap, and size before time: "time over size" let a stream
        // whose buffered duration stays short (a track that does not advance, a stream that never
        // finishes preparing) fill the whole heap, then stop loading and re-check every 10 ms.
        // Live seconds take little room: even 2 s of UHD is a few MB of the 16–64 MB target.
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playback, rebuffer)
            .setTargetBufferBytes(PlayerBuffers.targetBytes(BufferPolicy.Kind.LIVE))
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    @OptIn(ExperimentalApi::class)
    private fun buildPlayer(): ExoPlayer {
        val surfaceView = VideoOutput.useSurfaceView(settings.videoCompatibility)
        val compatibility = VideoOutput.compatibilityActive(settings.videoCompatibility, VideoQuirks.thisDevice)
        VideoOutput.surfaceView = surfaceView
        val decoderMode = settings.videoDecoder
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (!MimeTypes.isVideo(mime)) return@MediaCodecSelector infos
            val byName = infos.associateBy { it.name }
            VideoOutput.order(decoderMode, infos.map { it.name to it.hardwareAccelerated }).mapNotNull { byName[it] }
        }
        val renderers = LiveRenderersFactory(context, settings.hdmiPassthrough, audioSync, subtitleSync)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(selector)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // The early-render window only helps a TextureView; a SurfaceView keeps the default timing.
        if (!surfaceView) renderers.setVideoRendererEarlySchedulingThresholdUs(TEXTURE_VIEW_EARLY_SCHEDULING_US)
        EnigmaLog.player("video decoder=$decoderMode surface=${if (surfaceView) "SurfaceView" else "TextureView"} compatibility=$compatibility quirk=${VideoQuirks.thisDevice}")
        val trackSelector = DefaultTrackSelector(context)
        val preferredAudio = if (settings.hdmiPassthrough) PASSTHROUGH_AUDIO else STEREO_AUDIO
        trackSelector.parameters = trackSelector.buildUponParameters()
            // Tunneled playback is broken on several Amlogic/Realtek sticks; TiviMate leaves it off too.
            .setTunnelingEnabled(false)
            .setPreferredAudioMimeTypes(*preferredAudio)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesEnabled)
            .setSelectUndeterminedTextLanguage(settings.subtitlesEnabled)
            .build()
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        val http = OkHttpDataSource.Factory(clientFactory.streamCalls())
            .setTransferListener(byteCounter)
        val withAuthorization: DataSource.Factory = ResolvingDataSource.Factory(http) { spec: DataSpec ->
            val header = streamAuthorization
            val headers = buildMap {
                if (header != null) put("Authorization", header)
                // Seeking in a recording needs the file length (Content-Length) and Range requests;
                // a compressed answer would hide the length and make the file unseekable.
                if (filePlayback) put("Accept-Encoding", "identity")
            }
            if (headers.isEmpty()) spec else spec.withAdditionalHeaders(headers)
        }
        // A recording on this TV is a file. OkHttp only accepts http(s), so local files go through a file source.
        val localOrHttp = DefaultDataSource.Factory(context, withAuthorization)
        val inspected = DataSource.Factory {
            val buffered = store
            val upstream = if (buffered != null) {
                TimeshiftDataSource(buffered, byteCounter)
            } else {
                localOrHttp.createDataSource()
            }
            InspectingDataSource(upstream, tsInspector) { inspectStream }
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(inspected, extractors)
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
        // Display refresh-rate switching re-inits the video layer on some sticks; compatibility leaves the mode alone.
        if (compatibility) exo.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
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
            audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
            bufferMs = exo.totalBufferedDuration,
            liveOffsetMs = if (liveOffset == C.TIME_UNSET) -1 else liveOffset,
            error = exo.playerError?.errorCodeName,
            audioLabel = TrackLabels.audio(audio),
            audioTrackCount = exo.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
            subtitleLabel = TrackLabels.subtitle(exo.currentTracks, settings.subtitlesEnabled),
            subtitleTrackCount = exo.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT },
            pixelRatio = exo.videoSize.pixelWidthHeightRatio,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = exo.duration.takeIf { it > 0 } ?: fileDurationMs,
            bufferedPositionMs = exo.bufferedPosition.coerceAtLeast(0),
            playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED,
            filePlayback = filePlayback,
            streamTitle = streamTitle(exo),
            streamArtist = streamArtist(exo),
            timeshiftPaused = timeshiftPaused,
        )
        _tracks.value = TrackMenus.build(exo.currentTracks, settings.subtitlesEnabled)
    }

    private fun streamTitle(exo: Player): String {
        if (!metadataReady) return ""
        return exo.mediaMetadata.title?.toString()?.trim().orEmpty()
    }

    private fun streamArtist(exo: Player): String {
        if (!metadataReady) return ""
        val meta = exo.mediaMetadata
        return (meta.artist ?: meta.albumArtist)?.toString()?.trim().orEmpty()
    }

    private fun resetStats() {
        meter.reset()
        droppedFrames = 0
        stalls = 0
        wasReady = false
        _stats.value = StreamStats()
    }

    private val starved = StarvedBuffer(STARVED_RECOVER_MS)

    /**
     * Buffering, not loading and nothing to play: the load control will not load again by itself.
     * A tuner stream reconnects (a fresh start at the live edge), a recording reloads where it is.
     */
    private fun recoverIfStarved(now: Long) {
        val exo = player ?: return
        val buffering = exo.playbackState == Player.STATE_BUFFERING
        if (!starved.update(buffering, exo.isLoading, exo.totalBufferedDuration, now)) return
        EnigmaLog.player("buffer starved (${exo.totalBufferedDuration} ms, not loading): ${if (filePlayback) "reload at position" else "reconnect"}")
        if (filePlayback) exo.seekTo(exo.currentPosition) else scheduleReconnect()
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
                    recoverIfStarved(now)
                    meter.add(bytes - lastBytes, now - lastAt)
                    _stats.value = StreamStats(
                        bitrateKbps = meter.kbps,
                        droppedFrames = droppedFrames,
                        stalls = stalls,
                        bufferMs = player?.totalBufferedDuration ?: 0L,
                    )
                } else starved.reset()
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
            // Closing the previous stream on purpose is not a failure of the one that is starting.
            if (StreamFailures.isCancellation(error)) return
            // Media3 marks errors it will not retry as "canceled"; they still say why the start failed.
            lastFailure = StreamFailures.classify(error)
            EnigmaLog.player("load error ${error.javaClass.simpleName} → $lastFailure")
            if (checkStart && watchdog != null) checkHealth()
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@LivePlaybackController.droppedFrames += droppedFrames
        }
    }

    private fun noteGaveUp() {
        if (gaveUpSent || filePlayback) return
        gaveUpSent = true
        _gaveUp.tryEmit(Unit)
    }

    private fun scheduleReconnect() {
        if (filePlayback) return
        if (!settings.autoReconnect) {
            noteGaveUp()
            return
        }
        // A refused start is final; the screen offers to try again.
        if (checkStart && _health.value in FINAL_REFUSALS) {
            noteGaveUp()
            return
        }
        val owner = scope ?: return
        failures += 1
        if (failures >= 3) {
            noteGaveUp()
            return
        }
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
            if (StreamFailures.isCancellation(error)) return
            EnigmaLog.player("error ${error.errorCodeName}")
            if (internet) {
                val exo = player
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && exo != null) {
                    // Live HLS fell out of the window (e.g. after a pause): jump back to the live edge.
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    return
                }
                lastFailure = StreamFailures.classify(error)
                publish()
                onInternetFailure()
                return
            }
            if (checkStart) {
                lastFailure = StreamFailures.classify(error)
                if (watchdog != null) checkHealth()
            }
            publish()
            scheduleReconnect()
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
            if (internet) _health.value = StreamHealth.PLAYING
            if (checkStart && watchdog != null) checkHealth()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                failures = 0
                wasReady = true
                if (internet) {
                    internetAttempts = 0
                    // Audio-only internet radio never renders a frame; READY is enough.
                    if (_health.value == StreamHealth.STARTING) _health.value = StreamHealth.PLAYING
                }
            }
            if (playbackState == Player.STATE_ENDED && internet && currentUrl != null) {
                // A live internet stream should not end; treat it like a dropped connection.
                lastFailure = StreamFailure.ENDED
                onInternetFailure()
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

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            metadataReady = false
            publish()
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            metadataReady = true
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

/** Media item the player opens while a [TimeshiftPump] is filling [TimeshiftStore]. */
private const val TIMESHIFT_URI = "timeshift://live"

private val FINAL_REFUSALS = setOf(StreamHealth.NO_TUNER, StreamHealth.AUTH_REQUIRED, StreamHealth.UNREACHABLE)

@OptIn(UnstableApi::class)
private class LiveRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val audioSync: AudioSyncOffsets,
    private val subtitleSync: SubtitleSyncOffset,
) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(OffsetTextRenderer(output, outputLooper, subtitleSync))
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return
        try {
            // After the MediaCodec renderer, so hardware decode and HDMI passthrough stay first.
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        } catch (error: Exception) {
            EnigmaLog.player("ffmpeg audio unavailable: ${error.javaClass.simpleName}")
        }
    }

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
        // A codec the device can decode is downmixed to PCM. One it cannot (AC3, E-AC3, DTS
        // on many Google TV devices) is left to HDMI passthrough when the sink reports it.
        if (mime != null && mime != MimeTypes.AUDIO_RAW && hasDecoder(mime)) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        return super.getFormatSupport(formatConfig)
    }

    private fun hasDecoder(mime: String): Boolean = try {
        MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty()
    } catch (_: MediaCodecUtil.DecoderQueryException) {
        false
    }
}

private val PASSTHROUGH_AUDIO = arrayOf(
    MimeTypes.AUDIO_E_AC3_JOC,
    MimeTypes.AUDIO_E_AC3,
    MimeTypes.AUDIO_AC3,
    MimeTypes.AUDIO_DTS_HD,
    MimeTypes.AUDIO_DTS,
    MimeTypes.AUDIO_AAC,
    MimeTypes.AUDIO_MPEG,
)

private val STEREO_AUDIO = arrayOf(
    MimeTypes.AUDIO_AAC,
    MimeTypes.AUDIO_MPEG,
    MimeTypes.AUDIO_E_AC3_JOC,
    MimeTypes.AUDIO_E_AC3,
    MimeTypes.AUDIO_AC3,
    MimeTypes.AUDIO_DTS_HD,
    MimeTypes.AUDIO_DTS,
)

/** Settings that can change while a stream plays, without building a new player. */
private fun PlaybackSettings.withoutInPlaceOptions() = copy(
    audioDelayPcmMs = 0,
    audioDelayPassthroughMs = 0,
    subtitlesEnabled = true,
    subtitleDelayMs = 0,
)

private const val STATS_INTERVAL_MS = 1_000L
private const val STARVED_RECOVER_MS = 6_000L
