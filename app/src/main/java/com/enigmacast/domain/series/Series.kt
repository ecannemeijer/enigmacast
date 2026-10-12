package com.enigmacast.domain.series

import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.films.FilmCache
import com.enigmacast.domain.films.FilmCandidate
import com.enigmacast.domain.films.FilmDetector
import com.enigmacast.domain.films.FilmMatcher
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.TmdbApi
import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.films.TmdbSearchHit
import com.enigmacast.domain.tuner.NowEvents

/**
 * TMDB TV and movie ids overlap, so series share the film tables with a negative id.
 */
object SeriesIds {
    fun toRow(tvId: Int): Int = -kotlin.math.abs(tvId)
    fun tvId(rowId: Int): Int = kotlin.math.abs(rowId)
    fun isSeries(rowId: Int): Boolean = rowId < 0
}

/** "S2 E5", "Seizoen 2, aflevering 5", "afl. 5", "(5/8)" in title or description. */
data class EpisodeLabel(val season: Int?, val episode: Int?) {
    fun text(): String = when {
        season != null && episode != null -> "S$season E$episode"
        episode != null -> "E$episode"
        season != null -> "S$season"
        else -> ""
    }

    companion object {
        private val se = Regex("\\b[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})\\b")
        private val seasonEpisode = Regex("(?:seizoen|season|staffel|saison)\\s*(\\d{1,2})\\s*[,.:\\-]?\\s*(?:aflevering|afl\\.?|episode|ep\\.?|folge)\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
        private val episodeOnly = Regex("(?:aflevering|afl\\.|episode|folge)\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
        private val ofTotal = Regex("\\((\\d{1,3})/(\\d{1,3})\\)")

        fun parse(event: GuideEvent): EpisodeLabel? {
            val text = listOf(event.title, event.shortDescription, event.plot.take(300)).joinToString(" \n ")
            se.find(text)?.let { return EpisodeLabel(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            seasonEpisode.find(text)?.let { return EpisodeLabel(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            episodeOnly.find(text)?.let { return EpisodeLabel(null, it.groupValues[1].toInt()) }
            ofTotal.find(text)?.let { return EpisodeLabel(null, it.groupValues[1].toInt()) }
            return null
        }
    }
}

/**
 * Which EPG programmes are series episodes. Films are decided first ([FilmDetector]); of the
 * rest, a series needs enough clues: episode marks, a series genre, or a title that recurs
 * on the same channel within the guide window. News, sport, weather, shopping are never series.
 */
object SeriesDetector {
    const val MIN_SEC = 15L * 60
    const val MAX_SEC = 100L * 60

    private val notSeries = Regex(
        "\\b(journaal|nieuws|news|tagesschau|heute|weer|weather|wetter|sport|voetbal|football|soccer|f1|formule 1|teleshop|shopping|nachtprogramma|kerkdienst|loterij|lotto|kijkcijfers|reclame|tekst tv|teletekst|live)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val seriesWords = Regex("\\b(serie|series|reeks|sitcom|soap|dramaserie|misdaadserie|comedyserie|aflevering|episode|seizoen|season|staffel|folge)\\b", RegexOption.IGNORE_CASE)
    private val episodeMarks = Regex("\\b[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}\\b|\\(\\d{1,3}/\\d{1,3}\\)|\\b(?:afl\\.|aflevering|episode|folge)\\s*\\d+", RegexOption.IGNORE_CASE)
    private val titleMarks = Regex("\\s*(?:[-–:|]\\s*)?(?:\\b[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}\\b|\\(\\d{1,3}/\\d{1,3}\\)|\\b(?:afl\\.|aflevering|episode|folge)\\s*\\d+)\\s*$", RegexOption.IGNORE_CASE)

    /** The series name for matching and grouping: no episode marks, film-style cleaning. */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        repeat(2) { t = t.replace(titleMarks, "") }
        // "Show: Episode title" keeps the show name.
        t = FilmDetector.cleanTitle(t)
        return t.trim()
    }

    /** Key that groups episodes of one series on one channel. */
    fun key(title: String, reference: String): String = FilmDetector.normalize(cleanTitle(title)) + "|" + NowEvents.key(reference)

    fun score(event: GuideEvent, recurrences: Int): Int {
        val d = event.durationSec
        if (d < MIN_SEC || d > MAX_SEC) return 0
        if (notSeries.containsMatchIn(event.title)) return 0
        // DVB: 0x2x news, 0x4x sport, 0x6x music/ballet, 0xAx leisure/hobbies (often shopping/cooking).
        if (event.genreId in 0x20..0x2F || event.genreId in 0x40..0x4F) return 0
        if (FilmDetector.isFilm(event)) return 0
        var s = 0
        if (episodeMarks.containsMatchIn(event.title) || episodeMarks.containsMatchIn(event.shortDescription)) s += 3
        if (seriesWords.containsMatchIn(event.genre) || seriesWords.containsMatchIn(event.shortDescription.take(120))) s += 2
        if (event.genreId in 0x10..0x1F) s += 1
        // A title that comes back on the same channel is the strongest clue in a guide without genres.
        if (recurrences >= 2) s += 3
        return s
    }

    fun isSeries(event: GuideEvent, recurrences: Int): Boolean = score(event, recurrences) >= 3

    /** Candidates from a window of events: [channelOf] gives (name, picon) for a channel or null when not on Home. */
    fun candidates(events: List<GuideEvent>, channelOf: (GuideEvent) -> Pair<String, String>?): List<FilmCandidate> {
        val counts = events.groupingBy { key(it.title, it.reference) }.eachCount()
        return events.mapNotNull { e ->
            val channel = channelOf(e) ?: return@mapNotNull null
            if (!isSeries(e, counts[key(e.title, e.reference)] ?: 1)) return@mapNotNull null
            val title = cleanTitle(e.title)
            if (title.length < 2) return@mapNotNull null
            FilmCandidate(e, channel.first, title, null, channel.second)
        }
    }
}

/** Matches series candidates with TMDB /search/tv and /tv/{id}, cached like films. */
class HomeSeriesBuilder(private val tmdb: TmdbApi, private val cache: FilmCache = FilmCache.None) {
    suspend fun build(candidates: List<FilmCandidate>, country: String, language: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<HomeFilm> {
        val byTitle = candidates.groupBy { FilmDetector.normalize(it.searchTitle) }
        var done = 0
        val out = ArrayList<HomeFilm>()
        for (group in byTitle.values) {
            val show = runCatching { resolve(group.first(), country, language) }.getOrNull()
            onProgress(++done, byTitle.size)
            if (show != null) group.forEach { out += HomeFilm(it, show) }
        }
        return out
    }

    suspend fun resolve(candidate: FilmCandidate, country: String, language: String): TmdbMovie? {
        val key = "tv|${FilmDetector.normalize(candidate.searchTitle)}|$language|$country"
        cache.match(key)?.let { cached -> return cached.tmdbId?.let { show(it, country, language) } }
        val hits = TmdbJson.parseTvSearch(tmdb.searchTv(candidate.searchTitle, language) ?: return null)
        val hit = best(candidate, hits)
        if (hit == null) {
            cache.putMatch(key, null, 0.0)
            return null
        }
        val rowId = SeriesIds.toRow(hit.id)
        val show = show(rowId, country, language) ?: fromHit(hit)
        cache.putMatch(key, rowId, FilmMatcher.titleSimilarity(candidate.searchTitle, hit.title))
        return show
    }

    private fun best(candidate: FilmCandidate, hits: List<TmdbSearchHit>): TmdbSearchHit? = hits.take(8)
        .map { it to maxOf(FilmMatcher.titleSimilarity(candidate.searchTitle, it.title), FilmMatcher.titleSimilarity(candidate.searchTitle, it.originalTitle)) }
        .filter { it.second >= MIN_SIMILARITY }
        .maxWithOrNull(compareBy<Pair<TmdbSearchHit, Double>> { it.second }.thenBy { it.first.popularity })
        ?.first

    private suspend fun show(rowId: Int, country: String, language: String): TmdbMovie? {
        cache.movie(rowId, language)?.let { return it }
        val parsed = tmdb.tv(SeriesIds.tvId(rowId), language)?.let { TmdbJson.parseTv(it, country, language) } ?: return null
        cache.putMovie(parsed, language)
        return parsed
    }

    private fun fromHit(hit: TmdbSearchHit) = TmdbMovie(
        id = SeriesIds.toRow(hit.id), title = hit.title, originalTitle = hit.originalTitle, year = hit.year,
        overview = hit.overview, voteAverage = hit.voteAverage, voteCount = hit.voteCount, popularity = hit.popularity,
        posterPath = hit.posterPath, backdropPath = hit.backdropPath, genreIds = hit.genreIds, isSeries = true,
    )

    companion object {
        /** Series titles in the EPG are usually exact; be stricter than films (no year to help). */
        const val MIN_SIMILARITY = 0.8
    }
}

/** Duplicate check for series timers: same channel and a start within a few minutes counts as taken. */
object SeriesPlan {
    data class Slot(val serviceKey: String, val beginSec: Long)

    const val SLACK_SEC = 5 * 60L

    fun <T> missing(candidates: List<Pair<Slot, T>>, taken: List<Slot>): List<T> {
        val seen = ArrayList(taken)
        val out = ArrayList<T>()
        for ((slot, value) in candidates) {
            if (seen.any { it.serviceKey == slot.serviceKey && kotlin.math.abs(it.beginSec - slot.beginSec) <= SLACK_SEC }) continue
            seen += slot
            out += value
        }
        return out
    }
}

/** Scheduled episodes of one series on one channel, as one Home card. */
data class SeriesGroup(
    val key: String,
    val title: String,
    val channelName: String,
    val episodes: List<com.enigmacast.domain.recording.Recording>,
    val hasRule: Boolean,
) {
    val next get() = episodes.firstOrNull()
}

object SeriesGrouping {
    fun keyOf(item: com.enigmacast.domain.recording.Recording): String = SeriesDetector.key(item.title, item.reference)

    /**
     * Groups scheduled recordings: a series rule for the key, or two or more episodes of the
     * same title on the same channel, makes one group. Rules without episodes still show (from [ruleTitles]).
     */
    fun group(
        items: List<com.enigmacast.domain.recording.Recording>,
        ruleKeys: Map<String, Pair<String, String>>,
    ): Pair<List<SeriesGroup>, List<com.enigmacast.domain.recording.Recording>> {
        val byKey = items.sortedBy { it.beginEpochSec }.groupBy(::keyOf)
        val groups = ArrayList<SeriesGroup>()
        val singles = ArrayList<com.enigmacast.domain.recording.Recording>()
        for ((key, list) in byKey) {
            val rule = ruleKeys[key]
            if (rule != null || list.size >= 2) {
                val first = list.first()
                groups += SeriesGroup(key, rule?.first ?: SeriesDetector.cleanTitle(first.title).ifBlank { first.title }, first.channelName.ifBlank { rule?.second.orEmpty() }, list, rule != null)
            } else {
                singles += list
            }
        }
        for ((key, rule) in ruleKeys) if (key !in byKey) groups += SeriesGroup(key, rule.first, rule.second, emptyList(), true)
        return groups.sortedBy { it.next?.beginEpochSec ?: Long.MAX_VALUE } to singles
    }
}
