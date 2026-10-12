package com.enigmacast.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.tmdb.HomeFilmSettings
import com.enigmacast.domain.epg.ProgrammePrompt
import com.enigmacast.domain.films.FilmRanking
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.HomeRow
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.recording.RecordAsk
import com.enigmacast.domain.recording.Recording
import com.enigmacast.live.HomeFilmsRepository
import com.enigmacast.live.PiconRepository
import com.enigmacast.live.RecordingBook
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.ui.remote.AutoSwitchTexts
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import com.enigmacast.domain.sync.HomeClock
import com.enigmacast.sync.SyncProgress
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** The film home page: loads today's films, and Watch / Inplannen / Record from the info dialog. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeFilmsViewModel @Inject constructor(
    private val repository: HomeFilmsRepository,
    private val settingsRepository: SettingsRepository,
    private val reminders: ReminderScheduler,
    private val recordings: RecordingBook,
    private val picons: PiconRepository,
    val homeSettings: HomeFilmSettings,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object NoReceiver : State
        data object MissingKey : State
        data object Empty : State
        data object NoBouquets : State
        data class Ready(val films: List<HomeFilm>) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set
    var hero by mutableStateOf<HomeFilm?>(null)
        private set
    var rows by mutableStateOf<List<HomeRow>>(emptyList())
        private set
    /** The film shown in the info dialog, or null. */
    var info by mutableStateOf<HomeFilm?>(null)
        private set
    var recordAsk by mutableStateOf<RecordAsk?>(null)
        private set
    var notice by mutableStateOf(UiText.Empty)
        private set

    /** Sync progress for the active receiver ("Preparing your guide… 37%"), or null. */
    val progress: StateFlow<SyncProgress?> = repository.progress

    val autoSwitchKeys: StateFlow<Set<String>> = reminders.scheduledKeys

    /** Programme keys that have a scheduled or running recording. */
    val recordingKeys: StateFlow<Set<String>> = recordings.items
        .map { list -> list.filter(Recording::isActive).mapTo(HashSet()) { com.enigmacast.domain.reminder.Reminder.keyOf(it.reference, it.beginEpochSec) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val kind = MutableStateFlow(com.enigmacast.data.db.HomeKinds.FILM)
    /** True on the TV Series page. */
    val series: Boolean get() = kind.value == com.enigmacast.data.db.HomeKinds.SERIES

    /** The TV Series page uses its own instance of this view model with series rows. */
    fun setSeries(on: Boolean) {
        kind.value = if (on) com.enigmacast.data.db.HomeKinds.SERIES else com.enigmacast.data.db.HomeKinds.FILM
    }
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private var noticeJob: Job? = null
    private var noBouquets = false

    init {
        // The clock ticks every 30 s; rows are recomputed, and the Room query moves at midnight.
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                clock.value = System.currentTimeMillis()
            }
        }
        val receiver = settingsRepository.settings.map { it.activeReceiver() }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.host == b?.host }
        val window = clock.map { HomeClock.queryWindow(it, zone) }.distinctUntilChanged()
        viewModelScope.launch {
            combine(receiver, window, kind) { r, w, k -> Triple(r, w, k) }
                .flatMapLatest { (r, w, k) ->
                    if (r == null) {
                        flowOf<Pair<List<HomeFilm>, Boolean>?>(null)
                    } else {
                        // First use, a new box or a new day: fill whatever is missing (only due steps run).
                        repository.ensurePeriodic()
                        repository.syncNow(force = false)
                        val key = repository.receiverKey(r)
                        repository.films(key, w.first, w.second, k).combine(repository.syncState(key)) { films, states ->
                            val filmsDone = states.any { it.kind == com.enigmacast.domain.sync.SyncKind.FILMS.name && it.lastAttemptMs > 0 }
                            films to filmsDone
                        }.combine(repository.bouquetChoice(key)) { pair, choice ->
                            noBouquets = choice.first.isNotEmpty() && choice.second.isEmpty()
                            pair
                        }
                    }
                }
                .combine(clock) { films, now -> films to now }
                .combine(progress) { pair, sync -> Triple(pair.first, pair.second, sync) }
                .collect { (films, now, sync) -> publish(films?.first, films?.second ?: true, now, sync) }
        }
    }

    private fun publish(films: List<HomeFilm>?, filmsDone: Boolean, nowMs: Long, sync: SyncProgress?) {
        if (films == null) {
            state = State.NoReceiver
            hero = null
            rows = emptyList()
            return
        }
        // A series airs many times: show each show once per channel, its next (or current) episode.
        val shown = if (series) {
            val nowSec = nowMs / 1000
            films.filter { it.event.endEpochSec > nowSec }
                .sortedBy { it.event.beginEpochSec }
                .distinctBy { it.movie.id to com.enigmacast.domain.tuner.NowEvents.key(it.event.reference) }
        } else films
        val (top, list) = HomeClock.compute(shown, nowMs, zone)
        hero = top
        rows = list
        state = when {
            noBouquets -> State.NoBouquets
            list.isNotEmpty() -> State.Ready(films)
            repository.apiKeyMissing() -> State.MissingKey
            sync != null || !filmsDone -> State.Loading
            else -> State.Empty
        }
    }

    /** Try again / Refresh: [force] refreshes everything, otherwise only what is due. */
    fun load(force: Boolean) {
        repository.syncNow(force)
    }

    fun openInfo(film: HomeFilm) {
        info = film
    }

    fun closeInfo() {
        info = null
    }

    fun channelOf(film: HomeFilm) = BouquetEntry(
        name = film.candidate.channelName,
        reference = film.event.reference,
        picon = film.candidate.channelPicon,
    )

    suspend fun picon(entry: BouquetEntry) = settingsRepository.current().activeReceiver()?.let { picons.load(it, entry) }
    fun cachedPicon(entry: BouquetEntry) = picons.cached(entry)

    /** Inplannen: sets (or cancels) AutoSwitch with the existing reminder system. */
    fun toggleAutoSwitch(film: HomeFilm) {
        viewModelScope.launch {
            val receiverId = settingsRepository.current().activeReceiver()?.id.orEmpty()
            val prompt = ProgrammePrompt(film.event, film.candidate.channelName, reminders.isScheduled(film.key))
            val toggle = reminders.toggle(film.event, film.candidate.channelName, receiverId)
            showNotice(AutoSwitchTexts.result(toggle, prompt))
        }
    }

    /** Record: the same confirm step and recorder as the guide (TV storage or receiver timer, per Settings). */
    fun record(film: HomeFilm) {
        viewModelScope.launch {
            val event = film.event.copy(title = film.displayTitle)
            when (val result = recordings.prepare(event, film.candidate.channelName)) {
                is RecordingBook.PrepareResult.Ask -> recordAsk = result.ask
                is RecordingBook.PrepareResult.Message -> showNotice(result.text)
            }
        }
    }

    fun confirmRecord(place: com.enigmacast.domain.model.RecordingTarget, all: Boolean = false) {
        val ask = recordAsk ?: return
        recordAsk = null
        viewModelScope.launch { showNotice(recordings.schedule(ask.copy(place = place, allUpcoming = all))) }
    }

    fun dismissRecord() {
        recordAsk = null
    }

    fun setCountry(code: String) {
        homeSettings.setCountry(code)
        repository.syncNow(force = true)
    }

    private var keyJob: Job? = null

    /** Saved at once; the films reload when typing has paused. */
    fun setTmdbKey(key: String) {
        if (key.trim() == homeSettings.tmdbKeyOverride()) return
        homeSettings.setTmdbKey(key)
        keyJob?.cancel()
        keyJob = viewModelScope.launch {
            delay(1_500)
            repository.syncNow(force = true)
        }
    }

    private fun showNotice(text: UiText) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            notice = UiText.Empty
        }
    }

    private companion object {
        const val NOTICE_MS = 4_000L
    }
}
