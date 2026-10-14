package com.enigmacast.ui.xtream

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.XtreamDetection
import com.enigmacast.data.xtream.XtreamRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _detection = MutableStateFlow<XtreamDetection?>(null)
    /** Null until the active receiver was checked. The menu item shows when it has playlists. */
    val detection: StateFlow<XtreamDetection?> = _detection

    var account by mutableStateOf<XtreamAccount?>(null)
        private set
    var section by mutableStateOf(XtreamSection.LIVE)
        private set
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
        private set
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
        private set
    var buffering by mutableStateOf(false)
        private set
    /** Now/next of the focused or playing live channel, by stream id. */
    var epg by mutableStateOf<Map<String, List<XtreamEpg>>>(emptyMap())
        private set

    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private var epgJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.map { it.activeReceiver() }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.host == b?.host }
                .collect { receiver ->
                    _detection.value = null
                    if (receiver == null) return@collect
                    _detection.value = repository.cachedDetection(receiver.id) ?: repository.detect(receiver)
                    if (account != null && _detection.value?.accounts?.none { it.key == account?.key } == true) account = null
                }
        }
    }

    fun recheck() {
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            _detection.value = repository.detect(receiver, force = true)
        }
    }

    /** Opens the page: one playlist is chosen right away; several leave [account] null for the chooser. */
    fun open() {
        val accounts = _detection.value?.accounts.orEmpty()
        if (account == null && accounts.size == 1) choose(accounts.first())
    }

    fun choose(account: XtreamAccount) {
        if (this.account?.key == account.key && categories.isNotEmpty()) return
        this.account = account
        selectSection(XtreamSection.LIVE, force = true)
    }

    fun selectSection(section: XtreamSection, force: Boolean = false) {
        if (!force && section == this.section && categories.isNotEmpty()) return
        this.section = section
        val account = account ?: return
        loadJob?.cancel()
        categories = emptyList(); category = null; items = emptyList(); series = null; episodes = emptyList()
        loadJob = viewModelScope.launch {
            loading = true; error = false
            runCatching { repository.categories(account, section) }
                .onSuccess { list ->
                    categories = list
                    categoriesReady++
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

    private suspend fun loadCategory(account: XtreamAccount, category: XtreamCategory, force: Boolean = false) {
        pendingCategory = category
        loading = true; error = false
        try {
            runCatching { repository.items(account, section, category.id, force) }
                .onSuccess {
                    this.category = category
                    series = null; episodes = emptyList()
                    items = it
                    itemsReady++
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = true }
        } finally {
            if (pendingCategory?.id == category.id) pendingCategory = null
            loading = false
        }
    }

    fun refresh() {
        val account = account ?: return
        val current = category
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            runCatching { categories = repository.categories(account, section, force = true) }
            (current ?: categories.firstOrNull())?.let { loadCategory(account, it, force = true) }
            loading = false
        }
    }

    fun openSeries(item: XtreamItem) {
        val account = account ?: return
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
        if (item.section != XtreamSection.LIVE || epg.containsKey(item.id)) return
        val account = account ?: return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            delay(350)
            val list = repository.shortEpg(account, item.id)
            epg = epg + (item.id to list)
        }
    }

    fun play(item: XtreamItem) {
        val account = account ?: return
        when (item.section) {
            XtreamSection.SERIES -> openSeries(item)
            XtreamSection.LIVE -> start(XtreamUrls.live(account, item.id), XtreamPlaying(item.name, item), live = true, account = account).also { focusLive(item) }
            XtreamSection.MOVIES -> start(XtreamUrls.movie(account, item.id, item.extension), XtreamPlaying(item.name, item), live = false, account = account)
        }
    }

    fun playEpisode(episode: XtreamEpisode) {
        val account = account ?: return
        val title = listOfNotNull(series?.name, "S${episode.season}E${episode.episode}".takeIf { episode.season > 0 }).joinToString(" · ")
        start(XtreamUrls.episode(account, episode.id, episode.extension), XtreamPlaying("$title · ${episode.title}".trim(' ', '·'), series, episode), live = false, account = account)
    }

    /** Channel up/down while a live channel plays: the next one in the open category. */
    fun zap(step: Int) {
        val current = playing?.item?.takeIf { it.section == XtreamSection.LIVE } ?: return
        val list = items.filter { it.section == XtreamSection.LIVE }
        val index = list.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: return
        list.getOrNull((index + step).mod(list.size))?.let(::play)
    }

    @OptIn(UnstableApi::class)
    fun player(): ExoPlayer = player ?: run {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = state == Player.STATE_BUFFERING
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // The message never has the URL: only the error code name.
                        EnigmaLog.networkError("Xtream playback: ${error.errorCodeName}")
                        playerError = error.errorCodeName
                    }
                })
                player = p
            }
    }

    private fun start(url: String, what: XtreamPlaying, live: Boolean, account: XtreamAccount) {
        EnigmaLog.network("Xtream play ${XtreamUrls.redact(url, account).substringAfter("://").substringAfter('/')}")
        playerError = null
        playing = what
        val builder = MediaItem.Builder().setUri(url)
        if (url.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (live && url.endsWith(".ts")) builder.setMimeType(MimeTypes.VIDEO_MP2T)
        player().apply {
            setMediaItem(builder.build())
            prepare()
            playWhenReady = true
        }
    }

    /** Leaving the page: the stream stops, the lists stay for next time. */
    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        playing = null
    }

    override fun onCleared() {
        player?.release()
        player = null
    }
}
