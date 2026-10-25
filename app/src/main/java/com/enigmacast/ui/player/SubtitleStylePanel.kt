package com.enigmacast.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.player.SubtitleBackground
import com.enigmacast.domain.player.SubtitleColor
import com.enigmacast.domain.player.SubtitleSize
import com.enigmacast.domain.player.SubtitleStyle
import com.enigmacast.playback.SubtitleStyleStore
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.glassSheen
import com.enigmacast.ui.theme.requestWhenReady

/**
 * OSD › Settings › Subtitle style: size, colour and background, with a live sample line on a
 * dark picture. Left/Right (or OK) changes a row; it applies at once and is saved for every
 * player. Only the drawing changes: playback is never restarted or moved.
 */
@Composable
internal fun SubtitleStylePopup(anchor: PopupPositionProvider, background: Color, onClose: () -> Unit) {
    val style by SubtitleStyleStore.style.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Popup(popupPositionProvider = anchor, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        NavigationSoundsForWindow()
        Column(
            Modifier
                .width(440.dp)
                .background(background, RoundedCornerShape(14.dp))
                .glassSheen(RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.osd_subtitle_style), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp))
            SubtitleSample(style, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            Spacer(Modifier.height(4.dp))
            StyleRow(
                label = stringResource(R.string.subtitle_size),
                value = stringResource(sizeLabel(style.size)),
                first = true, last = false, focus = first,
            ) { step -> SubtitleStyleStore.set(style.copy(size = SubtitleStyle.cycle(SubtitleSize.entries, style.size, step))) }
            StyleRow(
                label = stringResource(R.string.subtitle_color),
                value = stringResource(colorLabel(style.color)),
                first = false, last = false,
            ) { step -> SubtitleStyleStore.set(style.copy(color = SubtitleStyle.cycle(SubtitleColor.entries, style.color, step))) }
            StyleRow(
                label = stringResource(R.string.subtitle_background),
                value = stringResource(backgroundLabel(style.background)),
                first = false, last = true,
            ) { step -> SubtitleStyleStore.set(style.copy(background = SubtitleStyle.cycle(SubtitleBackground.entries, style.background, step))) }
            Text(stringResource(R.string.subtitle_style_hint), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
    }
}

/** "Medium · White · Transparent" for the Settings row. */
@Composable
internal fun subtitleStyleSummary(style: SubtitleStyle): String = listOf(
    stringResource(sizeLabel(style.size)),
    stringResource(colorLabel(style.color)),
    stringResource(backgroundLabel(style.background)),
).joinToString(" · ")

/** The sample line on a dark, video-like picture, at the size it will have on screen. */
@Composable
internal fun SubtitleSample(style: SubtitleStyle, modifier: Modifier = Modifier) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val fontSize = with(LocalDensity.current) { (screenHeight * style.size.heightFraction).dp.toSp() }
    Box(
        modifier
            .height(118.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xFF34506B), Color(0xFF1B2533), Color(0xFF5A4A35))),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SubtitleText(stringResource(R.string.subtitle_sample), style, fontSize)
    }
}

/** One subtitle line drawn as the player draws it: box, or outline and shadow. */
@Composable
internal fun SubtitleText(text: String, style: SubtitleStyle, fontSize: androidx.compose.ui.unit.TextUnit) {
    val color = Color(style.color.argb)
    if (style.background == SubtitleBackground.BOX) {
        Text(
            text, color = color, fontSize = fontSize, textAlign = TextAlign.Center, lineHeight = fontSize * 1.2f,
            modifier = Modifier.background(Color(SubtitleStyle.BOX_ARGB), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    } else {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text, fontSize = fontSize, textAlign = TextAlign.Center, lineHeight = fontSize * 1.2f,
                style = TextStyle(color = Color.Black, drawStyle = Stroke(width = fontSize.value * 0.28f)),
            )
            Text(
                text, fontSize = fontSize, textAlign = TextAlign.Center, lineHeight = fontSize * 1.2f,
                style = TextStyle(color = color, shadow = Shadow(Color.Black, Offset(0f, 2f), blurRadius = 6f)),
            )
        }
    }
}

/** "Size      ‹ Medium ›": Left/Right steps, OK steps forward. */
@Composable
private fun StyleRow(label: String, value: String, first: Boolean, last: Boolean, focus: FocusRequester? = null, onStep: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed = remember { booleanArrayOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .focusProperties {
                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                if (first) up = FocusRequester.Cancel
                if (last) down = FocusRequester.Cancel
            }
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(50))
            .onKeyEvent { e ->
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (e.type == KeyEventType.KeyDown) onStep(if (e.key == Key.DirectionLeft) -1 else 1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when (e.type) {
                            KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) pressed[0] = true
                            KeyEventType.KeyUp -> if (pressed[0]) { pressed[0] = false; onStep(1) }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (focused) Color.Black else Color.White
        Text(label, color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("‹  $value  ›", color = color, fontSize = 15.sp)
    }
}

private fun sizeLabel(size: SubtitleSize) = when (size) {
    SubtitleSize.SMALL -> R.string.subtitle_size_small
    SubtitleSize.MEDIUM -> R.string.subtitle_size_medium
    SubtitleSize.LARGE -> R.string.subtitle_size_large
}

private fun colorLabel(color: SubtitleColor) = when (color) {
    SubtitleColor.WHITE -> R.string.subtitle_color_white
    SubtitleColor.YELLOW -> R.string.subtitle_color_yellow
}

private fun backgroundLabel(background: SubtitleBackground) = when (background) {
    SubtitleBackground.BOX -> R.string.subtitle_background_box
    SubtitleBackground.NONE -> R.string.subtitle_background_none
}
