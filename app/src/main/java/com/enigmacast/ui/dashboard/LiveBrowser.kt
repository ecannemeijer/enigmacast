package com.enigmacast.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.components.EnigmaGlassPanel
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.remote.Picon
import com.enigmacast.ui.remote.rememberGuideClock
import com.enigmacast.ui.theme.ChannelsLoadingDialog
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.focusListRow
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.wrapListEnds
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Loads the bouquet the dashboard is showing. Does not zap. */
@Composable
internal fun BindGuide(medium: GuideMedium, model: StreamGuideViewModel) {
    LaunchedEffect(medium) {
        model.show(medium)
    }
    LaunchedEffect(medium) {
        model.refresh()
        var elapsed = 0
        while (true) {
            delay(2_000)
            elapsed += 2_000
            if (elapsed >= 30_000) {
                elapsed = 0
                model.refresh()
                model.grid.refresh()
            } else {
                model.refreshIfServiceChanged()
            }
        }
    }
}

/**
 * Wave-style channel browser: bouquets, the channel list, and a preview.
 * Moving focus does not tune. OK uses [StreamGuideViewModel.tune].
 */
@Composable
internal fun LiveBrowser(
    showMiniPlayer: Boolean,
    listFocus: FocusRequester,
    bouquetFocus: FocusRequester,
    menuFocus: FocusRequester,
    menuFocused: () -> Boolean,
    focusPlayingChannel: Boolean,
    onPlayingChannelFocused: () -> Unit,
    onMiniPlayer: () -> Unit,
    onFullscreen: () -> Unit,
    medium: GuideMedium,
    guideModel: StreamGuideViewModel,
) {
    BindGuide(medium, guideModel)
    val channels = guideModel.channels
    val grid by guideModel.grid.state.collectAsState()
    val nowSec = rememberGuideClock()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val bouquet = guideModel.selectedBouquetReference
    val tunedIndex = channels.indexOfFirst { BouquetChannels.sameService(it.reference, guideModel.tunedReference) }
        .let { if (it >= 0) it else 0 }
    var focusedIndex by remember(bouquet) { mutableIntStateOf(tunedIndex) }
    LaunchedEffect(channels.size, bouquet) {
        if (channels.isEmpty()) return@LaunchedEffect
        if (focusedIndex !in channels.indices) focusedIndex = tunedIndex.coerceIn(0, channels.lastIndex)
    }
    LaunchedEffect(focusPlayingChannel, channels.isNotEmpty()) {
        if (!focusPlayingChannel || channels.isEmpty()) return@LaunchedEffect
        guideModel.focusTunedInGrid()
        focusedIndex = tunedIndex.coerceIn(0, channels.lastIndex)
        if (menuFocused()) {
            listState.scrollToItem(focusedIndex)
            listFocus.requestWhenReady()
        }
        onPlayingChannelFocused()
    }
    val selected = channels.getOrNull(focusedIndex)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BouquetColumn(
            bouquets = guideModel.bouquets,
            selectedReference = bouquet,
            bouquetFocus = bouquetFocus,
            settingsFocus = menuFocus,
            openToken = if (guideModel.bouquets.isEmpty()) 0 else 1,
            requestFocus = false,
            onPreview = guideModel::previewBouquet,
            onClose = { runCatching { listFocus.requestFocus() } },
            modifier = Modifier.width(210.dp).fillMaxHeight(),
        )
        Column(Modifier.weight(1.2f).fillMaxHeight()) {
            Text(
                guideModel.bouquetName.ifBlank { stringResource(R.string.channels) },
                color = EnigmaColors.Text,
                fontSize = EnigmaType.Title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
            )
            if (channels.isEmpty()) {
                Text(stringResource(R.string.osd_no_channels_loaded), color = EnigmaColors.Muted, fontSize = 14.sp)
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(channels, key = { index, entry -> "$index:${entry.reference}" }) { index, entry ->
                    val events = grid.eventsOf(entry)
                    val now = events.firstOrNull { it.isAiring(nowSec) }
                    val next = now?.let { current -> events.firstOrNull { it.beginEpochSec >= current.endEpochSec } }
                    CompactListRow(
                        onClick = { guideModel.tune(entry, onMiniPlayer, onFullscreen) },
                        selected = BouquetChannels.sameService(entry.reference, guideModel.tunedReference),
                        modifier = Modifier
                            .then(if (index == tunedIndex) Modifier.focusRequester(listFocus) else Modifier)
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(if (index == channels.lastIndex && channels.lastIndex > 0) Modifier.focusRequester(lastFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedIndex = index }
                            .wrapListEnds(
                                index = index,
                                lastIndex = channels.lastIndex,
                                onWrapToFirst = { scope.launch { firstFocus.focusListRow(listState, 0) } },
                                onWrapToLast = { scope.launch { lastFocus.focusListRow(listState, channels.lastIndex) } },
                            )
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                                runCatching { bouquetFocus.requestFocus() }
                                true
                            },
                    ) { focused ->
                        ChannelLine(entry, guideModel.numberOf(entry), now, next, nowSec, focused, guideModel::picon, guideModel::cachedPicon)
                    }
                }
            }
        }
        val motion = LocalMotionSpec.current
        AnimatedContent(
            targetState = selected?.reference.orEmpty(),
            modifier = Modifier.width(320.dp).fillMaxHeight(),
            transitionSpec = {
                if (motion.animate && motion.focusMs > 0) {
                    fadeIn(tween(motion.focusMs)) togetherWith fadeOut(tween(motion.focusMs))
                } else {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                }
            },
            label = "channelPreview",
        ) { reference ->
            val channel = channels.firstOrNull { it.reference == reference }
            PreviewPane(
                showMiniPlayer = showMiniPlayer,
                channel = channel,
                events = channel?.let(grid::eventsOf).orEmpty(),
                nowSec = nowSec,
                status = guideModel.status.asString().takeIf { guideModel.status.isNotEmpty() }.orEmpty(),
                picon = guideModel::picon,
                cachedPicon = guideModel::cachedPicon,
            )
        }
    }
    if (guideModel.loadingChannels) {
        ChannelsLoadingDialog(stringResource(R.string.channels_please_wait))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ChannelLine(
    entry: BouquetEntry,
    number: Int,
    now: GuideEvent?,
    next: GuideEvent?,
    nowSec: Long,
    focused: Boolean,
    picon: suspend (BouquetEntry) -> androidx.compose.ui.graphics.ImageBitmap?,
    cachedPicon: (BouquetEntry) -> androidx.compose.ui.graphics.ImageBitmap?,
) {
    val secondary = if (focused) EnigmaColors.OnFocus.copy(alpha = 0.85f) else EnigmaColors.Muted
    Text(
        if (number > 0) "$number" else "",
        fontSize = 12.sp,
        modifier = Modifier.width(32.dp),
        maxLines = 1,
    )
    Picon(entry, picon, cachedPicon, Modifier.width(40.dp).height(24.dp), fallbackSize = 8.sp)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
        Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = when {
            now != null && next != null -> now.title + "  ·  " + next.title
            now != null -> now.title
            else -> ""
        }
        if (line.isNotBlank()) {
            Text(line, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (now != null) {
            EnigmaProgramProgress(now.progress(nowSec), Modifier.padding(top = 3.dp), emphasized = !focused)
        }
    }
}

@Composable
private fun PreviewPane(
    showMiniPlayer: Boolean,
    channel: BouquetEntry?,
    events: List<GuideEvent>,
    nowSec: Long,
    status: String,
    picon: suspend (BouquetEntry) -> androidx.compose.ui.graphics.ImageBitmap?,
    cachedPicon: (BouquetEntry) -> androidx.compose.ui.graphics.ImageBitmap?,
) {
    val now = events.firstOrNull { it.isAiring(nowSec) }
    val next = now?.let { current -> events.firstOrNull { it.beginEpochSec >= current.endEpochSec } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            if (showMiniPlayer) {
                MiniStreamPlayer(Modifier.fillMaxSize())
            } else if (channel != null) {
                Picon(channel, picon, cachedPicon, Modifier.fillMaxSize().padding(24.dp))
            }
        }
        EnigmaGlassPanel(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    channel?.name ?: stringResource(R.string.channels),
                    color = EnigmaColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    Text(now.title, color = EnigmaColors.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    EnigmaProgramProgress(now.progress(nowSec))
                    val description = now.description
                    if (description.isNotBlank()) {
                        Text(description, color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(stringResource(R.string.home_no_programme), color = EnigmaColors.Muted, fontSize = 14.sp)
                }
                if (next != null) {
                    Text(stringResource(R.string.home_next), color = EnigmaColors.Cyan, fontSize = 11.sp)
                    Text(next.title, color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (status.isNotBlank()) {
                    Text(status, color = EnigmaColors.Accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
