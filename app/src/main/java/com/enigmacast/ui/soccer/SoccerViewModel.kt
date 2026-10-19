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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import com.enigmacast.util.EnigmaLog
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** The Soccer page: cached fixtures and tables of the region chosen on the Sport landing page, refreshed while visible. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SoccerViewModel @Inject constructor(
    private val repository: SoccerRepository,
    private val settingsRepository: SettingsRepository,
    private val reminders: ReminderScheduler,
) : ViewModel() {
    enum class Problem { NONE, NO_KEY, INVALID_KEY, RATE_LIMITED, NOT_IN_PLAN, OFFLINE, SERVER }

    val settings: SoccerSettings get() = repository.settings

    var country by mutableStateOf(SoccerCountries.byId(settings.values.value.region))
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

    /** Seconds a request waits for football-data.org's 10 per minute; 0 when not waiting. */
    var rateWaitSec by mutableStateOf(0)
        private set

    /** A failure of the last refresh that is not in the per-competition fetch rows (standings, exceptions). */
    private var refreshProblem by mutableStateOf(Problem.NONE)

    /** The first error in the chosen region's fetch rows. */
    private var fetchProblem = Problem.NONE

    val autoSwitchKeys: StateFlow<Set<String>> = reminders.scheduledKeys

    /** Cards of the Sport landing page: the regions the key can read, with emblems once known. */
    var regionCards by mutableStateOf(com.enigmacast.domain.soccer.SportRegions.cards(null, null))
        private set
    var landingRefreshing by mutableStateOf(false)
        private set

    private val clock = MutableStateFlow(System.currentTimeMillis())

    /** The region of the landing page, narrowed to the competitions the key can read. */
    private val selectedCountry = settings.values.map { it.region }.distinctUntilChanged()
        .combine(repository.available()) { id, available -> SoccerCountries.resolve(id, available) }
        .distinctUntilChanged()
    private var noticeJob: Job? = null
    private var h2hJob: Job? = null

    init {
        viewModelScope.launch {
            repository.available().combine(repository.competitions()) { available, list ->
                com.enigmacast.domain.soccer.SportRegions.cards(available, list)
            }.distinctUntilChanged().collect { regionCards = it }
        }
        viewModelScope.launch { runCatching { repository.refreshCompetitions() } }
        viewModelScope.launch {
            selectedCountry.flatMapLatest { c ->
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
                loading = list.isEmpty() && !fetched && states.none { it.error.isNotBlank() } && refreshProblem == Problem.NONE
                fetchProblem = states.map { it.error }.firstOrNull { it.isNotBlank() }?.let(::problemOf) ?: Problem.NONE
                recomputeProblem()
                if (problem == Problem.NO_KEY) loading = false
                linkChannels(list)
            }
        }
    }

    private fun recomputeProblem() {
        problem = when {
            repository.apiKeyMissing() -> Problem.NO_KEY
            fetchProblem != Problem.NONE -> fetchProblem
            else -> refreshProblem
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

    /**
     * Runs while the Sport page is on screen. The chosen region's competitions are fetched as soon
     * as it is chosen (only what is due; the cache answers the rest), and a quick switch to another
     * region cancels the old region's queue, so the 10 requests/minute go to what is on screen.
     * Then live scores every 60 s and the periodic refresh.
     */
    suspend fun whileVisible(): Unit = coroutineScope {
        launch {
            com.enigmacast.domain.soccer.RegionLoads.follow(selectedCountry) { codes -> load(codes, force = false) }
        }
        var minutes = 0
        while (true) {
            delay(LIVE_MS)
            clock.value = System.currentTimeMillis()
            minutes++
            val s = settings.values.value
            if (s.liveUpdates && sections.live.isNotEmpty() || s.liveUpdates && soonLive()) {
                val live = runCatching { repository.refreshLive(country.competitions) }
                refreshProblem = live.fold({ problemOfResult(it) }, { e -> if (e is CancellationException) throw e else Problem.SERVER })
                recomputeProblem()
            }
            if (s.refreshMinutes > 0 && minutes % s.refreshMinutes == 0) load(country.competitions, force = false)
        }
    }

    private fun soonLive(): Boolean {
        val now = System.currentTimeMillis() / 1000
        return matches.any { it.status == com.enigmacast.domain.soccer.MatchStatus.SCHEDULED && it.kickoffSec in (now - 3 * 3600)..(now + 600) }
    }

    /** Retry / refresh buttons on the page. */
    fun refresh(force: Boolean) {
        viewModelScope.launch { load(country.competitions, force) }
    }

    /**
     * Fetches [codes] (the repository sequences all requests through one window and skips what is
     * fresh). Errors are not swallowed: they end up in [problem] with a plain message.
     */
    private suspend fun load(codes: List<String>, force: Boolean) {
        refreshing = true
        val ticker = viewModelScope.launch {
            while (true) {
                rateWaitSec = ((repository.rateWaitMs() + 999) / 1000).toInt()
                delay(1_000)
            }
        }
        try {
            val result = repository.refresh(codes, force)
            refreshProblem = problemOfResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnigmaLog.connection("soccer refresh ${codes.joinToString(",")} failed: ${e.javaClass.simpleName}")
            refreshProblem = Problem.SERVER
        } finally {
            ticker.cancel()
            rateWaitSec = 0
            refreshing = false
        }
        recomputeProblem()
        if (refreshProblem != Problem.NONE || problem != Problem.NONE) loading = false
    }

    private fun problemOfResult(result: FdResult?): Problem = when (result) {
        null, is FdResult.Ok, FdResult.NotInPlan -> Problem.NONE
        else -> problemOf(SoccerRepository.errorCode(result))
    }

    /**
     * A card on the landing page was chosen. The region is remembered (initial focus next time,
     * and the PitchAPI page follows it); [whileVisible] fetches its competitions right away.
     */
    fun chooseRegion(id: String) {
        if (settings.values.value.region == id) return
        // No frame of the old region's data on the page that opens now.
        country = SoccerCountries.byId(id)
        tableCode = country.competitions.firstOrNull()
        matches = emptyList()
        standings = emptyMap()
        sections = SoccerSections(null, emptyList(), emptyList(), emptyList(), emptyList())
        refreshProblem = Problem.NONE
        fetchProblem = Problem.NONE
        recomputeProblem()
        loading = true
        settings.setRegion(id)
    }

    /** "Refresh" on the landing page: competitions (names, emblems) and the chosen region's matches. */
    fun refreshLanding() {
        if (landingRefreshing) return
        viewModelScope.launch {
            landingRefreshing = true
            runCatching { repository.refreshCompetitions(force = true) }.onFailure { if (it is CancellationException) throw it }
            load(country.competitions, force = true)
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
        "server_error" -> Problem.SERVER
        else -> Problem.OFFLINE
    }

    private companion object {
        const val LIVE_MS = 60_000L
        const val LINK_DEBOUNCE_MS = 400L
    }
}
