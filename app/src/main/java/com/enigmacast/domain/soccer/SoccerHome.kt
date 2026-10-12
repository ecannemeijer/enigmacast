package com.enigmacast.domain.soccer

/** The rows of the Soccer page, built from the cached matches. */
data class SoccerSections(
    val hero: SoccerMatch?,
    val live: List<SoccerMatch>,
    val today: List<SoccerMatch>,
    /** Later fixtures grouped by matchday (or date for cups without one), in kickoff order. */
    val upcoming: List<Pair<Int?, List<SoccerMatch>>>,
    val recent: List<SoccerMatch>,
) {
    val isEmpty: Boolean get() = live.isEmpty() && today.isEmpty() && upcoming.isEmpty() && recent.isEmpty()
}

object SoccerHome {
    /**
     * The hero: a live match first (the one that kicked off first; [preferred] competition wins a
     * tie), else the next kickoff in [preferred], else the next kickoff anywhere, else the latest result.
     */
    fun hero(matches: List<SoccerMatch>, preferred: String?, nowSec: Long): SoccerMatch? {
        val live = matches.filter { it.status.isLive }
            .sortedWith(compareBy<SoccerMatch> { it.competition != preferred }.thenBy { it.kickoffSec })
        if (live.isNotEmpty()) return live.first()
        val next = matches.filter { it.status == MatchStatus.SCHEDULED && it.kickoffSec >= nowSec - 15 * 60 }.sortedBy { it.kickoffSec }
        return next.firstOrNull { it.competition == preferred } ?: next.firstOrNull()
            ?: matches.filter { it.status == MatchStatus.FINISHED }.maxByOrNull { it.kickoffSec }
    }

    fun sections(matches: List<SoccerMatch>, preferred: String?, nowSec: Long, todayStartSec: Long, todayEndSec: Long): SoccerSections {
        val sorted = matches.sortedBy { it.kickoffSec }
        val live = sorted.filter { it.status.isLive }
        val today = sorted.filter { !it.status.isLive && it.kickoffSec in todayStartSec until todayEndSec && it.status != MatchStatus.FINISHED }
        val upcoming = sorted.filter { it.status == MatchStatus.SCHEDULED && it.kickoffSec >= todayEndSec }
            .groupBy { it.matchday }.toList()
        val recent = sorted.filter { it.status == MatchStatus.FINISHED }.sortedByDescending { it.kickoffSec }.take(20)
        return SoccerSections(hero(matches, preferred, nowSec), live, today, upcoming, recent)
    }
}
