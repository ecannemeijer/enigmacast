package com.enigmacast.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enigmacast.domain.model.OsdSkinId

/**
 * Look of the left navigation menu for one [Skin]. Colours come from that skin's
 * palette, so the rail matches the pages, dialogs and OSD; a few metrics (spacing,
 * corner radius, focus scale) are tuned per skin so a square OpenPLi rail does not
 * grow like a rounded Lumen one.
 */
data class NavChrome(
    /** Top-to-bottom fill of the rail (ignored on glass, which uses the frosted photo). */
    val gradient: List<Color>,
    val edge: Color,
    val edgeWidth: Dp,
    /** Radius of the rail's right-hand corners. */
    val endRadius: Dp,
    val itemSpacing: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    /** Fill of the sliding focus pill; several colours become a vertical gradient (OpenPLi). */
    val cursorFill: List<Color>,
    val cursorEdge: Color?,
    val cursorEdgeWidth: Dp,
    /** Quiet fill of the open section while focus is on the page. */
    val selectedFill: Color,
    /** Thin bar on the open section, always visible. */
    val selectedBar: Color,
    val selectedBarWidth: Dp,
    val focusIconScale: Float,
    val focusLabelScale: Float,
    val letterSpacing: TextUnit,
    val headerLetterSpacing: TextUnit,
)

/** [NavChrome] for a skin, or the active skin when none is given (previews, tests). */
object SkinNav {
    fun of(skin: Skin = ActiveSkin.current): NavChrome {
        val p = skin.palette
        val flat = skin.cornerScale == 0f
        val base = NavChrome(
            gradient = listOf(p.raised.copy(alpha = 0.96f), p.canvas.copy(alpha = 0.98f)),
            edge = p.cardBorder.copy(alpha = 0.45f),
            edgeWidth = 1.dp,
            endRadius = 18.dp * skin.cornerScale,
            itemSpacing = 4.dp,
            horizontalPadding = 8.dp,
            verticalPadding = 12.dp,
            cursorFill = p.focusBrush ?: listOf(p.focus),
            cursorEdge = when (skin.focusStyle) {
                FocusStyle.OUTLINE -> p.text
                FocusStyle.GLOW -> p.highlight.copy(alpha = 0.7f)
                FocusStyle.FILL -> null
            },
            cursorEdgeWidth = if (skin.focusStyle == FocusStyle.FILL) 0.dp else 2.dp,
            selectedFill = p.selected.copy(alpha = 0.92f),
            selectedBar = p.accent,
            selectedBarWidth = 3.dp,
            focusIconScale = when {
                flat -> 1f
                skin.focusStyle == FocusStyle.GLOW -> 1.10f
                else -> 1.08f
            },
            focusLabelScale = when {
                flat -> 1f
                skin.focusStyle == FocusStyle.GLOW -> 1.06f
                else -> 1.05f
            },
            letterSpacing = 0.sp,
            headerLetterSpacing = 0.2.sp,
        )
        return when (skin.id) {
            OsdSkinId.STANDARD -> base.copy(
                gradient = listOf(p.raised.copy(alpha = 0.94f), p.canvas.copy(alpha = 0.94f)),
                edge = p.highlight.copy(alpha = 0.20f),
                selectedBar = p.highlight,
            )
            OsdSkinId.OPENPLI -> base.copy(
                gradient = listOf(Color(0xF00E1A30), Color(0xF00A1428)),
                edge = p.cardBorder,
                endRadius = 0.dp,
                itemSpacing = 2.dp,
                selectedBar = p.highlight,
                letterSpacing = 0.15.sp,
                headerLetterSpacing = 0.4.sp,
            )
            OsdSkinId.MIDNIGHT -> base.copy(
                gradient = listOf(Color(0xF00A0A0A), Color(0xF0000000)),
                edge = p.cardBorder,
                cursorFill = listOf(p.focus),
                selectedBar = p.accent,
                selectedBarWidth = 2.dp,
                focusIconScale = 1.04f,
                focusLabelScale = 1.03f,
                letterSpacing = 0.2.sp,
            )
            OsdSkinId.METRIX -> base.copy(
                gradient = listOf(p.card, p.canvas),
                edge = Color.Transparent,
                edgeWidth = 0.dp,
                endRadius = 0.dp,
                itemSpacing = 2.dp,
                selectedBar = p.accent,
                letterSpacing = 0.1.sp,
            )
            OsdSkinId.AURORA -> base.copy(
                gradient = listOf(p.raised.copy(alpha = 0.95f), p.canvas.copy(alpha = 0.96f)),
                edge = p.highlight.copy(alpha = 0.28f),
                itemSpacing = 6.dp,
                letterSpacing = 0.15.sp,
                headerLetterSpacing = 0.35.sp,
            )
            OsdSkinId.GLASS_EARTH, OsdSkinId.GLASS_WAVE -> base.copy(
                edge = p.cardBorder,
                selectedBar = p.highlight,
                letterSpacing = 0.1.sp,
                headerLetterSpacing = 0.3.sp,
            )
            OsdSkinId.SLATE_CALM -> base.copy(
                gradient = listOf(p.raised, p.canvas),
                edge = p.accent.copy(alpha = 0.25f),
                selectedBar = p.accent,
                focusIconScale = 1.06f,
                focusLabelScale = 1.04f,
                letterSpacing = 0.15.sp,
            )
            OsdSkinId.LUMEN -> base.copy(
                gradient = listOf(Color(0xF01E1A24), Color(0xF0121016)),
                edge = p.accent.copy(alpha = 0.28f),
                itemSpacing = 6.dp,
                letterSpacing = 0.2.sp,
                headerLetterSpacing = 0.4.sp,
            )
            OsdSkinId.CINEMA_BLACK -> base.copy(
                gradient = listOf(Color(0xF016181C), Color(0xF0000000)),
                edge = p.accent.copy(alpha = 0.22f),
                selectedBar = p.accent,
                selectedBarWidth = 2.dp,
                letterSpacing = 0.15.sp,
                headerLetterSpacing = 0.5.sp,
            )
            OsdSkinId.ARENA -> base.copy(
                gradient = listOf(Color(0xF0141C16), Color(0xF00B110D)),
                edge = p.cardBorder,
                endRadius = 3.dp,
                itemSpacing = 3.dp,
                selectedBar = p.accent,
                selectedBarWidth = 4.dp,
                focusIconScale = 1.06f,
                focusLabelScale = 1.04f,
                letterSpacing = 0.4.sp,
                headerLetterSpacing = 0.8.sp,
            )
            OsdSkinId.STARBRIDGE -> base.copy(
                gradient = listOf(Color(0xF017141F), Color(0xF0000000)),
                edge = p.secondary.copy(alpha = 0.45f),
                itemSpacing = 6.dp,
                selectedBar = p.accent,
                letterSpacing = 0.25.sp,
                headerLetterSpacing = 0.5.sp,
            )
        }
    }
}
