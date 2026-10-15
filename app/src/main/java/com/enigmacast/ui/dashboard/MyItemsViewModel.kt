package com.enigmacast.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.forReceiver
import com.enigmacast.live.RecordingBook
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyItemsViewModel @Inject constructor(
    private val book: RecordingBook,
    settingsRepository: SettingsRepository,
    private val resume: com.enigmacast.live.ResumeTracker,
    private val artwork: com.enigmacast.live.RecordingArtwork,
    receiverNames: com.enigmacast.live.ReceiverNames,
    private val iptv: com.enigmacast.data.xtream.IptvLibrary,
    private val gate: com.enigmacast.live.PremiumGate,
    private val playlistStore: com.enigmacast.data.xtream.IptvPlaylistStore,
    private val xtream: com.enigmacast.data.xtream.XtreamRepository,
    private val settingsSource: SettingsRepository,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
) : ViewModel() {
    /** Playlist accounts that are configured now; Library hides (keeps) entries of others. */
    private val activeSources = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    private suspend fun loadSources() {
        val settings = settingsSource.current()
        activeSources.value = runCatching {
            if (settings.iptvSource == com.enigmacast.domain.model.IptvSourceMode.PLAYLIST) {
                if (!profiles.seesPlaylist()) return@runCatching emptySet()
                val login = playlistStore.login.value
                when (settings.iptvPlaylistKind) {
                    com.enigmacast.domain.model.IptvPlaylistKind.XTREAM -> setOfNotNull(login.xtreamAccount()?.key)
                    com.enigmacast.domain.model.IptvPlaylistKind.M3U ->
                        if (login.configured(com.enigmacast.domain.model.IptvPlaylistKind.M3U)) setOf(com.enigmacast.ui.xtream.XtreamViewModel.M3U_ACCOUNT.key) else emptySet()
                }
            } else {
                val receiver = settings.activeReceiver() ?: return@runCatching emptySet<String>()
                (xtream.cachedDetection(receiver.id) ?: xtream.detect(receiver)).accounts.map { it.key }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    /** Library › Continue watching and IPTV favorites (Premium IPTV only). */
    val iptvContinue: StateFlow<List<com.enigmacast.domain.xtream.IptvLibraryCard>> =
        combine(iptv.resumes, activeSources) { rows, sources -> com.enigmacast.domain.xtream.IptvLibraryRows.continueWatching(rows, sources) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val iptvFavorites: StateFlow<List<com.enigmacast.domain.xtream.IptvLibraryCard>> =
        combine(iptv.favorites, iptv.resumeMap, activeSources) { rows, resumes, sources -> com.enigmacast.domain.xtream.IptvLibraryRows.favorites(rows, resumes, sources) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun iptvAllowed(): Boolean = gate.allows(com.enigmacast.domain.account.PremiumFeature.XTREAM)

    /** OK on an IPTV card: the IPTV page plays the film (resume dialog) or shows the series. */
    /** The card to focus after coming back from the IPTV player (see [IptvLibrary.returnTo]). */
    val returnKey: String? get() = iptv.returnTo.value
    fun consumeReturn() { iptv.returnTo.value = null }

    fun openIptv(card: com.enigmacast.domain.xtream.IptvLibraryCard, onXtream: () -> Unit, origin: String? = null) {
        if (!gate.require(com.enigmacast.domain.account.PremiumFeature.XTREAM)) return
        val episode = rememberedEpisode(card)
        if (card.open.section == com.enigmacast.domain.xtream.XtreamSection.SERIES && episode == null) {
            infoIptv(card, onXtream, origin)
            return
        }
        iptv.openRequest.value = com.enigmacast.data.xtream.IptvOpenRequest(card.source, card.open, origin, episode = episode)
        onXtream()
    }

    /** OK on a My items card: play, the info page, or remove. */
    private var iptvOrigin: String? = null
    fun optionsIptv(card: com.enigmacast.domain.xtream.IptvLibraryCard, origin: String) {
        iptvOrigin = origin
        iptvOptions = card
    }

    fun playChosenIptv(onXtream: () -> Unit) {
        val card = iptvOptions ?: return
        val origin = iptvOrigin
        iptvOptions = null
        openIptv(card, onXtream, origin)
    }

    fun infoChosenIptv(onXtream: () -> Unit) {
        val card = iptvOptions ?: return
        val origin = iptvOrigin
        iptvOptions = null
        infoIptv(card, onXtream, origin)
    }

    private fun infoIptv(card: com.enigmacast.domain.xtream.IptvLibraryCard, onXtream: () -> Unit, origin: String?) {
        if (!gate.require(com.enigmacast.domain.account.PremiumFeature.XTREAM)) return
        iptv.openRequest.value = com.enigmacast.data.xtream.IptvOpenRequest(card.source, card.open, origin, detail = true)
        onXtream()
    }

    /** Latest saved episode of a series, or the Continue watching episode itself. */
    private fun rememberedEpisode(card: com.enigmacast.domain.xtream.IptvLibraryCard): com.enigmacast.domain.xtream.XtreamEpisode? {
        if (card.open.section != com.enigmacast.domain.xtream.XtreamSection.SERIES) return null
        val row = if (card.kind == "e") iptv.resumeMap.value["${card.source}/e/${card.id}"]
        else iptv.resumes.value.filter { it.source == card.source && it.seriesId == card.id }.maxByOrNull { it.updatedMs }
        return row?.let { com.enigmacast.domain.xtream.XtreamEpisode(it.id, it.season, it.episode, it.title, it.extension) }
    }

    /** Options on an IPTV card, then the confirmation, as for recordings. */
    var iptvOptions by mutableStateOf<com.enigmacast.domain.xtream.IptvLibraryCard?>(null)
        private set
    var iptvPendingRemove by mutableStateOf<com.enigmacast.domain.xtream.IptvLibraryCard?>(null)
        private set
    fun dismissIptvOptions() { iptvOptions = null }
    fun askRemoveIptv() { iptvPendingRemove = iptvOptions; iptvOptions = null }
    fun dismissRemoveIptv() { iptvPendingRemove = null }
    fun confirmRemoveIptv() {
        val card = iptvPendingRemove ?: return
        iptvPendingRemove = null
        if (card.key.startsWith("fav/")) {
            iptv.setFavorite(com.enigmacast.data.db.IptvLibraryRow(card.source, card.kind, card.id, "", null, null, null, "", "", "", 0), false)
        } else {
            iptv.clearResume(card.source, card.kind, card.id)
        }
    }

    init {
        viewModelScope.launch { settingsSource.settings.collect { loadSources() } }
        viewModelScope.launch { profiles.state.collect { loadSources() } }
    }

    /** Saved positions by recording id, for the progress bars. */
    val resumePoints: StateFlow<Map<String, com.enigmacast.domain.resume.ResumePoint>> = resume.points
    /** TMDB matches by recording id (null = no match, placeholder). */
    val posters: StateFlow<Map<String, com.enigmacast.domain.films.TmdbMovie?>> = artwork.movies
    /** "Resume from mm:ss / Start from the beginning" for this recording at this position. */
    var resumeAsk by mutableStateOf<Pair<Recording, Long>?>(null)
        private set
    private var resumeStarted: (() -> Unit)? = null

    /** Recordings that can be watched: shown as poster cards. */
    fun isPoster(item: Recording) = !isBoxTimer(item) &&
        (item.status == com.enigmacast.domain.recording.RecordingStatus.READY || item.status == com.enigmacast.domain.recording.RecordingStatus.RECORDING)

    fun loadArtwork(list: List<Recording>) {
        viewModelScope.launch { artwork.resolve(list.filter(::isPoster)) }
    }
    val items: StateFlow<List<Recording>> = combine(
        book.items,
        settingsRepository.settings.map { it.activeReceiver()?.id.orEmpty() }.distinctUntilChanged(),
    ) { list, receiverId -> list.forReceiver(receiverId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var selected by mutableStateOf<Recording?>(null)
        private set
    var pendingDelete by mutableStateOf<Recording?>(null)
        private set
    var notice by mutableStateOf(UiText.Empty)
        private set
    /** The row that should take focus again after a dialog closes. */
    var focusId by mutableStateOf<String?>(null)
        private set
    var focusToken by mutableIntStateOf(0)
        private set
    /**
     * True while a dialog is closing. Focus would otherwise land on Stream and that
     * menu item would leave My Items before the list can take focus back.
     */
    var holdList by mutableStateOf(false)
        private set
    private var deleting = false
    /** Recordings and timers on the box itself (OpenWebif movielist / timerlist), not started from this app. */
    var boxMovies by mutableStateOf<List<Recording>>(emptyList())
        private set
    var boxTimers by mutableStateOf<List<Recording>>(emptyList())
        private set

    /** Label of the box ("Octagon SF8008") for the section headers. */
    val receiverName = receiverNames.active

    fun isBox(item: Recording) = item.id.startsWith(RecordingBook.BOX_MOVIE) || item.id.startsWith(RecordingBook.BOX_TIMER)
    fun isBoxTimer(item: Recording) = item.id.startsWith(RecordingBook.BOX_TIMER)

    private fun restore(id: String?) {
        focusId = id
        focusToken += 1
    }

    fun releaseList() {
        holdList = false
    }

    /** Show the recordings that are actually stored, including one that just stopped. */
    suspend fun reloadNow() = book.reload()

    /** Back from playback: highlight this recording again. */
    fun focusRow(id: String) = restore(id)

    fun refresh() {
        viewModelScope.launch {
            book.refreshReceiver()
            book.boxItems()?.let { boxMovies = it.movies; boxTimers = it.timers }
        }
    }

    fun open(item: Recording) {
        notice = UiText.Empty
        focusId = item.id
        selected = item
    }

    fun dismiss() {
        val id = selected?.id ?: focusId
        holdList = true
        selected = null
        restore(id)
    }

    fun play(onStarted: () -> Unit) {
        val item = selected ?: return
        holdList = true
        selected = null
        viewModelScope.launch {
            val offer = com.enigmacast.domain.resume.ResumePolicy.offer(resume.get(item.id))
            if (offer != null) {
                resumeStarted = onStarted
                resumeAsk = item to offer
                return@launch
            }
            start(item, 0L, onStarted)
        }
    }

    /** Answer to the resume question: [fromStart] plays from 0 and forgets the position. */
    fun answerResume(fromStart: Boolean) {
        val (item, position) = resumeAsk ?: return
        val onStarted = resumeStarted ?: {}
        resumeAsk = null
        resumeStarted = null
        if (fromStart) resume.forget(item.id)
        viewModelScope.launch { start(item, if (fromStart) 0L else position, onStarted) }
    }

    fun dismissResume() {
        val item = resumeAsk?.first
        resumeAsk = null
        resumeStarted = null
        holdList = true
        restore(item?.id)
    }

    private suspend fun start(item: Recording, startMs: Long, onStarted: () -> Unit) {
        run {
            if (if (isBox(item)) book.playBox(item, startMs) else book.play(item.id, startMs)) {
                holdList = false
                onStarted()
            } else {
                notice = UiText.res(R.string.my_items_nothing)
                restore(item.id)
            }
        }
    }

    fun stop() {
        val item = selected ?: return
        holdList = true
        selected = null
        restore(item.id)
        viewModelScope.launch { book.stop(item.id) }
    }

    fun askDelete() {
        holdList = true
        pendingDelete = selected
        selected = null
    }

    fun dismissDelete() {
        val id = pendingDelete?.id ?: focusId
        holdList = true
        pendingDelete = null
        restore(id)
    }

    fun confirmDelete() {
        val item = pendingDelete ?: return
        if (deleting) return
        deleting = true
        holdList = true
        viewModelScope.launch {
            if (isBox(item)) {
                if (!book.deleteBox(item)) notice = UiText.res(R.string.my_items_box_delete_failed)
                book.boxItems()?.let { boxMovies = it.movies; boxTimers = it.timers }
            } else {
                book.delete(item.id)
            }
            resume.forget(item.id)
            deleting = false
            pendingDelete = null
            restore(items.value.firstOrNull()?.id)
        }
    }
}
