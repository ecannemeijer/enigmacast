package com.enigmacast.domain.soccer

/** A club or national team as football-data.org v4 sends it. */
data class SoccerTeam(
    val id: Int,
    val name: String,
    val shortName: String,
    val tla: String,
    val crest: String,
) {
    /** Short name for cards; falls back to the full name. */
    val label: String get() = shortName.ifBlank { name }
}

data class Score(val home: Int?, val away: Int?) {
    val known: Boolean get() = home != null && away != null
    override fun toString(): String = if (known) "$home – $away" else ""
}

data class SoccerMatch(
    val id: Int,
    val competition: String,
    val competitionName: String,
    val kickoffSec: Long,
    val status: MatchStatus,
    /** football-data.org's own status string, kept for details. */
    val rawStatus: String,
    val matchday: Int?,
    val stage: String,
    val group: String?,
    val home: SoccerTeam,
    val away: SoccerTeam,
    val fullTime: Score,
    val halfTime: Score,
    val venue: String?,
    val referee: String?,
    /** Minute from the API when the plan sends it; otherwise estimated, see [MatchClock]. */
    val minute: Int?,
)

data class StandingRow(
    val position: Int,
    val team: SoccerTeam,
    val played: Int,
    val won: Int,
    val draw: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDifference: Int,
    val points: Int,
    /** "W,D,L,W,W" when the plan provides it, else null. */
    val form: String?,
)

/** One table of a competition: the league table, or a group / league phase in a cup. */
data class StandingTable(val stage: String, val group: String?, val rows: List<StandingRow>)

data class Competition(val code: String, val name: String, val area: String, val emblem: String)

data class HeadToHead(val matches: List<SoccerMatch>, val homeWins: Int, val draws: Int, val awayWins: Int, val goals: Int)

/** Simplified match states for the UI. */
enum class MatchStatus {
    SCHEDULED,
    LIVE,
    HALF_TIME,
    FINISHED,
    POSTPONED,
    CANCELLED,
    ;

    val isLive: Boolean get() = this == LIVE || this == HALF_TIME

    companion object {
        /** football-data.org v4 statuses: SCHEDULED, TIMED, IN_PLAY, PAUSED, EXTRA_TIME, PENALTY_SHOOTOUT, FINISHED, SUSPENDED, POSTPONED, CANCELLED, AWARDED, LIVE. */
        fun of(raw: String?): MatchStatus = when (raw?.uppercase()) {
            "IN_PLAY", "LIVE", "EXTRA_TIME", "PENALTY_SHOOTOUT" -> LIVE
            "PAUSED" -> HALF_TIME
            "FINISHED", "AWARDED" -> FINISHED
            "POSTPONED", "SUSPENDED" -> POSTPONED
            "CANCELLED" -> CANCELLED
            else -> SCHEDULED
        }
    }
}

/** The minute shown on a live match. */
object MatchClock {
    private const val HALF = 45
    private const val BREAK_MIN = 15

    /**
     * The API minute when present. Otherwise estimated from kickoff: 1–45, "45+" in added time,
     * then a 15-minute break, 46–90 and "90+". Null when not live.
     */
    fun label(match: SoccerMatch, nowSec: Long): String? {
        if (match.status == MatchStatus.HALF_TIME) return "HT"
        if (match.status != MatchStatus.LIVE) return null
        match.minute?.let { return "$it'" }
        val elapsed = ((nowSec - match.kickoffSec) / 60).toInt() + 1
        return when {
            elapsed < 1 -> "1'"
            elapsed <= HALF -> "$elapsed'"
            elapsed <= HALF + BREAK_MIN -> "45+'"
            elapsed - BREAK_MIN <= 2 * HALF -> "${elapsed - BREAK_MIN}'"
            else -> "90+'"
        }
    }
}
