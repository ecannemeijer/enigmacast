package com.enigmacast.ui.soccer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.soccer.FdResult
import com.enigmacast.data.soccer.MatchChannel
import com.enigmacast.data.soccer.SoccerRepository
import com.enigmacast.data.soccer.SoccerSettings
import com.enigmacast.domain.epg.ProgrammePrompt
import com.enigmacast.domain.reminder.Reminder
import com.enigmacast.domain.soccer.HeadToHead
import com.enigmacast.domain.soccer.SoccerCountries
import com.enigmacast.domain.soccer.SoccerHome
import com.enigmacast.domain.soccer.SoccerMatch
import com.enigmacast.domain.soccer.SoccerSections
import com.enigmacast.domain.soccer.StandingTable
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.ui.remote.AutoSwitchTexts
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** The Soccer page: cached fixtures and tables of the followed country, refreshed while visible. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SoccerViewModel @Inject constructor(
    private val repository: SoccerRepository,
    private val settingsRepository: SettingsRepository,
    private val reminders: ReminderScheduler,
) : ViewModel() {
    enum class Problem { NONE, NO_KEY, INVALID_KEY, RATE_LIMITED, NOT_IN_PLAN, OFFLINE }

    val settings: SoccerSettings get() = repository.settings

    var country by mutableStateOf(SoccerCountries.byId(settings.values.value.country))
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var problem by mutableStateOf(Problem.NONE)
        private set
    var matches by mutableStateOf<List<SoccerMatch>>(emptyList())
        private set
    var sections by mutableStateOf(SoccerSections(null, emptyList(), emptyList(), emptyList(), emptyList()))
        private set
    var standings by mutableStateOf<Map<String, List<StandingTable>>>(emptyMap())
        private set
    var channels by mutableStateOf<Map<Int, MatchChannel>>(emptyMap())
        private set
    var tableCode by mutableStateOf<String?>(null)
    var info by mutableStateOf<SoccerMatch?>(null)
        private set
    var headToHead by mutableStateOf<HeadToHead?>(null)
        private set
    var headToHeadLoading by mutableStateOf(false)
        private set
    var notice by mutableStateOf(UiText.Empty)
        private set

    val autoSwitchKeys: StateFlow<Set<String>> = reminders.scheduledKeys

    /** Cards of the Sport landing page: the regions the key can read, with emblems once known. */
    var regionCards by mutableStateOf(com.enigmacast.domain.soccer.SportRegions.cards(null, null))
        private set
    var landingRefreshing by mutableStateOf(false)
        private set

    private val clock = MutableStateFlow(System.currentTimeMillis())
    private var noticeJob: Job? = null
    private var h2hJob: Job? = null

    init {
        viewModelScope.launch {
            repository.available().combine(repository.competitions()) { available, list ->
                com.enigmacast.domain.soccer.SportRegions.cards(available, list)
            }.distinctUntilChanged().collect { regionCards = it }
        }
        viewModelScope.launch { runCatching { repository.refreshCompetitions() } }
        val countryFlow = settings.values.map { it.country }.distinctUntilChanged()
            .combine(repository.available()) { id, available ->
                val offered = SoccerCountries.offered(available)
                offered.firstOrNull { it.id == id } ?: SoccerCountries.byId(id)
            }.distinctUntilChanged()
        viewModelScope.launch {
            countryFlow.flatMapLatest { c ->
                country = c
                if (tableCode !in c.competitions) tableCode = c.competitions.firstOrNull()
                val nowSec = System.currentTimeMillis() / 1000
                repository.matches(c.competitions, nowSec - 8 * 86_400L, nowSec + 16 * 86_400L)
                    .combine(repository.standings(c.competitions)) { m, s -> m to s }
                    .combine(repository.fetchState(c.competitions)) { pair, states -> Triple(pair.first, pair.second, states) }
            }.combine(clock) { t, now -> t to now }.collect { (t, now) ->
                val (list, tables, states) = t
                matches = list
                standings = tables
                val nowSec = now / 1000
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                sections = SoccerHome.sections(
                    list,
                    country.competitions.firstOrNull(),
                    nowSec,
                    today.atStartOfDay(zone).toEpochSecond(),
                    today.plusDays(1).atStartOfDay(zone).toEpochSecond(),
                )
                val fetched = states.any { it.fetchedAtMs > 0 }
                loading = list.isEmpty() && !fetched && states.none { it.error.isNotBlank() }
                problem = when {
                    repository.apiKeyMissing() -> Problem.NO_KEY
                    else -> states.map { it.error }.firstOrNull { it.isNotBlank() }?.let(::problemOf) ?: Problem.NONE
                }
                if (problem == Problem.NO_KEY) loading = false
                linkChannels(list)
            }
        }
    }

    private var linkKey = ""
    private var linkJob: Job? = null

    private fun linkChannels(list: List<SoccerMatch>) {
        val key = list.joinToString(",") { "${it.id}:${it.kickoffSec}:${it.status}" }
        if (key == linkKey) return
        linkKey = key
        // One search at a time: a refresh (or a country change) emits several lists in a row,
        // and every one of them started a full guide search that kept running.
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            delay(LINK_DEBOUNCE_MS)
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            channels = try {
                repository.channels(receiver, list)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    /** Runs while the page is on screen: refresh when due, then live scores every 60 s. */
    suspend fun whileVisible() {
        refresh(force = false)
        var minutes = 0
        while (true) {
            delay(LIVE_MS)
            clock.value = System.currentTimeMillis()
            minutes++
            val s = settings.values.value
            if (s.liveUpdates && sections.live.isNotEmpty() || s.liveUpdates && soonLive()) {
                repository.refreshLive(country.competitions)?.let { problem = problemOf(SoccerRepository.errorCode(it)) }
            }
            if (s.refreshMinutes > 0 && minutes % s.refreshMinutes == 0) refresh(force = false)
        }
    }

    private fun soonLive(): Boolean {
        val now = System.currentTimeMillis() / 1000
        return matches.any { it.status == com.enigmacast.domain.soccer.MatchStatus.SCHEDULED && it.kickoffSec in (now - 3 * 3600)..(now + 600) }
    }

    fun refresh(force: Boolean) {
        viewModelScope.launch {
            refreshing = true
            val result = runCatching { repository.refresh(country.competitions, force) }.getOrNull()
            if (result is FdResult.NoKey) problem = Problem.NO_KEY
            refreshing = false
            loading = false
        }
    }

    /** A card on the landing page was chosen: follow that region; the page reloads its competitions. */
    fun chooseRegion(id: String) {
        if (settings.values.value.country != id) settings.setCountry(id)
    }

    /** "Refresh" on the landing page: competitions (names, emblems) and the followed region's matches. */
    fun refreshLanding() {
        if (landingRefreshing) return
        viewModelScope.launch {
            landingRefreshing = true
            runCatching { repository.refreshCompetitions(force = true) }
            runCatching { repository.refresh(country.competitions, force = true) }
            landingRefreshing = false
        }
    }

    fun openInfo(match: SoccerMatch) {
        info = match
        headToHead = null
        h2hJob?.cancel()
        h2hJob = viewModelScope.launch {
            headToHeadLoading = true
            headToHead = runCatching { repository.headToHead(match) }.getOrNull()
            headToHeadLoading = false
        }
    }

    fun closeInfo() {
        info = null
        h2hJob?.cancel()
    }

    fun channelOf(match: SoccerMatch): MatchChannel? = channels[match.id]

    fun scheduleKey(match: SoccerMatch): String? = channels[match.id]?.let { Reminder.keyOf(it.event.reference, it.event.beginEpochSec) }

    /** AutoSwitch at the programme's start, with the existing reminders. */
    fun toggleSchedule(match: SoccerMatch) {
        val link = channels[match.id] ?: return
        viewModelScope.launch {
            val receiverId = settingsRepository.current().activeReceiver()?.id.orEmpty()
            val key = Reminder.keyOf(link.event.reference, link.event.beginEpochSec)
            val prompt = ProgrammePrompt(link.event, link.entry.name, reminders.isScheduled(key))
            val toggle = reminders.toggle(link.event, link.entry.name, receiverId)
            showNotice(AutoSwitchTexts.result(toggle, prompt))
        }
    }

    private fun showNotice(text: UiText) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(4_000)
            notice = UiText.Empty
        }
    }

    private fun problemOf(code: String): Problem = when (code) {
        "" -> Problem.NONE
        "no_key" -> Problem.NO_KEY
        "invalid_key" -> Problem.INVALID_KEY
        "rate_limited" -> Problem.RATE_LIMITED
        "not_in_plan" -> Problem.NOT_IN_PLAN
        else -> Problem.OFFLINE
    }

    private companion object {
        const val LIVE_MS = 60_000L
        const val LINK_DEBOUNCE_MS = 400L
    }
}
