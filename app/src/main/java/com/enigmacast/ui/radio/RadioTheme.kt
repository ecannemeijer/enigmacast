package com.enigmacast.ui.radio

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.enigmacast.ui.theme.ActiveSkin
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.FocusStyle
import com.enigmacast.ui.theme.skinShape

/** Corner radius that follows the active skin (OpenPLi is square, others keep Velixa rounding). */
internal fun radioShape(radius: Dp) = skinShape(radius)

/** Border/ring colour for a focusable Radio control. */
internal fun radioFocusBorderColor(focused: Boolean, idle: Color = EnigmaColors.CardBorder): Color {
    if (!focused) return idle
    return when (ActiveSkin.current.focusStyle) {
        FocusStyle.OUTLINE -> EnigmaColors.Text
        FocusStyle.GLOW -> EnigmaColors.Cyan.copy(alpha = 0.85f)
        FocusStyle.FILL -> EnigmaColors.Focus
    }
}

/** Filled focus (dropdown rows, dialog items, icon buttons). Uses OpenPLi's glossy bar when present. */
internal fun Modifier.radioFocusBackground(focused: Boolean, shape: Shape): Modifier {
    if (!focused) return this
    val palette = ActiveSkin.current.palette
    val brush = palette.focusBrush
    return if (brush != null) background(Brush.verticalGradient(brush), shape)
    else background(palette.focus, shape)
}

internal fun radioOnFocus(focused: Boolean, otherwise: Color = EnigmaColors.Text): Color =
    if (focused) EnigmaColors.OnFocus else otherwise
