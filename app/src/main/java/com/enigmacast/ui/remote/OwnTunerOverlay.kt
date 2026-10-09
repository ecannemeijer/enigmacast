package com.enigmacast.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.osdinfo.OsdInfo
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.tuner.ChannelListNavigation
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.domain.tuner.TunerUserKind
import com.enigmacast.live.TunerState
import com.enigmacast.live.TunerStatus
import com.enigmacast.live.text
import com.enigmacast.ui.osd.OsdInfoPresenter
import com.enigmacast.ui.osd.OsdSkins
import com.enigmacast.ui.recording.RecordingDot
import com.enigmacast.ui.recording.isRecording
import com.enigmacast.ui.theme.ChannelsLoadingDialog
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.focusListRow
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.wrapListEnds
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's own on-screen display for own-tuner mode. Enigma2 draws its OSD on the
 * receiver's HDMI output only, and a direct stream carries none of it, so the channel
 * banner, channel list and "tuners full" notice are drawn here.
 */
@Composable
fun OwnTunerOverlay(
    ui: RemoteDisplayUiState,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    numberOf: (BouquetEntry) -> Int,
    onZap: (BouquetEntry) -> Unit,
    onChooseBouquet: (BouquetEntry) -> Unit,
    quickBarActions: QuickBarActions,
    guideActions: GuideActions,
) {
    val own = ui.own
    val tuner = own.tuner
    Box(modifier = Modifier.fillMaxSize()) {
        val channel = own.channel
        if (own.loadingChannels) {
            ChannelsLoadingDialog(stringResource(R.string.channels_please_wait))
        } else if (tuner.status == TunerStatus.CHECKING || tuner.status == TunerStatus.STARTING) {
            StatusPill(
                text = tuner.message.asString().ifBlank { stringResource(R.string.loading) },
                color = EnigmaColors.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
            )
        }
        val tunerProblem = tuner.status == TunerStatus.FULL || tuner.status == TunerStatus.PROBLEM || tuner.status == TunerStatus.NO_PICTURE
        if (tunerProblem) {
            TunerNotice(
                tuner = tuner,
                channelName = channel?.name.orEmpty(),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (ui.numberEntry.isNotEmpty()) {
            Text(
                ui.numberEntry,
                color = EnigmaColors.Accent,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(EnigmaColors.Scrim, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (ui.notice.isNotEmpty()) {
            Text(
                ui.notice.asString(),
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(32.dp)
                    .background(EnigmaColors.Scrim, RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        val radio = channel != null && BouquetChannels.isRadioBouquet(own.bouquetReference)
        if (radio && ui.quickBar && !ui.channelList) {
            QuickOptionsBar(
                ui = ui,
                actions = quickBarActions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 44.dp, end = 44.dp, bottom = 24.dp),
            )
        }
        if (ui.banner && channel != null && !ui.channelList && !radio) {
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                ChannelBanner(
                    channel = channel,
                    number = numberOf(channel),
                    guide = own.guide?.takeIf { BouquetChannels.sameService(it.serviceReference, channel.reference) },
                    player = ui.playerState,
                    tuner = tuner,
                    details = ui.details,
                    optionsHint = !ui.bannerFromZap && !ui.quickBar,
                    recording = isRecording(ui.recordingReferences, channel.reference),
                    osdInfo = ui.osdInfo,
                    osdFields = ui.osdInfoFields,
                    osdSkin = ui.osdSkin,
                    picon = picon,
                    cachedPicon = cachedPicon,
                )
                if (ui.quickBar) {
                    QuickOptionsBar(
                        ui = ui,
                        actions = quickBarActions,
                        modifier = Modifier.padding(start = 44.dp, end = 44.dp, bottom = 24.dp),
                    )
                }
            }
        }
        if (ui.channelList) {
            ChannelListOverlay(
                ui = ui,
                numberOf = numberOf,
                picon = picon,
                cachedPicon = cachedPicon,
                onZap = onZap,
                onChooseBouquet = onChooseBouquet,
                guideActions = guideActions,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        ui.programmePrompt?.let { prompt -> ProgrammeDialog(prompt, guideActions) }
    }
}

@Composable
private fun ChannelBanner(
    channel: BouquetEntry,
    number: Int,
    guide: ChannelGuide?,
    player: PlayerState,
    tuner: TunerState,
    details: Boolean,
    optionsHint: Boolean,
    recording: Boolean,
    osdInfo: OsdInfo,
    osdFields: OsdInfoFields,
    osdSkin: OsdSkinId,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    val events = guide?.events.orEmpty()
    val current = events.firstOrNull { it.beginEpochSec <= nowSec && nowSec < it.beginEpochSec + it.durationSec }
        ?: guide?.currentEvent
    val next = current?.let { now -> events.firstOrNull { it.beginEpochSec >= now.beginEpochSec + now.durationSec } }
    Row(
        modifier = modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .fillMaxWidth()
            .background(EnigmaColors.Scrim, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Picon(channel, picon, cachedPicon, Modifier.width(116.dp).height(68.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number > 0) {
                    Text(
                        "$number",
                        color = EnigmaColors.Accent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                Text(
                    channel.name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (recording) {
                    RecordingDot(Modifier.padding(start = 8.dp))
                }
                if (tuner.status == TunerStatus.FULL) {
                    StatusPill(stringResource(R.string.osd_tuners_busy), EnigmaColors.Danger)
                }
            }
            if (current != null) {
                EventLine(current, emphasised = true)
                ProgressBar(current, nowSec)
            } else {
                Text(stringResource(R.string.osd_no_programme_data), color = EnigmaColors.Muted, fontSize = 14.sp)
            }
            if (next != null) EventLine(next, emphasised = false)
            if (details && current != null) {
                val plot = current.plot.ifBlank { current.shortDescription }
                if (plot.isNotBlank()) {
                    Text(
                        plot,
                        color = EnigmaColors.Muted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(0.85f),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val infoView = OsdInfoPresenter.present(osdInfo, osdFields)
            val resolution = videoLabel(player)
            if (!infoView.isEmpty || resolution.isNotBlank()) {
                OsdSkins.of(osdSkin).Strip(infoView, Modifier.fillMaxWidth()) {
                    if (resolution.isNotBlank()) Tag(resolution)
                }
            }
            if (player.audioLabel.isNotEmpty()) {
                val more = if (player.audioTrackCount > 1) {
                    pluralStringResource(R.plurals.osd_audio_languages, player.audioTrackCount, player.audioTrackCount)
                } else {
                    ""
                }
                Tag(stringResource(R.string.osd_audio, player.audioLabel.asString()) + more, Modifier.widthIn(max = 220.dp))
            }
            when {
                player.subtitleLabel.isNotEmpty() -> Tag(stringResource(R.string.osd_subtitles, player.subtitleLabel.asString()), Modifier.widthIn(max = 220.dp))
                player.subtitleTrackCount > 0 -> Tag(stringResource(R.string.osd_subtitles_available), Modifier.widthIn(max = 220.dp))
            }
            Text(
                stringResource(if (optionsHint) R.string.osd_banner_hint_options else R.string.osd_banner_hint),
                color = EnigmaColors.Subtle,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EventLine(event: EpgEvent, emphasised: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOf(event.beginLabel, event.endLabel).filter { it.isNotBlank() }.joinToString(" – "),
            color = if (emphasised) EnigmaColors.Accent else EnigmaColors.Muted,
            fontSize = if (emphasised) 15.sp else 13.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(118.dp),
        )
        Text(
            event.title.ifBlank { stringResource(R.string.osd_unknown_programme) },
            color = if (emphasised) Color.White else EnigmaColors.Muted,
            fontSize = if (emphasised) 15.sp else 13.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProgressBar(event: EpgEvent, nowSec: Long) {
    val fraction = if (event.durationSec > 0 && event.beginEpochSec > 0) {
        ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(EnigmaColors.Raised, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(EnigmaColors.Accent, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 168.dp)
            .background(EnigmaColors.Raised, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun videoLabel(player: PlayerState): String {
    if (player.videoHeight <= 0) return ""
    val kind = when {
        player.videoHeight >= 2000 -> "UHD"
        player.videoHeight >= 700 -> "HD"
        else -> "SD"
    }
    return "$kind ${player.videoWidth}×${player.videoHeight}"
}

@Composable
internal fun Picon(
    entry: BouquetEntry,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    fallbackSize: TextUnit = 14.sp,
) {
    var bitmap by remember(entry.reference, entry.picon) { mutableStateOf(cachedPicon(entry)) }
    LaunchedEffect(entry.reference, entry.picon) {
        if (bitmap == null) bitmap = picon(entry)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = entry.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                entry.name.take(10),
                color = EnigmaColors.Muted,
                fontSize = fallbackSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TunerNotice(tuner: TunerState, channelName: String, modifier: Modifier = Modifier) {
    val title = when (tuner.status) {
        TunerStatus.FULL -> stringResource(R.string.osd_no_tuner_free)
        TunerStatus.NO_PICTURE -> stringResource(R.string.osd_no_picture)
        else -> stringResource(R.string.osd_stream_not_started)
    }
    Column(
        modifier = modifier
            .widthIn(max = 440.dp)
            .background(EnigmaColors.Scrim, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (tuner.status == TunerStatus.NO_PICTURE) EnigmaColors.Accent else EnigmaColors.Danger, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (channelName.isNotBlank()) {
                Text("  ·  $channelName", color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(tuner.message.asString(), color = Color.White, fontSize = 13.sp, lineHeight = 17.sp)
        if (tuner.users.isNotEmpty()) {
            Text(stringResource(R.string.osd_in_use), color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            tuner.users.forEach { user ->
                val prefix = when (user.kind) {
                    TunerUserKind.LIVE -> "📺"
                    TunerUserKind.RECORDING -> "⏺"
                    TunerUserKind.STREAM -> "📱"
                }
                Text("$prefix  ${user.text().asString()}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val hint = if (tuner.status == TunerStatus.NO_PICTURE) {
            stringResource(R.string.osd_hint_no_picture)
        } else {
            stringResource(R.string.osd_hint_retry)
        }
        Text(hint, color = EnigmaColors.Subtle, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ChannelListOverlay(
    ui: RemoteDisplayUiState,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    onZap: (BouquetEntry) -> Unit,
    onChooseBouquet: (BouquetEntry) -> Unit,
    guideActions: GuideActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(EnigmaColors.Scrim),
    ) {
        if (ui.bouquetPanel) {
            BouquetPanel(
                bouquets = ui.bouquets,
                activeReference = ui.own.bouquetReference,
                onChoose = onChooseBouquet,
            )
            Box(Modifier.fillMaxHeight().width(1.dp).background(EnigmaColors.Raised))
        }
        ChannelPanel(
            ui = ui,
            numberOf = numberOf,
            picon = picon,
            cachedPicon = cachedPicon,
            onZap = onZap,
            onOpenEpg = guideActions.onOpenChannelEpg,
        )
        if (ui.epgChannel != null) {
            Box(Modifier.fillMaxHeight().width(1.dp).background(EnigmaColors.Raised))
            ChannelEpgPanel(ui = ui, actions = guideActions)
        }
    }
}

/** The bouquets, shown left of the channel list by Left; OK or Right shows that bouquet's channels. */
@Composable
internal fun BouquetPanel(
    bouquets: List<BouquetEntry>,
    activeReference: String,
    onChoose: (BouquetEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val lastIndex = bouquets.lastIndex
    val anchor = ChannelListNavigation.bouquetFocusIndex(bouquets, activeReference)
    LaunchedEffect(bouquets.size) {
        if (bouquets.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((anchor - 4).coerceAtLeast(0))
        focus.requestWhenReady()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(210.dp)
            .padding(horizontal = 10.dp, vertical = 20.dp),
    ) {
        Text(stringResource(R.string.osd_bouquets), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.osd_bouquet_choose_hint), color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (bouquets.isEmpty()) {
            Text(stringResource(R.string.osd_bouquets_loading), color = EnigmaColors.Muted, fontSize = 13.sp)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(bouquets, key = { index, entry -> "$index:${entry.reference}" }) { index, bouquet ->
                CompactListRow(
                    onClick = { onChoose(bouquet) },
                    selected = index == anchor && activeReference.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    modifier = Modifier
                        .then(if (index == anchor) Modifier.focusRequester(focus) else Modifier)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (index == lastIndex && lastIndex > 0) Modifier.focusRequester(lastFocus) else Modifier)
                        .focusProperties { left = FocusRequester.Cancel }
                        .wrapListEnds(
                            index = index,
                            lastIndex = lastIndex,
                            onWrapToFirst = { scope.launch { firstFocus.focusListRow(listState, 0) } },
                            onWrapToLast = { scope.launch { lastFocus.focusListRow(listState, lastIndex) } },
                        )
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) onChoose(bouquet)
                            true
                        },
                ) {
                    Text(bouquet.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Channel list: number, picon, name and what is on now (start time, title, thin progress
 * bar) on one dense row, so about 15 channels fit on a 1080p screen.
 */
@Composable
private fun ChannelPanel(
    ui: RemoteDisplayUiState,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    onZap: (BouquetEntry) -> Unit,
    onOpenEpg: (BouquetEntry) -> Unit,
) {
    val own = ui.own
    val channels = own.channels
    val current = own.channel?.reference.orEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val lastIndex = channels.lastIndex
    val epgOpen = ui.epgChannel != null
    // The playing channel, or the one whose programme panel was just closed.
    val anchor = ChannelListNavigation.channelFocusIndex(channels, ui.listFocusReference.ifBlank { current })
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    // Focus the playing channel when the list opens, after a bouquet was chosen and when
    // the bouquet column or programme panel closes (the token), and once the new bouquet's
    // channels arrive. The list only scrolls when that channel is off screen.
    LaunchedEffect(ui.listFocusToken, own.bouquetReference, channels.size) {
        if (channels.isEmpty() || ui.bouquetPanel || epgOpen) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val onScreen = visible.size > 2 && anchor > visible.first().index && anchor < visible.last().index
        if (!onScreen) listState.scrollToItem((anchor - 5).coerceAtLeast(0))
        focus.requestWhenReady()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (epgOpen) 460.dp else 600.dp)
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(stringResource(R.string.osd_channels), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                own.bouquetName,
                color = EnigmaColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp, bottom = 2.dp),
            )
            if (!ui.bouquetPanel && !epgOpen) {
                Text(stringResource(R.string.osd_bouquets_hint), color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (channels.isEmpty()) {
            Text(stringResource(R.string.osd_no_channels_loaded), color = EnigmaColors.Muted, fontSize = 13.sp)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(channels, key = { index, entry -> "$index:${entry.reference}" }) { index, entry ->
                CompactListRow(
                    onClick = { onZap(entry) },
                    selected = BouquetChannels.sameService(entry.reference, current),
                    modifier = Modifier
                        .then(if (index == anchor) Modifier.focusRequester(focus) else Modifier)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (index == lastIndex && lastIndex > 0) Modifier.focusRequester(lastFocus) else Modifier)
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
                    ChannelRowContent(
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

@Composable
private fun RowScope.ChannelRowContent(
    entry: BouquetEntry,
    number: Int,
    event: NowEvent?,
    nowSec: Long,
    focused: Boolean,
    recording: Boolean,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
) {
    val secondary = if (focused) EnigmaColors.OnFocus.copy(alpha = 0.8f) else EnigmaColors.Muted
    Text(
        if (number > 0) "$number" else "",
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier.width(34.dp),
    )
    Picon(entry, picon, cachedPicon, Modifier.width(36.dp).height(22.dp), fallbackSize = 8.sp)
    Spacer(Modifier.width(8.dp))
    if (recording) {
        RecordingDot(Modifier.padding(end = 6.dp))
    }
    Text(
        entry.name,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(0.38f),
    )
    Spacer(Modifier.width(8.dp))
    Column(modifier = Modifier.weight(0.62f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event?.let { timeLabel(it.beginEpochSec) }.orEmpty(),
                color = secondary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.width(36.dp),
            )
            Text(
                event?.title.orEmpty(),
                color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (event != null) {
            Box(
                modifier = Modifier
                    .padding(start = 36.dp, top = 2.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(EnigmaColors.Raised, RoundedCornerShape(1.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(event.progress(nowSec))
                        .height(2.dp)
                        .background(if (focused) EnigmaColors.OnFocus else EnigmaColors.Accent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun timeLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(ClockFormat)
