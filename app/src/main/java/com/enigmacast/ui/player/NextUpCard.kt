package com.enigmacast.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.requestWhenReady

/**
 * "Next episode" in the bottom-right corner during the last minute of an episode, for every
 * series player. Play (focused when the card appears) starts the next episode at once;
 * Cancel hides the card and lets the episode end. The countdown follows the playback.
 */
@Composable
fun NextUpCard(
    title: String,
    subtitle: String?,
    image: String?,
    seconds: Int,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
    /** Off while the OSD is open: its control keeps the focus (the card gets it when the OSD closes). */
    takeFocus: Boolean = true,
) {
    LaunchedEffect(Unit) { if (takeFocus) playFocus.requestWhenReady() }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .width(380.dp)
            .background(Color(0xF0101418), shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(128.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                if (!image.isNullOrBlank()) {
                    AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.next_up_title), color = EnigmaColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (seconds <= 0) stringResource(R.string.next_up_now) else stringResource(R.string.iptv_next_in, seconds),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NextUpButton(stringResource(R.string.next_up_play), primary = true, onClick = onPlay, edgeLeft = true, modifier = Modifier.weight(1.4f).focusRequester(playFocus))
            NextUpButton(stringResource(R.string.iptv_next_cancel), primary = false, onClick = onCancel, edgeRight = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NextUpButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier, edgeLeft: Boolean = false, edgeRight: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed = remember { booleanArrayOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                if (edgeLeft) left = FocusRequester.Cancel
                if (edgeRight) right = FocusRequester.Cancel
            }
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!ok) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) pressed[0] = true
                    // A press that began elsewhere (the OK that opened the card) does not count.
                    KeyEventType.KeyUp -> if (pressed[0]) { pressed[0] = false; onClick() }
                }
                true
            }
            .focusable(interactionSource = interaction)
            .background(
                when {
                    focused -> Color.White
                    primary -> Color.White.copy(alpha = 0.18f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
                shape,
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
