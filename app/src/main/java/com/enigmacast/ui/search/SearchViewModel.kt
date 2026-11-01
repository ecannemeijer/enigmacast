package com.enigmacast.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchKind
import com.enigmacast.domain.search.SearchQuery
import com.enigmacast.domain.search.SearchResults
import com.enigmacast.live.ProgrammeSearch
import com.enigmacast.live.RecordingBook
import com.enigmacast.live.SearchOutcome
import com.enigmacast.live.SearchPlayer
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val programmeSearch: ProgrammeSearch,
    private val player: SearchPlayer,
    private val recordings: RecordingBook,
    private val reminders: com.enigmacast.live.ReminderScheduler,
    private val settingsRepository: com.enigmacast.data.settings.SettingsRepository,
    private val iptvSearch: com.enigmacast.data.xtream.IptvSearchSource,
    private val iptvLibrary: com.enigmacast.data.xtream.IptvLibrary,
    private val gate: com.enigmacast.live.PremiumGate,
    private val tmdb: com.enigmacast.data.tmdb.TmdbHttp,
    private val country: com.enigmacast.live.CountryDetector,
    private val scraper: com.enigmacast.data.torrent.ScraperRepository,
    private val torrentLibrary: com.enigmacast.data.torrent.TorrentLibrary,
) : ViewModel() {
    /** Torrents row: TMDB films and series for the query, only while the Torrent page is available. */
    var torrents by mutableStateOf<List<com.enigmacast.domain.torrent.TmdbTitle>>(emptyList())
        private set

    /** Same rule as the menu: a valid scraper and Premium. */
    fun torrentsAllowed(): Boolean = scraper.status.value is com.enigmacast.data.torrent.ScraperStatus.Valid &&
        gate.allows(com.enigmacast.domain.account.PremiumFeature.TORRENT)

    /** OK on a Torrents result: its Torrent info page; Back there comes back here, on this card. */
    fun openTorrent(key: String, rowId: String, onTorrent: () -> Unit) {
        val title = torrents.firstOrNull { it.key == key } ?: return
        if (!gate.require(com.enigmacast.domain.account.PremiumFeature.TORRENT)) return
        torrentLibrary.openRequest.value = com.enigmacast.domain.torrent.TorrentOpenRequest(
            title, origin = com.enigmacast.domain.xtream.LibraryReturn.searchOrigin(rowId, key),
        )
        onTorrent()
    }

    private suspend fun findTorrents(text: String): List<com.enigmacast.domain.torrent.TmdbTitle> = try {
        val q = text.trim()
        if (q.length < com.enigmacast.domain.search.TorrentSearch.MIN_QUERY || !torrentsAllowed()) emptyList() else {
            val language = java.util.Locale.getDefault().toLanguageTag()
            kotlinx.coroutines.coroutineScope {
                val films = async { com.enigmacast.domain.torrent.TorrentTmdb.parsePage(tmdb.searchPage(false, q, 1, language), com.enigmacast.domain.torrent.TorrentKind.MOVIE).items }
                val series = async { com.enigmacast.domain.torrent.TorrentTmdb.parsePage(tmdb.searchPage(true, q, 1, language), com.enigmacast.domain.torrent.TorrentKind.TV).items }
                com.enigmacast.domain.search.TorrentSearch.merge(films.await(), series.await())
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        emptyList()
    }
    /** Actors whose name matches the query (TMDB people search); OK opens the actor page. */
    var people by mutableStateOf<List<com.enigmacast.domain.films.TmdbCastMember>>(emptyList())
        private set
    /** IPTV Live / Films / Series rows (Premium XTREAM; playlist DB or already fetched lists). */
    var iptv by mutableStateOf(com.enigmacast.domain.xtream.IptvSearchResults())
        private set

    /** Saved places and watch history: progress and check marks on the film results. */
    val iptvResumes get() = iptvLibrary.resumeMap
    val iptvHistory get() = iptvLibrary.history

    /** Where the IPTV screen sent the viewer back to ("search/<row>/<card>" when it is a result here). */
    val returnKeys get() = iptvLibrary.returnTo
    fun consumeReturn() { iptvLibrary.returnTo.value = null }

    /** OK on an IPTV result: live plays, a film opens its info page, a series its series page. */
    fun openIptv(card: com.enigmacast.domain.xtream.IptvLibraryCard, rowId: String? = null, onXtream: () -> Unit) =
        openIptvFrom(card, rowId?.let { com.enigmacast.domain.xtream.LibraryReturn.searchOrigin(it, card.key) }, onXtream)

    /** [openIptv] with the page Back comes back to ([origin]; null keeps the IPTV screen). */
    fun openIptvFrom(card: com.enigmacast.domain.xtream.IptvLibraryCard, origin: String?, onXtream: () -> Unit) {
        if (!gate.require(com.enigmacast.domain.account.PremiumFeature.XTREAM)) return
        // With Search as origin, Back on the info page (or after playing) comes back here, on this card.
        iptvLibrary.openRequest.value = com.enigmacast.data.xtream.IptvOpenRequest(
            card.source, card.open, detail = true,
            origin = origin,
        )
        onXtream()
    }
    /** A programme whose OK opened the action dialog (AutoSwitch / Record / Watch now). */
    var programme by mutableStateOf<SearchHit?>(null)
        private set
    var recordAsk by mutableStateOf<com.enigmacast.domain.recording.RecordAsk?>(null)
        private set
    val autoSwitchKeys = reminders.scheduledKeys

    fun openProgramme(hit: SearchHit) {
        message = UiText.Empty
        programme = hit
    }

    fun dismissProgramme() {
        programme = null
    }

    fun toggleAutoSwitch() {
        val hit = programme ?: return
        val event = hit.event ?: return
        programme = null
        viewModelScope.launch {
            val receiverId = settingsRepository.current().activeReceiver()?.id.orEmpty()
            val toggle = reminders.toggle(event, hit.channelName, receiverId)
            message = com.enigmacast.ui.remote.AutoSwitchTexts.result(toggle, com.enigmacast.domain.epg.ProgrammePrompt(event, hit.channelName, false))
        }
    }

    fun chooseRecord() {
        val hit = programme ?: return
        val event = hit.event ?: return
        programme = null
        viewModelScope.launch {
            when (val result = recordings.prepare(event, hit.channelName)) {
                is RecordingBook.PrepareResult.Ask -> recordAsk = result.ask
                is RecordingBook.PrepareResult.Message -> message = result.text
            }
        }
    }

    fun confirmRecord(place: com.enigmacast.domain.model.RecordingTarget, all: Boolean = false) {
        val ask = recordAsk ?: return
        recordAsk = null
        viewModelScope.launch { message = recordings.schedule(ask.copy(place = place, allUpcoming = all)) }
    }

    fun dismissRecord() {
        recordAsk = null
    }
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf(SearchResults())
        private set
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf(UiText.Empty)
        private set
    private var searchJob: Job? = null

    fun onQuery(value: String, immediate: Boolean = false) {
        query = value
        message = UiText.Empty
        searchJob?.cancel()
        val nowSec = System.currentTimeMillis() / 1000
        if (!SearchQuery.parse(value, nowSec).isSearchable) {
            results = SearchResults()
            iptv = com.enigmacast.domain.xtream.IptvSearchResults()
            people = emptyList()
            torrents = emptyList()
            searching = false
            return
        }
        searching = true
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            launch { iptv = iptvSearch.search(value) }
            launch { torrents = findTorrents(SearchQuery.parse(value, nowSec).text.ifBlank { value }) }
            launch { people = findPeople(SearchQuery.parse(value, nowSec).text.ifBlank { value }) }
            val found = programmeSearch.search(value, recordings.items.value, System.currentTimeMillis() / 1000)
            results = found
            searching = false
        }
    }

    private suspend fun findPeople(name: String): List<com.enigmacast.domain.films.TmdbCastMember> = try {
        if (name.trim().length < 3) emptyList() else {
            val cc = runCatching { country.country() }.getOrDefault("NL")
            val language = com.enigmacast.domain.films.CountryResolver.tmdbLanguage(country.appLanguage(), cc)
            tmdb.searchPerson(name.trim(), language)?.let { com.enigmacast.data.tmdb.TmdbJson.parsePeople(it) }.orEmpty()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        emptyList()
    }

    fun applyExternalQuery(
        value: String,
        playFirst: Boolean,
        onPlayed: () -> Unit,
        onBouquet: (BouquetEntry) -> Unit,
        prepare: () -> Unit = {},
    ) {
        onQuery(value, immediate = true)
        if (!playFirst) return
        viewModelScope.launch {
            searchJob?.join()
            val hit = results.playHit(SearchQuery.parse(value, System.currentTimeMillis() / 1000).text.ifBlank { value })
                ?: return@launch
            choose(hit, onPlayed, onBouquet, prepare)
        }
    }

    fun choose(
        hit: SearchHit,
        onPlayed: () -> Unit,
        onBouquet: (BouquetEntry) -> Unit,
        prepare: () -> Unit = {},
    ) {
        message = UiText.Empty
        viewModelScope.launch {
            prepare()
            when (val outcome = player.open(hit)) {
                SearchOutcome.Played -> onPlayed()
                is SearchOutcome.Bouquet -> onBouquet(outcome.bouquet)
                is SearchOutcome.Failed -> message = outcome.text
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}

/** Section title for a non-empty group, in the order the list shows them. */
fun SearchResults.sections(): List<Pair<SearchKind, List<SearchHit>>> = listOf(
    SearchKind.NOW to now,
    SearchKind.LATER to later,
    SearchKind.RECORDING to recordings,
    SearchKind.CHANNEL to stations,
).filter { it.second.isNotEmpty() }
