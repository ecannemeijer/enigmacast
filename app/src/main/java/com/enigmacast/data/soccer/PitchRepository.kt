package com.enigmacast.data.soccer

import com.enigmacast.data.db.SoccerDao
import com.enigmacast.data.db.SoccerFetchRow
import com.enigmacast.domain.soccer.pitch.PitchH2H
import com.enigmacast.domain.soccer.pitch.PitchEvent
import com.enigmacast.domain.soccer.pitch.PitchJson
import com.enigmacast.domain.soccer.pitch.PitchLeague
import com.enigmacast.domain.soccer.pitch.PitchLineups
import com.enigmacast.domain.soccer.pitch.PitchMatch
import com.enigmacast.domain.soccer.pitch.PitchPlayer
import com.enigmacast.domain.soccer.pitch.PitchStatus
import com.enigmacast.domain.soccer.pitch.PlayerMatchStats
import com.enigmacast.domain.soccer.pitch.StatGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PitchAPI answers cached as raw JSON in `soccer_fetches` (keys "pitch|…"), so screens open from
 * the cache and only stale parts are fetched again:
 * leagues a week, a season's matches per the Soccer refresh setting (5 min around live matches),
 * finished match details a week, other match details two minutes, players a week.
 */
@Singleton
class PitchRepository @Inject constructor(
    private val http: PitchHttp,
    private val dao: SoccerDao,
    private val settings: SoccerSettings,
) {
    fun hasKey(): Boolean = http.apiKey().isNotBlank()

    /** Last error code for the banner ("" = fine). */
    @Volatile var lastError: String = ""
        private set

    private suspend fun cached(key: String, maxAgeMs: Long, force: Boolean, path: String, params: List<Pair<String, String>> = emptyList()): String? {
        val now = System.currentTimeMillis()
        val row = dao.fetch(key)
        val fresh = row != null && row.body.isNotBlank() && now - row.fetchedAtMs < maxAgeMs
        if (fresh && !force) return row!!.body
        return when (val r = http.get(path, params)) {
            is FdResult.Ok -> { lastError = ""; dao.putFetch(SoccerFetchRow(key, now, r.body)); r.body }
            else -> { lastError = SoccerRepository.errorCode(r); row?.body?.takeIf { it.isNotBlank() } }
        }
    }

    suspend fun leagues(): List<PitchLeague> = cached("pitch|leagues", WEEK_MS, false, "/v1/leagues")?.let(PitchJson::leagues).orEmpty()

    /** A league's whole season (played and upcoming), cached raw JSON. */
    suspend fun season(league: PitchLeague, force: Boolean = false): List<PitchMatch> {
        val season = league.currentSeason ?: return emptyList()
        val key = "pitch|season|${league.id}|$season"
        val peek = dao.fetch(key)?.body?.let(PitchJson::leagueMatches).orEmpty()
        val now = System.currentTimeMillis() / 1000
        val liveNear = peek.any { it.status == PitchStatus.LIVE || (it.status == PitchStatus.SCHEDULED && it.kickoffSec in (now - 3 * 3600)..(now + 600)) }
        val minutes = settings.values.value.refreshMinutes.takeIf { it > 0 } ?: (24 * 60)
        val age = if (liveNear && settings.values.value.liveUpdates) LIVE_MS else minutes * 60_000L
        return cached(key, age, force, "/v1/leagues/${league.id}/matches", listOf("season" to season, "status" to "all"))?.let(PitchJson::leagueMatches).orEmpty()
    }

    /** Cached seasons only, no network: for finding a match while a screen opens. */
    suspend fun cachedSeason(league: PitchLeague): List<PitchMatch> =
        dao.fetch("pitch|season|${league.id}|${league.currentSeason}")?.body?.let(PitchJson::leagueMatches).orEmpty()

    private fun detailAge(m: PitchMatch): Long = if (m.status == PitchStatus.FINISHED) WEEK_MS else DETAIL_MS

    suspend fun match(m: PitchMatch, force: Boolean = false): PitchMatch =
        cached("pitch|m|${m.id}", detailAge(m), force, "/v1/matches/${m.id}")?.let(PitchJson::matchDetail)?.let { d ->
            d.copy(leagueId = d.leagueId.ifBlank { m.leagueId }, leagueName = d.leagueName.ifBlank { m.leagueName })
        } ?: m

    suspend fun events(m: PitchMatch, force: Boolean = false): List<PitchEvent>? = cached("pitch|ev|${m.id}", detailAge(m), force, "/v1/matches/${m.id}/events")?.let(PitchJson::events)
    suspend fun lineups(m: PitchMatch, force: Boolean = false): PitchLineups? = cached("pitch|lu|${m.id}", detailAge(m), force, "/v1/matches/${m.id}/lineups")?.let(PitchJson::lineups)
    suspend fun stats(m: PitchMatch, force: Boolean = false): List<StatGroup>? = cached("pitch|st|${m.id}", detailAge(m), force, "/v1/matches/${m.id}/stats")?.let(PitchJson::stats)
    suspend fun h2h(m: PitchMatch): PitchH2H? = cached("pitch|h2h|${m.id}", DAY_MS, false, "/v1/matches/${m.id}/h2h")?.let(PitchJson::h2h)
    suspend fun players(m: PitchMatch, force: Boolean = false): List<PlayerMatchStats>? = cached("pitch|pl|${m.id}", detailAge(m), force, "/v1/matches/${m.id}/players")?.let(PitchJson::playerStats)
    /** One player's stats in one match (cached a week; finished matches don't change). */
    suspend fun playerInMatch(matchId: String, playerId: String): PlayerMatchStats? =
        cached("pitch|pm|$matchId|$playerId", WEEK_MS, false, "/v1/matches/$matchId/players/$playerId")?.let(PitchJson::playerStats)?.firstOrNull()

    suspend fun player(id: String): PitchPlayer? = cached("pitch|p|$id", WEEK_MS, false, "/v1/players/$id")?.let(PitchJson::player)

    /** Events of finished matches already cached (no network), for club top scorers. */
    suspend fun cachedEvents(matches: List<PitchMatch>): List<PitchEvent> = matches.flatMap { m -> dao.fetch("pitch|ev|${m.id}")?.body?.let(PitchJson::events).orEmpty() }

    companion object {
        private const val WEEK_MS = 7L * 86_400_000
        private const val DAY_MS = 86_400_000L
        private const val DETAIL_MS = 2L * 60_000
        private const val LIVE_MS = 5L * 60_000
    }
}
