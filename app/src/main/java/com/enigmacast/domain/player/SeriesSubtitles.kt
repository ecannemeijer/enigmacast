package com.enigmacast.domain.player

import com.enigmacast.domain.player.LiveSubtitles.Mode
import com.enigmacast.domain.subsource.SubSourceHit
import com.enigmacast.domain.subsource.SubSourceLanguages

/**
 * The subtitle choice per series (IPTV and torrent episodes), so the next episode and a resume
 * start with the same subtitles: off, or a language/kind/label. Like [LiveSubtitles] a track is
 * matched by what it is, never by its index. A SubSource file is remembered by its language.
 */
object SeriesSubtitles {
    /** [LiveSubtitles.mime] of a choice that was a SubSource file. */
    const val SUBSOURCE = "subsource"
    /** Series kept; the oldest choice goes first. */
    const val MAX_SERIES = 300

    fun iptvKey(providerId: String, seriesId: String): String = "iptv:$providerId:$seriesId"
    fun torrentKey(kind: String, id: String): String = "torrent:$kind:$id"

    fun ofExternal(hit: SubSourceHit): LiveSubtitles =
        LiveSubtitles(Mode.ON, isoOf(hit.language), "", SUBSOURCE, hit.hearingImpaired)

    fun isoOf(language: String): String =
        SubSourceLanguages.bySlug(language.trim().lowercase())?.iso ?: LiveSubtitles.normalizeLanguage(language)

    /**
     * The embedded track for [preference]: the same language (then kind/label/hearing). Unlike
     * live TV no "any track" fallback: a missing language is looked for among the SubSource files.
     */
    fun pickEmbedded(preference: LiveSubtitles?, tracks: List<LiveSubtitles.Track>): LiveSubtitles.Track? {
        if (preference == null || preference.mode != Mode.ON || preference.mime == SUBSOURCE) return null
        val language = LiveSubtitles.normalizeLanguage(preference.language)
        if (language.isBlank()) return LiveSubtitles.pick(preference, tracks)
        val same = tracks.filter { LiveSubtitles.normalizeLanguage(it.language) == language }
        return if (same.isEmpty()) null else LiveSubtitles.pick(preference, same)
    }

    /** A SubSource file in the remembered language (hearing-impaired flag as before when there is a choice). */
    fun pickExternal(preference: LiveSubtitles?, hits: List<SubSourceHit>): SubSourceHit? {
        if (preference == null || preference.mode != Mode.ON) return null
        val language = LiveSubtitles.normalizeLanguage(preference.language)
        if (language.isBlank()) return null
        val same = hits.filter { isoOf(it.language) == language }
        return same.firstOrNull { it.hearingImpaired == preference.hearing } ?: same.firstOrNull()
    }

    /** [map] with [choice] for [key], newest last, at most [MAX_SERIES] series. */
    fun remember(map: Map<String, LiveSubtitles>, key: String, choice: LiveSubtitles): Map<String, LiveSubtitles> {
        val next = LinkedHashMap(map)
        next.remove(key)
        next[key] = choice
        while (next.size > MAX_SERIES) next.remove(next.keys.first())
        return next
    }
}
