package com.enigmacast.domain.films

import com.enigmacast.domain.epg.GuideEvent
import java.text.Normalizer
import java.util.Locale

/** A programme from today's guide that looks like a feature film. */
data class FilmCandidate(
    val event: GuideEvent,
    val channelName: String,
    val searchTitle: String,
    val year: Int?,
    /** Picon path from the bouquet (may be blank). */
    val channelPicon: String = "",
)

/**
 * Decides which EPG programmes are films, and cleans their titles for a TMDB search.
 * Pure Kotlin so it is unit tested.
 */
object FilmDetector {
    const val MIN_FILM_SEC = 70L * 60
    const val MAX_FILM_SEC = 4L * 60 * 60

    private val moviePrefix = Regex(
        "^(?:(?:speel)?film|movie|kinofilm|spielfilm|cinema|cin[eé]ma|filmtip|nachtfilm|zomerfilm|premiere|première|tv[- ]?film|blockbuster)\\s*[:\\-–|]\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val trailingYear = Regex("\\s*[(\\[]((?:19|20)\\d{2})[)\\]]\\s*$")
    private val bareYear = Regex("\\s+[-–]?\\s*((?:19|20)\\d{2})\\s*$")
    private val descriptionYear = Regex("(?:^|[^\\d])((?:19[2-9]|20[0-4])\\d)(?:[^\\d]|$)")
    private val noiseSuffix = Regex(
        "\\s*[(\\[](?:hd|uhd|4k|ov|ut|omu|zweikanal|wh|herh\\.?|herhaling|rerun|repeat|nl|ned|dub|subs?)[)\\]]\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val notFilm = Regex(
        "\\b(journaal|nieuws|news|tagesschau|sport|voetbal|football|soccer|f1|formule 1|studio sport|talkshow|quiz|weer|weather|docu|documentaire|documentary|serie|series|aflevering|episode|afl\\.|seizoen|season|kerkdienst|concert|nachtprogramma|teleshop|shopping|kijkcijfers|loterij)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val episodeMark = Regex("\\b[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}\\b|\\(\\d{1,3}/\\d{1,3}\\)|\\bdeel \\d+\\b|\\bpart \\d+\\b", RegexOption.IGNORE_CASE)
    private val filmWords = Regex("\\b(speelfilm|spielfilm|film|movie|thriller|komedie|comedy|drama|actiefilm|actionfilm|regie|director|regisseur|met in de hoofdrol|starring)\\b", RegexOption.IGNORE_CASE)

    /** Movie/Drama in the DVB content nibble (0x10–0x1F), or OpenWebif's genre text says so. */
    fun isMovieGenre(event: GuideEvent): Boolean {
        if (event.genreId in 0x10..0x1F) return true
        val genre = event.genre.lowercase(Locale.ROOT)
        return genre.contains("movie") || genre.contains("film")
    }

    /** True when the genre is known and is clearly something else (news, sport, children's, music…). */
    private fun isOtherGenre(event: GuideEvent): Boolean = event.genreId in 0x20..0xBF

    /** The film score: 0 is not a film. Genre counts most, then length and the wording. */
    fun filmScore(event: GuideEvent): Int {
        val duration = event.durationSec
        if (duration < MIN_FILM_SEC || duration > MAX_FILM_SEC) return 0
        if (notFilm.containsMatchIn(event.title) || episodeMark.containsMatchIn(event.title)) return 0
        if (isOtherGenre(event)) return 0
        var score = 1
        if (isMovieGenre(event)) score += 3
        if (moviePrefix.containsMatchIn(event.title)) score += 3
        if (filmWords.containsMatchIn(event.shortDescription) || filmWords.containsMatchIn(event.plot.take(400))) score += 1
        if (yearOf(event) != null) score += 1
        if (duration in 85L * 60..150L * 60) score += 1
        return score
    }

    /** Films need a genre, a prefix, or enough of the softer clues. */
    fun isFilm(event: GuideEvent): Boolean = filmScore(event) >= 2

    fun candidate(event: GuideEvent, channelName: String, channelPicon: String = ""): FilmCandidate? {
        if (!isFilm(event)) return null
        val title = cleanTitle(event.title)
        if (title.length < 2) return null
        return FilmCandidate(event, channelName, title, yearOf(event), channelPicon)
    }

    /** "Film: The Matrix (1999) (HD)" → "The Matrix". */
    fun cleanTitle(raw: String): String {
        var title = raw.trim()
        repeat(3) { title = title.replace(noiseSuffix, "") }
        title = title.replace(moviePrefix, "")
        title = title.replace(trailingYear, "")
        title = title.replace(bareYear, "")
        title = title.replace(noiseSuffix, "")
        return title.trim().trim('-', '–', ':', '|').trim()
    }

    /** A year in the title, else in the description (first plausible one). */
    fun yearOf(event: GuideEvent): Int? {
        trailingYear.find(event.title.trim())?.let { return it.groupValues[1].toInt() }
        bareYear.find(event.title.trim())?.let { return it.groupValues[1].toInt() }
        val text = event.shortDescription.ifBlank { event.plot.take(200) }
        return descriptionYear.find(text)?.groupValues?.get(1)?.toInt()
    }

    /** Lower case, no accents or punctuation, single spaces. */
    fun normalize(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("^(the|de|het|der|die|das|le|la|les) "), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
