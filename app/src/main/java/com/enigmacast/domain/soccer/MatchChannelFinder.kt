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

    // Compiled once: compiling these per call on the main thread froze the Soccer page (ANR).
    private val marks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^a-z0-9']+")

    fun normalize(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "")
        return plain.lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace(nonWord, " ")
            .trim()
    }

    private val NORMALIZED_ALIASES: Map<String, List<String>> by lazy {
        ALIASES.mapValues { (_, values) -> values.map(::normalize) }
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
        out.toList().forEach { v -> NORMALIZED_ALIASES[v]?.let { out += it } }
        NORMALIZED_ALIASES.forEach { (key, values) -> if (values.any { it in out }) out += key }
        return out.filter { it.length >= 3 || it == "az" }.sortedByDescending { it.length }
    }

    private fun mentions(normalizedHay: String, variants: List<String>): Boolean =
        variants.any { normalizedHay.contains(" $it ") }

    /** Best programme for [match], or null. Title matches beat description matches; then closest to kickoff. */
    fun find(match: SoccerMatch, candidates: List<EpgCandidate>): EpgCandidate? {
        val inWindow = candidates.filter { kotlin.math.abs(it.beginSec - match.kickoffSec) <= WINDOW_SEC }
        if (inWindow.isEmpty()) return null
        // Team spellings once per match, each programme text normalised once.
        val home = variants(match.home)
        val away = variants(match.away)
        return inWindow
            .mapNotNull { c ->
                val title = " ${normalize(c.title)} "
                val inTitle = mentions(title, home) && mentions(title, away)
                val both = if (inTitle || c.description.isBlank()) title else " ${normalize("${c.title} ${c.description}")} "
                val inBoth = !inTitle && mentions(both, home) && mentions(both, away)
                when {
                    inTitle -> c to 2
                    inBoth -> c to 1
                    else -> null
                }
            }
            .sortedWith(compareByDescending<Pair<EpgCandidate, Int>> { it.second }.thenBy { kotlin.math.abs(it.first.beginSec - match.kickoffSec) })
            .firstOrNull()?.first
    }
}
