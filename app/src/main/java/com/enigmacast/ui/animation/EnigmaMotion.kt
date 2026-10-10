package com.enigmacast.ui.animation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf
import com.enigmacast.domain.model.MotionQuality

/**
 * Durations and flags for focus, sidebar, screens and the channel banner.
 * [animatorScale] is Android's animator duration scale; 0 turns decorative motion off.
 */
data class MotionSpec(
    val focusMs: Int,
    val cardMs: Int,
    val sidebarMs: Int,
    val screenMs: Int,
    val osdMs: Int,
    val cardScale: Float,
    val glow: Boolean,
    val shadows: Boolean,
    val animate: Boolean,
)

object EnigmaMotion {
    const val FOCUS_MS = 160
    const val CARD_MS = 200
    const val SIDEBAR_MS = 280
    const val SCREEN_MS = 300
    const val OSD_MS = 220
    const val CARD_SCALE = 1.07f

    fun spec(quality: MotionQuality, animatorScale: Float): MotionSpec {
        if (animatorScale <= 0f) {
            return MotionSpec(
                focusMs = 0,
                cardMs = 0,
                sidebarMs = 0,
                screenMs = 0,
                osdMs = 0,
                cardScale = 1f,
                glow = false,
                shadows = false,
                animate = false,
            )
        }
        val scale = animatorScale.coerceIn(0.01f, 2f)
        fun ms(base: Int): Int = (base * scale).toInt().coerceAtLeast(1)
        return when (quality) {
            MotionQuality.PERFORMANCE -> MotionSpec(
                focusMs = ms(FOCUS_MS / 2),
                cardMs = ms(CARD_MS / 2),
                sidebarMs = ms(SIDEBAR_MS / 2),
                screenMs = ms(SCREEN_MS / 2),
                osdMs = ms(OSD_MS / 2),
                cardScale = 1.03f,
                glow = false,
                shadows = false,
                animate = true,
            )
            MotionQuality.PREMIUM -> MotionSpec(
                focusMs = ms(FOCUS_MS),
                cardMs = ms(CARD_MS),
                sidebarMs = ms(SIDEBAR_MS),
                screenMs = ms(SCREEN_MS),
                osdMs = ms(OSD_MS),
                cardScale = CARD_SCALE,
                glow = true,
                shadows = true,
                animate = true,
            )
        }
    }
}

val LocalMotionSpec = compositionLocalOf { EnigmaMotion.spec(MotionQuality.PREMIUM, 1f) }

/** Focus colour and size. Snaps when motion is off. */
fun MotionSpec.focusTween(): AnimationSpec<Float> =
    if (animate) tween(durationMillis = focusMs, easing = FastOutSlowInEasing) else snap()

/** The menu highlight glides to the next row. A bit slower than a colour change. */
fun MotionSpec.travelTween(): AnimationSpec<Float> =
    if (animate) tween(durationMillis = (focusMs * 1.8f).toInt().coerceAtLeast(1), easing = FastOutSlowInEasing) else snap()
