package com.enigmacast.domain.player

import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * The subtitle choice for live TV (satellite own tuner and IPTV live), kept per profile in the
 * settings and synced with them. A track is remembered by what it is (language, kind, label),
 * not by its index: the next channel lists its tracks in another order.
 *
 * [AUTO] is the state before a choice was ever made; live TV then follows Settings › Playback
 * (subtitles on picks the first track), as before.
 */
@Serializable
data class LiveSubtitles(
    val mode: Mode = Mode.AUTO,
    /** ISO 639-1 where known ("nl"), else the stream's own code; empty when the track had none. */
    val language: String = "",
    /** The track's own label from the stream, when it had one. */
    val label: String = "",
    /** Sample MIME type (DVB, teletext, WebVTT …), so "Dutch · teletext" beats "Dutch · DVB" when that was chosen. */
    val mime: String = "",
    /** Subtitles for the deaf and hard of hearing. */
    val hearing: Boolean = false,
) {
    enum class Mode { AUTO, OFF, ON }

    /** One text track of the playing stream; [id] is the player's `group:track`. */
    data class Track(
        val id: String,
        val language: String = "",
        val label: String = "",
        val mime: String = "",
        val hearing: Boolean = false,
    )

    /** What a new live channel does with its subtitles. */
    sealed interface Decision {
        /** No live choice yet: the old behaviour (Settings › Playback). */
        data object Default : Decision
        data object Off : Decision
        data class Show(val id: String) : Decision
        /** On, but this channel has no text track (yet). */
        data object NoTrack : Decision
    }

    companion object {
        val OFF = LiveSubtitles(Mode.OFF)

        /** The choice the viewer just made on [track] (null = off). */
        fun of(track: Track?): LiveSubtitles =
            if (track == null) OFF
            else LiveSubtitles(Mode.ON, normalizeLanguage(track.language), track.label.trim(), track.mime, track.hearing)

        /**
         * The track [preference] asks for among [tracks]: the same language first (then the same
         * kind, label and hearing flag); without that language the first track, so "on" stays on.
         */
        fun decide(preference: LiveSubtitles, tracks: List<Track>): Decision = when (preference.mode) {
            Mode.AUTO -> Decision.Default
            Mode.OFF -> Decision.Off
            Mode.ON -> pick(preference, tracks)?.let { Decision.Show(it.id) } ?: Decision.NoTrack
        }

        fun pick(preference: LiveSubtitles, tracks: List<Track>): Track? {
            if (tracks.isEmpty()) return null
            val language = normalizeLanguage(preference.language)
            val sameLanguage = if (language.isBlank()) tracks.filter { normalizeLanguage(it.language).isBlank() }
            else tracks.filter { normalizeLanguage(it.language) == language }
            val pool = sameLanguage.ifEmpty { if (language.isBlank()) tracks else return tracks.first() }
            // The best score; on a tie the first of them, as the stream lists them.
            var best = pool.first()
            var bestScore = score(preference, best)
            for (track in pool.drop(1)) {
                val value = score(preference, track)
                if (value > bestScore) { best = track; bestScore = value }
            }
            return best
        }

        private fun score(preference: LiveSubtitles, track: Track): Int {
            var score = 0
            if (preference.mime.isNotBlank() && preference.mime.equals(track.mime, ignoreCase = true)) score += 4
            if (preference.label.isNotBlank() && preference.label.equals(track.label.trim(), ignoreCase = true)) score += 2
            if (preference.hearing == track.hearing) score += 1
            return score
        }

        private val iso3: Map<String, String> by lazy {
            val map = HashMap<String, String>()
            for (code in Locale.getISOLanguages()) {
                val three = runCatching { Locale(code).isO3Language }.getOrNull().orEmpty()
                if (three.isNotBlank()) map[three] = code
            }
            // Bibliographic codes that broadcasts use next to the terminology ones above.
            map += mapOf(
                "dut" to "nl", "ger" to "de", "fre" to "fr", "chi" to "zh", "cze" to "cs", "gre" to "el",
                "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu",
                "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
                "tib" to "bo", "wel" to "cy", "scc" to "sr", "scr" to "hr",
            )
            map
        }

        /** "dut", "nld", "NL", "nl-NL" → "nl". Unknown codes stay as they are (lower case). */
        fun normalizeLanguage(code: String?): String {
            val raw = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (raw.isBlank() || raw == "und" || raw == "unk") return ""
            val base = raw.substringBefore('-').substringBefore('_')
            return when (base.length) {
                2 -> base
                3 -> iso3[base] ?: base
                else -> base
            }
        }
    }
}
