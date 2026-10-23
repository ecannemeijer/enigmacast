package com.enigmacast.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.requestWhenReady

/**
 * The last eight recent live channels as a horizontal strip with their logos, plus a final
 * "Geschiedenis wissen" tile. Opened by pressing Down once more from the quick-options bar;
 * Up returns to the options, Back closes the OSD.
 */
@Composable
fun RecentChannelsBar(
    channels: List<LastChannel>,
    currentReference: String,
    focusToken: Int,
    onChoose: (LastChannel) -> Unit,
    onClear: () -> Unit,
    onBackToOptions: () -> Unit,
    onClose: () -> Unit,
    logo: @Composable (LastChannel, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = channels.filter { it.reference != currentReference }.take(MAX_RECENT)
    val focus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    LaunchedEffect(focusToken, visible) {
        if (visible.isNotEmpty()) focus.requestWhenReady() else clearFocus.requestWhenReady()
    }
    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { onBackToOptions(); true }
                    Key.Back -> { onClose(); true }
                    else -> false
                }
            },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(EnigmaColors.Scrim, RoundedCornerShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Recente zenders",
                color = EnigmaColors.Text,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )
            if (visible.isEmpty()) {
                Text(
                    "Nog geen recente zenders.",
                    color = EnigmaColors.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(visible, key = { _, channel -> channel.reference }) { index, channel ->
                    RecentChannelTile(
                        channel = channel,
                        logo = logo,
                        onClick = { onChoose(channel) },
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    )
                }
                item(key = "clear-history") {
                    ClearHistoryTile(
                        onClick = onClear,
                        modifier = if (visible.isEmpty()) Modifier.focusRequester(clearFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentChannelTile(
    channel: LastChannel,
    logo: @Composable (LastChannel, Modifier) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier
            .width(142.dp)
            .height(104.dp)
            .background(
                if (focused) EnigmaColors.Selected else EnigmaColors.Card,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (focused) EnigmaColors.Focus else EnigmaColors.CardBorder,
                RoundedCornerShape(10.dp),
            )
            .focusable(interactionSource = interaction)
            .onPreviewKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(width = 76.dp, height = 46.dp), contentAlignment = Alignment.Center) {
            logo(channel, Modifier.fillMaxSize())
        }
        Text(
            channel.name.ifBlank { channel.reference },
            color = if (focused) Color.White else EnigmaColors.Text,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ClearHistoryTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier
            .width(142.dp)
            .height(104.dp)
            .background(
                if (focused) EnigmaColors.Selected else EnigmaColors.Card,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (focused) EnigmaColors.Focus else EnigmaColors.CardBorder,
                RoundedCornerShape(10.dp),
            )
            .focusable(interactionSource = interaction)
            .onPreviewKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = EnigmaColors.Muted, modifier = Modifier.size(28.dp))
        Text(
            "Geschiedenis wissen",
            color = if (focused) Color.White else EnigmaColors.Muted,
            fontSize = 11.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private const val MAX_RECENT = 8
