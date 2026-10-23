@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.enigmacast.ui.xtream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import com.enigmacast.domain.tuner.RestartBudget
import com.enigmacast.playback.LiveLoadErrorHandlingPolicy
import com.enigmacast.playback.StreamFailures
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
    private val rowEpg: com.enigmacast.data.xtream.IptvRowEpg,
    private val playbackOwner: com.enigmacast.playback.PlaybackOwner,
    private val playbackLifecycle: com.enigmacast.live.PlaybackLifecycle,
    private val artworkSource: com.enigmacast.data.xtream.IptvArtwork,
    private val hiddenCategories: com.enigmacast.data.xtream.IptvHiddenCategories,
    private val library: com.enigmacast.data.xtream.IptvLibrary,
    private val watchNext: com.enigmacast.data.xtream.WatchNextPublisher,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
    private val recentIptv: com.enigmacast.data.xtream.RecentIptvStore,
    private val sleepTimer: com.enigmacast.playback.AppSleepTimer,
    private val subtitles: SubSourceClient,
    val pip: com.enigmacast.playback.PipController,
    private val fallbackFinder: com.enigmacast.live.TvFallbackFinder,
    @ApplicationContext private val context: Context,
    private val equivalents: com.enigmacast.data.channel.EquivalentChannelsController,
    private val channelSwitch: com.enigmacast.data.channel.ChannelSwitchBus,
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

    /** A live channel plays (not a film, an episode or catch-up): it may continue in the mini player. */
    val playingLive: Boolean
        get() = playing.let { com.enigmacast.domain.xtream.IptvMini.continues(it?.item?.section, it?.episode != null, catchup) }

    /** Set by Back on the bare live picture: the page closes, the channel plays on (mini player). */
    private var continueInMini = false
    fun continueInMiniPlayer() { continueInMini = playingLive }
    /** The page closes: true when the live channel must keep playing (read once). */
    fun consumeContinueInMini(): Boolean = continueInMini.also { continueInMini = false }

    /** OK on the mini player: back to that channel full screen, without restarting it. */
    var liveResume by mutableStateOf(false)
        private set
    fun resumeLiveFullscreen(): Boolean {
        if (!playingLive) return false
        openLiveFullscreen = false
        liveListNeeded = false
        requestedSection = null
        liveResume = true
        return true
    }
    fun consumeLiveResume() { liveResume = false }

    /** The mini player's line: what is on air on the channel that plays. */
    fun playingNowTitle(): String? {
        val item = playing?.item?.takeIf { playingLive } ?: return null
        return com.enigmacast.domain.xtream.IptvMini.nowTitle(epgOf(item.id), System.currentTimeMillis() / 1000)
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
    val equivalentsState get() = equivalents.state
    private var pendingEquivalent: com.enigmacast.domain.channel.ChannelSwitchRequest.Iptv? = null
    var playerError by mutableStateOf<String?>(null)
    private val iptvRetries = RestartBudget(5, 1_000L, 8_000L)
    private var iptvRecoveryJob: Job? = null
    private var mediaAccount: XtreamAccount? = null
    private var lastIptvPosition = -1L
    private var lastIptvAdvanceAt = 0L
    private var iptvStableSince = 0L
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                if (playing == null) return@launch
                iptvRetries.reset()
                requestIptvRecovery("network available", immediate = true)
            }
        }
    }
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
    /**
     * Listings per live channel, by stream id: one state per row, so a row's answer only
     * recomposes that row (and the OSD of that channel), never the whole list. Main thread only.
     */
    private val epgRows = HashMap<String, androidx.compose.runtime.MutableState<List<XtreamEpg>?>>()

    // Like remember { mutableStateOf() }: the first reader (a row, during composition) creates it.
    private fun epgState(id: String) = epgRows.getOrPut(id) { mutableStateOf<List<XtreamEpg>?>(null) }

    /** The listings of one channel, observed by its row; null until known. */
    fun epgOf(id: String): List<XtreamEpg>? = epgState(id).value

    /** Without observing (event handlers, the VM itself). */
    private fun epgNow(id: String): List<XtreamEpg>? = epgRows[id]?.value

    private fun clearEpgRows() {
        epgRows.values.forEach { it.value = null }
        epgRows.clear()
    }

    private fun publishEpg(id: String, events: List<XtreamEpg>) {
        val state = epgState(id)
        if (state.value != events) state.value = events
    }

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
    private var pendingEmbeddedId: String? = null
    private var holdAutoText = false
    private var subtitleJob: Job? = null
    private var downloadJob: Job? = null

    /** GUI skin and OSD transparency, as the receiver player uses them. */
    val osdLook: StateFlow<Pair<com.enigmacast.domain.model.OsdSkinId, Int>> = settingsRepository.settings
        .map { it.osdSkin to it.osd.transparencyPercent.coerceIn(0, 100) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.enigmacast.domain.model.OsdSkinId.STANDARD to 10)

    /**
     * Decoded channel logos, by URL. Bounded by bytes (the list could pass thousands of channels),
     * each at most [LOGO_MAX_PX] wide: the row shows 36 dp, the OSD banner a little more.
     */
    private val logos = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(LOGO_CACHE_BYTES) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
    }
    private val logoFailed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val logoGate = kotlinx.coroutines.sync.Semaphore(4)
    private val logoClient by lazy { okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build() }

    private fun kotlin.coroutines.CoroutineContext.isActiveCompat() = this[kotlinx.coroutines.Job]?.isActive != false

    fun cachedLogo(url: String): androidx.compose.ui.graphics.ImageBitmap? = logos.get(url)

    /**
     * The channel logo of the playlist (tvg-logo / stream_icon), off the main thread, 4 at a time.
     * Decoded subsampled to at most [LOGO_MAX_PX] wide instead of at full size. A logo that failed
     * is not asked again for 10 minutes; a cancelled caller (row scrolled away) stops early.
     */
    suspend fun logo(url: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        logos.get(url)?.let { return it }
        logoFailed[url]?.let { if (System.currentTimeMillis() - it < 10 * 60_000L) return null }
        logoGate.acquire()
        try {
            logos.get(url)?.let { return it }
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = try {
                    logoClient.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", XtreamRepository.USER_AGENT).build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body.bytes().takeIf { it.size <= LOGO_MAX_BYTES }
                    }
                } catch (e: java.io.IOException) {
                    null
                }
                if (!kotlinx.coroutines.currentCoroutineContext().isActiveCompat()) return@withContext null
                val image = bytes?.let { com.enigmacast.ui.xtream.LogoDecode.decode(it, LOGO_MAX_PX) }
                if (image == null) logoFailed[url] = System.currentTimeMillis() else logos.put(url, image)
                image
            }
        } finally {
            logoGate.release()
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
     * Now/next for the rows on screen. Called whenever the visible rows change; the previous
     * batch is cancelled, and nothing starts before scrolling rests [RowGuide.SETTLE_MS], so
     * holding Up/Down fetches nothing. Then, off the main thread:
     *  1. the bulk guide in Room (the receiver's satellite guide, matched by name), one query;
     *  2. for the rest, listings stored per stream (memory, then Room, within their TTL);
     *  3. what is still missing from Xtream, the same listings the programme column uses,
     *     [RowGuide.CONCURRENCY] at a time. Each answer updates its own row as it arrives.
     * A failed or cancelled request is not remembered as "no guide", so the row is asked again.
     */
    fun requestEpg(visible: List<XtreamItem>) {
        visibleJob?.cancel()
        val current = account ?: return
        visibleJob = viewModelScope.launch {
            delay(com.enigmacast.domain.xtream.RowGuide.SETTLE_MS)
            val nowMs = System.currentTimeMillis()
            val now = nowMs / 1000
            val source = current.key
            val todo = visible.asSequence()
                .filter { it.section == XtreamSection.LIVE && rowEpg.needsFetch(source, it.id, nowMs) }
                .distinctBy { it.id }
                .take(EPG_BATCH)
                .toList()
            if (todo.isEmpty()) return@launch
            val xtream = current.takeIf { it !== M3U_ACCOUNT }
            val sat = try {
                satelliteEpg.byName(todo.map { it.name })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            val stored = if (xtream != null) rowEpg.stored(xtream, todo.map { it.id }) else emptyMap()
            val network = ArrayList<XtreamItem>()
            todo.forEach { item ->
                val bulk = sat[item.name].orEmpty()
                val kept = stored[item.id]
                when {
                    bulk.airingAt(now) != null -> { publishEpg(item.id, bulk); rowEpg.remember(source, item.id, bulk, nowMs) }
                    kept != null -> publishEpg(item.id, chooseGuide(kept, bulk, now))
                    xtream == null -> { publishEpg(item.id, bulk); rowEpg.remember(source, item.id, bulk, nowMs) }
                    else -> { if (bulk.isNotEmpty()) publishEpg(item.id, bulk); network += item }
                }
            }
            if (xtream == null || network.isEmpty()) return@launch
            kotlinx.coroutines.coroutineScope {
                network.forEach { item ->
                    launch {
                        val list = rowEpg.fetch(xtream, item.id) ?: return@launch
                        publishEpg(item.id, chooseGuide(list, sat[item.name].orEmpty(), System.currentTimeMillis() / 1000))
                    }
                }
            }
        }
    }

    /** Right on a channel: its programme column. */
    fun openGuide(item: XtreamItem) {
        guideItem = item
        guideEvents = epgNow(item.id).orEmpty()
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            guideLoading = true
            try {
                val list = loadGuide(item)
                if (list.isNotEmpty()) {
                    guideEvents = list
                    publishEpg(item.id, list)
                    account?.takeIf { it !== M3U_ACCOUNT }?.let { rowEpg.store(it, item.id, list) }
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
            val sat = try {
                satelliteEpg.byName(listOf(item.name), hours = 36, pastHours = pastDays * 24)[item.name].orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
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
        detailEpisodeHint = null
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

    /** The episode a Continue watching card opened the series page for (Play starts it). */
    private var detailEpisodeHint: XtreamEpisode? = null

    /**
     * What Play on the series info page starts: the Continue watching episode, else the latest
     * episode watched, else the first one. Null while nothing is known yet (list still loading).
     */
    fun seriesPlayTarget(show: XtreamItem): XtreamEpisode? {
        val source = account?.key
        val latest = library.resumes.value
            .filter { it.kind == "e" && it.source == source && it.seriesId == show.id }
            .maxByOrNull { it.updatedMs }
            ?.let { XtreamEpisode(it.id, it.season, it.episode, it.title, it.extension) }
        return com.enigmacast.domain.xtream.SeriesPlay.target(detailEpisodeHint?.takeIf { detail?.id == show.id }, latest, detailEpisodes)
    }

    /** Play on a series info page: that episode, with the resume question when it was started. */
    fun playSeries(show: XtreamItem, onStarted: () -> Unit) {
        val episode = seriesPlayTarget(show) ?: return
        holdEpisodes(detailEpisodes)
        closeDetail()
        requestEpisode(episode, show, onStarted)
    }

    fun closeDetail() {
        detailEpisodeHint = null
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
        val what = playing
        val item = what?.item
        val episode = what?.episode
        val position = p.currentPosition
        val duration = p.duration.coerceAtLeast(0)
        if (episode != null && item != null && duration > 0 && com.enigmacast.domain.resume.ResumePolicy.watched(position, duration)) {
            finishEpisode(source, kind, item, episode, position, duration)
            resumeVersion++
            return
        }
        when (val keep = IptvResume.toStore(position, p.duration)) {
            null -> {
                library.clearResume(source, kind, id)
                watchNext.remove(source, kind, id)
            }
            else -> {
                val row = resumeRow(source, kind, id, item, episode, what?.title.orEmpty(), keep, duration)
                library.saveResume(row)
                watchNext.publish(row)
            }
        }
        resumeVersion++
    }

    private fun resumeRow(
        source: String, kind: String, id: String, item: XtreamItem?, episode: XtreamEpisode?,
        fallbackTitle: String, positionMs: Long, durationMs: Long,
    ) = com.enigmacast.data.db.IptvResumeRow(
        source = source, kind = kind, id = id,
        positionMs = positionMs, durationMs = durationMs, updatedMs = System.currentTimeMillis(),
        title = if (episode != null) episode.title.ifBlank { fallbackTitle } else item?.name ?: fallbackTitle,
        poster = item?.let(::posterOf),
        extension = episode?.extension ?: item?.extension.orEmpty(),
        url = if (episode == null) item?.url.orEmpty() else "",
        categoryId = item?.categoryId.orEmpty(),
        seriesId = if (episode != null) item?.id else null,
        seriesTitle = if (episode != null) item?.name else null,
        season = episode?.season ?: 0,
        episode = episode?.episode ?: 0,
    )

    /** A watched episode: the series card moves on to the next one, or goes after the very last. */
    private fun finishEpisode(source: String, kind: String, item: XtreamItem, episode: XtreamEpisode, position: Long, duration: Long) {
        val saved = library.resumeMap.value
        when (val done = com.enigmacast.domain.resume.SeriesContinue.afterIptv(episode, playbackEpisodes)) {
            com.enigmacast.domain.resume.EpisodeDone.Unknown -> {
                val row = resumeRow(source, kind, episode.id, item, episode, episode.title, position, duration)
                library.saveResume(row)
                watchNext.publish(row)
            }
            com.enigmacast.domain.resume.EpisodeDone.SeriesDone -> {
                // The whole series (every saved episode, with tombstones) leaves Continue watching.
                if (saved.values.any { it.source == source && it.kind == "e" && it.seriesId == item.id }) {
                    library.removeContinue(source, kind, episode.id)
                }
            }
            is com.enigmacast.domain.resume.EpisodeDone.Next -> {
                val next = playbackEpisodes.firstOrNull { it.id == done.id } ?: return
                if (!com.enigmacast.domain.resume.SeriesContinue.keepNext(saved[com.enigmacast.data.xtream.IptvLibrary.key(source, kind, next.id)])) {
                    val row = resumeRow(source, kind, next.id, item, next, next.title, 0L, 0L)
                    library.saveResume(row)
                    watchNext.publish(row)
                }
                if (saved.containsKey(com.enigmacast.data.xtream.IptvLibrary.key(source, kind, episode.id))) {
                    library.clearResume(source, kind, episode.id)
                    watchNext.remove(source, kind, episode.id)
                }
            }
        }
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
        } else if (request.detail && item.section == XtreamSection.SERIES) {
            // The info page (My Items / Home OK): Play there starts the card's episode.
            selectSection(item.section)
            openDetail(item)
            detailEpisodeHint = episode
            libraryDetail++
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

    /** The episode after the one playing (for the OSD's Next button), any time. */
    fun nextEpisode(): XtreamEpisode? = playing?.episode?.let { com.enigmacast.domain.xtream.NextEpisode.after(it, playbackEpisodes) }

    var playbackSpeed by mutableStateOf(1f)
        private set

    fun choosePlaybackSpeed(value: Float) {
        playbackSpeed = value
        player?.setPlaybackSpeed(value)
    }

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

    private val starved = com.enigmacast.playback.StarvedBuffer(8_000)

    private fun startTicker() {
        starved.reset()
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var sinceSave = 0L
            while (true) {
                delay(500)
                val p = player ?: continue
                // Stuck with nothing playable and not loading: reload at the position (empties the buffer).
                val stuck = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady
                val now = android.os.SystemClock.elapsedRealtime()
                val position = p.currentPosition
                if (p.playWhenReady && position > lastIptvPosition + 500) {
                    lastIptvPosition = position
                    lastIptvAdvanceAt = now
                }
                if (starved.update(stuck, p.isLoading, p.totalBufferedDuration, now)) {
                    requestIptvRecovery("starved")
                } else if (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING && now - lastIptvAdvanceAt >= 15_000L) {
                    // Also recover when the loader itself is stuck (isLoading=true), not only
                    // when Media3 refuses to load because the buffer is empty.
                    requestIptvRecovery("no progress while buffering")
                }
                if (p.isPlaying) {
                    if (iptvStableSince == 0L) iptvStableSince = now
                    if (now - iptvStableSince >= 30_000L) {
                        iptvRetries.reset()
                        iptvStableSince = now
                    }
                } else {
                    iptvStableSince = 0L
                }
                if (p.duration != C.TIME_UNSET && p.duration > 0) lastDurationMs = p.duration
                val duration = com.enigmacast.playback.SubtitleSafety.durationFor(p.duration, lastDurationMs, catchupDurationMs)
                playerState = playerState.copy(positionMs = p.currentPosition.coerceAtLeast(0), durationMs = duration, bufferedPositionMs = p.bufferedPosition.coerceAtLeast(0))
                sinceSave += 500
                if (sinceSave >= com.enigmacast.domain.resume.ResumePolicy.SAVE_EVERY_MS && p.isPlaying) { sinceSave = 0; saveResume() }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        // Media3 sends every seek on an unseekable stream to 0: leave such a stream playing instead.
        if (!com.enigmacast.playback.SubtitleSafety.canSeek(p.isCurrentMediaItemSeekable, false) && !catchup) {
            EnigmaLog.subs("iptv seek to ${positionMs}ms refused: ${com.enigmacast.playback.SubtitleSafety.describe(snapshot(p))}")
            subsNotice(com.enigmacast.R.string.seek_unseekable)
            return
        }
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
            channelSwitch.request.collect { request ->
                if (request is com.enigmacast.domain.channel.ChannelSwitchRequest.Iptv) {
                    pendingEquivalent = request
                    applyPendingEquivalent()
                }
            }
        }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
        viewModelScope.launch {
            val playback = settingsRepository.current().playback
            playbackSubtitles = playback.subtitlesEnabled
            preferredText = preferredTextLanguages(settingsRepository.current().iptvSubtitleLanguages)
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
        // Another profile: a channel that went on in the mini player was the previous profile's.
        viewModelScope.launch {
            profiles.switched.collect {
                recentChannels = recentIptv.load(account?.key.orEmpty())
                if (playing != null && playbackOwner.foreground != com.enigmacast.playback.PlaybackOwner.Owner.IPTV) stop()
            }
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
        // Stream ids are per account: another playlist's rows start without listings.
        if (this.account?.key != account.key) clearEpgRows()
        this.account = account
        recentChannels = recentIptv.load(account.key)
        if (same) {
            if (start != null && start != section) selectSection(start)
            if (fullscreen) playLastLiveFullscreen() else if (startLive) startRememberedLive()
            applyPendingEquivalent()
            return
        }
        selectSection(start ?: if (startLive) XtreamSection.LIVE else section, force = true)
        if (fullscreen) playLastLiveFullscreen() else if (startLive) startRememberedLive()
        applyPendingEquivalent()
    }

    /** First quick-bar tile: same channel on satellite or another IPTV source. */
    fun openEquivalent() {
        val name = playing?.item?.name?.takeIf { it.isNotBlank() }
            ?: playing?.title?.takeIf { it.isNotBlank() }
            ?: return
        viewModelScope.launch {
            equivalents.open(
                name = name,
                receiverId = settingsRepository.current().activeReceiver()?.id,
                account = account,
                playlistMode = playlist != null,
            )
        }
    }

    fun chooseEquivalent(channel: com.enigmacast.domain.channel.ChannelAlternative) {
        equivalents.choose(channel)
    }

    fun closeEquivalent() = equivalents.close()

    /** "Geschiedenis wissen" in the recent strip. */
    fun clearRecent() {
        recentIptv.clear(account?.key.orEmpty())
        recentChannels = emptyList()
    }

    private fun applyPendingEquivalent() {
        val request = pendingEquivalent ?: return
        val current = account ?: return
        pendingEquivalent = null
        val target = request.account
        if (target != null && target.key != current.key) choose(target, startLive = false)
        play(request.item)
        channelSwitch.clear(request.id)
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

    /** The playing live channel's listings for the OSD (not called per focused row). */
    private fun loadPlayingGuide(item: XtreamItem) {
        if (item.section != XtreamSection.LIVE || account == null) return
        if (epgNow(item.id)?.airingAt(System.currentTimeMillis() / 1000) != null) return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val list = loadGuide(item)
            if (list.isNotEmpty()) publishEpg(item.id, list)
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
            XtreamSection.LIVE -> start(XtreamUrls.live(account, item.id), XtreamPlaying(item.name, item), live = true, account = account).also { loadPlayingGuide(item) }
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
        // Text stays selected even with subtitles off (they are only hidden): turning a text
        // track on later re-reads a progressive file, which restarts an unseekable one.
        selector.parameters = selector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setSelectUndeterminedTextLanguage(true)
            .setPreferredTextLanguages(*preferredText.toTypedArray())
            .build()
        EnigmaLog.subs("iptv player created (text on, preferred ${preferredText.ifEmpty { listOf("-") }.joinToString()})")
        ExoPlayer.Builder(context)
            // Read-ahead capped to the heap (Media3's ~144 MB default does not fit many TV boxes,
            // and the picture-in-picture inset may play next to this player).
            .setLoadControl(com.enigmacast.playback.PlayerBuffers.vod())
            .setRenderersFactory(IptvRenderersFactory(context, audioSync, subtitleSync))
            .setTrackSelector(selector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
                    .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy()),
            )
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_ENDED && mediaLive && !catchup && playing != null) {
                            requestIptvRecovery("live stream ended")
                        }
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

                    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                            EnigmaLog.subs("iptv position ${old.positionMs}ms -> ${new.positionMs}ms (reason $reason)")
                        }
                    }

                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        EnigmaLog.subs("iptv timeline: dur=${p.duration}ms seekable=${p.isCurrentMediaItemSeekable} (reason $reason)")
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        if (externalCues == null && selectedExternalId == null) cues = if (textOn && !subtitlesHidden) cueGroup.cues else emptyList()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        onIptvPlaybackError(error)
                    }
                })
                player = p
            }
    }

    /**
     * Rebuild the current IPTV item at the live edge, or at the last VOD position, within a
     * small retry budget. A hard 4xx/auth/parse failure is not retried; it is shown instead.
     */
    private fun requestIptvRecovery(reason: String, immediate: Boolean = false) {
        val what = playing ?: return
        val account = mediaAccount ?: return
        if (mediaUrl.isBlank()) return
        if (iptvRecoveryJob?.isActive == true && !immediate) return
        val delayMs = if (immediate) 0L else (iptvRetries.next() ?: run {
            playerError = "STREAM_STALLED"
            trySatelliteFallback()
            return
        })
        iptvRecoveryJob?.cancel()
        lastIptvAdvanceAt = android.os.SystemClock.elapsedRealtime()
        EnigmaLog.player("iptv recovery ($reason), try ${iptvRetries.attempts}/${RestartBudget.MAX_ATTEMPTS} in ${delayMs}ms")
        iptvRecoveryJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (playing !== what) return@launch
            val position = if (mediaLive || catchup) 0L else player?.currentPosition ?: 0L
            playerError = null
            start(mediaUrl, what, mediaLive, account, position, resumeKey, catchup, automaticRetry = true)
        }
    }

    private fun httpStatus(error: Throwable): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
            depth++
        }
        return null
    }

    private fun isPermanentIptvFailure(error: PlaybackException): Boolean {
        val status = httpStatus(error)
        if (status != null && status in 400..499) return true
        return StreamFailures.classify(error) == com.enigmacast.domain.tuner.StreamFailure.PARSE
    }

    private fun onIptvPlaybackError(error: PlaybackException) {
        if (StreamFailures.isCancellation(error)) return
        EnigmaLog.networkError("Xtream playback: ${error.errorCodeName} http=${httpStatus(error) ?: "-"}")
        if (isPermanentIptvFailure(error)) {
            playerError = error.errorCodeName
            trySatelliteFallback()
            return
        }
        playerError = null
        requestIptvRecovery(error.errorCodeName)
    }

    private fun start(url: String, what: XtreamPlaying, live: Boolean, account: XtreamAccount, startMs: Long = 0L, resume: String? = null, archive: Boolean = false, automaticRetry: Boolean = false) {
        if (!automaticRetry) {
            iptvRecoveryJob?.cancel()
            iptvRetries.reset()
        }
        mediaAccount = account
        lastIptvPosition = -1L
        lastIptvAdvanceAt = android.os.SystemClock.elapsedRealtime()
        iptvStableSince = 0L
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
        // Speed is per film/episode: each one starts at normal speed.
        if (playbackSpeed != 1f) { playbackSpeed = 1f; player?.setPlaybackSpeed(1f) }
        clearExternalSubtitles()
        mediaUrl = url
        mediaLive = live
        playerState = com.enigmacast.domain.model.PlayerState(filePlayback = !live)
        subtitlesHidden = !playbackSubtitles
        lastDurationMs = 0L
        guardJob?.cancel()
        if (!live) player().let { pl ->
            pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguages(*preferredText.toTypedArray())
                .build()
        }
        EnigmaLog.subs("iptv setMediaItem+prepare (new item) at ${startMs}ms live=$live ${what.item?.section}")
        player().apply {
            setMediaItem(mediaItem(url, live), startMs.coerceAtLeast(0L))
            prepare()
            playWhenReady = true
        }
        if (!live && !archive) loadExternalSubtitles(what, account)
        startTicker()
        val item = what.item
        if (live && !archive && item != null) {
            val recent = com.enigmacast.domain.model.LastChannel(item.id, item.name, item.categoryId, item.icon)
            recentChannels = recentIptv.remember(account.key, recent)
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
        val before = snapshot(p)
        EnigmaLog.subs("iptv choose ${id ?: "off"} at ${com.enigmacast.playback.SubtitleSafety.describe(before)} live=$mediaLive")
        if (id == null) {
            // Off only hides the lines: no track change, so switching on again never re-reads the file.
            selectedExternalId = null
            pendingEmbeddedId = null
            subtitlesHidden = true
            dropExternalCues()
            cues = emptyList()
            publishTracks(p)
            return
        }
        if (id.startsWith(EXTERNAL_PREFIX)) {
            val hitId = id.removePrefix(EXTERNAL_PREFIX).toLongOrNull() ?: return
            if (externalHits.none { it.id == hitId }) return
            selectedExternalId = hitId
            pendingEmbeddedId = null
            subtitlesHidden = false
            // Drawn by the app next to the media: no media item, track or player change at all.
            dropExternalCues()
            cues = emptyList()
            publishTracks(p)
            downloadJob?.cancel()
            downloadJob = viewModelScope.launch {
                val file = subtitles.file(hitId)
                if (file == null) { EnigmaLog.subs("iptv external $hitId: download failed"); return@launch }
                if (selectedExternalId != hitId) return@launch
                val timeline = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.enigmacast.playback.ExternalSubtitles.parse(file, subtitleMime(file.extension))
                }
                if (timeline == null) { EnigmaLog.subs("iptv external $hitId: unreadable ${file.extension}"); return@launch }
                if (selectedExternalId != hitId) return@launch
                EnigmaLog.subs("iptv external $hitId: ${timeline.size} cues, player untouched")
                showExternalCues(timeline)
            }
            return
        }
        selectedExternalId = null
        dropExternalCues()
        subtitlesHidden = false
        if (embeddedSelected(p, id)) {
            EnigmaLog.subs("iptv embedded $id already read: shown, no track change")
            publishTracks(p)
            return
        }
        if (!com.enigmacast.playback.SubtitleSafety.canSwitchEmbedded(before.seekable, before.positionMs)) {
            EnigmaLog.subs("iptv embedded $id refused: stream not seekable, a switch would restart it")
            subsNotice(com.enigmacast.R.string.subs_switch_unseekable)
            publishTracks(p)
            return
        }
        applyEmbeddedSubtitles(p, id)
        guardPosition(p, before, "embedded $id")
        publishTracks(p)
    }

    /** Shown while a subtitle choice is on but [subtitlesHidden] is false. */
    private var subtitlesHidden = false
    /** Settings › IPTV › Subtitles as ISO codes: the first text track picked is already the wanted language. */
    private var preferredText: List<String> = emptyList()
    private var lastDurationMs = 0L
    private var guardJob: Job? = null
    private fun subsNotice(res: Int) = showNotice(context.getString(res))

    private fun snapshot(p: ExoPlayer) = com.enigmacast.playback.PlayheadSnapshot(
        p.currentPosition.coerceAtLeast(0), p.duration, p.isCurrentMediaItemSeekable,
    )

    private fun embeddedSelected(p: ExoPlayer, id: String): Boolean {
        val (group, track) = com.enigmacast.playback.TrackMenus.find(p.currentTracks, id, C.TRACK_TYPE_TEXT) ?: return false
        return group.isTrackSelected(track) && !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    }

    /** A track switch re-reads the file at the position; if it still lands far back, return there. */
    private fun guardPosition(p: ExoPlayer, before: com.enigmacast.playback.PlayheadSnapshot, why: String) {
        guardJob?.cancel()
        guardJob = viewModelScope.launch {
            val until = android.os.SystemClock.elapsedRealtime() + com.enigmacast.playback.SubtitleSafety.GUARD_MS
            while (android.os.SystemClock.elapsedRealtime() < until && player === p) {
                delay(250)
                val target = com.enigmacast.playback.SubtitleSafety.restoreTo(before, p.currentPosition, p.isCurrentMediaItemSeekable)
                if (target != null) {
                    EnigmaLog.subs("iptv $why jumped to ${p.currentPosition}ms: back to ${target}ms")
                    p.seekTo(target)
                    break
                }
            }
            EnigmaLog.subs("iptv $why settled at ${com.enigmacast.playback.SubtitleSafety.describe(snapshot(p))}")
        }
    }

    /** The SubSource file being shown, and the job that follows the player position with it. */
    private var externalCues: com.enigmacast.playback.CueTimeline<androidx.media3.common.text.Cue>? = null
    private var externalCueJob: Job? = null

    private fun showExternalCues(timeline: com.enigmacast.playback.CueTimeline<androidx.media3.common.text.Cue>) {
        externalCues = timeline
        externalCueJob?.cancel()
        externalCueJob = viewModelScope.launch {
            var last: List<androidx.media3.common.text.Cue> = emptyList()
            while (true) {
                // Always the current player: a lookup per frame-ish tick is a binary search.
                val p = player
                val now = if (p == null) emptyList()
                else com.enigmacast.playback.ExternalSubtitles.cuesAt(timeline, p.currentPosition, subtitleDelayMs)
                if (now != last) { last = now; cues = now }
                delay(100)
            }
        }
    }

    private fun dropExternalCues() {
        externalCueJob?.cancel()
        externalCueJob = null
        if (externalCues != null) cues = emptyList()
        externalCues = null
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

    /** Recent strip: zap back to a live channel watched earlier. */
    fun playRecent(channel: com.enigmacast.domain.model.LastChannel) {
        val known = items.firstOrNull { it.section == XtreamSection.LIVE && it.id == channel.reference }
        play(
            known ?: XtreamItem(
                channel.reference,
                channel.name,
                XtreamSection.LIVE,
                channel.bouquetReference,
                icon = channel.logo,
            ),
        )
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
        player?.let {
            if (it.playWhenReady) saveResume()
            it.playWhenReady = !it.playWhenReady
        }
    }

    /** Leaving the page: the stream stops, the lists stay for next time. */
    fun stop() {
        iptvRecoveryJob?.cancel()
        iptvRecoveryJob = null
        iptvRetries.reset()
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
        EnigmaLog.subs("iptv player released: another player took over")
        stop()
        player?.release()
        player = null
        playerVersion++
    }

    /** Bumped when the ExoPlayer instance is replaced, so the video view binds the new one. */
    var playerVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    override fun onCleared() {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        iptvRecoveryJob?.cancel()
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


    private fun clearExternalSubtitles() {
        subtitleJob?.cancel()
        downloadJob?.cancel()
        externalHits = emptyList()
        selectedExternalId = null
        pendingEmbeddedId = null
        dropExternalCues()
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
        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && !subtitlesHidden
        val menu = com.enigmacast.playback.TrackMenus.build(p.currentTracks, textOn && selectedExternalId == null)
        val embedded = menu.subtitles.filter { choice ->
            val found = com.enigmacast.playback.TrackMenus.find(p.currentTracks, choice.id, C.TRACK_TYPE_TEXT)
            found?.first?.getTrackFormat(found.second)?.id?.startsWith(EXTERNAL_PREFIX) != true
        }
        val extra = externalHits.map { hit ->
            com.enigmacast.playback.TrackChoice(
                "$EXTERNAL_PREFIX${hit.id}",
                com.enigmacast.util.UiText.Raw(com.enigmacast.domain.subsource.SubSourceMatch.label(hit)),
                selectedExternalId == hit.id,
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
        // A late pick re-reads the file; on an unseekable one that is a restart from 0.
        if (!com.enigmacast.playback.SubtitleSafety.canSwitchEmbedded(p.isCurrentMediaItemSeekable, p.currentPosition)) {
            EnigmaLog.subs("iptv auto pick skipped at ${p.currentPosition}ms: not seekable")
            return
        }
        EnigmaLog.subs("iptv auto pick first text track at ${p.currentPosition}ms")
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
        const val LOGO_MAX_PX = 192
        const val LOGO_MAX_BYTES = 4 * 1024 * 1024
        const val LOGO_CACHE_BYTES = 16 * 1024 * 1024
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

internal fun preferredTextLanguages(slugs: List<String>?): List<String> =
    com.enigmacast.playback.SubtitleSafety.preferredTextLanguages(slugs) { com.enigmacast.domain.subsource.SubSourceLanguages.bySlug(it)?.iso }
