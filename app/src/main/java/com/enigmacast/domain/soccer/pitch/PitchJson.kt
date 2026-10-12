package com.enigmacast.domain.soccer.pitch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Parses pitchapi.dev answers (`{"data": …}` / `{"error": …}`) by hand; missing fields never fail a screen. */
object PitchJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun data(body: String?): JsonElement? = body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonObject }?.get("data")

    /** `{"error":{"code":"UNAUTHORIZED","message":"…"}}` → "UNAUTHORIZED: …". */
    fun error(body: String?): String {
        val o = body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonObject }?.get("error") as? JsonObject ?: return ""
        return listOfNotNull(o.str("code"), o.str("message")).joinToString(": ")
    }

    fun leagues(body: String): List<PitchLeague> =
        ((data(body) as? JsonObject)?.get("leagues") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            PitchLeague(
                o.str("id") ?: return@mapNotNull null,
                o.str("name").orEmpty(),
                o.str("country_code").orEmpty(),
                o.str("image_url").orEmpty(),
                (o["seasons"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }

    /** /v1/leagues/{id}/matches: the league comes once at the top. */
    fun leagueMatches(body: String): List<PitchMatch> {
        val d = data(body) as? JsonObject ?: return emptyList()
        val league = d["league"] as? JsonObject
        return (d["matches"] as? JsonArray).orEmpty().mapNotNull { match(it as? JsonObject, league) }
    }

    /** /v1/date/{date}: each match carries its league. */
    fun dateMatches(body: String): List<PitchMatch> =
        ((data(body) as? JsonObject)?.get("matches") as? JsonArray).orEmpty().mapNotNull { match(it as? JsonObject, null) }

    fun matchDetail(body: String): PitchMatch? = match(data(body) as? JsonObject, null)

    fun match(o: JsonObject?, league: JsonObject?): PitchMatch? {
        o ?: return null
        val id = o.str("id") ?: return null
        val kickoff = o.str("time_utc")?.let(::instant)
            ?: o.str("date")?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toEpochSecond() }.getOrNull() }
            ?: return null
        val l = (o["league"] as? JsonObject) ?: league
        return PitchMatch(
            id = id,
            leagueId = l?.str("id").orEmpty(),
            leagueName = l?.str("name").orEmpty(),
            kickoffSec = kickoff,
            status = PitchStatus.of(o.str("status")),
            home = team(o["home_team"] as? JsonObject ?: o["home"] as? JsonObject) ?: return null,
            away = team(o["away_team"] as? JsonObject ?: o["away"] as? JsonObject) ?: return null,
            scoreHome = o.int("score_home"),
            scoreAway = o.int("score_away"),
            round = o.str("round_name"),
            referee = o.str("referee"),
            stadium = o.str("stadium"),
        )
    }

    fun team(o: JsonObject?): PitchTeam? = o?.let { PitchTeam(it.str("id") ?: return null, it.str("name").orEmpty(), it.str("image_url").orEmpty()) }

    private fun person(o: JsonObject?): PitchPerson? = o?.let { PitchPerson(it.str("id") ?: it.str("player_id") ?: return null, it.str("name").orEmpty(), it.str("image_url").orEmpty()) }

    fun events(body: String): List<PitchEvent> =
        ((data(body) as? JsonObject)?.get("events") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val raw = o.str("event_type").orEmpty()
            val own = o.bool("is_own_goal")
            val pen = o.bool("is_penalty")
            val kind = when (raw.lowercase().replace("_", "")) {
                "goal" -> when { own -> EventKind.OWN_GOAL; pen -> EventKind.PENALTY_GOAL; else -> EventKind.GOAL }
                "owngoal" -> EventKind.OWN_GOAL
                "missedpenalty", "penaltymissed" -> EventKind.MISSED_PENALTY
                "yellowcard", "yellow" -> EventKind.YELLOW
                "secondyellow", "yellowred", "yellowredcard", "secondyellowcard" -> EventKind.SECOND_YELLOW
                "redcard", "red" -> EventKind.RED
                "substitution", "sub" -> EventKind.SUBSTITUTION
                "var" -> EventKind.VAR
                else -> EventKind.OTHER
            }
            PitchEvent(kind, raw, o.int("minute") ?: 0, o.int("minute_added") ?: 0, o.str("team_id").orEmpty(), person(o["player"] as? JsonObject), person(o["sub_in_player"] as? JsonObject), o.int("score_home"), o.int("score_away"))
        }

    fun lineups(body: String): PitchLineups? {
        val d = data(body) as? JsonObject ?: return null
        fun side(o: JsonObject?): TeamLineup? {
            o ?: return null
            fun players(a: JsonElement?) = (a as? JsonArray).orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                LineupPlayer(po.str("player_id") ?: po.str("id") ?: return@mapNotNull null, po.str("name").orEmpty(), po.str("shirt_number").orEmpty(), po.int("position_id"), po.bool("is_captain"), po.float("pitch_x"), po.float("pitch_y"))
            }
            return TeamLineup(o.str("formation").orEmpty(), o.bool("confirmed"), (o["coach"] as? JsonObject)?.str("name"), players(o["starters"]), players(o["subs"] ?: o["substitutes"]))
        }
        return PitchLineups(side(d["home"] as? JsonObject), side(d["away"] as? JsonObject)).takeIf { it.home != null || it.away != null }
    }

    /** Only the "All" period (whole match). */
    fun stats(body: String): List<StatGroup> {
        val periods = ((data(body) as? JsonObject)?.get("periods") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val all = periods.firstOrNull { it.str("period") == "All" } ?: periods.firstOrNull() ?: return emptyList()
        return (all["groups"] as? JsonArray).orEmpty().mapNotNull { g ->
            val go = g as? JsonObject ?: return@mapNotNull null
            val items = (go["items"] as? JsonArray).orEmpty().mapNotNull { i ->
                val io = i as? JsonObject ?: return@mapNotNull null
                StatItem(io.str("title").orEmpty(), io.str("key").orEmpty(), io.str("home").orEmpty(), io.str("away").orEmpty())
            }
            StatGroup(go.str("group_name").orEmpty(), items).takeIf { items.isNotEmpty() }
        }
    }

    fun h2h(body: String): PitchH2H? {
        val d = data(body) as? JsonObject ?: return null
        val recent = (d["recent_matches"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull null
            val home = team(o["home"] as? JsonObject) ?: return@mapIndexedNotNull null
            val away = team(o["away"] as? JsonObject) ?: return@mapIndexedNotNull null
            PitchMatch("h2h$i", "", "", o.str("time_utc")?.let(::instant) ?: 0L, if (o.bool("finished")) PitchStatus.FINISHED else PitchStatus.SCHEDULED, home, away, o.int("score_home"), o.int("score_away"))
        }
        return PitchH2H(d.int("home_wins") ?: 0, d.int("draws") ?: 0, d.int("away_wins") ?: 0, d.int("total_matches") ?: recent.size, recent)
    }

    /** /v1/matches/{id}/players (array) or …/players/{pid} (one object). */
    fun playerStats(body: String): List<PlayerMatchStats> = when (val d = data(body)) {
        is JsonArray -> d.mapNotNull { playerLine(it as? JsonObject) }
        is JsonObject -> listOfNotNull(playerLine(d))
        else -> emptyList()
    }

    private fun playerLine(o: JsonObject?): PlayerMatchStats? {
        o ?: return null
        val player = person(o["player"] as? JsonObject) ?: return null
        val groups = (o["stats"] as? JsonArray).orEmpty().mapNotNull { g ->
            val go = g as? JsonObject ?: return@mapNotNull null
            val stats = go["stats"] as? JsonObject ?: return@mapNotNull null
            go.str("key").orEmpty() to stats.entries.mapNotNull { (label, v) ->
                val stat = (v as? JsonObject)?.get("stat") as? JsonObject ?: return@mapNotNull null
                if (stat.str("type") == "boolean") return@mapNotNull null
                val value = stat["value"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
                val total = stat.str("total")
                label to formatNumber(value) + (total?.let { "/$it" } ?: "")
            }
        }
        fun find(key: String): String? = (o["stats"] as? JsonArray).orEmpty().firstNotNullOfOrNull { g ->
            ((g as? JsonObject)?.get("stats") as? JsonObject)?.values?.firstOrNull { (it as? JsonObject)?.str("key") == key }
                ?.let { ((it as JsonObject)["stat"] as? JsonObject)?.str("value") }
        }
        return PlayerMatchStats(player, o.str("team_id").orEmpty(), find("rating_title")?.toDoubleOrNull(), find("minutes_played")?.toDoubleOrNull()?.toInt(), find("goals")?.toDoubleOrNull()?.toInt(), find("assists")?.toDoubleOrNull()?.toInt(), groups)
    }

    fun player(body: String): PitchPlayer? {
        val o = data(body) as? JsonObject ?: return null
        return PitchPlayer(o.str("id") ?: return null, o.str("name").orEmpty(), o.str("short_name").orEmpty(), o.str("shirt_number").orEmpty(), o.int("position_id"), o.str("country_code").orEmpty(), o.str("image_url").orEmpty())
    }

    private fun formatNumber(v: String): String = v.toDoubleOrNull()?.let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, d) } ?: v

    private fun instant(s: String): Long? = runCatching { Instant.parse(s).epochSecond }.getOrNull()
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(k: String): Int? = str(k)?.toDoubleOrNull()?.toInt()
    private fun JsonObject.float(k: String): Float = str(k)?.toFloatOrNull() ?: 0f
    private fun JsonObject.bool(k: String): Boolean = str(k)?.toBooleanStrictOrNull() ?: false
}
