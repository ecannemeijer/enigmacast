package com.enigmacast.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enigmacast.domain.model.OsdSkinId

/**
 * Colour tokens of a GUI skin. Every screen paints with these names (through [EnigmaColors]),
 * so a skin is data only: no screen knows which skin is active.
 */
data class SkinPalette(
    val canvas: Color,
    val card: Color,
    val cardBorder: Color,
    val raised: Color,
    val selected: Color,
    val focus: Color,
    val onFocus: Color,
    val accent: Color,
    val highlight: Color,
    val secondary: Color,
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val subtle: Color,
    val success: Color,
    val danger: Color,
    val dangerFocus: Color,
    /** Fill behind the OSD; transparency is applied on top of it. */
    val osdFill: Color,
    /** Second colour of the OSD gradient; equal to [osdFill] for a flat bar. */
    val osdFillEnd: Color,
    val osdEdge: Color,
    /** Gradient drawn on a focused row instead of the flat [focus] colour (OpenPLi's sel.png), or null. */
    val focusBrush: List<Color>? = null,
)

/** How a focused control is marked. */
enum class FocusStyle {
    /** Solid focus colour behind the control. */
    FILL,
    /** Quiet fill with a bright outline (OLED friendly). */
    OUTLINE,
    /** Focus fill plus a soft light edge. */
    GLOW,
}

/** The layout of the channel banner. Separate from the colours, so layouts and palettes can be mixed. */
enum class OsdLayout {
    /** Rounded floating card (EnigmaCast). */
    CARD,
    /** Full-width bottom bar with gradient, channel number + name left, clock right (OpenPLi PLi-HD). */
    PLI_BAR,
    /** Flat bar with a coloured channel block (MetrixHD). */
    FLAT,
    /** Card without border on pure black, few tags (Midnight). */
    MINIMAL,
    /** Translucent card with a coloured edge (Aurora). */
    GLASS,
}

data class Skin(
    val id: OsdSkinId,
    val palette: SkinPalette,
    /** Multiplies every corner radius in the app: 0 is square, 1 is the EnigmaCast default. */
    val cornerScale: Float,
    /** Weight of titles and labels that stand out. */
    val strongWeight: FontWeight,
    val focusStyle: FocusStyle,
    val osd: OsdLayout,
)

object Skins {
    val EnigmaCast = Skin(
        id = OsdSkinId.STANDARD,
        palette = SkinPalette(
            canvas = Color(0xFF080D1C), card = Color(0xFF10192B), cardBorder = Color(0xFF243049),
            raised = Color(0xFF162033), selected = Color(0xFF1A2740), focus = Color(0xFF4188FF),
            onFocus = Color.White, accent = Color(0xFF4188FF), highlight = Color(0xFF24D9FF),
            secondary = Color(0xFF916BFF), onAccent = Color.White, text = Color.White,
            muted = Color(0xFFB7C0D0), subtle = Color(0xFF7D8696), success = Color(0xFF6FCF97),
            danger = Color(0xFFFF7A70), dangerFocus = Color(0xFFB3261E),
            osdFill = Color(0xFF10192B), osdFillEnd = Color(0xFF10192B), osdEdge = Color(0x4024D9FF),
        ),
        cornerScale = 1f, strongWeight = FontWeight.SemiBold, focusStyle = FocusStyle.FILL, osd = OsdLayout.CARD,
    )

    /**
     * OpenPLi PLi-HD (littlesat/skin-PLiHD, PLi-HD/skin.xml): near-black #101214 screens,
     * a glossy blue-grey selection bar (buttons/sel.png) with secondFG yellow text, yellow
     * clock and accents, grey secondary text, square corners, one semi-bold sans font.
     */
    val OpenPli = Skin(
        id = OsdSkinId.OPENPLI,
        palette = SkinPalette(
            canvas = Color(0xFF101214), card = Color(0xFF16181B), cardBorder = Color(0xFF33333A),
            raised = Color(0xFF242424), selected = Color(0xFF303240), focus = Color(0xFF2B3745),
            onFocus = Color(0xFFFCC000), accent = Color(0xFFFFC000), highlight = Color(0xFFFCC000),
            secondary = Color(0xFF879CE1), onAccent = Color.Black, text = Color(0xFFF0F0F0),
            muted = Color(0xFFAAAAAA), subtle = Color(0xFF555556), success = Color(0xFF56C856),
            danger = Color(0xFFFF4A3C), dangerFocus = Color(0xFF4C0000),
            osdFill = Color(0xFF101214), osdFillEnd = Color(0xFF101214), osdEdge = Color(0xFF33333A),
            focusBrush = listOf(Color(0xFF939FAB), Color(0xFF647080), Color(0xFF2B3745), Color(0xFF061220), Color(0xFF0D1E2E), Color(0xFF435260)),
        ),
        cornerScale = 0f, strongWeight = FontWeight.SemiBold, focusStyle = FocusStyle.FILL, osd = OsdLayout.PLI_BAR,
    )

    /** OLED black: no grey canvas, white outline focus, teal accent. */
    val Midnight = Skin(
        id = OsdSkinId.MIDNIGHT,
        palette = SkinPalette(
            canvas = Color.Black, card = Color(0xFF0A0A0A), cardBorder = Color(0xFF262626),
            raised = Color(0xFF141414), selected = Color(0xFF1A1A1A), focus = Color(0xFF1F1F1F),
            onFocus = Color.White, accent = Color(0xFF00C9A7), highlight = Color(0xFF7CF5DF),
            secondary = Color(0xFF8C8C8C), onAccent = Color.Black, text = Color(0xFFF2F2F2),
            muted = Color(0xFFAAAAAA), subtle = Color(0xFF6E6E6E), success = Color(0xFF4FD08A),
            danger = Color(0xFFFF6B6B), dangerFocus = Color(0xFF9E1C1C),
            osdFill = Color.Black, osdFillEnd = Color.Black, osdEdge = Color(0xFF262626),
        ),
        cornerScale = 0.8f, strongWeight = FontWeight.Medium, focusStyle = FocusStyle.OUTLINE, osd = OsdLayout.MINIMAL,
    )

    /** openATV MetrixHD: flat dark tiles, square corners, one coloured accent. */
    val Metrix = Skin(
        id = OsdSkinId.METRIX,
        palette = SkinPalette(
            canvas = Color(0xFF111111), card = Color(0xFF1B1B1B), cardBorder = Color(0xFF1B1B1B),
            raised = Color(0xFF262626), selected = Color(0xFF2E2E2E), focus = Color(0xFF0E9AD7),
            onFocus = Color.White, accent = Color(0xFF0E9AD7), highlight = Color(0xFF52C3F2),
            secondary = Color(0xFFBDBDBD), onAccent = Color.White, text = Color.White,
            muted = Color(0xFFBDBDBD), subtle = Color(0xFF808080), success = Color(0xFF7CC242),
            danger = Color(0xFFE5483C), dangerFocus = Color(0xFFA4281E),
            osdFill = Color(0xFF1B1B1B), osdFillEnd = Color(0xFF1B1B1B), osdEdge = Color(0xFF0E9AD7),
        ),
        cornerScale = 0f, strongWeight = FontWeight.Normal, focusStyle = FocusStyle.FILL, osd = OsdLayout.FLAT,
    )

    /** Glassy modern: deep indigo, violet/teal accents, large radii, glowing focus. */
    val Aurora = Skin(
        id = OsdSkinId.AURORA,
        palette = SkinPalette(
            canvas = Color(0xFF0A0F1F), card = Color(0xFF161C35), cardBorder = Color(0x5594A3FF),
            raised = Color(0xFF1E2645), selected = Color(0xFF262F56), focus = Color(0xFF7C5CFF),
            onFocus = Color.White, accent = Color(0xFF9B83FF), highlight = Color(0xFF3DE0C9),
            secondary = Color(0xFFFF7EB6), onAccent = Color.White, text = Color.White,
            muted = Color(0xFFC3C8E6), subtle = Color(0xFF8189AD), success = Color(0xFF5BE3A8),
            danger = Color(0xFFFF7A8A), dangerFocus = Color(0xFFB3264A),
            osdFill = Color(0xFF1D2550), osdFillEnd = Color(0xFF102A3A), osdEdge = Color(0x803DE0C9),
        ),
        cornerScale = 1.4f, strongWeight = FontWeight.SemiBold, focusStyle = FocusStyle.GLOW, osd = OsdLayout.GLASS,
    )

    val all: List<Skin> = listOf(EnigmaCast, OpenPli, Midnight, Metrix, Aurora)

    fun of(id: OsdSkinId): Skin = all.firstOrNull { it.id == id } ?: EnigmaCast
}

/** The skin the app paints with. Snapshot state: changing it recomposes every screen at once. */
object ActiveSkin {
    var current: Skin by mutableStateOf(Skins.EnigmaCast)
        private set

    fun apply(id: OsdSkinId) {
        val next = Skins.of(id)
        if (next != current) current = next
    }
}

/** A rounded shape whose radius follows the active skin. */
fun skinShape(radius: Dp): RoundedCornerShape = RoundedCornerShape(radius * ActiveSkin.current.cornerScale)

/** A shape for [skin] instead of the active one (previews). */
fun skinShape(radius: Dp, skin: Skin): RoundedCornerShape = RoundedCornerShape(radius * skin.cornerScale)

internal val ZeroDp = 0.dp

/** Focus outline of TV buttons for the active skin's [FocusStyle]. */
@androidx.compose.runtime.Composable
fun skinButtonBorder(): androidx.tv.material3.ButtonBorder {
    val skin = ActiveSkin.current
    val shape = skinShape(10.dp)
    return when (skin.focusStyle) {
        FocusStyle.FILL -> androidx.tv.material3.ButtonDefaults.border()
        FocusStyle.OUTLINE -> androidx.tv.material3.ButtonDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, skin.palette.text), shape = shape),
        )
        FocusStyle.GLOW -> androidx.tv.material3.ButtonDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, skin.palette.highlight.copy(alpha = 0.7f)), shape = shape),
        )
    }
}

/** Focus edge for the app's own focusable rows; nothing for [FocusStyle.FILL]. */
fun androidx.compose.ui.Modifier.skinFocusEdge(focused: Boolean, radius: Dp): androidx.compose.ui.Modifier {
    if (!focused) return this
    val skin = ActiveSkin.current
    return when (skin.focusStyle) {
        FocusStyle.FILL -> this
        FocusStyle.OUTLINE -> border(2.dp, skin.palette.text, skinShape(radius))
        FocusStyle.GLOW -> border(2.dp, skin.palette.highlight.copy(alpha = 0.7f), skinShape(radius))
    }
}
