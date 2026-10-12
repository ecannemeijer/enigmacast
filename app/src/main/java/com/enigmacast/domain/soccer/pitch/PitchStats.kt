package com.enigmacast.domain.soccer.pitch

import java.text.Normalizer

/** Club numbers computed from a league's played matches (PitchAPI has no team-stats or table endpoint). */
data class ClubStats(
    val team: PitchTeam,
    val played: Int,
    val won: Int,
    val draw: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val cleanSheets: Int,
    val failedToScore: Int,
    val home: Triple<Int, Int, Int>,
    val away: Triple<Int, Int, Int>,
    /** Last five, newest last: "W", "D", "L". */
    val form: List<String>,
    val biggestWin: PitchMatch?,
) {
    val points: Int get() = won * 3 + draw
    val goalDifference: Int get() = goalsFor - goalsAgainst
    fun avgFor(): Double = if (played == 0) 0.0 else goalsFor.toDouble() / played
    fun avgAgainst(): Double = if (played == 0) 0.0 else goalsAgainst.toDouble() / played
}

data class TableRow(val position: Int, val stats: ClubStats)

object PitchStats {
    fun played(matches: List<PitchMatch>): List<PitchMatch> = matches.filter { it.status == PitchStatus.FINISHED && it.hasScore }.sortedBy { it.kickoffSec }

    fun club(team: PitchTeam, matches: List<PitchMatch>): ClubStats {
        var w = 0; var d = 0; var l = 0; var gf = 0; var ga = 0; var cs = 0; var fts = 0
        val home = IntArray(3); val away = IntArray(3)
        val form = ArrayList<String>()
        var best: PitchMatch? = null; var bestMargin = 0
        for (m in played(matches)) {
            val isHome = m.home.id == team.id
            if (!isHome && m.away.id != team.id) continue
            val f = if (isHome) m.scoreHome!! else m.scoreAway!!
            val a = if (isHome) m.scoreAway!! else m.scoreHome!!
            gf += f; ga += a
            if (a == 0) cs++
            if (f == 0) fts++
            val idx = when { f > a -> 0; f == a -> 1; else -> 2 }
            when (idx) { 0 -> w++; 1 -> d++; else -> l++ }
            (if (isHome) home else away)[idx]++
            form += listOf("W", "D", "L")[idx]
            if (f - a > bestMargin) { bestMargin = f - a; best = m }
        }
        return ClubStats(team, w + d + l, w, d, l, gf, ga, cs, fts, Triple(home[0], home[1], home[2]), Triple(away[0], away[1], away[2]), form.takeLast(5), best)
    }

    /** League table from results: points, goal difference, goals for, then name. */
    fun table(matches: List<PitchMatch>): List<TableRow> {
        val teams = LinkedHashMap<String, PitchTeam>()
        matches.forEach { teams.putIfAbsent(it.home.id, it.home); teams.putIfAbsent(it.away.id, it.away) }
        return teams.values.map { club(it, matches) }
            .sortedWith(compareByDescending<ClubStats> { it.points }.thenByDescending { it.goalDifference }.thenByDescending { it.goalsFor }.thenBy { it.team.name })
            .mapIndexed { i, s -> TableRow(i + 1, s) }
    }

    fun teamMatches(team: PitchTeam, matches: List<PitchMatch>) = matches.filter { it.home.id == team.id || it.away.id == team.id }.sortedBy { it.kickoffSec }

    /** Player totals over the events of several matches (goals, cards) — used on the club page for top scorers. */
    fun scorers(events: List<PitchEvent>, teamId: String): List<Pair<PitchPerson, Int>> =
        events.filter { it.teamId == teamId && (it.kind == EventKind.GOAL || it.kind == EventKind.PENALTY_GOAL) && it.player != null }
            .groupBy { it.player!!.id }.map { (_, e) -> e.first().player!! to e.size }.sortedByDescending { it.second }

    /** Same club in football-data.org and PitchAPI spelling: "PSV" / "PSV Eindhoven", "AFC Ajax" / "Ajax". */
    fun sameTeam(a: String, b: String): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private val noise = setOf("fc", "afc", "sc", "sv", "cf", "ac", "bv", "vv", "sbv", "nec", "the", "club", "de")

    fun normalize(name: String): String {
        val plain = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val words = plain.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val kept = words.filter { it !in noise }.ifEmpty { words }
        return kept.joinToString("")
    }

    /** Followed country (Settings ids) → PitchAPI league country codes and an optional name filter. */
    fun leaguesFor(country: String, all: List<PitchLeague>): List<PitchLeague> {
        val (codes, names) = when (country) {
            "NL" -> listOf("NED") to emptyList()
            "ENG" -> listOf("ENG") to listOf("Premier League", "Championship")
            "DE" -> listOf("GER") to listOf("Bundesliga", "2. Bundesliga")
            "ES" -> listOf("ESP") to emptyList()
            "IT" -> listOf("ITA") to emptyList()
            "FR" -> listOf("FRA") to emptyList()
            "PT" -> listOf("POR") to emptyList()
            "BR" -> listOf("BRA") to listOf("Serie A", "Serie B")
            "EU" -> listOf("INT-2") to emptyList()
            else -> listOf("INT") to listOf("World Cup")
        }
        val order = listOf("Eredivisie", "Eerste Divisie", "Premier League", "Bundesliga", "Champions League", "Europa League")
        return all.filter { it.countryCode in codes && (names.isEmpty() || it.name in names) }
            .sortedWith(compareBy<PitchLeague> { order.indexOf(it.name).let { i -> if (i < 0) 99 else i } }.thenBy { it.name })
    }

    /** Lineup position ids: 11 keeper, 2x/3x defence, 6x–8x midfield, 9x–1xx attack (seen in lineups). */
    enum class Line { GK, DEF, MID, ATT, UNKNOWN }

    fun line(positionId: Int?): Line = when {
        positionId == null -> Line.UNKNOWN
        positionId == 11 || positionId == 0 -> Line.GK
        positionId in 20..59 -> Line.DEF
        positionId in 60..89 -> Line.MID
        positionId >= 90 -> Line.ATT
        else -> Line.UNKNOWN
    }

    /** Minute shown on a live match, estimated from kickoff (the API sends no minute). */
    fun liveMinute(m: PitchMatch, nowSec: Long): String {
        val e = ((nowSec - m.kickoffSec) / 60).toInt() + 1
        return when {
            e < 1 -> "1'"
            e <= 45 -> "$e'"
            e <= 60 -> "45+'"
            e - 15 <= 90 -> "${e - 15}'"
            else -> "90+'"
        }
    }
}
