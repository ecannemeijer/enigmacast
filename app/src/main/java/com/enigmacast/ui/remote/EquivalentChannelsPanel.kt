package com.enigmacast.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.data.channel.EquivalentChannelsState
import com.enigmacast.domain.channel.ChannelAlternative
import com.enigmacast.domain.channel.ChannelSource
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.requestWhenReady

/**
 * Equivalent channels in the same left-hand panel and row style as the live channel list.
 * The row shows source, logo, channel name and bouquet/category (plus orbital position for
 * satellite channels). Up/Down/OK are normal channel-list focus; Back closes the panel.
 */
@Composable
fun EquivalentChannelsPanel(
    state: EquivalentChannelsState,
    loadLogo: suspend (ChannelAlternative) -> ImageBitmap?,
    onChoose: (ChannelAlternative) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.open) return
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(state.channels) {
        if (state.channels.isNotEmpty()) first.requestWhenReady()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(min = 520.dp, max = 660.dp)
                .background(EnigmaColors.Scrim, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .padding(vertical = 16.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Equivalent kanaal", color = EnigmaColors.Text, fontSize = 20.sp)
            Text(
                state.currentName.ifBlank { "Zelfde zender op een andere bron" },
                color = EnigmaColors.Muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.loading) {
                Text("Kanalen zoeken…", color = EnigmaColors.Muted, fontSize = 14.sp)
            } else if (state.message.isNotBlank()) {
                Text(state.message, color = EnigmaColors.Muted, fontSize = 14.sp)
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(state.channels, key = { _, channel -> "${channel.source}:${channel.reference}" }) { index, channel ->
                        CompactListRow(
                            onClick = { onChoose(channel) },
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        ) { focused ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (channel.source == ChannelSource.IPTV) "IPTV" else "SAT",
                                    color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Subtle,
                                    fontSize = 10.sp,
                                    modifier = Modifier.width(36.dp),
                                )
                                EquivalentLogo(
                                    channel = channel,
                                    loadLogo = loadLogo,
                                    modifier = Modifier.width(44.dp).height(26.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        channel.name,
                                        color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Text,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        channel.context,
                                        color = if (focused) EnigmaColors.OnFocus.copy(alpha = 0.75f) else EnigmaColors.Muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text("›", color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Accent, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EquivalentLogo(
    channel: ChannelAlternative,
    loadLogo: suspend (ChannelAlternative) -> ImageBitmap?,
    modifier: Modifier,
) {
    val image by produceState<ImageBitmap?>(initialValue = null, channel.reference) {
        value = runCatching { loadLogo(channel) }.getOrNull()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(channel.name.trim().take(2).uppercase().ifBlank { "TV" }, color = EnigmaColors.Subtle, fontSize = 10.sp)
        }
    }
}
