package com.enigmacast.ui.remote

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.ui.theme.EnigmaColors
import kotlinx.coroutines.launch

/**
 * The last eight recent live channels as a compact horizontal strip with their logos, plus a
 * final "Geschiedenis wissen" tile. The strip itself owns the D-pad: Left/Right scroll and
 * move the selection, OK plays, Up returns to the options and Back closes the OSD.
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
    loadLogo: suspend (LastChannel) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val visible = channels.filter { it.reference != currentReference }.take(MAX_RECENT)
    val total = visible.size + 1
    var selected by remember { mutableIntStateOf(0) }
    var activity by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusToken, visible) {
        selected = 0
        listState.scrollToItem(0)
        // This is a leaf focus target, so request the box itself. requestFocus(Enter) would
        // look for a focusable child and leave the previous player focus in place.
        repeat(10) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // No interaction for five seconds: put the live picture back.
    LaunchedEffect(focusToken, activity) {
        kotlinx.coroutines.delay(5_000)
        onClose()
    }
    fun moveTo(index: Int) {
        selected = index.coerceIn(0, total - 1)
        scope.launch { listState.animateScrollToItem(selected) }
    }
    Box(
        modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                activity++
                when (event.key) {
                    Key.DirectionLeft -> { moveTo(selected - 1); true }
                    Key.DirectionRight -> { moveTo(selected + 1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (selected < visible.size) visible[selected].let(onChoose) else onClear()
                        true
                    }
                    Key.DirectionUp -> { onBackToOptions(); true }
                    Key.Back -> { onClose(); true }
                    else -> false
                }
            },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .background(EnigmaColors.Scrim, RoundedCornerShape(12.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Recente zenders",
                color = EnigmaColors.Text,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp),
            )
            if (visible.isEmpty()) {
                Text(
                    "Nog geen recente zenders.",
                    color = EnigmaColors.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                )
            }
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(visible, key = { _, channel -> channel.reference }) { index, channel ->
                    RecentChannelTile(channel, loadLogo, selected == index)
                }
                item(key = "clear-history") {
                    ClearHistoryTile(selected == visible.size)
                }
            }
        }
    }
}

@Composable
private fun RecentChannelTile(
    channel: LastChannel,
    loadLogo: suspend (LastChannel) -> ImageBitmap?,
    selected: Boolean,
) {
    Column(
        Modifier
            .width(112.dp)
            .height(66.dp)
            .background(
                if (selected) EnigmaColors.Selected else EnigmaColors.Card,
                RoundedCornerShape(9.dp),
            )
            .border(
                1.dp,
                if (selected) EnigmaColors.Focus else EnigmaColors.CardBorder,
                RoundedCornerShape(9.dp),
            )
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RecentLogo(channel, loadLogo, Modifier.width(42.dp).height(28.dp))
        Text(
            channel.name.ifBlank { channel.reference },
            color = if (selected) Color.White else EnigmaColors.Text,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecentLogo(channel: LastChannel, loadLogo: suspend (LastChannel) -> ImageBitmap?, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, channel.reference, channel.logo) {
        value = runCatching { loadLogo(channel) }.getOrNull()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                channel.name.trim().take(2).uppercase().ifBlank { "TV" },
                color = EnigmaColors.Subtle,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ClearHistoryTile(selected: Boolean) {
    Column(
        Modifier
            .width(112.dp)
            .height(66.dp)
            .background(
                if (selected) EnigmaColors.Selected else EnigmaColors.Card,
                RoundedCornerShape(9.dp),
            )
            .border(
                1.dp,
                if (selected) EnigmaColors.Focus else EnigmaColors.CardBorder,
                RoundedCornerShape(9.dp),
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = EnigmaColors.Muted, modifier = Modifier.size(20.dp))
        Text(
            "Geschiedenis wissen",
            color = if (selected) Color.White else EnigmaColors.Muted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private const val MAX_RECENT = 8
