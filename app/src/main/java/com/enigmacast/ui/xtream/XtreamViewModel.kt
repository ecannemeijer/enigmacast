@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.enigmacast.ui.xtream

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.subsource.SubSourceClient
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.data.xtream.XtreamDetection
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.data.xtream.XtreamRepository
import com.enigmacast.domain.xtream.airingAt
import com.enigmacast.domain.xtream.chooseGuide
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.domain.xtream.XtreamEpisode
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.domain.xtream.XtreamUrls
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** What plays: a live channel, a film or an episode. */
data class XtreamPlaying(val title: String, val item: XtreamItem?, val episode: XtreamEpisode? = null)

/**
 * IPTV page: the XStreamity playlists of the active receiver, browsed through the Xtream Codes
 * API and played in the app's own player. Detection follows the active receiver.
 */
@HiltViewModel
class XtreamViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val settingsRepository: SettingsRepository,
    private val playlistStore: IptvPlaylistStore,
    private val playlistSync: IptvPlaylistSync,
    private val satelliteEpg: com.enigmacast.data.xtream.IptvSatelliteEpg,
    private val playbackOwner: com.enigmacast.playback.PlaybackOwner,
    private val playbackLifecycle: com.enigmacast.live.PlaybackLifecycle,
    private val artworkSource: com.enigmacast.data.xtream.IptvArtwork,
    private val hiddenCategories: com.enigmacast.data.xtream.IptvHiddenCategories,
    private val library: com.enigmacast.data.xtream.IptvLibrary,
    private val watchNext: com.enigmacast.data.xtream.WatchNextPublisher,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
    private val sleepTimer: com.enigmacast.playback.AppSleepTimer,
    private val subtitles: SubSourceClient,
    val pip: com.enigmacast.playback.PipController,
    private val fallbackFinder: com.enigmacast.live.TvFallbackFinder,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _detection = MutableStateFlow<XtreamDetection?>(null)
    /** Null until the active receiver was checked. The menu item shows when it has playlists. */
    val detection: StateFlow<XtreamDetection?> = _detection

    var account by mutableStateOf<XtreamAccount?>(null)
        private set
    var section by mutableStateOf(XtreamSection.LIVE)
        private set
    /** Section chosen in the IPTV menu, applied when the page opens. */
    private var requestedSection: XtreamSection? = null
    /** Back from the category column: the dashboard focuses the IPTV menu item. */
    var returnToMenu by mutableStateOf(false)
        private set

    /** Live TV from the menu: play the last channel full screen, without the channel list. */
    var openLiveFullscreen by mutableStateOf(false)
        private set
    /** No saved live channel, so the list opens after all. */
    var liveListNeeded by mutableStateOf(false)
        private set

    fun requestSection(section: XtreamSection) {
        requestedSection = section
        openLiveFullscreen = section == XtreamSection.LIVE
        liveListNeeded = false
    }

    fun consumeLiveListNeeded() {
        liveListNeeded = false
    }

    fun markReturnToMenu() {
        returnToMenu = true
    }

    fun consumeReturnToMenu() {
        returnToMenu = false
    }
    var categories by mutableStateOf<List<XtreamCategory>>(emptyList())
        private set
    var category by mutableStateOf<XtreamCategory?>(null)
        private set
    var items by mutableStateOf<List<XtreamItem>>(emptyList())
        private set
    /** Episodes of the series opened in the list, or null when the list shows the category. */
    var series by mutableStateOf<XtreamItem?>(null)
        private set
    var episodes by mutableStateOf<List<XtreamEpisode>>(emptyList())
    /** Episodes of the series that is playing, so the next one can start on its own. */
    private var playbackEpisodes by mutableStateOf<List<XtreamEpisode>>(emptyList())
    /** Episode id for which autoplay of the next one was cancelled. */
    private var nextDismissedFor by mutableStateOf<String?>(null)
        private set
    /** [items] as cards with the playlist's own name and cover; artwork replaces them per card. */
    var cards by mutableStateOf<List<IptvCard>>(emptyList())
        private set
    /** Poster that had focus in the film or series grid, so Back from info or the player returns there. */
    var gridAnchor by mutableIntStateOf(0)
        private set
    /** This film or episode was started from the dashboard list, so Back returns there. */
    var vodFromDashboard by mutableStateOf(false)
        private set

    fun rememberGridAnchor(index: Int) {
        if (index >= 0) gridAnchor = index
    }

    fun consumeVodFromDashboard() {
        vodFromDashboard = false
    }
    /** Items per category id for the categories column (Room in playlist mode, fetched ones otherwise). */
    var categoryCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    private suspend fun loadCounts(account: XtreamAccount, section: XtreamSection) {
        categoryCounts = runCatching {
            if (playlist != null) playlistSync.counts(section) else repository.cachedCounts(account, section)
        }.getOrDefault(emptyMap())
    }
    var loading by mutableStateOf(false)
        private set
    /** The category whose list is being fetched after OK; the shown list stays until it arrives. */
    var pendingCategory by mutableStateOf<XtreamCategory?>(null)
        private set
    /** Goes up when a list loaded through OK is ready: the screen moves focus into it. */
    var itemsReady by mutableStateOf(0)
        private set
    /** Goes up when the categories of a section chosen with OK are ready. */
    var categoriesReady by mutableStateOf(0)
        private set
    var error by mutableStateOf(false)
        private set
    var playing by mutableStateOf<XtreamPlaying?>(null)
        private set
    var playerError by mutableStateOf<String?>(null)
    var catchup by mutableStateOf(false)
        private set
    var showPipPicker by mutableStateOf(false)
    var notice by mutableStateOf("")
        private set
    private var catchupDurationMs = 0L
    private var fallbackLatched = false
    private var catchupIndexStarted = false
    private var noticeJob: Job? = null
        private set
    var buffering by mutableStateOf(false)
        private set
    /** Now/next of the focused or playing live channel, by stream id. */
    var epg by mutableStateOf<Map<String, List<XtreamEpg>>>(emptyMap())
        private set

    /** Set while Settings › IPTV uses a playlist: lists come from Room instead of the API. */
    private var playlist: IptvPlaylistKind? = null

    /** What the full-player OSD shows: resolution, codecs and audio from the player itself. */
    var playerState by mutableStateOf(com.enigmacast.domain.model.PlayerState())
        private set
    var tracks by mutableStateOf(com.enigmacast.playback.TrackMenu())
        private set
    /** Cues of the text track that plays, drawn over the IPTV picture. */
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    var pictureMode by mutableStateOf(com.enigmacast.domain.player.PictureMode.AUTO)
        private set
    var audioDelayMs by mutableStateOf(0)
        private set
    var audioDelayPassthrough by mutableStateOf(false)
        private set
    var subtitleDelayMs by mutableStateOf(0)
        private set
    var sleepEndsAtMs by mutableStateOf(0L)
        private set
    /** Live channels zapped in this visit, newest first. The OSD Recent tile uses them. */
    var recentChannels by mutableStateOf<List<com.enigmacast.domain.model.LastChannel>>(emptyList())
        private set
    private val audioSync = com.enigmacast.playback.AudioSyncOffsets()
    private val subtitleSync = com.enigmacast.playback.SubtitleSyncOffset()
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(XtreamRepository.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(20_000)
    /** Playback setting at start: unknown-language text tracks are selected when this is on. */
    private var playbackSubtitles = true
    private var mediaUrl = ""
    private var mediaLive = false
    private var externalHits = emptyList<com.enigmacast.domain.subsource.SubSourceHit>()
    private var selectedExternalId: Long? = null
    private var externalAttached = false
    private var pendingEmbeddedId: String? = null
    private var holdAutoText = false
    private var subtitleJob: Job? = null
    private var downloadJob: Job? = null

    /** GUI skin and OSD transparency, as the receiver player uses them. */
    val osdLook: StateFlow<Pair<com.enigmacast.domain.model.OsdSkinId, Int>> = settingsRepository.settings
        .map { it.osdSkin to it.osd.transparencyPercent.coerceIn(0, 100) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.enigmacast.domain.model.OsdSkinId.STANDARD to 10)

    private val logos = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
    private val logoClient by lazy { okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun cachedLogo(url: String): androidx.compose.ui.graphics.ImageBitmap? = logos[url]

    /** The channel logo of the playlist (tvg-logo / stream_icon), decoded off the main thread. */
    suspend fun logo(url: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        logos[url]?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                logoClient.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", XtreamRepository.USER_AGENT).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                    android.graphics.BitmapFactory.decodeStream(r.body.byteStream(), null, opts)?.let { bmp ->
                        val scaled = if (bmp.width > 320) android.graphics.Bitmap.createScaledBitmap(bmp, 320, (bmp.height * 320f / bmp.width).toInt().coerceAtLeast(1), true) else bmp
                        scaled.asImageBitmap()
                    }
                }
            }.getOrNull()?.also { logos[url] = it }
        }
    }


    /** Settings › Remote control keys of the own-tuner player; the IPTV player uses the same. */
    val keyBindings: StateFlow<com.enigmacast.domain.remote.Bindings> = settingsRepository.settings
        .map { com.enigmacast.domain.remote.KeyBindings.effective(it.remote.keyBindings, com.enigmacast.domain.remote.KeyMode.OWN) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.enigmacast.domain.remote.KeyBindings.defaults(com.enigmacast.domain.remote.KeyMode.OWN))

    /** The channel whose programme column is open (Right on a channel), and its events. */
    var guideItem by mutableStateOf<XtreamItem?>(null)
        private set
    var guideEvents by mutableStateOf<List<XtreamEpg>>(emptyList())
        private set
    var guideLoading by mutableStateOf(false)
        private set
    private var guideJob: Job? = null
    private var visibleJob: Job? = null

    /**
     * Now/next for the rows on screen: called on every scroll, the previous batch is cancelled,
     * so only rows that stay visible for a moment are fetched, at most 4 requests at a time.
     * Xtream channels use the same listings as the programme column. When none of them is on
     * now, the receiver's satellite guide is matched by name. Rows without any guide are remembered too.
     */
    fun requestEpg(visible: List<XtreamItem>) {
        visibleJob?.cancel()
        visibleJob = viewModelScope.launch {
            delay(250)
            val todo = visible.filter { it.section == XtreamSection.LIVE && !epg.containsKey(it.id) }.take(EPG_BATCH)
            if (todo.isEmpty()) return@launch
            val now = System.currentTimeMillis() / 1000
            val xtream = account?.takeIf { it !== M3U_ACCOUNT }
            val found = HashMap<String, List<XtreamEpg>>()
            if (xtream != null) {
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                kotlinx.coroutines.coroutineScope {
                    todo.map { item -> async { gate.withPermitCompat { item.id to repository.guide(xtream, item.id) } } }
                        .map { it.await() }
                }.forEach { (id, list) -> found[id] = list }
            }
            val missing = todo.filter { found[it.id].orEmpty().airingAt(now) == null }
            if (missing.isNotEmpty()) {
                val sat = runCatching { satelliteEpg.byName(missing.map { it.name }) }.getOrDefault(emptyMap())
                missing.forEach { item ->
                    found[item.id] = chooseGuide(found[item.id].orEmpty(), sat[item.name].orEmpty(), now)
                }
            }
            epg = epg + found
        }
    }

    private suspend fun <T> kotlinx.coroutines.sync.Semaphore.withPermitCompat(block: suspend () -> T): T {
        acquire()
        try { return block() } finally { release() }
    }

    /** Right on a channel: its programme column. */
    fun openGuide(item: XtreamItem) {
        guideItem = item
        guideEvents = epg[item.id].orEmpty()
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            guideLoading = true
            try {
                val list = loadGuide(item)
                if (list.isNotEmpty()) {
                    guideEvents = list
                    epg = epg + (item.id to list)
                }
            } finally {
                guideLoading = false
            }
        }
    }

    /**
     * Xtream's full table when the account has one (that includes the past), otherwise the
     * short list, and the satellite guide when those have no programme on now. The column
     * keeps the archive window so Up reaches programmes catch-up can play.
     */
    private suspend fun loadGuide(item: XtreamItem): List<XtreamEpg> {
        val now = System.currentTimeMillis() / 1000
        val pastDays = com.enigmacast.domain.xtream.Catchup.windowDays(item).coerceAtLeast(1)
        val xtream = account?.takeIf { it !== M3U_ACCOUNT }
        val full = if (xtream != null) repository.guideFull(xtream, item.id) else emptyList()
        val listings = full.ifEmpty { if (xtream != null) repository.guide(xtream, item.id) else emptyList() }
        val chosen = if (listings.airingAt(now) != null) listings else {
            val sat = runCatching {
                satelliteEpg.byName(listOf(item.name), hours = 36, pastHours = pastDays * 24)[item.name]
            }.getOrNull().orEmpty()
            chooseGuide(listings, sat, now)
        }
        return com.enigmacast.domain.xtream.Catchup.listed(chosen, now, pastDays)
    }

    fun closeGuide() {
        guideJob?.cancel()
        guideItem = null
        guideEvents = emptyList()
    }

    /** TMDB matches of the film and series cards, by IptvVod.key. */
    val artwork: StateFlow<Map<String, com.enigmacast.domain.films.TmdbMovie?>> = artworkSource.movies
    private var artworkJob: Job? = null

    /** Artwork for the cards on screen; a scroll cancels the batch that was waiting. */
    fun requestArtwork(visible: List<XtreamItem>) {
        artworkJob?.cancel()
        artworkJob = viewModelScope.launch {
            delay(200)
            artworkSource.resolve(visible)
        }
    }

    /** The info page of a film or series (OK on a card). */
    var detail by mutableStateOf<XtreamItem?>(null)
        private set
    var detailFilm by mutableStateOf<com.enigmacast.domain.films.HomeFilm?>(null)
        private set
    var detailEpisodes by mutableStateOf<List<XtreamEpisode>>(emptyList())
        private set
    var detailLoading by mutableStateOf(false)
        private set
    private var detailJob: Job? = null

    fun openDetail(item: XtreamItem) {
        val vod = com.enigmacast.data.xtream.IptvVod
        detail = item
        detailEpisodes = emptyList()
        detailFilm = vod.film(item, artwork.value[vod.key(item)])
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            detailLoading = true
            try {
                runCatching { artworkSource.resolve(listOf(item)) }
                val xtream = account?.takeIf { it !== M3U_ACCOUNT }
                val info = xtream?.let { repository.vodInfo(it, item) }
                detailFilm = vod.film(item, vod.merge(item, artwork.value[vod.key(item)], info))
                if (item.section == XtreamSection.SERIES && xtream != null) {
                    detailEpisodes = runCatching { repository.episodes(xtream, item.id) }.getOrDefault(emptyList())
                }
            } finally {
                detailLoading = false
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        detail = null
        detailFilm = null
        detailEpisodes = emptyList()
    }

    private var resumeKey: String? = null

    private fun resumeKeyOf(kind: String, id: String): String? = account?.let { "${it.key}/$kind/$id" }
    private fun resumeRow(kind: String, id: String) = resumeKeyOf(kind, id)?.let { library.resumeMap.value[it] }

    /** Where a film or episode was left, in ms; 0 when it was not started or watched to the end. */
    fun resumeOf(item: XtreamItem): Long = resumeRow("m", item.id)?.positionMs ?: 0L
    fun resumeOf(episode: XtreamEpisode): Long = resumeRow("e", episode.id)?.positionMs ?: 0L

    /** 0..1 for the progress line on a card (0 when nothing to resume). */
    fun progressOf(item: XtreamItem): Float = resumeRow("m", item.id)?.let { row ->
        com.enigmacast.domain.resume.ResumePolicy.fraction(com.enigmacast.domain.resume.ResumePoint(row.id, row.positionMs, row.durationMs), 0L)
    } ?: 0f

    /** The poster Library shows: TMDB when matched, else the playlist's cover. */
    private fun posterOf(item: XtreamItem): String? {
        val movie = artwork.value[com.enigmacast.data.xtream.IptvVod.key(item)]
        return com.enigmacast.data.tmdb.TmdbJson.image(movie?.posterPath, "w342") ?: item.icon.ifBlank { null }
    }

    private fun saveResume() {
        val key = resumeKey ?: return
        val p = player ?: return
        val parts = key.split('/')
        if (parts.size != 3) return
        val (source, kind, id) = parts
        when (val keep = IptvResume.toStore(p.currentPosition, p.duration)) {
            null -> {
                library.clearResume(source, kind, id)
                watchNext.remove(source, kind, id)
            }
            else -> {
                val what = playing
                val item = what?.item
                val episode = what?.episode
                val row = com.enigmacast.data.db.IptvResumeRow(
                    source = source, kind = kind, id = id,
                    positionMs = keep, durationMs = p.duration.coerceAtLeast(0), updatedMs = System.currentTimeMillis(),
                    title = if (episode != null) episode.title.ifBlank { what.title } else item?.name ?: what?.title.orEmpty(),
                    poster = item?.let(::posterOf),
                    extension = episode?.extension ?: item?.extension.orEmpty(),
                    url = if (episode == null) item?.url.orEmpty() else "",
                    categoryId = item?.categoryId.orEmpty(),
                    seriesId = if (episode != null) item?.id else null,
                    seriesTitle = if (episode != null) item?.name else null,
                    season = episode?.season ?: 0,
                    episode = episode?.episode ?: 0,
                )
                library.saveResume(row)
                watchNext.publish(row)
            }
        }
        resumeVersion++
    }

    /** IPTV favorites (Library): the film or series on the info page. */
    fun isFavorite(item: XtreamItem): Boolean = account?.let {
        com.enigmacast.data.xtream.IptvLibrary.key(it.key, com.enigmacast.data.xtream.IptvLibrary.kindOf(item.section), item.id) in library.favoriteKeys.value
    } == true

    fun toggleFavorite(item: XtreamItem) {
        val account = account ?: return
        val movie = artwork.value[com.enigmacast.data.xtream.IptvVod.key(item)]
        val on = !isFavorite(item)
        library.setFavorite(
            com.enigmacast.data.db.IptvLibraryRow(
                source = account.key, kind = com.enigmacast.data.xtream.IptvLibrary.kindOf(item.section), id = item.id,
                title = item.name, poster = posterOf(item),
                backdrop = com.enigmacast.data.tmdb.TmdbJson.image(movie?.backdropPath, "w780"),
                year = movie?.year ?: com.enigmacast.data.xtream.IptvVod.title(item.name).second,
                categoryId = item.categoryId, extension = item.extension, url = item.url, addedMs = System.currentTimeMillis(),
            ),
            on,
        )
        favoriteVersion++
    }

    var favoriteVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Library asked for a film (play, with the resume dialog) or a series (info page). */
    var libraryPlayed by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var libraryDetail by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Started from Home/Library: stopping returns there instead of to the IPTV pages. */
    var launchOrigin by mutableStateOf<String?>(null)
        private set
    /** Leaving the IPTV screen: hand the origin card to Home/Library. */
    fun exitToOrigin() { library.returnTo.value = launchOrigin; launchOrigin = null }

    private fun handleOpenRequest() {
        val request = library.openRequest.value ?: return
        val account = account ?: return
        if (account.key != request.source) return
        library.openRequest.value = null
        launchOrigin = request.origin
        val item = request.item
        val episode = request.episode
        if (item.section == XtreamSection.LIVE) {
            selectSection(XtreamSection.LIVE)
            play(item)
            libraryPlayed++
        } else if (episode != null) {
            selectSection(XtreamSection.SERIES)
            if (request.resume) { playEpisode(episode, item, fromStart = false); libraryPlayed++ }
            else requestEpisode(episode, item) { libraryPlayed++ }
        } else if (item.section == XtreamSection.SERIES || request.detail) {
            selectSection(item.section)
            openDetail(item)
            libraryDetail++
        } else {
            selectSection(XtreamSection.MOVIES)
            if (request.resume) { playFilm(item, fromStart = false); libraryPlayed++ }
            else requestFilm(item) { libraryPlayed++ }
        }
    }

    /** Bumped whenever a position is saved, so cards redraw their progress line. */
    var resumeVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** "Resume from … / Start from the beginning" before a film or episode with a saved place. */
    data class ResumeAsk(val title: String, val positionMs: Long, val play: (fromStart: Boolean) -> Unit)
    var resumeAsk by mutableStateOf<ResumeAsk?>(null)
        private set

    fun requestFilm(item: XtreamItem, onStarted: () -> Unit) {
        val offer = IptvResume.offer(resumeOf(item))
        if (offer == null) { playFilm(item, fromStart = true); onStarted() }
        else resumeAsk = ResumeAsk(item.name, offer) { fromStart -> playFilm(item, fromStart); onStarted() }
    }

    /** Keeps the series list across closing the info page, which clears [detailEpisodes]. */
    fun holdEpisodes(list: List<XtreamEpisode>) {
        if (list.isNotEmpty()) playbackEpisodes = list
    }

    fun requestEpisode(episode: XtreamEpisode, show: XtreamItem? = series, onStarted: () -> Unit) {
        if (detailEpisodes.any { it.id == episode.id }) playbackEpisodes = detailEpisodes
        else if (episodes.any { it.id == episode.id }) playbackEpisodes = episodes
        val offer = IptvResume.offer(resumeOf(episode))
        if (offer == null) { playEpisode(episode, show, fromStart = true); onStarted() }
        else resumeAsk = ResumeAsk(episode.title, offer) { fromStart -> playEpisode(episode, show, fromStart); onStarted() }
    }

    /** The next episode, once this one has 30 seconds or less left and autoplay was not cancelled. */
    fun nextOffer(): XtreamEpisode? {
        val current = playing?.episode ?: return null
        if (nextDismissedFor == current.id) return null
        val duration = playerState.durationMs
        val left = duration - playerState.positionMs
        if (duration <= 0L || left !in 1L..60_000L) return null
        return com.enigmacast.domain.xtream.NextEpisode.after(current, playbackEpisodes)
    }

    fun dismissUpcoming() { nextDismissedFor = playing?.episode?.id }

    /** Starts the next episode from the beginning. */
    fun playUpcoming() {
        val current = playing?.episode ?: return
        val next = com.enigmacast.domain.xtream.NextEpisode.after(current, playbackEpisodes) ?: return
        nextDismissedFor = null
        playEpisode(next, playing?.item, fromStart = true)
    }

    /** A Watch Next card is waiting: do not start the remembered live channel over it. */
    fun choosingStartsLive() = library.openRequest.value == null

    fun answerResume(fromStart: Boolean) { val ask = resumeAsk ?: return; resumeAsk = null; ask.play(fromStart) }
    fun dismissResume() { resumeAsk = null }

    /** Films and episodes: position for the seek bar twice a second, the resume place every 5 s. */
    private var tickJob: Job? = null
    private fun showNotice(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(5_000)
            notice = ""
        }
    }

    private fun trySatelliteFallback() {
        if (fallbackLatched || catchup) return
        val name = playing?.item?.name ?: return
        if (playing?.item?.section != XtreamSection.LIVE && !mediaLive) return
        viewModelScope.launch {
            val mode = settingsRepository.current().tvFallback
            if (mode != com.enigmacast.domain.model.TvFallback.IPTV_TO_SAT && mode != com.enigmacast.domain.model.TvFallback.BOTH) return@launch
            if (fallbackLatched) return@launch
            val hit = fallbackFinder.satellite(name) ?: return@launch
            fallbackLatched = true
            val headers = hit.authorization?.let { mapOf("Authorization" to it) } ?: emptyMap()
            httpFactory.setDefaultRequestProperties(headers)
            val login = account ?: M3U_ACCOUNT
            showNotice(context.getString(com.enigmacast.R.string.notice_fallback_sat, hit.name))
            start(hit.url, XtreamPlaying(hit.name, null), live = true, account = login)
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var sinceSave = 0L
            while (true) {
                delay(500)
                val p = player ?: continue
                val duration = p.duration.takeIf { it > 0 } ?: catchupDurationMs
                playerState = playerState.copy(positionMs = p.currentPosition.coerceAtLeast(0), durationMs = duration)
                sinceSave += 500
                if (sinceSave >= com.enigmacast.domain.resume.ResumePolicy.SAVE_EVERY_MS && p.isPlaying) { sinceSave = 0; saveResume() }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs.coerceAtLeast(0))
        playerState = playerState.copy(positionMs = positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) { player?.let { seekTo(it.currentPosition + deltaMs) } }

    fun playFilm(item: XtreamItem, fromStart: Boolean) {
        val account = account ?: return
        if (launchOrigin == null) vodFromDashboard = true
        val url = item.url.ifBlank { XtreamUrls.movie(account, item.id, item.extension) }
        start(url, XtreamPlaying(item.name, item), live = false, account = account, startMs = if (fromStart) 0L else resumeOf(item), resume = resumeKeyOf("m", item.id))
    }

    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private var epgJob: Job? = null

    init {
        viewModelScope.launch {
            val playback = settingsRepository.current().playback
            playbackSubtitles = playback.subtitlesEnabled
            audioDelayMs = playback.audioDelayPcmMs
            audioSync.set(playback.audioDelayPcmMs, playback.audioDelayPassthroughMs)
            subtitleDelayMs = playback.subtitleDelayMs
            subtitleSync.set(playback.subtitleDelayMs)
        }
        viewModelScope.launch { sleepTimer.endsAtMs.collect { sleepEndsAtMs = it } }
        viewModelScope.launch {
            audioSync.passthroughActive.collect { active ->
                if (active == null) return@collect
                audioDelayPassthrough = active
                audioDelayMs = audioSync.delayFor(active)
            }
        }
        viewModelScope.launch { library.resumes.collect { resumeVersion++ } }
        viewModelScope.launch { library.favorites.collect { favoriteVersion++ } }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(library.openRequest, snapshotFlow { account }) { r, a -> r to a }.collect { handleOpenRequest() }
        }
        viewModelScope.launch {
            playbackLifecycle.resumes.collect { count -> if (count > 0) startRememberedLive(force = true) }
        }
        // Categories changed in Settings: the open section is read again (from the cache).
        viewModelScope.launch {
            hiddenCategories.changes.drop(1).collect { if (account != null && categories.isNotEmpty()) selectSection(section, force = true) }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsRepository.settings.map { Triple(it.iptvSource, it.iptvPlaylistKind, it.activeReceiver()) },
                playlistStore.login,
                playlistSync.lastLoad,
                profiles.state.map { com.enigmacast.domain.profile.ProfileRules.seesPlaylist(it, it.activeUuid) },
            ) { s, login, loaded, sees -> Source(s.first, s.second, s.third?.let { it.id to it.host }, login, loaded, sees) }
                .distinctUntilChanged()
                .collectLatest { source -> apply(source) }
        }
    }

    private data class Source(
        val mode: IptvSourceMode,
        val kind: IptvPlaylistKind,
        val receiver: Pair<String, String>?,
        val login: com.enigmacast.data.xtream.IptvPlaylistLogin,
        val loaded: Int,
        val seesPlaylist: Boolean,
    )

    private suspend fun apply(source: Source) {
        if (source.mode == IptvSourceMode.PLAYLIST) {
            val wasPlaylist = playlist != null
            playlist = source.kind
            val playlistAccount = if (!source.seesPlaylist) null else when (source.kind) {
                IptvPlaylistKind.XTREAM -> source.login.xtreamAccount()?.copy(name = "Playlist")
                IptvPlaylistKind.M3U -> M3U_ACCOUNT.takeIf { source.login.configured(IptvPlaylistKind.M3U) }
            }
            val hasRows = playlistAccount != null && playlistSync.count() > 0
            _detection.value = XtreamDetection(false, listOfNotNull(playlistAccount?.takeIf { hasRows }))
            if (hasRows && !catchupIndexStarted && playlistStore.needsCatchupIndex()) {
                catchupIndexStarted = true
                viewModelScope.launch {
                    val result = playlistSync.load(source.kind)
                    if (result !is com.enigmacast.data.xtream.IptvLoadResult.Done) catchupIndexStarted = false
                }
            }
            // A new load or another playlist: the lists start over from Room.
            if (wasPlaylist || account != null) { account = null; categories = emptyList(); category = null; items = emptyList(); cards = emptyList(); series = null }
            open()
            return
        }
        if (playlist != null) { playlist = null; account = null; categories = emptyList(); category = null; items = emptyList(); cards = emptyList() }
        _detection.value = null
        val receiver = settingsRepository.current().activeReceiver() ?: return
        _detection.value = repository.cachedDetection(receiver.id) ?: repository.detect(receiver)
        if (account != null && _detection.value?.accounts?.none { it.key == account?.key } == true) account = null
    }

    fun recheck() {
        if (playlist != null) return
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            _detection.value = repository.detect(receiver, force = true)
        }
    }

    /** The section's categories without the ones hidden in Settings › IPTV › Categories. */
    private suspend fun categoriesOf(account: XtreamAccount, section: XtreamSection, force: Boolean = false): List<XtreamCategory> {
        val all = if (playlist != null) playlistSync.categories(section) else repository.categories(account, section, force)
        val source = com.enigmacast.domain.xtream.IptvCategoryFilter.sourceKey(playlist?.name, account.key)
        return com.enigmacast.domain.xtream.IptvCategoryFilter.visible(all, hiddenCategories.hidden(source, section))
    }

    private suspend fun itemsOf(account: XtreamAccount, section: XtreamSection, id: String, force: Boolean) =
        if (playlist != null) playlistSync.items(section, id) else repository.items(account, section, id, force)

    /**
     * Films or series inside the dashboard: pick the playlist and the requested section,
     * without starting the remembered live channel.
     */
    fun prepareLibrary() {
        openLiveFullscreen = false
        val accounts = _detection.value?.accounts.orEmpty()
        when {
            account == null && accounts.size == 1 -> choose(accounts.first(), startLive = false)
            account != null -> applyRequested()
        }
    }

    /** Opens the page: one playlist is chosen right away; several leave [account] null for the chooser. */
    fun open() {
        val pending = library.openRequest.value
        val accounts = _detection.value?.accounts.orEmpty()
        if (pending != null) {
            openLiveFullscreen = false
            val match = accounts.firstOrNull { it.key == pending.source }
            when {
                match != null && account?.key != pending.source -> choose(match, startLive = false)
                account == null && accounts.size == 1 -> choose(accounts.first(), startLive = false)
            }
            return
        }
        when {
            account == null && accounts.size == 1 -> choose(accounts.first())
            account != null -> {
                val fullscreen = openLiveFullscreen
                applyRequested()
                if (fullscreen) playLastLiveFullscreen() else startRememberedLive()
            }
        }
    }

    fun choose(account: XtreamAccount, startLive: Boolean = true) {
        val start = requestedSection
        val fullscreen = startLive && openLiveFullscreen && (start == null || start == XtreamSection.LIVE)
        requestedSection = null
        if (!fullscreen) openLiveFullscreen = false
        val same = this.account?.key == account.key && categories.isNotEmpty()
        this.account = account
        if (same) {
            if (start != null && start != section) selectSection(start)
            if (fullscreen) playLastLiveFullscreen() else if (startLive) startRememberedLive()
            return
        }
        selectSection(start ?: if (startLive) XtreamSection.LIVE else section, force = true)
        if (fullscreen) playLastLiveFullscreen() else if (startLive) startRememberedLive()
    }

    /**
     * Own tuner, and the last picture was IPTV live: play that channel again.
     * Follow-the-receiver keeps the box's picture and does not start IPTV on its own.
     * Once per start; coming back from the background ([force]) starts it again.
     */
    private var rememberedLive = false
    private fun startRememberedLive(force: Boolean = false) {
        if (!force && rememberedLive) return
        if (account == null) return
        rememberedLive = true
        viewModelScope.launch {
            val settings = settingsRepository.current()
            if (settings.streamMode != com.enigmacast.domain.model.StreamMode.OWN_TUNER) return@launch
            val startupIptv = !force && settings.startupLive == com.enigmacast.domain.model.StartupLive.IPTV
            if (settings.lastLiveSource != com.enigmacast.domain.model.LastLiveSource.IPTV && !startupIptv) return@launch
            val last = settings.lastIptvChannels[account?.key] ?: return@launch
            if (playing?.item?.id == last.streamId && playing?.item?.section == XtreamSection.LIVE) return@launch
            if (vodFromDashboard || playing?.item?.section.let { it != null && it != XtreamSection.LIVE } == true) return@launch
            play(XtreamItem(last.streamId, last.name, XtreamSection.LIVE, last.categoryId, url = last.url))
            libraryPlayed++
        }
    }

    /**
     * IPTV Live TV in the menu: the last channel of this playlist, full screen.
     * Without a saved channel the list stays, so there is something to pick.
     */
    private fun playLastLiveFullscreen() {
        openLiveFullscreen = false
        rememberedLive = true
        viewModelScope.launch {
            val settings = settingsRepository.current()
            if (settings.streamMode != com.enigmacast.domain.model.StreamMode.OWN_TUNER) {
                liveListNeeded = true
                return@launch
            }
            val last = settings.lastIptvChannels[account?.key]
            if (last == null || (last.streamId.isBlank() && last.url.isBlank())) {
                liveListNeeded = true
                return@launch
            }
            val item = XtreamItem(last.streamId, last.name, XtreamSection.LIVE, last.categoryId, url = last.url)
            if (playing?.item?.id != item.id || playing?.item?.section != XtreamSection.LIVE) play(item)
            if (playing != null) libraryPlayed++ else liveListNeeded = true
        }
    }

    private fun applyRequested() {
        val start = requestedSection ?: return
        requestedSection = null
        if (account != null && start != section) selectSection(start)
    }

    fun selectSection(section: XtreamSection, force: Boolean = false) {
        if (!force && section == this.section && categories.isNotEmpty()) return
        if (section != this.section) gridAnchor = 0
        this.section = section
        val account = account ?: return
        loadJob?.cancel()
        categories = emptyList(); category = null; items = emptyList(); cards = emptyList(); series = null; episodes = emptyList()
        loadJob = viewModelScope.launch {
            loading = true; error = false
            runCatching { categoriesOf(account, section) }
                .onSuccess { list ->
                    categories = list
                    loadCounts(account, section)
                    categoriesReady++
                    val preferred = if (section == XtreamSection.LIVE) {
                        settingsRepository.current().lastIptvChannels[account.key]?.categoryId
                    } else null
                    val pick = list.firstOrNull { it.id == preferred } ?: list.firstOrNull()
                    pick?.let { loadCategory(account, it) }
                }
                .onFailure { error = true }
            loading = false
        }
    }

    /**
     * OK on a category: fetches its list (cancelling a fetch that was still running). The list on
     * screen stays until the new one is there. OK on the category that is shown only moves focus.
     */
    fun selectCategory(category: XtreamCategory) {
        val account = account ?: return
        if (category.id == this.category?.id && series == null && pendingCategory == null && items.isNotEmpty()) {
            itemsReady++
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadCategory(account, category) }
    }

    private suspend fun loadCategory(account: XtreamAccount, category: XtreamCategory, force: Boolean = false, announce: Boolean = true) {
        pendingCategory = category
        loading = true; error = false
        try {
            val started = System.currentTimeMillis()
            runCatching {
                val list = itemsOf(account, section, category.id, force)
                // The cards' playlist data (title clean-up per name) is built once, off the main thread.
                val base = if (section == XtreamSection.LIVE) emptyList()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { IptvCard.of(list) }
                list to base
            }
                .onSuccess { (list, base) ->
                    if (com.enigmacast.BuildConfig.DEBUG) timber.log.Timber.d("IPTV ${section.name} category: ${list.size} items in ${System.currentTimeMillis() - started} ms")
                    if (this.category?.id != category.id) gridAnchor = 0
                    this.category = category
                    series = null; episodes = emptyList()
                    cards = base
                    categoryCounts = categoryCounts + (category.id to list.size)
                    items = list
                    // Only an OK-loaded category moves focus into the grid; the auto-loaded first one does not.
                    if (announce) itemsReady++
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = true }
        } finally {
            if (pendingCategory?.id == category.id) pendingCategory = null
            loading = false
        }
    }

    /** In playlist mode the list is reloaded in Settings or by the background sync; here Room is read again. */
    fun refresh() {
        val account = account ?: return
        val current = category
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            runCatching { categories = categoriesOf(account, section, force = true) }
            (current ?: categories.firstOrNull())?.let { loadCategory(account, it, force = true) }
            loading = false
        }
    }

    fun openSeries(item: XtreamItem) {
        val account = account ?: return
        if (account === M3U_ACCOUNT) {
            if (item.url.isNotBlank()) start(item.url, XtreamPlaying(item.name, item), live = false, account = account)
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true; error = false
            try {
                runCatching { repository.episodes(account, item.id) }
                    .onSuccess { episodes = it; series = item; itemsReady++ }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = true }
            } finally {
                loading = false
            }
        }
    }

    fun closeSeries() {
        series = null
        episodes = emptyList()
    }

    /** Loads now/next for a live channel once focus rests on it. */
    fun focusLive(item: XtreamItem) {
        if (item.section != XtreamSection.LIVE || epg.containsKey(item.id) || account == null) return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            delay(350)
            epg = epg + (item.id to loadGuide(item))
        }
    }

    fun play(item: XtreamItem) {
        catchup = false
        catchupDurationMs = 0L
        fallbackLatched = false
        httpFactory.setDefaultRequestProperties(emptyMap())
        val account = account ?: return
        if (item.url.isNotBlank() && item.section != XtreamSection.SERIES) {
            start(item.url, XtreamPlaying(item.name, item), live = item.section == XtreamSection.LIVE, account = account)
            return
        }
        when (item.section) {
            XtreamSection.SERIES -> openSeries(item)
            XtreamSection.LIVE -> start(XtreamUrls.live(account, item.id), XtreamPlaying(item.name, item), live = true, account = account).also { focusLive(item) }
            XtreamSection.MOVIES -> start(XtreamUrls.movie(account, item.id, item.extension), XtreamPlaying(item.name, item), live = false, account = account)
        }
    }

    fun playCatchup(item: XtreamItem, event: XtreamEpg) {
        val loginAccount = account ?: return
        val login = loginAccount.takeIf { it !== M3U_ACCOUNT }
        val url = com.enigmacast.domain.xtream.Catchup.url(login, item, event) ?: return
        catchup = true
        catchupDurationMs = (event.endSec - event.startSec).coerceAtLeast(1) * 1000
        fallbackLatched = false
        start(url, XtreamPlaying(event.title, item), live = false, account = loginAccount, archive = true)
    }

    fun openPipPicker() {
        if (pip.playing()) pip.stop() else showPipPicker = true
    }

    fun playEpisode(episode: XtreamEpisode, show: XtreamItem? = series, fromStart: Boolean = false) {
        val account = account ?: return
        if (launchOrigin == null) vodFromDashboard = true
        val series = show
        if (playbackEpisodes.none { it.id == episode.id } && series != null) {
            viewModelScope.launch {
                val list = runCatching { repository.episodes(account, series.id) }.getOrDefault(emptyList())
                if (playing?.episode?.id == episode.id && list.any { it.id == episode.id }) playbackEpisodes = list
            }
        }
        val title = listOfNotNull(series?.name, "S${episode.season}E${episode.episode}".takeIf { episode.season > 0 }).joinToString(" · ")
        start(
            XtreamUrls.episode(account, episode.id, episode.extension),
            XtreamPlaying("$title · ${episode.title}".trim(' ', '·'), series, episode),
            live = false,
            account = account,
            startMs = if (fromStart) 0L else resumeOf(episode),
            resume = resumeKeyOf("e", episode.id),
        )
    }

    /** Channel up/down while a live channel plays: the next one in the open category. */
    fun zap(step: Int) {
        if (pip.playing() && pip.swapped.value) {
            pip.zap(step)
            return
        }
        fallbackLatched = false
        catchup = false
        val current = playing?.item?.takeIf { it.section == XtreamSection.LIVE } ?: return
        val list = items.filter { it.section == XtreamSection.LIVE }
        val index = list.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: return
        list.getOrNull((index + step).mod(list.size))?.let(::play)
    }

    @OptIn(UnstableApi::class)
    fun player(): ExoPlayer = player ?: run {
        val http = httpFactory
        val selector = DefaultTrackSelector(context)
        selector.parameters = selector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !playbackSubtitles)
            .setSelectUndeterminedTextLanguage(playbackSubtitles)
            .build()
        ExoPlayer.Builder(context)
            .setRenderersFactory(IptvRenderersFactory(context, audioSync, subtitleSync))
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = state == Player.STATE_BUFFERING
                        playerState = playerState.copy(
                            playbackState = when (state) {
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "IDLE"
                            },
                        )
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playerState = playerState.copy(playing = isPlaying)
                    }

                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                        playerState = playerState.copy(videoWidth = size.width, videoHeight = size.height, pixelRatio = size.pixelWidthHeightRatio)
                    }

                    override fun onTracksChanged(t: Tracks) {
                        val pending = pendingEmbeddedId
                        pendingEmbeddedId = null
                        if (pending != null) {
                            holdAutoText = true
                            applyEmbeddedSubtitles(p, pending)
                            holdAutoText = false
                        } else if (selectedExternalId == null && !holdAutoText) {
                            selectFirstTextTrack(p)
                        }
                        publishTracks(p)
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        cues = if (textOn) cueGroup.cues else emptyList()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // The message never has the URL: only the error code name.
                        EnigmaLog.networkError("Xtream playback: ${error.errorCodeName}")
                        playerError = error.errorCodeName
                        trySatelliteFallback()
                    }
                })
                player = p
            }
    }

    private fun start(url: String, what: XtreamPlaying, live: Boolean, account: XtreamAccount, startMs: Long = 0L, resume: String? = null, archive: Boolean = false) {
        saveResume()
        resumeKey = resume
        // Playlist addresses can carry a login anywhere: only the section is logged for those.
        if (account === M3U_ACCOUNT) EnigmaLog.network("IPTV play playlist entry (${what.item?.section})")
        else EnigmaLog.network("Xtream play ${XtreamUrls.redact(url, account).substringAfter("://").substringAfter('/')}")
        // One player at a time: a running Enigma2 channel, film or mini player stops first.
        // Enigma2 is stopped and its ExoPlayer released (decoder freed) before this one prepares.
        playbackOwner.register(com.enigmacast.playback.PlaybackOwner.Owner.IPTV) { yieldToOther() }
        playbackOwner.claim(com.enigmacast.playback.PlaybackOwner.Owner.IPTV)
        playerError = null
        playing = what
        cues = emptyList()
        clearExternalSubtitles()
        mediaUrl = url
        mediaLive = live
        playerState = com.enigmacast.domain.model.PlayerState(filePlayback = !live)
        player().apply {
            setMediaItem(mediaItem(url, live), startMs.coerceAtLeast(0L))
            prepare()
            playWhenReady = true
        }
        if (!live && !archive) loadExternalSubtitles(what, account)
        if (live && !archive) { tickJob?.cancel(); tickJob = null } else startTicker()
        val item = what.item
        if (live && !archive && item != null) {
            val recent = com.enigmacast.domain.model.LastChannel(item.id, item.name, item.categoryId)
            recentChannels = listOf(recent) + recentChannels.filter { it.reference != item.id }.take(11)
            viewModelScope.launch {
                settingsRepository.update { settings ->
                    com.enigmacast.domain.model.ChannelMemory.rememberIptv(
                        settings,
                        com.enigmacast.domain.model.LastIptvChannel(account.key, item.categoryId, item.id, item.name, url),
                    )
                }
            }
        }
    }

    fun chooseAudio(id: String) {
        val p = player ?: return
        val (group, track) = com.enigmacast.playback.TrackMenus.find(p.currentTracks, id, androidx.media3.common.C.TRACK_TYPE_AUDIO) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }

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

    fun choosePictureMode(mode: com.enigmacast.domain.player.PictureMode) { pictureMode = mode }

    /** Same correction as the Enigma2 player: it applies at once and is kept in settings. */
    fun setSubtitleDelay(ms: Int) {
        val value = com.enigmacast.playback.SubtitleSync.clamp(ms)
        subtitleDelayMs = value
        subtitleSync.set(value)
        viewModelScope.launch {
            settingsRepository.update { settings ->
                settings.copy(playback = settings.playback.copy(subtitleDelayMs = value))
            }
        }
    }

    fun setAudioDelay(ms: Int) {
        val value = com.enigmacast.playback.AudioSync.clamp(ms)
        audioDelayMs = value
        val passthrough = audioDelayPassthrough
        audioSync.set(
            if (passthrough) audioSync.pcmMs else value,
            if (passthrough) value else audioSync.passthroughMs,
        )
        viewModelScope.launch {
            settingsRepository.update { settings ->
                val playback = if (passthrough) {
                    settings.playback.copy(audioDelayPassthroughMs = value)
                } else {
                    settings.playback.copy(audioDelayPcmMs = value)
                }
                settings.copy(playback = playback)
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimer.set(minutes)
    }

    /** Recent tile: zap to a live channel watched earlier in this visit. */
    fun playRecent(channel: com.enigmacast.domain.model.LastChannel) {
        val known = items.firstOrNull { it.section == XtreamSection.LIVE && it.id == channel.reference }
        play(known ?: XtreamItem(channel.reference, channel.name, XtreamSection.LIVE, channel.bouquetReference))
    }

    /** Live reloads the stream. A film or episode goes back to the start. */
    fun restart() {
        val item = playing?.item
        if (item != null && item.section == XtreamSection.LIVE && playing?.episode == null) {
            play(item)
            return
        }
        player?.seekTo(0)
    }

    fun togglePause() {
        player?.let { it.playWhenReady = !it.playWhenReady }
    }

    /** Leaving the page: the stream stops, the lists stay for next time. */
    fun stop() {
        tickJob?.cancel()
        saveResume()
        resumeKey = null
        clearExternalSubtitles()
        cues = emptyList()
        playbackOwner.release(com.enigmacast.playback.PlaybackOwner.Owner.IPTV)
        player?.stop()
        player?.clearMediaItems()
        playing = null
    }

    fun setForeground(front: Boolean) {
        val o = com.enigmacast.playback.PlaybackOwner.Owner.IPTV
        if (front) playbackOwner.foreground = o else if (playbackOwner.foreground == o) playbackOwner.foreground = null
    }

    /** Enigma2 takes over: stop and release the IPTV ExoPlayer too, so its decoder is free. */
    private fun yieldToOther() {
        stop()
        player?.release()
        player = null
        playerVersion++
    }

    /** Bumped when the ExoPlayer instance is replaced, so the video view binds the new one. */
    var playerVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    override fun onCleared() {
        playbackOwner.unregister(com.enigmacast.playback.PlaybackOwner.Owner.IPTV)
        player?.release()
        player = null
    }

    private fun mediaItem(url: String, live: Boolean, subtitle: MediaItem.SubtitleConfiguration? = null): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (url.endsWith(".m3u8") || (url.substringBefore('?').endsWith(".m3u8") && !url.endsWith(".m3u8"))) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        if (live && url.endsWith(".ts")) builder.setMimeType(MimeTypes.VIDEO_MP2T)
        if (subtitle != null) builder.setSubtitleConfigurations(listOf(subtitle))
        return builder.build()
    }

    private fun reload(p: ExoPlayer, subtitle: MediaItem.SubtitleConfiguration?) {
        val position = p.currentPosition.coerceAtLeast(0L)
        val playingNow = p.playWhenReady
        p.setMediaItem(mediaItem(mediaUrl, mediaLive, subtitle), position)
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

    /** SubSource files for a film or episode, in the languages turned on in Settings. */
    private fun loadExternalSubtitles(what: XtreamPlaying, account: XtreamAccount) {
        val item = what.item ?: return
        if (item.section == XtreamSection.LIVE && what.episode == null) return
        subtitleJob = viewModelScope.launch {
            val saved = settingsRepository.current().iptvSubtitleLanguages
            val languages = saved ?: listOfNotNull(
                com.enigmacast.domain.subsource.SubSourceLanguages.forAppTag(
                    com.enigmacast.locale.AppLanguageStore.current(context).tag,
                ),
            )
            if (languages.isEmpty()) return@launch
            val (title, nameYear) = com.enigmacast.domain.subsource.SubSourceMatch.cleanName(item.name)
            val year = nameYear ?: if (account !== M3U_ACCOUNT) {
                runCatching { repository.vodInfo(account, item)?.year }.getOrNull()
            } else {
                null
            }
            val episode = what.episode
            val hits = subtitles.find(
                com.enigmacast.domain.subsource.SubSourceQuery(
                    title = title,
                    year = year,
                    season = episode?.season?.takeIf { it > 0 },
                    episode = episode?.episode?.takeIf { it > 0 },
                    languages = languages,
                ),
            )
            if (playing != what) return@launch
            externalHits = hits
            player?.let(::publishTracks)
        }
    }

    private fun publishTracks(p: ExoPlayer) {
        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val menu = com.enigmacast.playback.TrackMenus.build(p.currentTracks, textOn && selectedExternalId == null)
        val embedded = menu.subtitles.filter { choice ->
            val found = com.enigmacast.playback.TrackMenus.find(p.currentTracks, choice.id, C.TRACK_TYPE_TEXT)
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
        val video = p.videoFormat
        val audio = p.audioFormat
        playerState = playerState.copy(
            videoCodec = video?.sampleMimeType.orEmpty(),
            frameRate = video?.frameRate?.takeIf { it > 0f } ?: 0f,
            audioCodec = audio?.sampleMimeType.orEmpty(),
            audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
            audioLabel = com.enigmacast.playback.TrackLabels.audio(audio),
            audioTrackCount = menu.audio.size,
            subtitleLabel = merged.subtitles.firstOrNull { it.selected }?.label ?: com.enigmacast.util.UiText.Empty,
            subtitleTrackCount = merged.subtitles.size,
        )
    }

    /** Same as the Enigma2 player: the first supported text track, when none is selected yet. */
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
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        holdAutoText = false
    }

    private fun applyEmbeddedSubtitles(p: ExoPlayer, id: String) {
        val (group, track) = com.enigmacast.playback.TrackMenus.find(p.currentTracks, id, C.TRACK_TYPE_TEXT) ?: return
        if (group.getTrackFormat(track).id?.startsWith(EXTERNAL_PREFIX) == true) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }

    private fun subtitleMime(extension: String): String = when (extension.lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    internal companion object {
        const val EPG_BATCH = 24
        private const val EXTERNAL_PREFIX = "ext:"
        /** Stand-in account of an M3U playlist: every entry has its own address. */
        val M3U_ACCOUNT = XtreamAccount("Playlist", "http://playlist.invalid", "", "", "ts")
    }
}

/** IPTV player with the same audio-delay clock as the Enigma2 player. */
@OptIn(UnstableApi::class)
internal class IptvRenderersFactory(
    context: Context,
    private val offsets: com.enigmacast.playback.AudioSyncOffsets,
    private val subtitleSync: com.enigmacast.playback.SubtitleSyncOffset,
) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(com.enigmacast.playback.OffsetTextRenderer(output, outputLooper, subtitleSync))
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
        return com.enigmacast.playback.AudioSyncSink(sink, offsets)
    }
}

/**
 * One film or series card: the item, its playlist-only card and its unique grid key, built
 * together so the grid's list and its keys always come from the same snapshot.
 */
data class IptvCard(val key: String, val item: XtreamItem, val base: com.enigmacast.domain.films.HomeFilm) {
    companion object {
        fun of(items: List<XtreamItem>): List<IptvCard> =
            com.enigmacast.domain.xtream.LazyKeys.keyed(items, "vod") { "${it.section.name}:${it.id}" }
                .map { IptvCard(it.key, it.value, com.enigmacast.data.xtream.IptvVod.film(it.value, null)) }
    }
}

/** Resume rules of the recordings (Library), for IPTV films and episodes. */
object IptvResume {
    /** The position to keep, or null to forget it (too early, or watched to the end). */
    fun toStore(positionMs: Long, durationMs: Long): Long? {
        if (positionMs < com.enigmacast.domain.resume.ResumePolicy.MIN_RESUME_MS) return null
        if (durationMs > 0 && com.enigmacast.domain.resume.ResumePolicy.watched(positionMs, durationMs)) return null
        return positionMs
    }

    fun offer(savedMs: Long): Long? = savedMs.takeIf { it >= com.enigmacast.domain.resume.ResumePolicy.MIN_RESUME_MS }
}

