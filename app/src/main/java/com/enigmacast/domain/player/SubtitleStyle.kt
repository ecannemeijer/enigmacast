package com.enigmacast.domain.player

/** Subtitle text size: a fraction of the video's height, so the mini player scales too. */
enum class SubtitleSize(val heightFraction: Float) {
    SMALL(0.040f),
    MEDIUM(0.052f),
    LARGE(0.066f),
}

enum class SubtitleColor(val argb: Int) {
    WHITE(0xFFFFFFFF.toInt()),
    YELLOW(0xFFFFE14D.toInt()),
}

/** BOX: a dark box behind each line. NONE: no box, the text keeps a dark outline and shadow. */
enum class SubtitleBackground { BOX, NONE }

/**
 * How text subtitles look (embedded tracks and the app's own SRT/VTT alike). One global
 * choice, saved on the device. Changing it only redraws: playback never restarts or seeks.
 */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.MEDIUM,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.NONE,
) {
    fun encode(): String = "${size.name}|${color.name}|${background.name}"

    /** Text size in px for a video area [viewHeightPx] high (never below 12 px). */
    fun textSizePx(viewHeightPx: Float): Float = (viewHeightPx * size.heightFraction).coerceAtLeast(12f)

    companion object {
        val DEFAULT = SubtitleStyle()
        /** Box colour: black, 80 % opaque. */
        const val BOX_ARGB = 0xCC000000.toInt()
        const val OUTLINE_ARGB = 0xFF000000.toInt()

        /** Unknown or missing parts fall back to the default, so an older or damaged value still loads. */
        fun decode(value: String?): SubtitleStyle {
            if (value.isNullOrBlank()) return DEFAULT
            val p = value.split('|')
            fun <T : Enum<T>> pick(i: Int, all: Array<T>, fallback: T): T = all.firstOrNull { it.name == p.getOrNull(i) } ?: fallback
            return SubtitleStyle(
                pick(0, SubtitleSize.entries.toTypedArray(), DEFAULT.size),
                pick(1, SubtitleColor.entries.toTypedArray(), DEFAULT.color),
                pick(2, SubtitleBackground.entries.toTypedArray(), DEFAULT.background),
            )
        }

        /** The neighbour of [value] in [all] ([step] -1 or +1), wrapping around. */
        fun <T> cycle(all: List<T>, value: T, step: Int): T {
            val i = all.indexOf(value).coerceAtLeast(0)
            return all[(i + step).mod(all.size)]
        }

        /**
         * Lines of a cue, word-wrapped to [maxWidth] with [measure]; explicit line breaks stay.
         * Very long words are kept whole on their own line.
         */
        fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
            val out = mutableListOf<String>()
            text.replace("\r\n", "\n").split('\n').forEach { raw ->
                val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) return@forEach
                var line = ""
                for (w in words) {
                    val candidate = if (line.isEmpty()) w else "$line $w"
                    if (line.isNotEmpty() && measure(candidate) > maxWidth) {
                        out += line
                        line = w
                    } else {
                        line = candidate
                    }
                }
                if (line.isNotEmpty()) out += line
            }
            return out
        }
    }
}
