package com.enigmacast.ui.soccer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.soccer.PitchRepository
import com.enigmacast.data.soccer.SoccerSettings
import com.enigmacast.domain.soccer.SoccerMatch
import com.enigmacast.domain.soccer.pitch.ClubStats
import com.enigmacast.domain.soccer.pitch.PitchLeague
import com.enigmacast.domain.soccer.pitch.PitchMatch
import com.enigmacast.domain.soccer.pitch.PitchMatchDetail
import com.enigmacast.domain.soccer.pitch.PitchPerson
import com.enigmacast.domain.soccer.pitch.PitchPlayer
import com.enigmacast.domain.soccer.pitch.PitchStats
import com.enigmacast.domain.soccer.pitch.PitchStatus
import com.enigmacast.domain.soccer.pitch.PitchTeam
import com.enigmacast.domain.soccer.pitch.PlayerMatchStats
import com.enigmacast.domain.soccer.pitch.TableRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** PitchAPI part of the Soccer page: league rows, computed tables, and the match / club / player screens. */
@HiltViewModel
class PitchViewModel @Inject constructor(
    private val repository: PitchRepository,
    private val settings: SoccerSettings,
) : ViewModel() {
    data class LeagueBlock(
        val league: PitchLeague,
        val matches: List<PitchMatch>,
        val live: List<PitchMatch>,
        val recent: List<PitchMatch>,
        val upcoming: List<PitchMatch>,
        val table: List<TableRow>,
    )

    sealed interface Screen {
        data class Match(val match: PitchMatch) : Screen
        data class Club(val team: PitchTeam, val leagueId: String) : Screen
        data class Player(val person: PitchPerson, val matchId: String?) : Screen
    }

    enum class Tab { EVENTS, LINEUPS, STATS, PLAYERS, H2H }

    val hasKey: Boolean get() = repository.hasKey()
    var blocks by mutableStateOf<List<LeagueBlock>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var problem by mutableStateOf("")
        private set
    var tableLeague by mutableStateOf<String?>(null)

    /** Open screens, top last; Back pops one. */
    val stack = mutableStateListOf<Screen>()
    var detail by mutableStateOf<PitchMatchDetail?>(null)
        private set
    var tab by mutableStateOf(Tab.EVENTS)
    var club by mutableStateOf<ClubView?>(null)
        private set
    var player by mutableStateOf<PlayerView?>(null)
        private set

    data class ClubView(
        val team: PitchTeam,
        val league: PitchLeague?,
        val stats: ClubStats,
        val position: Int?,
        val recent: List<PitchMatch>,
        val upcoming: List<PitchMatch>,
        val scorers: List<Pair<PitchPerson, Int>>?,
    )

    data class PlayerView(val person: PitchPerson, val profile: PitchPlayer?, val match: PlayerMatchStats?, val loading: Boolean)

    private var loadJob: Job? = null
    private var detailJob: Job? = null

    init {
        if (hasKey) {
            viewModelScope.launch {
                settings.values.map { it.country }.distinctUntilChanged().collect { load(it, force = false) }
            }
        }
    }

    private fun load(country: String, force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            val leagues = PitchStats.leaguesFor(country, repository.leagues())
            // Cache first, so rows show at once; then network where stale.
            blocks = leagues.map { block(it, repository.cachedSeason(it)) }.filter { it.matches.isNotEmpty() }
            val fresh = leagues.map { block(it, repository.season(it, force)) }.filter { it.matches.isNotEmpty() }
            blocks = fresh
            if (tableLeague !in fresh.map { it.league.id }) tableLeague = fresh.firstOrNull()?.league?.id
            problem = repository.lastError
            loading = false
        }
    }

    fun refresh() = load(settings.values.value.country, force = true)

    /** While the page is visible: re-read seasons every minute (the repository only fetches when stale). */
    suspend fun whileVisible() {
        if (!hasKey) return
        while (true) {
            delay(60_000)
            if (loadJob?.isActive != true) load(settings.values.value.country, force = false)
            (stack.lastOrNull() as? Screen.Match)?.takeIf { it.match.status == PitchStatus.LIVE }?.let { openMatchData(it.match, force = true) }
        }
    }

    private fun block(league: PitchLeague, matches: List<PitchMatch>): LeagueBlock {
        val now = System.currentTimeMillis() / 1000
        val live = matches.filter { it.status == PitchStatus.LIVE }
        val recent = matches.filter { it.status == PitchStatus.FINISHED }.sortedByDescending { it.kickoffSec }.take(ROW)
        val upcoming = matches.filter { it.status == PitchStatus.SCHEDULED && it.kickoffSec >= now - 3 * 3600 }.sortedBy { it.kickoffSec }.take(ROW)
        return LeagueBlock(league, matches, live, recent, upcoming, PitchStats.table(matches))
    }

    fun leagueOf(id: String): LeagueBlock? = blocks.firstOrNull { it.league.id == id }

    /** PitchAPI match for a football-data.org match: same day (±1) and both team names alike. */
    fun find(m: SoccerMatch): PitchMatch? = blocks.asSequence().flatMap { it.matches }.firstOrNull { p ->
        kotlin.math.abs(p.kickoffSec - m.kickoffSec) < 30 * 3600 && PitchStats.sameTeam(p.home.name, m.home.name) && PitchStats.sameTeam(p.away.name, m.away.name)
    }

    fun openMatch(m: PitchMatch) {
        stack.add(Screen.Match(m))
        tab = if (m.status == PitchStatus.SCHEDULED) Tab.LINEUPS else Tab.EVENTS
        openMatchData(m, force = false)
    }

    private fun openMatchData(m: PitchMatch, force: Boolean) {
        detailJob?.cancel()
        if (detail?.match?.id != m.id) detail = PitchMatchDetail(m, null, null, null, null, null)
        detailJob = viewModelScope.launch {
            val full = repository.match(m, force)
            detail = detail?.copy(match = full)
            // Most wanted first; each part arrives on its own.
            val ev = repository.events(full, force)
            detail = detail?.copy(events = ev ?: emptyList())
            val lu = repository.lineups(full, force)
            detail = detail?.copy(lineups = lu)
            if (full.status != PitchStatus.SCHEDULED) {
                detail = detail?.copy(stats = repository.stats(full, force) ?: emptyList())
                detail = detail?.copy(players = repository.players(full, force) ?: emptyList())
            } else {
                detail = detail?.copy(stats = emptyList(), players = emptyList())
            }
            detail = detail?.copy(h2h = repository.h2h(full))
        }
    }

    fun openClub(team: PitchTeam, leagueId: String) {
        stack.add(Screen.Club(team, leagueId))
        showClub(team, leagueId)
    }

    private fun showClub(team: PitchTeam, leagueId: String) {
        val block = leagueOf(leagueId) ?: blocks.firstOrNull { b -> b.matches.any { it.home.id == team.id || it.away.id == team.id } }
        val matches = block?.matches.orEmpty()
        val teamMatches = PitchStats.teamMatches(team, matches)
        val played = teamMatches.filter { it.status == PitchStatus.FINISHED }
        club = ClubView(
            team = team,
            league = block?.league,
            stats = PitchStats.club(team, matches),
            position = block?.table?.firstOrNull { it.stats.team.id == team.id }?.position,
            recent = played.takeLast(ROW).reversed(),
            upcoming = teamMatches.filter { it.status == PitchStatus.SCHEDULED }.take(5),
            scorers = null,
        )
        viewModelScope.launch {
            // Top scorers from the events of the season's played matches; recent ones fetched, older from cache.
            played.takeLast(SCORER_FETCH).forEach { repository.events(it) }
            val scorers = PitchStats.scorers(repository.cachedEvents(played), team.id)
            club = club?.takeIf { it.team.id == team.id }?.copy(scorers = scorers)
        }
    }

    fun openPlayer(person: PitchPerson, matchId: String?) {
        stack.add(Screen.Player(person, matchId))
        showPlayer(person, matchId)
    }

    private fun showPlayer(person: PitchPerson, matchId: String?) {
        player = PlayerView(person, null, null, loading = true)
        viewModelScope.launch {
            val profile = repository.player(person.id)
            val line = if (matchId != null) detail?.takeIf { it.match.id == matchId }?.players?.firstOrNull { it.player.id == person.id } else null
            player = PlayerView(person, profile, line, loading = false)
        }
    }

    /** Back: pop one screen and restore the one below. */
    fun back() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.lastIndex)
        when (val top = stack.lastOrNull()) {
            is Screen.Match -> if (detail?.match?.id != top.match.id) openMatchData(top.match, force = false)
            is Screen.Club -> if (club?.team?.id != top.team.id) showClub(top.team, top.leagueId)
            is Screen.Player -> if (player?.person?.id != top.person.id) showPlayer(top.person, top.matchId)
            null -> Unit
        }
    }

    fun closeAll() { stack.clear() }

    companion object {
        const val ROW = 12
        const val SCORER_FETCH = 6
        fun day(sec: Long) = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
