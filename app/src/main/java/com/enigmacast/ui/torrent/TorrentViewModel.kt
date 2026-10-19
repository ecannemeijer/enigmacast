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
import com.enigmacast.domain.torrent.LastStream
import com.enigmacast.domain.torrent.TmdbEpisode
import com.enigmacast.domain.torrent.TmdbGenre
import com.enigmacast.domain.torrent.TorrentSort
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
import com.enigmacast.data.tmdb.TmdbJson
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The info page of one TMDB title; [film] grows from the card data to the full TMDB details. */
data class TorrentInfo(
    val title: TmdbTitle,
    val film: com.enigmacast.domain.films.HomeFilm,
    val loading: Boolean = false,
    val seasons: List<TmdbSeason> = emptyList(),
    val season: Int? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
    val episodesLoading: Boolean = false,
    /** Continue watching: the episode Play starts at (season, episode). */
    val resumeEpisode: Pair<Int, Int>? = null,
)

/** What SubSource is asked for the stream being played. */
data class TorrentSubtitleTarget(
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    /** behaviorHints.filename (or the release line of the stream title), for release matching. */
    val fileName: String?,
) {
    /** The TMDB title, then the original title when it differs. */
    fun titles(): List<String> = listOf(title, originalTitle).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
}

/** What the Torrent page shows on top of the grid. */
sealed interface TorrentOverlay {
    data object None : TorrentOverlay
    data class Streams(val title: TmdbTitle, val subtitle: String, val streams: List<TorrentStream>?, val error: Boolean, val resumeKey: String) : TorrentOverlay
    /** [logo]: the TMDB title logo (PNG), shown instead of the title text when there is one. */
    data class Loading(val title: TmdbTitle, val subtitle: String, val logo: String? = null) : TorrentOverlay
    /** Play on a title with a saved place and a remembered stream: resume it, start over, or pick another source. */
    data class ResumeSource(val title: TmdbTitle, val subtitle: String, val atMs: Long, val stream: TorrentStream) : TorrentOverlay
}

@HiltViewModel
class TorrentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdb: TmdbHttp,
    private val scraper: ScraperRepository,
    val engine: TorrentEngine,
    private val owner: PlaybackOwner,
    private val settingsRepository: SettingsRepository,
    private val subtitles: com.enigmacast.data.subsource.SubSourceClient,
    private val library: com.enigmacast.data.torrent.TorrentLibrary,
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
    /** Grid filters (not applied to a search): genre id or null for all, and the order. */
    var genre by mutableStateOf<TmdbGenre?>(null)
        private set
    var sort by mutableStateOf(TorrentSort.POPULAR)
        private set
    /** The current kind's genres, or empty while loading / without TMDB. */
    var genres by mutableStateOf<List<TmdbGenre>>(emptyList())
        private set
    private val genreCache = HashMap<TorrentKind, List<TmdbGenre>>()
    private var genreJob: Job? = null
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

    init { reload(); loadGenres() }

    fun selectKind(k: TorrentKind) {
        if (k == kind) return
        kind = k
        // Movie and series genres are different lists (ids too).
        genre = null
        loadGenres()
        reload()
    }

    fun selectGenre(g: TmdbGenre?) {
        if (g == genre) return
        genre = g
        reload()
    }

    fun selectSort(s: TorrentSort) {
        if (s == sort) return
        sort = s
        reload()
    }

    private fun loadGenres() {
        val k = kind
        genreCache[k]?.let { genres = it; return }
        genres = emptyList()
        genreJob?.cancel()
        genreJob = viewModelScope.launch {
            val list = TorrentTmdb.parseGenres(tmdb.genres(k == TorrentKind.TV, language))
            if (list.isNotEmpty()) genreCache[k] = list
            if (kind == k) genres = list
        }
    }

    /** Bumped on every press of the search icon: the field (re)starts typing with the keyboard up. */
    var searchToken by mutableIntStateOf(0)
        private set

    fun openSearch() { searchOpen = true; searchToken++ }
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
            val g = genre?.id
            val s = sort
            val body = when {
                q.length >= 2 -> tmdb.searchPage(k == TorrentKind.TV, q, next, language)
                TorrentTmdb.needsDiscover(g, s) -> tmdb.discover(k == TorrentKind.TV, TorrentTmdb.discoverParams(k, g, s, next, language))
                else -> tmdb.popular(k == TorrentKind.TV, next, language)
            }
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

    /** The TMDB info page of a card (film or series); streams are only fetched from its Play. */
    var info by mutableStateOf<TorrentInfo?>(null)
        private set
    /** Key of the card whose info page was opened last: focus goes back to it on the grid. */
    var focusKey by mutableStateOf<String?>(null)
        private set
    /** Bumped when the info page closes, so the grid puts focus back on [focusKey]. */
    var restoreToken by mutableIntStateOf(0)
        private set
    private var infoJob: Job? = null
    private val country = Locale.getDefault().country.ifBlank { "US" }

    /** OK on a card: the info page, filled in with TMDB details (runtime, genres, cast, seasons). */
    fun openTitle(title: TmdbTitle, resumeAt: Pair<Int, Int>? = null) {
        returnOrigin = null
        focusKey = title.key
        stepJob?.cancel()
        infoJob?.cancel()
        // A series continues at its latest saved episode (Continue watching or an earlier visit).
        val resume = resumeAt ?: if (title.kind == TorrentKind.TV) library.latestEpisode(title.id)?.let { it.season to it.episode }?.takeIf { it.first > 0 } else null
        info = TorrentInfo(title, TorrentCards.film(title), loading = true, resumeEpisode = resume)
        infoJob = viewModelScope.launch {
            val tv = title.kind == TorrentKind.TV
            val body = runCatching { if (tv) tmdb.tv(title.id, language) else tmdb.movie(title.id, language) }.getOrNull()
            val parsed = body?.let { if (tv) TmdbJson.parseTv(it, country, language) else TmdbJson.parseMovie(it, country, language) }
            val seasons = if (tv) TorrentTmdb.parseSeasons(body) else emptyList()
            val current = info?.takeIf { it.title.key == title.key } ?: return@launch
            // Opened from Library: the row has no year; TMDB fills it in (SubSource and favorites use it).
            val known = current.title.copy(year = current.title.year ?: parsed?.year)
            info = current.copy(title = known, film = TorrentCards.film(known, parsed), seasons = seasons, loading = false)
            val start = current.resumeEpisode?.first?.takeIf { n -> seasons.any { it.number == n } } ?: seasons.firstOrNull()?.number
            start?.let { chooseSeason(it) }
        }
    }

    /** Back on the info page: to the grid, on the same card. */
    fun closeInfo() {
        infoJob?.cancel()
        stepJob?.cancel()
        info = null
        if (overlay is TorrentOverlay.Streams) overlay = TorrentOverlay.None
        // Opened from Library or Home: back there, on the same card.
        val origin = returnOrigin
        returnOrigin = null
        if (origin != null) leaveTo = origin else restoreToken++
    }

    /** Library/Home card that opened the info page; Back (from the page or its player) goes there. */
    private var returnOrigin: String? = null
    /** Set when the page should hand back to Library or Home ([TorrentDash.onLeave]). */
    var leaveTo by mutableStateOf<String?>(null)
        private set
    fun consumeLeave() { leaveTo = null }

    fun chooseSeason(number: Int) {
        val i = info ?: return
        if (i.season == number && i.episodes.isNotEmpty()) return
        info = i.copy(season = number, episodes = emptyList(), episodesLoading = true)
        infoJob = viewModelScope.launch {
            val episodes = TorrentTmdb.parseEpisodes(tmdb.season(i.title.id, number, language))
            info?.takeIf { it.title.key == i.title.key && it.season == number }?.let { info = it.copy(episodes = episodes, episodesLoading = false) }
        }
    }

    /** Play on the info page: a film's streams, or a series' chosen season from its first episode. */
    fun playInfo() {
        val i = info ?: return
        if (i.title.kind == TorrentKind.TV) {
            if (i.loading) return // seasons not known yet
            val resume = i.resumeEpisode?.takeIf { it.first == i.season }
            val (s, e) = resume ?: TorrentTmdb.playTarget(i.seasons, i.season, i.episodes) ?: run { message = "imdb"; return }
            searchStreams(i.title, s, e, i.episodes.firstOrNull { it.number == e }?.name)
        } else {
            searchStreams(i.title, null, null, null)
        }
    }

    fun chooseEpisode(episode: TmdbEpisode) {
        val i = info ?: return
        val season = i.season ?: return
        searchStreams(i.title, season, episode.number, episode.name)
    }

    private fun searchStreams(title: TmdbTitle, season: Int?, episode: Int?, episodeName: String?) {
        stepJob?.cancel()
        streamSeason = season
        streamEpisode = episode
        streamEpisodeName = episodeName
        streamOriginalTitle = info?.takeIf { it.title.key == title.key }?.film?.movie?.originalTitle.orEmpty()
        streamLogo = info?.takeIf { it.title.key == title.key }?.film?.movie?.logoPath?.let { TmdbJson.image(it, "w500") }
        val subtitle = if (season != null) "S$season E$episode" + (episodeName?.let { " · $it" } ?: "") else ""
        seekOnStart = null
        // A saved place and the stream it was played with: offer that stream first.
        val row = com.enigmacast.domain.torrent.TorrentLibraryRows.resumeRow(title, season, episode, episodeName, 0, 0, 0)
        val saved = library.resume(row.kind, row.id)?.positionMs ?: 0L
        val remembered = lastStreams.getString(LastStream.key(row.kind, row.id), null)?.let(LastStream::decode)
        if (remembered != null && saved >= ResumePolicy.MIN_RESUME_MS) {
            overlay = TorrentOverlay.ResumeSource(title, subtitle, saved, remembered)
            return
        }
        openStreamList(title, season, episode, subtitle)
    }

    /** The answer on the resume-source dialog: 0 resume, 1 start over, 2 another source. */
    fun answerResumeSource(choice: Int) {
        val o = overlay as? TorrentOverlay.ResumeSource ?: return
        when (choice) {
            0 -> { seekOnStart = o.atMs; startStream(o.title, o.subtitle, o.stream, null, "") }
            1 -> {
                resumeRowFor(o.title)?.let { library.clearResume(it.kind, it.id) }
                seekOnStart = null
                startStream(o.title, o.subtitle, o.stream, null, "")
            }
            else -> { seekOnStart = o.atMs; openStreamList(o.title, streamSeason, streamEpisode, o.subtitle) }
        }
    }

    private fun resumeRowFor(title: TmdbTitle) =
        com.enigmacast.domain.torrent.TorrentLibraryRows.resumeRow(title, streamSeason, streamEpisode, streamEpisodeName, 0, 0, 0)

    /** The streams of a title (the dialog opens at once, loading, while the IMDb id is looked up). */
    private fun openStreamList(title: TmdbTitle, season: Int?, episode: Int?, subtitle: String) {
        stepJob?.cancel()
        overlay = TorrentOverlay.Streams(title, subtitle, null, false, "")
        stepJob = viewModelScope.launch {
            val imdb = TorrentTmdb.parseImdb(tmdb.externalIds(title.kind == TorrentKind.TV, title.id))
            if (imdb == null) { overlay = TorrentOverlay.None; message = "imdb"; return@launch }
            loadStreams(title, imdb, season, episode, subtitle)
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
        // Back from the streams returns to the info page (still open underneath).
        overlay = TorrentOverlay.None
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
    /** Last stream per title/episode (info hash, file index, trackers; no links, no keys). Private to the app. */
    private val lastStreams = context.getSharedPreferences("torrent_last_stream", Context.MODE_PRIVATE)
    /** Set by Resume / another source: the player seeks here when ready instead of asking. */
    private var seekOnStart: Long? = null
    private var playingTitle: TmdbTitle? = null
    private var playingSubtitle = ""
    /** The OSD title: "Title (year)" or "Title · S1E2 · Episode". */
    var osdTitle by mutableStateOf("")
        private set

    // ---- Subtitles: the IPTV player's SubSource flow (XtreamViewModel) ----
    var cues by mutableStateOf<List<androidx.media3.common.text.Cue>>(emptyList())
        private set
    private var streamSeason: Int? = null
    private var streamEpisode: Int? = null
    private var streamOriginalTitle = ""
    private var streamEpisodeName: String? = null
    private var streamLogo: String? = null
    /** What is playing, for Continue watching: the title and its episode. */
    private var resumeTarget: com.enigmacast.data.db.IptvResumeRow? = null
    private var subtitleTarget: TorrentSubtitleTarget? = null
    private var mediaUrl = ""
    /** Playback setting at start: unknown-language text tracks are selected when this is on. */
    private var playbackSubtitles = true
    private var externalHits = emptyList<com.enigmacast.domain.subsource.SubSourceHit>()
    private var selectedExternalId: Long? = null
    private var externalAttached = false
    private var pendingEmbeddedId: String? = null
    private var holdAutoText = false
    private var subtitleJob: Job? = null
    private var downloadJob: Job? = null

    /** OK on a stream: direct (debrid/http) links play at once, torrents go through the loading page. */
    fun chooseStream(stream: TorrentStream) {
        val o = overlay as? TorrentOverlay.Streams ?: return
        startStream(o.title, o.subtitle, stream, o.streams, o.resumeKey)
    }

    /**
     * Plays [stream]. [streams] is the list it was picked from (null for the remembered stream:
     * when that fails, the scraper's list opens instead).
     */
    private fun startStream(title: TmdbTitle, subtitle: String, stream: TorrentStream, streams: List<TorrentStream>?, legacyKey: String) {
        val o = TorrentOverlay.Streams(title, subtitle, streams, false, legacyKey)
        playingTitle = title
        playingSubtitle = subtitle
        osdTitle = com.enigmacast.domain.player.VodOsdKeys.title(title.title, title.year, streamSeason, streamEpisode, streamEpisodeName)
        resumeKey = o.resumeKey
        resumeTarget = com.enigmacast.domain.torrent.TorrentLibraryRows.resumeRow(o.title, streamSeason, streamEpisode, streamEpisodeName, 0, 0, 0)
        val rememberKey = resumeTarget?.let { LastStream.key(it.kind, it.id) }
        // SubSource: TMDB title and year, season/episode, and the file name for the release.
        subtitleTarget = TorrentSubtitleTarget(
            title = o.title.title,
            originalTitle = streamOriginalTitle,
            year = o.title.year,
            season = streamSeason,
            episode = streamEpisode,
            fileName = stream.filename ?: stream.title.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() },
        )
        val url = stream.url
        if (url != null) {
            overlay = TorrentOverlay.None
            play(url, o.title.title)
            return
        }
        overlay = TorrentOverlay.Loading(o.title, o.subtitle, streamLogo)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            TorrentStreamingService.start(context)
            // A resume buffers from the saved place (its share of the file), not from the start.
            val resumeAt = seekOnStart?.takeIf { it >= ResumePolicy.MIN_RESUME_MS } ?: 0L
            val savedDuration = resumeTarget?.let { t -> library.resume(t.kind, t.id)?.durationMs } ?: 0L
            val local = runCatching { engine.open(stream, resumeAt, savedDuration) }
            if (overlay !is TorrentOverlay.Loading) return@launch
            local.onSuccess {
                // Remembered for the next resume of this title/episode (torrents only, never a link).
                rememberKey?.let { k -> LastStream.encode(stream)?.let { v -> lastStreams.edit().putString(k, v).apply() } }
                overlay = TorrentOverlay.None
                play(it, o.title.title)
            }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    TorrentStreamingService.stop(context)
                    if (it is com.enigmacast.torrent.TorrentUnavailableException) {
                        overlay = TorrentOverlay.Streams(o.title, o.subtitle, o.streams, false, o.resumeKey)
                        message = "engine:${it.abi}"
                    } else if (o.streams == null) {
                        // The remembered stream is gone: the scraper's list, still resuming at the saved place.
                        rememberKey?.let { k -> lastStreams.edit().remove(k).apply() }
                        openStreamList(o.title, streamSeason, streamEpisode, o.subtitle)
                    } else {
                        overlay = o
                        message = "torrent"
                    }
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
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
        selector.parameters = selector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !playbackSubtitles)
            .setSelectUndeterminedTextLanguage(playbackSubtitles)
            .build()
        ExoPlayer.Builder(context)
            // Read-ahead capped to the heap: Media3's default (~144 MB) does not fit many TV boxes.
            .setLoadControl(com.enigmacast.playback.PlayerBuffers.vod())
            .setRenderersFactory(com.enigmacast.ui.xtream.IptvRenderersFactory(context, audioSync, subtitleSync))
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING && playerState.playbackState == "READY") {
                            // A stall while playing: what the player had and the last read that had to wait.
                            EnigmaLog.player(
                                "torrent player BUFFERING at ${p.currentPosition / 1000}s, buffered ${p.totalBufferedDuration} ms " +
                                    "(to ${p.bufferedPosition / 1000}s), loading ${p.isLoading}, last wait ${engine.lastWait ?: "none"}",
                            )
                        }
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
                        val pending = pendingEmbeddedId
                        pendingEmbeddedId = null
                        if (pending != null) {
                            holdAutoText = true
                            applyEmbeddedSubtitles(p, pending)
                            holdAutoText = false
                        } else if (selectedExternalId == null && !holdAutoText && !autoTextDone && t.groups.isNotEmpty()) {
                            // Once per file: a new override while playing makes progressive media re-read
                            // from the current place (a codec flush and a new Range request).
                            autoTextDone = true
                            selectFirstTextTrack(p)
                        }
                        publishTracks(p)
                    }
                    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        cues = if (textOn) cueGroup.cues else emptyList()
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
    /** The first embedded subtitle was picked for this file (or there was none to pick). */
    private var autoTextDone = false

    private fun play(url: String, title: String) {
        // The info page closes once playback starts; Back from the player lands on the grid card.
        infoJob?.cancel()
        info = null
        // One player at a time: Enigma2 and IPTV stop (and free the decoder) first.
        owner.register(PlaybackOwner.Owner.TORRENT) { stopPlayback() }
        owner.claim(PlaybackOwner.Owner.TORRENT)
        playError = null
        resumeOffered = false
        autoTextDone = false
        playing = title
        playerState = PlayerState(filePlayback = true)
        clearExternalSubtitles()
        cues = emptyList()
        mediaUrl = url
        val target = subtitleTarget
        viewModelScope.launch {
            val pb = settingsRepository.current().playback
            audioSync.set(pb.audioDelayPcmMs, pb.audioDelayPassthroughMs)
            audioDelayMs = pb.audioDelayPcmMs
            subtitleSync.set(pb.subtitleDelayMs); subtitleDelayMs = pb.subtitleDelayMs
            // Subtitles on/off from Settings › Playback, as in the IPTV player.
            playbackSubtitles = pb.subtitlesEnabled
            if (playing != title || mediaUrl != url) return@launch
            player().apply {
                // The screen may have created the player before the setting was read.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !playbackSubtitles)
                    .setPreferredTextLanguage(null)
                    .setSelectUndeterminedTextLanguage(playbackSubtitles)
                    .build()
                // Resume / another source: straight to the saved place, no question.
                val start = seekOnStart?.takeIf { it >= ResumePolicy.MIN_RESUME_MS }
                if (start != null) setMediaItem(mediaItem(url, null), start) else setMediaItem(mediaItem(url, null))
                prepare()
                playWhenReady = true
            }
            if (target != null) loadExternalSubtitles(target, url)
        }
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var n = 0
            while (true) {
                delay(500)
                val p = player ?: break
                val position = p.currentPosition
                val duration = p.duration.coerceAtLeast(0)
                val buffered = bufferedMs(position, duration, p.bufferedPosition)
                if (player !== p) break
                playerState = playerState.copy(positionMs = position, durationMs = duration, bufferedPositionMs = buffered)
                // Stuck with nothing playable and not loading (the read-ahead full of one track):
                // reload at the position, which empties the buffer. A read waiting for pieces is
                // still loading and is left alone.
                val buffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady
                if (starved.update(buffering, p.isLoading, p.totalBufferedDuration, android.os.SystemClock.elapsedRealtime())) {
                    EnigmaLog.player("torrent player starved at ${position / 1000}s (${p.totalBufferedDuration} ms buffered, not loading): reload")
                    p.seekTo(position)
                }
                if (++n % 10 == 0) {
                    saveResume()
                    engine.setDuration(p.duration)
                    // A synchronous call into libtorrent: never on the main thread.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { engine.refreshStats() }
                }
            }
        }
    }

    /**
     * How far playback can go without waiting: the verified torrent data contiguous from the
     * playing byte (position share of the file), as time; ExoPlayer's own buffer when more or
     * when the engine has no file (a debrid link).
     */
    private suspend fun bufferedMs(position: Long, duration: Long, exoBuffered: Long): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        bufferedMsBlocking(position, duration, exoBuffered)
    }

    private fun bufferedMsBlocking(position: Long, duration: Long, exoBuffered: Long): Long {
        val size = engine.fileSize()
        if (size <= 0 || duration <= 0) return exoBuffered
        val pos = (size.toDouble() * position.coerceIn(0, duration) / duration).toLong()
        val end = engine.contiguousEnd(pos) ?: return exoBuffered
        return maxOf(exoBuffered, com.enigmacast.domain.torrent.StreamWindow.bytesToMs(end, size, duration)).coerceAtMost(duration)
    }

    private fun offerResume() {
        if (resumeOffered) return
        resumeOffered = true
        // Already started at the saved place (Resume / another source).
        if (seekOnStart != null) { seekOnStart = null; return }
        val target = resumeTarget
        // The user database first; positions saved before Continue watching existed are keyed by IMDb id.
        val saved = target?.let { library.resume(it.kind, it.id)?.positionMs }
            ?: resumeKey?.let { resumes.getLong(it, 0L) } ?: return
        if (saved >= ResumePolicy.MIN_RESUME_MS) { player?.playWhenReady = false; resumeAsk = saved }
    }

    fun answerResume(resume: Boolean) {
        val at = resumeAsk ?: return
        resumeAsk = null
        // From the beginning: the old place is forgotten (as for recordings).
        if (!resume) resumeTarget?.let { t -> library.clearResume(t.kind, t.id); resumeKey?.let { k -> resumes.edit().remove(k).apply() } }
        player?.let { if (resume) it.seekTo(at); it.playWhenReady = true }
    }

    private fun saveResume() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.coerceAtLeast(0)
        val action = com.enigmacast.domain.torrent.TorrentLibraryRows.saveAction(resumeOffered, resumeAsk != null, pos, dur)
        if (action == com.enigmacast.domain.torrent.ResumeSave.NONE) return
        // A position from before Continue watching (preferences, by IMDb id) moves into the user database.
        resumeKey?.takeIf { it.isNotBlank() && resumes.contains(it) }?.let { legacy -> resumes.edit().remove(legacy).apply() }
        // The key is the title (TMDB id, season/episode), so any stream of it resumes here.
        val target = resumeTarget ?: return
        if (action == com.enigmacast.domain.torrent.ResumeSave.KEEP) {
            library.saveResume(target.copy(positionMs = pos, durationMs = dur, updatedMs = System.currentTimeMillis()))
        } else if (library.resume(target.kind, target.id) != null) {
            library.clearResume(target.kind, target.id)
        }
    }

    // ---- Favorites (info page) ----

    fun isFavorite(title: TmdbTitle): Boolean = library.isFavorite(title)

    fun toggleFavorite(title: TmdbTitle) {
        library.setFavorite(title, !library.isFavorite(title))
        favoriteVersion++
    }

    var favoriteVersion by mutableIntStateOf(0)
        private set

    /** Library or Home opened a title: its info page (series at the remembered episode). */
    private fun handleOpenRequest() {
        val request = library.openRequest.value ?: return
        library.openRequest.value = null
        returnOrigin = null
        if (playing != null) stopPlayback()
        overlay = TorrentOverlay.None
        val resume = request.season?.let { s -> s to (request.episode ?: 1) }
        openTitle(request.title, resume)
        returnOrigin = request.origin
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
    /** Off, an embedded track, or a SubSource file (downloaded, then added to the media item). */
    fun chooseSubtitles(id: String?) {
        val p = player ?: return
        if (id == null) {
            selectedExternalId = null
            pendingEmbeddedId = null
            val dropFile = externalAttached
            externalAttached = false
            cues = emptyList()
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            if (dropFile) reload(p, null) else publishTracks(p)
            return
        }
        if (id.startsWith(EXTERNAL_PREFIX)) {
            val hitId = id.removePrefix(EXTERNAL_PREFIX).toLongOrNull() ?: return
            val hit = externalHits.firstOrNull { it.id == hitId } ?: return
            selectedExternalId = hitId
            pendingEmbeddedId = null
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            publishTracks(p)
            downloadJob?.cancel()
            downloadJob = viewModelScope.launch {
                val file = subtitles.file(hitId) ?: return@launch
                if (selectedExternalId != hitId) return@launch
                val language = com.enigmacast.domain.subsource.SubSourceLanguages.bySlug(hit.language)
                val config = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                    .setId("$EXTERNAL_PREFIX$hitId")
                    .setMimeType(subtitleMime(file.extension))
                    .setLanguage(language?.iso?.substringBefore('-'))
                    .setLabel(com.enigmacast.domain.subsource.SubSourceMatch.label(hit))
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                externalAttached = true
                reload(player ?: return@launch, config)
            }
            return
        }
        selectedExternalId = null
        if (externalAttached) {
            externalAttached = false
            pendingEmbeddedId = id
            reload(p, null)
        } else {
            applyEmbeddedSubtitles(p, id)
        }
    }

    private fun mediaItem(url: String, subtitle: MediaItem.SubtitleConfiguration?): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (subtitle != null) builder.setSubtitleConfigurations(listOf(subtitle))
        return builder.build()
    }

    /** Same position and play state with or without the SubSource file. */
    private fun reload(p: ExoPlayer, subtitle: MediaItem.SubtitleConfiguration?) {
        val position = p.currentPosition.coerceAtLeast(0L)
        val playingNow = p.playWhenReady
        p.setMediaItem(mediaItem(mediaUrl, subtitle), position)
        p.prepare()
        p.playWhenReady = playingNow
    }

    private fun clearExternalSubtitles() {
        subtitleJob?.cancel()
        downloadJob?.cancel()
        externalHits = emptyList()
        selectedExternalId = null
        externalAttached = false
        pendingEmbeddedId = null
    }

    /**
     * SubSource files for the film or episode, in the languages turned on in Settings › IPTV ›
     * Subtitles (else the app language). The TMDB title is tried first, then the original
     * title; files whose release matches the torrent's file name come first.
     */
    private fun loadExternalSubtitles(target: TorrentSubtitleTarget, url: String) {
        subtitleJob?.cancel()
        subtitleJob = viewModelScope.launch {
            val saved = settingsRepository.current().iptvSubtitleLanguages
            val languages = saved ?: listOfNotNull(
                com.enigmacast.domain.subsource.SubSourceLanguages.forAppTag(
                    com.enigmacast.locale.AppLanguageStore.current(context).tag,
                ),
            )
            if (languages.isEmpty()) return@launch
            var hits = emptyList<com.enigmacast.domain.subsource.SubSourceHit>()
            for (title in target.titles()) {
                hits = runCatching {
                    subtitles.find(
                        com.enigmacast.domain.subsource.SubSourceQuery(
                            title = title,
                            year = target.year,
                            season = target.season?.takeIf { it > 0 },
                            episode = target.episode?.takeIf { it > 0 },
                            languages = languages,
                        ),
                    )
                }.getOrDefault(emptyList())
                if (hits.isNotEmpty()) break
            }
            if (mediaUrl != url || playing == null) return@launch
            externalHits = com.enigmacast.domain.subsource.SubSourceMatch.byRelease(hits, target.fileName)
            player?.let(::publishTracks)
        }
    }

    private fun publishTracks(p: ExoPlayer) {
        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val menu = TrackMenus.build(p.currentTracks, textOn && selectedExternalId == null)
        val embedded = menu.subtitles.filter { choice ->
            val found = TrackMenus.find(p.currentTracks, choice.id, C.TRACK_TYPE_TEXT)
            found?.first?.getTrackFormat(found.second)?.id?.startsWith(EXTERNAL_PREFIX) != true
        }
        val extra = externalHits.map { hit ->
            com.enigmacast.playback.TrackChoice(
                "$EXTERNAL_PREFIX${hit.id}",
                com.enigmacast.util.UiText.Raw(com.enigmacast.domain.subsource.SubSourceMatch.label(hit)),
                textOn && selectedExternalId == hit.id,
            )
        }
        val merged = menu.copy(subtitles = embedded + extra, subtitlesOn = embedded.any { it.selected } || extra.any { it.selected })
        tracks = merged
        playerState = playerState.copy(
            videoCodec = p.videoFormat?.sampleMimeType.orEmpty(),
            audioCodec = p.audioFormat?.sampleMimeType.orEmpty(),
            audioLabel = com.enigmacast.playback.TrackLabels.audio(p.audioFormat),
            audioTrackCount = menu.audio.size,
            subtitleLabel = merged.subtitles.firstOrNull { it.selected }?.label ?: com.enigmacast.util.UiText.Empty,
            subtitleTrackCount = merged.subtitles.size,
        )
    }

    /** Same as the IPTV player: the first supported text track, when none is selected yet. */
    private fun selectFirstTextTrack(p: ExoPlayer) {
        if (holdAutoText || p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return
        val groups = p.currentTracks.groups
        if (groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }) return
        val group = groups.firstOrNull { candidate ->
            candidate.type == C.TRACK_TYPE_TEXT &&
                candidate.isTrackSupported(0) &&
                candidate.getTrackFormat(0).id?.startsWith(EXTERNAL_PREFIX) != true
        } ?: return
        holdAutoText = true
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        holdAutoText = false
    }

    private fun applyEmbeddedSubtitles(p: ExoPlayer, id: String) {
        val (group, track) = TrackMenus.find(p.currentTracks, id, C.TRACK_TYPE_TEXT) ?: return
        if (group.getTrackFormat(track).id?.startsWith(EXTERNAL_PREFIX) == true) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }

    private fun subtitleMime(extension: String): String = when (extension.lowercase()) {
        "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
        else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
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
        // Back on the grid, focus on the card that was played (or Library/Home, where it was started).
        if (playing != null) {
            val origin = returnOrigin
            returnOrigin = null
            if (origin != null) leaveTo = origin else restoreToken++
        }
        releasePlayback()
    }

    /** OSD globe: the streams of what plays; the one picked continues at the current place. */
    fun chooseOtherSource() {
        val title = playingTitle ?: return
        val at = player?.currentPosition ?: 0L
        releasePlayback()
        seekOnStart = at.takeIf { it >= ResumePolicy.MIN_RESUME_MS }
        openStreamList(title, streamSeason, streamEpisode, playingSubtitle)
    }

    var playbackSpeed by mutableStateOf(1f)
        private set

    fun choosePlaybackSpeed(value: Float) {
        playbackSpeed = value
        player?.setPlaybackSpeed(value)
    }

    private val starved = com.enigmacast.playback.StarvedBuffer(8_000)

    private fun releasePlayback() {
        starved.reset()
        playbackSpeed = 1f
        tickJob?.cancel()
        saveResume()
        resumeAsk = null
        player?.release()
        player = null
        playing = null
        clearExternalSubtitles()
        cues = emptyList()
        mediaUrl = ""
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

    // Last in the class: these collectors run at once and use the state declared above.
    init {
        viewModelScope.launch { library.openRequest.collect { if (it != null) handleOpenRequest() } }
        viewModelScope.launch { library.favorites.collect { favoriteVersion++ } }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
        private const val EXTERNAL_PREFIX = "ext:"
    }
}
