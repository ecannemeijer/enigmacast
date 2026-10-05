package com.enigmacast.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

private val Background = Color(0xFF080C12)
private val Accent = Color(0xFFE8B84A)
private val Surface = Color(0xFF161C27)

private val Colors = darkColorScheme(
    background = Background,
    onBackground = Color.White,
    surface = Surface,
    onSurface = Color.White,
    primary = Accent,
    onPrimary = Color(0xFF1A1404),
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
            focusedContentColor = Color(0xFF1A1404),
        ),
    ) {
        Text(text = text, fontSize = 20.sp)
    }
}
