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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** The film home page: loads today's films, and Watch / Inplannen / Record from the info dialog. */
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
        data class Ready(val films: List<HomeFilm>, val country: String) : State
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

    val autoSwitchKeys: StateFlow<Set<String>> = reminders.scheduledKeys

    /** Programme keys that have a scheduled or running recording. */
    val recordingKeys: StateFlow<Set<String>> = recordings.items
        .map { list -> list.filter(Recording::isActive).mapTo(HashSet()) { com.enigmacast.domain.reminder.Reminder.keyOf(it.reference, it.beginEpochSec) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var loadJob: Job? = null
    private var noticeJob: Job? = null

    init {
        load(false)
    }

    fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (state !is State.Ready || force) state = State.Loading
            val result = runCatching { repository.load(force) }.getOrNull()
            state = when {
                result == null && settingsRepository.current().activeReceiver() == null -> State.NoReceiver
                result == null -> State.Empty
                result.missingKey -> State.MissingKey
                result.films.isEmpty() -> State.Empty
                else -> State.Ready(result.films, result.country)
            }
            refreshRows()
        }
    }

    /** Re-sorts as time passes, so ended films drop off and "Now on TV" moves on. */
    fun refreshRows() {
        val ready = state as? State.Ready ?: run { rows = emptyList(); hero = null; return }
        val now = System.currentTimeMillis() / 1000
        hero = FilmRanking.hero(ready.films, now)
        rows = FilmRanking.rows(ready.films, now, primeTimeStartSec())
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

    fun confirmRecord() {
        val ask = recordAsk ?: return
        recordAsk = null
        viewModelScope.launch { showNotice(recordings.schedule(ask)) }
    }

    fun dismissRecord() {
        recordAsk = null
    }

    fun setCountry(code: String) {
        homeSettings.setCountry(code)
        load(true)
    }

    private var keyJob: Job? = null

    /** Saved at once; the films reload when typing has paused. */
    fun setTmdbKey(key: String) {
        if (key.trim() == homeSettings.tmdbKeyOverride()) return
        homeSettings.setTmdbKey(key)
        keyJob?.cancel()
        keyJob = viewModelScope.launch {
            delay(1_500)
            load(true)
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

    private fun primeTimeStartSec(): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).atTime(LocalTime.of(FilmRanking.PRIME_TIME_HOUR, 0)).atZone(zone).toEpochSecond()
    }

    private companion object {
        const val NOTICE_MS = 4_000L
    }
}
