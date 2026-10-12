package com.enigmacast.domain.soccer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

/** Parses football-data.org v4 answers by hand; missing fields never fail the page. */
object SoccerJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun competitions(body: String): List<Competition> =
        (root(body)?.get("competitions") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Competition(o.str("code") ?: return@mapNotNull null, o.str("name").orEmpty(), (o["area"] as? JsonObject)?.str("name").orEmpty(), o.str("emblem").orEmpty())
        }

    fun matches(body: String): List<SoccerMatch> =
        (root(body)?.get("matches") as? JsonArray).orEmpty().mapNotNull { match(it as? JsonObject) }

    /** One match object (also used for the stored rows). */
    fun match(o: JsonObject?): SoccerMatch? {
        o ?: return null
        val id = o.int("id") ?: return null
        val kickoff = o.str("utcDate")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } ?: return null
        val comp = o["competition"] as? JsonObject
        val score = o["score"] as? JsonObject
        val raw = o.str("status").orEmpty()
        return SoccerMatch(
            id = id,
            competition = comp?.str("code").orEmpty(),
            competitionName = comp?.str("name").orEmpty(),
            kickoffSec = kickoff,
            status = MatchStatus.of(raw),
            rawStatus = raw,
            matchday = o.int("matchday"),
            stage = o.str("stage").orEmpty(),
            group = o.str("group"),
            home = team(o["homeTeam"] as? JsonObject),
            away = team(o["awayTeam"] as? JsonObject),
            fullTime = score(score?.get("fullTime") as? JsonObject),
            halfTime = score(score?.get("halfTime") as? JsonObject),
            venue = o.str("venue")?.takeIf { it.isNotBlank() },
            referee = (o["referees"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.let { refs -> refs.firstOrNull { it.str("type") == "REFEREE" } ?: refs.firstOrNull() }?.str("name"),
            minute = o.int("minute"),
        )
    }

    /** The raw JSON objects of a matches answer, to store each match on its own. */
    fun matchObjects(body: String): List<Pair<SoccerMatch, String>> =
        (root(body)?.get("matches") as? JsonArray).orEmpty().mapNotNull { e ->
            val m = match(e as? JsonObject) ?: return@mapNotNull null
            m to e.toString()
        }

    fun matchFromStored(text: String): SoccerMatch? = match(runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull())

    fun standings(body: String): List<StandingTable> =
        (root(body)?.get("standings") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != null && o.str("type") != "TOTAL") return@mapNotNull null
            val rows = (o["table"] as? JsonArray).orEmpty().mapNotNull { r ->
                val t = r as? JsonObject ?: return@mapNotNull null
                StandingRow(
                    position = t.int("position") ?: 0,
                    team = team(t["team"] as? JsonObject),
                    played = t.int("playedGames") ?: 0,
                    won = t.int("won") ?: 0,
                    draw = t.int("draw") ?: 0,
                    lost = t.int("lost") ?: 0,
                    goalsFor = t.int("goalsFor") ?: 0,
                    goalsAgainst = t.int("goalsAgainst") ?: 0,
                    goalDifference = t.int("goalDifference") ?: 0,
                    points = t.int("points") ?: 0,
                    form = t.str("form")?.takeIf { it.isNotBlank() },
                )
            }
            StandingTable(o.str("stage").orEmpty(), o.str("group"), rows)
        }

    /** Head-to-head, counted from the listed matches from the point of view of [homeTeamId]. */
    fun headToHead(body: String, homeTeamId: Int): HeadToHead {
        val list = matches(body).filter { it.status == MatchStatus.FINISHED && it.fullTime.known }
        var h = 0
        var d = 0
        var a = 0
        var goals = 0
        list.forEach { m ->
            val hg = m.fullTime.home!!
            val ag = m.fullTime.away!!
            goals += hg + ag
            val ours = if (m.home.id == homeTeamId) hg - ag else ag - hg
            when {
                ours > 0 -> h++
                ours < 0 -> a++
                else -> d++
            }
        }
        return HeadToHead(list.sortedByDescending { it.kickoffSec }, h, d, a, goals)
    }

    /** "message" of an error answer, for the log. */
    fun errorMessage(body: String?): String = body?.let { root(it)?.str("message") }.orEmpty()

    private fun team(o: JsonObject?) = SoccerTeam(
        id = o?.int("id") ?: 0,
        name = o?.str("name").orEmpty(),
        shortName = o?.str("shortName").orEmpty(),
        tla = o?.str("tla").orEmpty(),
        crest = o?.str("crest").orEmpty(),
    )

    private fun score(o: JsonObject?) = Score(o?.int("home"), o?.int("away"))

    private fun root(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
}
