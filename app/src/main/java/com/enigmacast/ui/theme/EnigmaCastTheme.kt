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

private val Background = Color(0xFF080D1C)
private val Accent = Color(0xFF4188FF)
private val Surface = Color(0xFF10192B)

/** Dashboard palette: midnight canvas, electric blue accent, cyan and violet highlights. */
object EnigmaColors {
    val Canvas = Color(0xFF080D1C)
    val Navy = Color(0xFF10192B)
    val Card = Color(0xFF10192B)
    val CardBorder = Color(0xFF243049)
    val Raised = Color(0xFF162033)
    val Selected = Color(0xFF1A2740)
    val Focus = Color(0xFF4188FF)
    val OnFocus = Color.White
    val Accent = Color(0xFF4188FF)
    val Cyan = Color(0xFF24D9FF)
    val Violet = Color(0xFF916BFF)
    val OnAccent = Color.White
    val Text = Color.White
    val Muted = Color(0xFFB7C0D0)
    val Subtle = Color(0xFF7D8696)
    val Success = Color(0xFF6FCF97)
    val Danger = Color(0xFFFF7A70)
    val DangerFocus = Color(0xFFB3261E)
    val Scrim = Color(0xE010192B)
    val GlassEdge = Color(0x4024D9FF)

    val SafeHorizontal = 48.dp
    val SafeVertical = 27.dp

    /** [transparencyPercent] 0 is this colour solid; 100 has no fill. */
    fun scrim(transparencyPercent: Int): Color {
        val solid = (100 - transparencyPercent.coerceIn(0, 100)) / 100f
        return Color(0xFF10192B).copy(alpha = solid)
    }
}

object EnigmaType {
    val Hero = 32.sp
    val Title = 22.sp
    val Body = 16.sp
    val Label = 13.sp
    val Caption = 11.sp
}

private val Colors = darkColorScheme(
    background = Background,
    onBackground = Color.White,
    surface = Surface,
    onSurface = Color.White,
    primary = Accent,
    onPrimary = Color.White,
    border = Accent,
)

@Composable
fun EnigmaCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        colors = ButtonDefaults.colors(
            containerColor = Surface,
            contentColor = Color.White,
            focusedContainerColor = Accent,
            focusedContentColor = Color.White,
        ),
    ) {
        Text(text = text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}
