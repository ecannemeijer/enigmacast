package com.enigmacast.data.soccer

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.db.SoccerDao
import com.enigmacast.data.db.SoccerFetchRow
import com.enigmacast.data.db.SoccerMatchRow
import com.enigmacast.data.db.SoccerStandingsRow
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.soccer.EpgCandidate
import com.enigmacast.domain.soccer.HeadToHead
import com.enigmacast.domain.soccer.MatchChannelFinder
import com.enigmacast.domain.soccer.MatchStatus
import com.enigmacast.domain.soccer.SoccerJson
import com.enigmacast.domain.soccer.SoccerMatch
import com.enigmacast.domain.soccer.StandingTable
import com.enigmacast.domain.tuner.NowEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** A guide programme that shows a match, with the channel to zap to. */
data class MatchChannel(val entry: BouquetEntry, val event: GuideEvent)

/**
 * The Soccer page's data: football-data.org answers cached in Room per competition, so the page
 * opens from the cache at once and refreshes in the background within the 10 requests/minute.
 */
@Singleton
class SoccerRepository @Inject constructor(
    private val http: FootballDataHttp,
    private val dao: SoccerDao,
    private val cache: CacheDao,
    val settings: SoccerSettings,
) {
    private val gate = Mutex()

    fun apiKeyMissing(): Boolean = http.apiKey().isBlank()

    /** How long the next request waits for the 10 per minute (0 = goes at once). */
    fun rateWaitMs(): Long = http.window.waitMs()

    /** Competition codes the key can read (from /v4/competitions), or null before the first answer. */
    fun available(): Flow<Set<String>?> = dao.observeFetches(listOf(KEY_COMPETITIONS)).map { rows ->
        rows.firstOrNull()?.body?.takeIf { it.isNotBlank() }?.let { body -> SoccerJson.competitions(body).map { it.code }.toSet().takeIf { it.isNotEmpty() } }
    }

    fun matches(codes: List<String>, fromSec: Long, toSec: Long): Flow<List<SoccerMatch>> =
        dao.observeMatches(codes, fromSec, toSec).map { rows -> rows.mapNotNull { SoccerJson.matchFromStored(it.json) } }

    fun standings(codes: List<String>): Flow<Map<String, List<StandingTable>>> =
        dao.observeStandings(codes).map { rows -> rows.associate { it.competition to SoccerJson.standings(it.json) } }

    /** Last fetch state per competition ("matches|CODE"), for the error banner and the "updated" line. */
    fun fetchState(codes: List<String>): Flow<List<SoccerFetchRow>> = dao.observeFetches(codes.map { matchesKey(it) })

    /** The competitions the key reads, with names and emblems (Sport landing page); null before the first answer. */
    fun competitions(): Flow<List<com.enigmacast.domain.soccer.Competition>?> = dao.observeFetches(listOf(KEY_COMPETITIONS)).map { rows ->
        rows.firstOrNull()?.body?.takeIf { it.isNotBlank() }?.let { body -> SoccerJson.competitions(body).takeIf { it.isNotEmpty() } }
    }

    /** Competitions list, refreshed once a week, or now with [force] (Sport › Refresh). */
    suspend fun refreshCompetitions(nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (http.apiKey().isBlank()) return
        val last = dao.fetch(KEY_COMPETITIONS)
        if (!force && last != null && last.body.isNotBlank() && nowMs - last.fetchedAtMs < WEEK_MS) return
        val result = http.get("/competitions")
        if (result is FdResult.Ok) dao.putFetch(SoccerFetchRow(KEY_COMPETITIONS, nowMs, result.body))
    }

    /**
     * Fixtures (yesterday … +14 days) and standings for each code, when older than the refresh
     * setting or [force]. Returns the first error, or null.
     */
    suspend fun refresh(codes: List<String>, force: Boolean, nowMs: Long = System.currentTimeMillis()): FdResult? = gate.withLock {
        refreshCompetitions(nowMs)
        val minutes = settings.values.value.refreshMinutes
        var error: FdResult? = null
        for (code in codes) {
            val last = dao.fetch(matchesKey(code))
            val due = force || last == null || last.error.isNotBlank() || (minutes > 0 && nowMs - last.fetchedAtMs >= minutes * 60_000L)
            if (!due) continue
            val today = LocalDate.now(ZoneOffset.UTC)
            val from = today.minusDays(1)
            val to = today.plusDays(DAYS_AHEAD)
            val result = http.get("/competitions/$code/matches", listOf("dateFrom" to from.toString(), "dateTo" to to.toString()))
            if (result is FdResult.Ok) {
                val rows = SoccerJson.matchObjects(result.body).map { (m, json) -> row(m, json, code) }
                dao.replaceMatches(code, from.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(), rows)
                dao.putFetch(SoccerFetchRow(matchesKey(code), nowMs))
            } else {
                dao.putFetch(SoccerFetchRow(matchesKey(code), last?.fetchedAtMs ?: 0L, error = errorCode(result)))
                error = error ?: result
                if (result is FdResult.InvalidKey || result is FdResult.NoKey) break
                continue
            }
            val table = http.get("/competitions/$code/standings")
            if (table is FdResult.Ok) dao.putStandings(SoccerStandingsRow(code, table.body, nowMs)) else if (table !is FdResult.NotInPlan) error = error ?: table
        }
        dao.purgeMatches(nowMs / 1000 - 8 * 86_400L)
        error
    }

    /**
     * Live scores: today's matches again for each competition that has a match in play or about
     * to start, so status changes (half time, full time) arrive too. One request per competition.
     */
    suspend fun refreshLive(codes: List<String>, nowMs: Long = System.currentTimeMillis()): FdResult? = gate.withLock {
        val nowSec = nowMs / 1000
        val near = dao.matches(codes, nowSec - LIVE_BEFORE_SEC, nowSec + 10 * 60)
            .filter { MatchStatus.of(it.status).let { s -> s.isLive || s == MatchStatus.SCHEDULED } }
            .map { it.competition }.distinct()
        var error: FdResult? = null
        for (code in near) {
            val today = LocalDate.now(ZoneOffset.UTC)
            val result = http.get("/competitions/$code/matches", listOf("dateFrom" to today.minusDays(1).toString(), "dateTo" to today.toString()))
            if (result is FdResult.Ok) {
                dao.putMatches(SoccerJson.matchObjects(result.body).map { (m, json) -> row(m, json, code) })
            } else {
                error = error ?: result
            }
        }
        error
    }

    /** Last five meetings, cached a day. Null when not available (plan, rate limit, offline). */
    suspend fun headToHead(match: SoccerMatch, nowMs: Long = System.currentTimeMillis()): HeadToHead? {
        val key = "h2h|${match.id}"
        val cached = dao.fetch(key)
        if (cached != null && cached.body.isNotBlank() && nowMs - cached.fetchedAtMs < DAY_MS) return SoccerJson.headToHead(cached.body, match.home.id)
        if (http.window.waitMs() > 0) return cached?.body?.takeIf { it.isNotBlank() }?.let { SoccerJson.headToHead(it, match.home.id) }
        val result = http.get("/matches/${match.id}/head2head", listOf("limit" to "5"))
        if (result !is FdResult.Ok) return null
        dao.putFetch(SoccerFetchRow(key, nowMs, result.body))
        return SoccerJson.headToHead(result.body, match.home.id)
    }

    /** Channels in the cached guide of [receiver] that show each match (matches within the synced days only). */
    suspend fun channels(receiver: ReceiverConfig, matches: List<SoccerMatch>): Map<Int, MatchChannel> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { channelsOffMain(receiver, matches) }

    private suspend fun channelsOffMain(receiver: ReceiverConfig, matches: List<SoccerMatch>): Map<Int, MatchChannel> {
        val rk = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
        val names = cache.channels(rk).associateBy { it.serviceKey.ifBlank { NowEvents.key(it.reference) } }
        if (names.isEmpty()) return emptyMap()
        val result = HashMap<Int, MatchChannel>()
        matches.filter { it.status == MatchStatus.SCHEDULED || it.status.isLive }.groupBy { it.kickoffSec }.forEach { (kickoff, group) ->
            val rows = cache.eventsStarting(rk, kickoff - MatchChannelFinder.WINDOW_SEC, kickoff + MatchChannelFinder.WINDOW_SEC)
            if (rows.isEmpty()) return@forEach
            val candidates = rows.mapNotNull { r ->
                val ch = names[r.serviceKey] ?: return@mapNotNull null
                EpgCandidate(ch.reference, ch.name, r.eventId, r.title, r.shortDescription, r.beginSec, r.endSec)
            }
            val found = group.associate { m -> m.id to MatchChannelFinder.find(m, candidates) }
            group.forEach { m ->
                found[m.id]?.let { c ->
                    val ch = names[NowEvents.key(c.reference)] ?: return@let
                    val lean = rows.first { it.serviceKey == NowEvents.key(c.reference) && it.beginSec == c.beginSec }
                    val row = cache.eventAt(rk, lean.serviceKey, lean.beginSec) ?: lean
                    result[m.id] = MatchChannel(BouquetEntry(name = ch.name, reference = ch.reference, number = ch.number, picon = ch.picon), CacheMappers.event(row))
                }
            }
        }
        return result
    }

    private fun row(m: SoccerMatch, json: String, code: String) =
        SoccerMatchRow(m.id, m.competition.ifBlank { code }, m.kickoffSec, m.rawStatus, json)

    companion object {
        const val KEY_COMPETITIONS = "competitions"
        const val DAYS_AHEAD = 14L
        private const val LIVE_BEFORE_SEC = 3 * 3600L
        private const val WEEK_MS = 7L * 86_400_000
        private const val DAY_MS = 86_400_000L
        fun matchesKey(code: String) = "matches|$code"

        fun errorCode(result: FdResult?): String = when (result) {
            null, is FdResult.Ok -> ""
            FdResult.NoKey -> "no_key"
            FdResult.InvalidKey -> "invalid_key"
            FdResult.NotInPlan -> "not_in_plan"
            is FdResult.RateLimited -> "rate_limited"
            is FdResult.Failed -> if (result.message.startsWith("HTTP")) "server_error" else "failed"
        }
    }
}
