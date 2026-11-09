package com.enigmacast.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.tuner.ChannelListNavigation
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.ui.components.LivePanelWidths
import com.enigmacast.ui.components.ensureVisible
import com.enigmacast.ui.components.TrackRows
import com.enigmacast.ui.theme.wrapListEnds
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.focusListRow
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.recording.RecordingDot
import com.enigmacast.ui.recording.isRecording
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * OpenPLi-style channel list: bouquet title, numbered rows with now/progress, a description
 * panel for the focused channel, and red/green/yellow/blue hints. Drawn in Compose with
 * the PLi-HD palette — no receiver OSD, no third-party skin assets.
 */
@Composable
internal fun PliChannelListOverlay(
    ui: RemoteDisplayUiState,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    onZap: (BouquetEntry) -> Unit,
    onChooseBouquet: (BouquetEntry) -> Unit,
    guideActions: GuideActions,
    channelList: com.enigmacast.ui.components.PresetList,
    bouquetList: com.enigmacast.ui.components.PresetList,
    modifier: Modifier = Modifier,
) {
    val own = ui.own
    var focusedIndex by remember(own.bouquetReference, own.channels.size) { mutableIntStateOf(-1) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    val focused = own.channels.getOrNull(focusedIndex)
        ?: own.channels.firstOrNull { BouquetChannels.sameService(it.reference, own.channel?.reference.orEmpty()) }
    val focusedEvent = focused?.let { NowEvents.lookup(ui.nowEvents, it.reference, nowSec) }
    Column(
        modifier
            .fillMaxHeight()
            .background(PliHd.TranspBlack)
            .border(1.dp, PliHd.DarkGrey),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.bouquetPanel) {
                BouquetPanel(
                    bouquets = ui.bouquets,
                    activeReference = own.bouquetReference,
                    onChoose = onChooseBouquet,
                    preset = bouquetList,
                )
                Box(Modifier.fillMaxHeight().width(1.dp).background(PliHd.DarkGrey))
            }
            PliChannelColumn(
                ui = ui,
                numberOf = numberOf,
                picon = picon,
                cachedPicon = cachedPicon,
                onZap = onZap,
                onOpenEpg = guideActions.onOpenChannelEpg,
                preset = channelList,
                nowSec = nowSec,
                onFocused = { focusedIndex = it },
                modifier = Modifier.weight(1f),
            )
            if (ui.epgChannel != null) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(PliHd.DarkGrey))
                ChannelEpgPanel(ui = ui, actions = guideActions)
            } else {
                Box(Modifier.fillMaxHeight().width(1.dp).background(PliHd.DarkGrey))
                PliDescriptionPanel(
                    channel = focused,
                    event = focusedEvent,
                    nowSec = nowSec,
                    modifier = Modifier.width(LivePanelWidths.CHANNELS_WITH_GUIDE).fillMaxHeight(),
                )
            }
        }
        PliColourHints(
            Modifier
                .fillMaxWidth()
                .background(PliHd.NoTransBg)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PliChannelColumn(
    ui: RemoteDisplayUiState,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    onZap: (BouquetEntry) -> Unit,
    onOpenEpg: (BouquetEntry) -> Unit,
    preset: com.enigmacast.ui.components.PresetList,
    nowSec: Long,
    onFocused: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val own = ui.own
    val channels = own.channels
    val current = own.channel?.reference.orEmpty()
    val playingIndex = channels.indexOfFirst { it.reference.equals(current, ignoreCase = true) }
    val listState = preset.state
    preset.TrackRows()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val lastIndex = channels.lastIndex
    val epgOpen = ui.epgChannel != null
    val anchor = ChannelListNavigation.channelFocusIndex(channels, ui.listFocusReference.ifBlank { current })
    LaunchedEffect(ui.listFocusToken, own.bouquetReference, channels.size) {
        if (channels.isEmpty() || ui.bouquetPanel || epgOpen) return@LaunchedEffect
        preset.ensureVisible(anchor, channels.size)
        onFocused(anchor)
        focus.requestWhenReady()
    }
    Column(modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        Text(own.bouquetName.ifBlank { stringResource(R.string.osd_channels) }, color = PliHd.SecondFg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.pli_list_hint), color = PliHd.Grey, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (channels.isEmpty()) {
            Text(stringResource(R.string.osd_no_channels_loaded), color = PliHd.Grey, fontSize = 13.sp)
            return@Column
        }
        com.enigmacast.ui.theme.MinimalScrolling {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                itemsIndexed(channels, key = { index, entry -> "$index:${entry.reference}" }) { index, entry ->
                    CompactListRow(
                        onClick = { onZap(entry) },
                        selected = if (playingIndex >= 0) index == playingIndex else BouquetChannels.sameService(entry.reference, current),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .focusRequesterIf(index == anchor, focus)
                            .focusRequesterIf(index == 0, firstFocus)
                            .focusRequesterIf(index == lastIndex && lastIndex > 0, lastFocus)
                            .onFocusChanged { if (it.isFocused) onFocused(index) }
                            .wrapListEnds(
                                index = index,
                                lastIndex = lastIndex,
                                onWrapToFirst = { scope.launch { firstFocus.focusListRow(listState, 0) } },
                                onWrapToLast = { scope.launch { lastFocus.focusListRow(listState, lastIndex) } },
                            )
                            .onPreviewKeyEvent { event ->
                                if (event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onOpenEpg(entry)
                                true
                            },
                    ) { focused ->
                        PliChannelRow(
                            entry = entry,
                            number = numberOf(entry),
                            event = NowEvents.lookup(ui.nowEvents, entry.reference, nowSec),
                            nowSec = nowSec,
                            focused = focused,
                            recording = isRecording(ui.recordingReferences, entry.reference),
                            picon = picon,
                            cachedPicon = cachedPicon,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.PliChannelRow(
    entry: BouquetEntry,
    number: Int,
    event: NowEvent?,
    nowSec: Long,
    focused: Boolean,
    recording: Boolean,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
) {
    val titleColor = PliHd.Foreground
    val secondary = if (focused) PliHd.SecondFg else PliHd.Grey
    Text(if (number > 0) "%04d".format(number) else "", color = titleColor, fontSize = 13.sp, fontFamily = FontFamily.SansSerif, maxLines = 1, modifier = Modifier.width(44.dp))
    Picon(entry, picon, cachedPicon, Modifier.width(40.dp).height(24.dp), fallbackSize = 8.sp)
    Spacer(Modifier.width(8.dp))
    if (recording) RecordingDot(Modifier.padding(end = 6.dp))
    Text(entry.name, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.36f))
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(0.64f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(event?.let { pliTime(it.beginEpochSec) }.orEmpty(), color = secondary, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            Text(event?.title.orEmpty(), color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (event != null) {
            Box(
                Modifier
                    .padding(start = 40.dp, top = 2.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(PliHd.ProgressBg),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(event.progress(nowSec))
                        .fillMaxHeight()
                        .background(Brush.verticalGradient(PliHd.ProgressFill)),
                )
            }
        }
    }
}

@Composable
private fun PliDescriptionPanel(
    channel: BouquetEntry?,
    event: NowEvent?,
    nowSec: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pli_event_info), color = PliHd.SecondFg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
        if (channel == null) {
            Text(stringResource(R.string.osd_no_programme_data), color = PliHd.Grey, fontSize = 13.sp)
            return@Column
        }
        Text(channel.name, color = PliHd.Foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (event == null) {
            Text(stringResource(R.string.osd_no_programme_data), color = PliHd.Grey, fontSize = 13.sp)
            return@Column
        }
        Text(event.title, color = PliHd.SecondFg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            buildString {
                append(pliTime(event.beginEpochSec))
                if (event.durationSec > 0) {
                    append(" – ")
                    append(pliTime(event.beginEpochSec + event.durationSec))
                    append("  (")
                    append((event.durationSec / 60).toInt())
                    append(" min)")
                }
            },
            color = PliHd.Grey,
            fontSize = 12.sp,
        )
        Box(Modifier.fillMaxWidth().height(6.dp).background(PliHd.ProgressBg)) {
            Box(
                Modifier
                    .fillMaxWidth(event.progress(nowSec))
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(PliHd.ProgressFill)),
            )
        }
        Text(
            stringResource(R.string.pli_event_open_epg),
            color = PliHd.Grey,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
fun PliColourHints(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        PliHint(PliHd.Red, stringResource(R.string.pli_hint_red))
        PliHint(PliHd.Green, stringResource(R.string.pli_hint_green))
        PliHint(PliHd.Yellow, stringResource(R.string.pli_hint_yellow))
        PliHint(PliHd.Blue, stringResource(R.string.pli_hint_blue))
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.pli_hint_ok), color = PliHd.Grey, fontSize = 12.sp)
    }
}

@Composable
private fun PliHint(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(14.dp).height(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, color = PliHd.Foreground, fontSize = 12.sp, fontFamily = FontFamily.SansSerif, maxLines = 1)
    }
}

private val PliClock = DateTimeFormatter.ofPattern("HH:mm")

private fun pliTime(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(PliClock)
