package com.enigmacast.ui.torrent

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.tmdb.TmdbHttp
import com.enigmacast.data.torrent.ScraperRepository
import com.enigmacast.data.torrent.ScraperStatus
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.domain.resume.ResumePolicy
import com.enigmacast.domain.torrent.TmdbEpisode
import com.enigmacast.domain.torrent.TmdbSeason
import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.torrent.TorrentStream
import com.enigmacast.domain.torrent.TorrentTmdb
import com.enigmacast.playback.PlaybackOwner
import com.enigmacast.playback.TrackMenu
import com.enigmacast.playback.TrackMenus
import com.enigmacast.torrent.TorrentEngine
import com.enigmacast.torrent.TorrentStreamingService
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Torrent page shows on top of the grid. */
sealed interface TorrentOverlay {
    data object None : TorrentOverlay
    data class Seasons(val title: TmdbTitle, val imdb: String, val seasons: List<TmdbSeason>, val season: Int, val episodes: List<TmdbEpisode>) : TorrentOverlay
    data class Streams(val title: TmdbTitle, val subtitle: String, val streams: List<TorrentStream>?, val error: Boolean, val resumeKey: String) : TorrentOverlay
    data class Loading(val title: TmdbTitle, val subtitle: String) : TorrentOverlay
}

@HiltViewModel
class TorrentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdb: TmdbHttp,
    private val scraper: ScraperRepository,
    val engine: TorrentEngine,
    private val owner: PlaybackOwner,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val language = Locale.getDefault().toLanguageTag()

    var kind by mutableStateOf(TorrentKind.MOVIE)
        private set
    var items by mutableStateOf<List<TmdbTitle>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var searchOpen by mutableStateOf(false)
        private set
    /** Bumped when a new list replaces the grid (focus goes to its first card). */
    var listToken by mutableIntStateOf(0)
        private set
    var overlay by mutableStateOf<TorrentOverlay>(TorrentOverlay.None)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var page = 0
    private var totalPages = 1
    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var stepJob: Job? = null
    private var loadJob: Job? = null

    val scraperReady get() = scraper.status.value is ScraperStatus.Valid

    init { reload() }

    fun selectKind(k: TorrentKind) {
        if (k == kind) return
        kind = k
        reload()
    }

    fun openSearch() { searchOpen = true }
    fun closeSearch() {
        searchOpen = false
        if (query.isNotEmpty()) { query = ""; reload() }
    }

    /** Typing in the search field: TMDB search after a short pause (debounce). */
    fun onQuery(value: String) {
        query = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reload()
        }
    }

    private fun reload() {
        listJob?.cancel()
        page = 0; totalPages = 1
        items = emptyList()
        loadMore(reset = true)
    }

    /** The focused card is near the end: the next TMDB page. */
    fun onFocusedIndex(index: Int) {
        TorrentTmdb.nextPage(page, totalPages, index, items.size, loading) ?: return
        loadMore(reset = false)
    }

    private fun loadMore(reset: Boolean) {
        val next = page + 1
        val k = kind
        val q = query.trim()
        loading = true
        listJob = viewModelScope.launch {
            val body = if (q.length >= 2) tmdb.searchPage(k == TorrentKind.TV, q, next, language) else tmdb.popular(k == TorrentKind.TV, next, language)
            val result = TorrentTmdb.parsePage(body, k)
            page = next
            totalPages = result.totalPages
            val seen = items.mapTo(HashSet()) { it.key }
            items = items + result.items.filter { seen.add(it.key) }
            loading = false
            if (reset) listToken++
            if (body == null && items.isEmpty()) message = "tmdb"
        }
    }

    /** OK on a card: the IMDb id, then the streams (films) or the season/episode picker (series). */
    fun openTitle(title: TmdbTitle) {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            val tv = title.kind == TorrentKind.TV
            val imdb = TorrentTmdb.parseImdb(tmdb.externalIds(tv, title.id))
            if (imdb == null) { message = "imdb"; return@launch }
            if (!tv) {
                loadStreams(title, imdb, null, null, "")
            } else {
                val seasons = TorrentTmdb.parseSeasons(tmdb.tvBasic(title.id, language))
                if (seasons.isEmpty()) { message = "imdb"; return@launch }
                overlay = TorrentOverlay.Seasons(title, imdb, seasons, seasons.first().number, emptyList())
                chooseSeason(seasons.first().number)
            }
        }
    }

    fun chooseSeason(number: Int) {
        val o = overlay as? TorrentOverlay.Seasons ?: return
        overlay = o.copy(season = number, episodes = emptyList())
        stepJob = viewModelScope.launch {
            val episodes = TorrentTmdb.parseEpisodes(tmdb.season(o.title.id, number, language))
            (overlay as? TorrentOverlay.Seasons)?.takeIf { it.season == number }?.let { overlay = it.copy(episodes = episodes) }
        }
    }

    fun chooseEpisode(episode: TmdbEpisode) {
        val o = overlay as? TorrentOverlay.Seasons ?: return
        stepJob = viewModelScope.launch {
            loadStreams(o.title, o.imdb, o.season, episode.number, "S${o.season} E${episode.number} · ${episode.name}")
        }
    }

    private suspend fun loadStreams(title: TmdbTitle, imdb: String, season: Int?, episode: Int?, subtitle: String) {
        val key = if (season != null) "$imdb:$season:$episode" else imdb
        overlay = TorrentOverlay.Streams(title, subtitle, null, false, key)
        val result = scraper.streams(imdb, season, episode)
        overlay = TorrentOverlay.Streams(title, subtitle, result.getOrNull(), result.isFailure, key)
    }

    fun dismissOverlay() {
        stepJob?.cancel()
        overlay = when (val o = overlay) {
            // Back from the streams of an episode returns to the picker.
            is TorrentOverlay.Streams -> TorrentOverlay.None
            else -> TorrentOverlay.None
        }
    }

    fun dismissMessage() { message = null }

    // ---- Playback ----

    var playing by mutableStateOf<String?>(null)
        private set
    var playerState by mutableStateOf(PlayerState(filePlayback = true))
        private set
    var tracks by mutableStateOf(TrackMenu())
        private set
    var pictureMode by mutableStateOf(PictureMode.AUTO)
        private set
    var audioDelayMs by mutableIntStateOf(0)
        private set
    var subtitleDelayMs by mutableIntStateOf(0)
        private set
    var resumeAsk by mutableStateOf<Long?>(null)
        private set
    var playError by mutableStateOf<String?>(null)
        private set
    private var player: ExoPlayer? = null
    private var resumeKey: String? = null
    private var tickJob: Job? = null
    private val audioSync = com.enigmacast.playback.AudioSyncOffsets()
    private val subtitleSync = com.enigmacast.playback.SubtitleSyncOffset()
    private val resumes = context.getSharedPreferences("torrent_resume", Context.MODE_PRIVATE)

    /** OK on a stream: direct (debrid/http) links play at once, torrents go through the loading page. */
    fun chooseStream(stream: TorrentStream) {
        val o = overlay as? TorrentOverlay.Streams ?: return
        resumeKey = o.resumeKey
        val url = stream.url
        if (url != null) {
            overlay = TorrentOverlay.None
            play(url, o.title.title)
            return
        }
        overlay = TorrentOverlay.Loading(o.title, o.subtitle)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            TorrentStreamingService.start(context)
            val local = runCatching { engine.open(stream) }
            if (overlay !is TorrentOverlay.Loading) return@launch
            local.onSuccess { overlay = TorrentOverlay.None; play(it, o.title.title) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    TorrentStreamingService.stop(context)
                    overlay = TorrentOverlay.Streams(o.title, o.subtitle, o.streams, false, o.resumeKey)
                    message = if (it is com.enigmacast.torrent.TorrentUnavailableException) "engine:${it.abi}" else "torrent"
                }
        }
        viewModelScope.launch {
            while (overlay is TorrentOverlay.Loading) { delay(1000) }
        }
    }

    /** Back on the loading page: cancels and removes the torrent. */
    fun cancelLoading() {
        loadJob?.cancel()
        overlay = TorrentOverlay.None
        engine.stop()
        TorrentStreamingService.stop(context)
    }

    @OptIn(UnstableApi::class)
    fun player(): ExoPlayer = player ?: run {
        // Long read timeout: the local server waits for pieces that are still downloading.
        val http = DefaultHttpDataSource.Factory().setReadTimeoutMs(70_000).setConnectTimeoutMs(10_000).setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setRenderersFactory(com.enigmacast.ui.xtream.IptvRenderersFactory(context, audioSync, subtitleSync))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        playerState = playerState.copy(playbackState = when (state) {
                            Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "IDLE"
                        })
                        if (state == Player.STATE_READY) offerResume()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playerState = playerState.copy(playing = isPlaying) }
                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                        playerState = playerState.copy(videoWidth = size.width, videoHeight = size.height, pixelRatio = size.pixelWidthHeightRatio)
                    }
                    override fun onTracksChanged(t: androidx.media3.common.Tracks) {
                        val on = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        val menu = TrackMenus.build(t, on)
                        tracks = menu
                        playerState = playerState.copy(
                            videoCodec = p.videoFormat?.sampleMimeType.orEmpty(),
                            audioCodec = p.audioFormat?.sampleMimeType.orEmpty(),
                            audioLabel = com.enigmacast.playback.TrackLabels.audio(p.audioFormat),
                            audioTrackCount = menu.audio.size,
                            subtitleLabel = menu.subtitles.firstOrNull { it.selected }?.label ?: com.enigmacast.util.UiText.Empty,
                            subtitleTrackCount = menu.subtitles.size,
                        )
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        EnigmaLog.networkError("torrent playback: ${error.errorCodeName}")
                        playError = error.errorCodeName
                    }
                })
                player = p
            }
    }

    private var resumeOffered = false

    private fun play(url: String, title: String) {
        // One player at a time: Enigma2 and IPTV stop (and free the decoder) first.
        owner.register(PlaybackOwner.Owner.TORRENT) { stopPlayback() }
        owner.claim(PlaybackOwner.Owner.TORRENT)
        viewModelScope.launch {
            val pb = settingsRepository.current().playback
            audioSync.set(pb.audioDelayPcmMs, pb.audioDelayPassthroughMs)
            audioDelayMs = pb.audioDelayPcmMs
            subtitleSync.set(pb.subtitleDelayMs); subtitleDelayMs = pb.subtitleDelayMs
        }
        playError = null
        resumeOffered = false
        playing = title
        playerState = PlayerState(filePlayback = true)
        player().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var n = 0
            while (true) {
                delay(500)
                val p = player ?: break
                playerState = playerState.copy(positionMs = p.currentPosition, durationMs = p.duration.coerceAtLeast(0))
                if (++n % 10 == 0) { saveResume(); engine.refreshStats() }
            }
        }
    }

    private fun offerResume() {
        if (resumeOffered) return
        resumeOffered = true
        val key = resumeKey ?: return
        val saved = resumes.getLong(key, 0L)
        if (saved >= ResumePolicy.MIN_RESUME_MS) { player?.playWhenReady = false; resumeAsk = saved }
    }

    fun answerResume(resume: Boolean) {
        val at = resumeAsk ?: return
        resumeAsk = null
        player?.let { if (resume) it.seekTo(at); it.playWhenReady = true }
    }

    private fun saveResume() {
        val key = resumeKey ?: return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.coerceAtLeast(0)
        val keep = pos >= ResumePolicy.MIN_RESUME_MS && !(dur > 0 && ResumePolicy.watched(pos, dur))
        resumes.edit().apply { if (keep) putLong(key, pos) else remove(key) }.apply()
    }

    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)) }
    fun togglePause() { player?.let { it.playWhenReady = !it.playWhenReady } }
    fun restart() { player?.seekTo(0) }
    fun choosePictureMode(mode: PictureMode) { pictureMode = mode }
    fun chooseAudio(id: String) {
        val p = player ?: return
        val (group, track) = TrackMenus.find(p.currentTracks, id, C.TRACK_TYPE_AUDIO) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, track)).build()
    }
    fun chooseSubtitles(id: String?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (id == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) else {
            val (group, track) = TrackMenus.find(p.currentTracks, id, C.TRACK_TYPE_TEXT) ?: return
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, track))
        }
        p.trackSelectionParameters = b.build()
    }
    fun setAudioDelay(ms: Int) {
        val v = com.enigmacast.playback.AudioSync.clamp(ms)
        audioDelayMs = v
        audioSync.set(v, audioSync.passthroughMs)
    }
    fun setSubtitleDelay(ms: Int) {
        val v = com.enigmacast.playback.SubtitleSync.clamp(ms)
        subtitleDelayMs = v
        subtitleSync.set(v)
    }

    /** Back/stop/end: the position is kept, the player released and the torrent removed with its files. */
    fun stopPlayback() {
        tickJob?.cancel()
        saveResume()
        resumeAsk = null
        player?.release()
        player = null
        playing = null
        owner.release(PlaybackOwner.Owner.TORRENT)
        engine.stop()
        TorrentStreamingService.stop(context)
    }

    fun setForeground(front: Boolean) {
        val o = PlaybackOwner.Owner.TORRENT
        if (front) owner.foreground = o else if (owner.foreground == o) owner.foreground = null
    }

    override fun onCleared() {
        loadJob?.cancel()
        if (playing != null || overlay is TorrentOverlay.Loading) stopPlayback()
        owner.unregister(PlaybackOwner.Owner.TORRENT)
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
    }
}
