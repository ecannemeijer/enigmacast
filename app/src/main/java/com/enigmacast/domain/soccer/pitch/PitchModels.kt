package com.enigmacast.domain.soccer.pitch

/** A league as pitchapi.dev lists it (country codes are three letters: NED, ENG, GER…). */
data class PitchLeague(val id: String, val name: String, val countryCode: String, val image: String, val seasons: List<String>) {
    val currentSeason: String? get() = seasons.lastOrNull()
}

data class PitchTeam(val id: String, val name: String, val image: String) {
    val initials: String get() = name.split(' ').filter { it.isNotBlank() && it.first().isUpperCase() }.joinToString("") { it.take(1) }.take(3).ifBlank { name.take(3).uppercase() }
}

enum class PitchStatus {
    SCHEDULED, LIVE, FINISHED, POSTPONED, CANCELLED;

    companion object {
        /** Seen: not_started, finished. Anything in-play-like counts as live. */
        fun of(raw: String?): PitchStatus = when (raw?.lowercase()) {
            null, "", "not_started", "scheduled", "upcoming", "tbd" -> SCHEDULED
            "finished", "ft", "aet", "pen", "awarded", "played" -> FINISHED
            "postponed", "suspended", "delayed" -> POSTPONED
            "cancelled", "canceled", "abandoned" -> CANCELLED
            else -> LIVE
        }
    }
}

data class PitchMatch(
    val id: String,
    val leagueId: String,
    val leagueName: String,
    val kickoffSec: Long,
    val status: PitchStatus,
    val home: PitchTeam,
    val away: PitchTeam,
    val scoreHome: Int?,
    val scoreAway: Int?,
    val round: String? = null,
    val referee: String? = null,
    val stadium: String? = null,
) {
    val hasScore: Boolean get() = scoreHome != null && scoreAway != null
}

data class PitchPerson(val id: String, val name: String, val image: String)

enum class EventKind { GOAL, OWN_GOAL, PENALTY_GOAL, MISSED_PENALTY, YELLOW, SECOND_YELLOW, RED, SUBSTITUTION, VAR, OTHER }

data class PitchEvent(
    val kind: EventKind,
    val rawType: String,
    val minute: Int,
    val added: Int,
    val teamId: String,
    val player: PitchPerson?,
    /** For substitutions: the player coming on ([player] goes off). */
    val subIn: PitchPerson?,
    val scoreHome: Int?,
    val scoreAway: Int?,
) {
    val minuteLabel: String get() = if (added > 0) "$minute+$added'" else "$minute'"
}

data class LineupPlayer(
    val id: String,
    val name: String,
    val shirt: String,
    val positionId: Int?,
    val captain: Boolean,
    /** 0…1 from own goal (x) and side (y); 0,0 for substitutes. */
    val x: Float,
    val y: Float,
)

data class TeamLineup(val formation: String, val confirmed: Boolean, val coach: String?, val starters: List<LineupPlayer>, val subs: List<LineupPlayer>)

data class PitchLineups(val home: TeamLineup?, val away: TeamLineup?)

data class StatItem(val title: String, val key: String, val home: String, val away: String)
data class StatGroup(val name: String, val items: List<StatItem>)

data class PitchH2H(val homeWins: Int, val draws: Int, val awayWins: Int, val total: Int, val recent: List<PitchMatch>)

/** A player's line in one match: rating, goals, assists, minutes and every other stat as label → value. */
data class PlayerMatchStats(
    val player: PitchPerson,
    val teamId: String,
    val rating: Double?,
    val minutes: Int?,
    val goals: Int?,
    val assists: Int?,
    val groups: List<Pair<String, List<Pair<String, String>>>>,
)

data class PitchPlayer(val id: String, val name: String, val shortName: String, val shirt: String, val positionId: Int?, val countryCode: String, val image: String)

/** Everything on the match screen. Parts are null while loading or when the API has nothing. */
data class PitchMatchDetail(
    val match: PitchMatch,
    val events: List<PitchEvent>?,
    val lineups: PitchLineups?,
    val stats: List<StatGroup>?,
    val h2h: PitchH2H?,
    val players: List<PlayerMatchStats>?,
)
