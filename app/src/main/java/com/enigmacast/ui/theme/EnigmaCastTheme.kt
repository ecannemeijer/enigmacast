package com.enigmacast.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** Colour tokens of the active GUI skin ([ActiveSkin]). Read in composition, so a skin change repaints at once. */
object EnigmaColors {
    private val p get() = ActiveSkin.current.palette
    val Canvas get() = p.canvas
    val Navy get() = p.card
    val Card get() = p.card
    val CardBorder get() = p.cardBorder
    val Raised get() = p.raised
    val Selected get() = p.selected
    val Focus get() = p.focus
    val OnFocus get() = p.onFocus
    val Accent get() = p.accent
    val Cyan get() = p.highlight
    val Violet get() = p.secondary
    val OnAccent get() = p.onAccent
    val Text get() = p.text
    val Muted get() = p.muted
    val Subtle get() = p.subtle
    val Success get() = p.success
    val Danger get() = p.danger
    val DangerFocus get() = p.dangerFocus
    val Scrim get() = p.osdFill.copy(alpha = 0.88f)
    val GlassEdge get() = p.osdEdge

    val SafeHorizontal = 48.dp
    val SafeVertical = 27.dp

    /** [transparencyPercent] 0 is this colour solid; 100 has no fill. */
    /** [color] at the alpha of [scrim], for gradients that keep the OSD transparency. */
    fun scrimOf(color: Color, scrim: Color): Color = color.copy(alpha = scrim.alpha)

    fun scrim(transparencyPercent: Int): Color {
        val solid = (100 - transparencyPercent.coerceIn(0, 100)) / 100f
        return p.osdFill.copy(alpha = solid)
    }
}

object EnigmaType {
    val Hero = 32.sp
    val Title = 22.sp
    val Body = 16.sp
    val Label = 13.sp
    val Caption = 11.sp
    /** Weight of titles and labels that stand out; set by the skin. */
    val Strong: FontWeight get() = ActiveSkin.current.strongWeight
}

@Composable
fun EnigmaCastTheme(content: @Composable () -> Unit) {
    val p = ActiveSkin.current.palette
    val colors = darkColorScheme(
        background = p.canvas,
        onBackground = p.text,
        surface = p.card,
        onSurface = p.text,
        primary = p.accent,
        onPrimary = p.onAccent,
        border = p.focus,
    )
    val skin = ActiveSkin.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Glass skins: decode the photo once, off the main thread; a flat skin frees it again.
    androidx.compose.runtime.LaunchedEffect(skin.glass?.backdrop) {
        val res = skin.glass?.backdrop
        if (res == null) GlassBackdrop.release() else GlassBackdrop.load(context, res)
    }
    val family = skin.fontFamily
    if (family == null) {
        // Flat skins: exactly the theme they always had.
        MaterialTheme(colorScheme = colors, content = content)
        return
    }
    val base = androidx.tv.material3.Typography()
    val typography = androidx.tv.material3.Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        // Text that does not take a typography style (tv Text's default, foundation text fields).
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.tv.material3.LocalTextStyle provides androidx.tv.material3.LocalTextStyle.current.copy(fontFamily = family),
            androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = family),
            content = content,
        )
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = if (compact) PaddingValues(horizontal = 14.dp, vertical = 4.dp) else PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = if (compact) 1.04f else 1.08f),
        colors = ButtonDefaults.colors(
            containerColor = EnigmaColors.Raised,
            contentColor = EnigmaColors.Text,
            focusedContainerColor = EnigmaColors.Focus,
            focusedContentColor = EnigmaColors.OnFocus,
        ),
        shape = ButtonDefaults.shape(shape = skinShape(if (compact) 8.dp else 12.dp)),
        border = skinButtonBorder(),
    ) {
        Text(text = text, fontSize = if (compact) 13.sp else 20.sp, fontWeight = EnigmaType.Strong)
    }
}
