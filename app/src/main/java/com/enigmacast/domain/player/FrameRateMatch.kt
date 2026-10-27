package com.enigmacast.domain.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which display refresh rate suits a video frame rate, and which display mode provides it.
 * Pure logic; [com.enigmacast.playback.AutoFrameRate] applies the result to the window.
 */
object FrameRateMatch {
    /** One display mode: [id] is Display.Mode.modeId, [hz] its refresh rate. */
    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    /** DVB (satellite, cable, terrestrial in Europe) is 25 fps interlaced: 50 Hz. */
    const val DVB_FALLBACK_FPS = 50f

    /**
     * The refresh rate for [fps] (Format.frameRate, <= 0 when unknown): 23.976, 24, 50, 59.94 or 60.
     * Unknown on an Enigma2 stream ([dvb]) is 50 Hz; unknown elsewhere, or an unusual rate, is null
     * (leave the display alone).
     */
    fun target(fps: Float, dvb: Boolean): Float? {
        val rate = if (fps > 0f && !fps.isNaN()) fps else if (dvb) DVB_FALLBACK_FPS else return null
        return when {
            rate in 23.5f..23.99f -> 23.976f
            rate in 23.99f..24.5f -> 24f
            rate in 24.5f..25.5f || rate in 49f..51f -> 50f
            rate in 29.5f..29.985f || rate in 59f..59.97f -> 59.94f
            rate in 29.985f..30.5f || rate in 59.97f..61f -> 60f
            else -> null
        }
    }

    /**
     * True when [hz] shows [target] without judder: the same rate or a whole multiple (120 Hz plays
     * 24 and 60 fps). 23.976 and 24, and 59.94 and 60, count as the same (one frame in 1000 s).
     */
    fun fits(hz: Float, target: Float): Boolean = multiple(hz, target) != null

    private fun multiple(hz: Float, target: Float): Int? {
        if (hz <= 0f || target <= 0f) return null
        val k = (hz / target).roundToInt()
        if (k !in 1..5) return null
        return k.takeIf { abs(hz - target * k) <= 0.08f * k }
    }

    /**
     * The mode to switch to for [target], or null to stay: [current] already fits, or no mode with
     * the [resolution] of the original mode fits. The same rate beats a multiple, then the closest.
     */
    fun pick(modes: List<Mode>, current: Mode, resolution: Mode, target: Float): Mode? {
        if (current.width == resolution.width && current.height == resolution.height && fits(current.hz, target)) return null
        return modes
            .filter { it.width == resolution.width && it.height == resolution.height }
            .mapNotNull { mode -> multiple(mode.hz, target)?.let { k -> Triple(mode, k, abs(mode.hz - target * k)) } }
            .minWithOrNull(compareBy<Triple<Mode, Int, Float>> { it.second }.thenBy { it.third })
            ?.first
            ?.takeIf { it.id != current.id }
    }

    /** "50.00 Hz" style text for the log. */
    fun hz(value: Float): String = String.format(java.util.Locale.ROOT, "%.3f", value)
}
