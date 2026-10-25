package com.enigmacast.domain.player

/**
 * The title block at the top left of the film/episode OSD (Netflix style): the TMDB logo (or
 * the name as text), the episode code and name, and a short plot.
 */
data class VodTitleInfo(
    val title: String,
    /** TMDB logo in the app language, else English, else language-free; null shows [title] as text. */
    val logoUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String = "",
    val overview: String = "",
) {
    val isEpisode: Boolean get() = season != null && season > 0 && episode != null && episode > 0
}

object VodTitleCard {
    /** Show the title block this long when playback starts (the bottom controls stay hidden). */
    const val START_PEEK_MS = 6_000L

    /** Plot lines on screen. */
    const val OVERVIEW_LINES = 3

    private val EPISODE_CODE = Regex("""(?i)^.*?\bS\d{1,2}\s*[EX]\s*\d{1,4}\b\s*[-–—:·.|]*\s*""")
    private val SPACES = Regex("""\s+""")

    /**
     * The episode's own name from a playlist title: "Monster - S01E03 - The Trial" → "The Trial".
     * A title that is only the code (or the series name, or an id) gives "".
     */
    fun episodeName(raw: String?, seriesName: String = "", id: String = ""): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == id) return ""
        val stripped = text.replace(EPISODE_CODE, "").trim().trim('-', '–', '—', ':', '·', '|').trim()
        if (stripped.isEmpty()) return ""
        if (seriesName.isNotBlank() && stripped.equals(seriesName.trim(), ignoreCase = true)) return ""
        return stripped
    }

    /** The first non-blank plot (TMDB before the playlist's), on one paragraph. */
    fun overview(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() }?.replace(SPACES, " ")?.trim().orEmpty()

    /** TMDB's name for the episode unless it is TMDB's placeholder ("Episode 3", "Aflevering 3"). */
    fun preferEpisodeName(tmdb: String?, playlist: String, number: Int?): String {
        val t = tmdb?.trim().orEmpty()
        val placeholder = t.isEmpty() || (number != null && t.matches(Regex("""(?i)^\D{0,16}\s*$number$"""))) || t == "E$number"
        return if (placeholder) playlist.ifBlank { t } else t
    }
}
