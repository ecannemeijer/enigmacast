package com.enigmacast.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.enigmacast.data.channel.EquivalentChannelsState
import com.enigmacast.domain.channel.ChannelAlternative
import com.enigmacast.domain.channel.ChannelSource
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.requestWhenReady

/**
 * List of the same channel on the other source. Opens from the first quick-option tile and
 * replaces the former Search tile; choosing a row switches the live picture.
 */
@Composable
fun EquivalentChannelsPanel(
    state: EquivalentChannelsState,
    onChoose: (ChannelAlternative) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.open) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        val first = remember { FocusRequester() }
        LaunchedEffect(state.channels) {
            if (state.channels.isNotEmpty()) first.requestWhenReady()
        }
        Column(
            Modifier
                .widthIn(max = 780.dp)
                .background(EnigmaColors.Card, RoundedCornerShape(16.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(16.dp))
                .padding(vertical = 18.dp, horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Equivalent kanaal", color = EnigmaColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.currentName.isBlank()) "Kies dezelfde zender op een andere bron."
                else "Zelfde zender als ${state.currentName}",
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            when {
                state.loading -> Text("Kanalen zoeken…", color = EnigmaColors.Muted, fontSize = 15.sp)
                state.message.isNotBlank() -> Text(state.message, color = EnigmaColors.Muted, fontSize = 15.sp)
                else -> LazyColumn(Modifier.fillMaxWidth().height(420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.channels, key = { "${it.source}:${it.reference}" }) { channel ->
                        EquivalentRow(
                            channel = channel,
                            modifier = if (channel == state.channels.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                            onClick = { onChoose(channel) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EquivalentRow(channel: ChannelAlternative, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .background(if (focused) EnigmaColors.Selected else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) EnigmaColors.Focus else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (channel.source == ChannelSource.IPTV) "IPTV" else "SAT",
            color = if (channel.source == ChannelSource.IPTV) EnigmaColors.Cyan else EnigmaColors.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(Modifier.weight(1f)) {
            Text(channel.name, color = EnigmaColors.Text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(channel.subtitle, color = EnigmaColors.Subtle, fontSize = 11.sp)
        }
        Text("▶", color = EnigmaColors.Accent, fontSize = 12.sp)
    }
}
