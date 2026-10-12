package com.enigmacast.domain.soccer

import java.text.Normalizer
import java.util.Locale

/** A guide programme that might show a match. */
data class EpgCandidate(
    val reference: String,
    val channelName: String,
    val eventId: Int,
    val title: String,
    val description: String,
    val beginSec: Long,
    val endSec: Long,
)

/**
 * Finds the channel that broadcasts a match in the cached guide: a programme starting within
 * [WINDOW_SEC] of kickoff whose title (or, second best, its short description) names both
 * teams. Team names are normalised (lower case, no accents, no club prefixes like FC/AFC/SC)
 * and tried as full name, short name and a few common TV spellings (Man United → Manchester United).
 */
object MatchChannelFinder {
    const val WINDOW_SEC = 30 * 60L

    private val CLUB_WORDS = setOf(
        "fc", "afc", "cf", "sc", "sv", "ac", "as", "ss", "ssc", "us", "cd", "ud", "sd", "rc", "rcd", "bv", "vv", "fk",
        "sk", "if", "bk", "tsg", "vfb", "vfl", "rb", "1", "club", "de", "futbol", "calcio", "football", "hotspur", "and",
    )

    private val ALIASES = mapOf(
        "man united" to listOf("manchester united", "man utd"),
        "man city" to listOf("manchester city"),
        "tottenham" to listOf("spurs"),
        "wolverhampton" to listOf("wolves"),
        "psv" to listOf("psv eindhoven"),
        "az" to listOf("az alkmaar"),
        "inter" to listOf("internazionale", "inter milan"),
        "bayern" to listOf("bayern munchen", "bayern munich", "fc bayern"),
        "barca" to listOf("barcelona"),
        "atleti" to listOf("atletico madrid", "atletico"),
        "psg" to listOf("paris saint germain", "paris sg", "paris"),
        "leverkusen" to listOf("bayer leverkusen", "bayer 04"),
        "dortmund" to listOf("borussia dortmund", "bvb"),
        "m'gladbach" to listOf("gladbach", "monchengladbach"),
    )

    fun normalize(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace(Regex("[^a-z0-9']+"), " ")
            .trim()
    }

    /** Spellings to look for, longest first. Single words shorter than 3 letters are left out. */
    fun variants(team: SoccerTeam): List<String> {
        val out = LinkedHashSet<String>()
        fun add(raw: String) {
            val n = normalize(raw)
            if (n.isBlank()) return
            out += n
            val stripped = n.split(' ').filter { it !in CLUB_WORDS && !it.all(Char::isDigit) }.joinToString(" ")
            if (stripped.isNotBlank()) out += stripped
        }
        add(team.name)
        add(team.shortName)
        out.toList().forEach { v -> ALIASES[v]?.forEach { out += normalize(it) } }
        ALIASES.forEach { (key, values) -> if (values.any { normalize(it) in out }) out += key }
        return out.filter { it.length >= 3 || it == "az" }.sortedByDescending { it.length }
    }

    private fun mentions(text: String, team: SoccerTeam): Boolean {
        val hay = " ${normalize(text)} "
        return variants(team).any { hay.contains(" $it ") }
    }

    /** Best programme for [match], or null. Title matches beat description matches; then closest to kickoff. */
    fun find(match: SoccerMatch, candidates: List<EpgCandidate>): EpgCandidate? =
        candidates
            .filter { kotlin.math.abs(it.beginSec - match.kickoffSec) <= WINDOW_SEC }
            .mapNotNull { c ->
                val inTitle = mentions(c.title, match.home) && mentions(c.title, match.away)
                val inBoth = !inTitle && mentions("${c.title} ${c.description}", match.home) && mentions("${c.title} ${c.description}", match.away)
                when {
                    inTitle -> c to 2
                    inBoth -> c to 1
                    else -> null
                }
            }
            .sortedWith(compareByDescending<Pair<EpgCandidate, Int>> { it.second }.thenBy { kotlin.math.abs(it.first.beginSec - match.kickoffSec) })
            .firstOrNull()?.first
}
