package com.enigmacast.ui.remote

import com.enigmacast.ui.theme.glassSheen
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.focusRequesterIf
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.enigmacast.ui.theme.ActiveSkin
import com.enigmacast.ui.theme.OsdLayout
import androidx.compose.ui.graphics.Brush
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
import com.enigmacast.ui.components.EnigmaAnimatedOsd
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.components.KeepAnchored
import com.enigmacast.ui.components.LivePanelWidths
import com.enigmacast.ui.components.TrackRows
import com.enigmacast.ui.components.ensureVisible
import com.enigmacast.ui.components.EnigmaProgramProgress
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val scrim = EnigmaColors.scrim(ui.panelTransparency)
    // The channel and bouquet lists keep their place while hidden: on every zap they move to the
    // playing channel / current bouquet silently, so they open there without scrolling.
    val channelList = com.enigmacast.ui.components.rememberPresetList()
    val bouquetList = com.enigmacast.ui.components.rememberPresetList()
    val playingRef = own.channel?.reference.orEmpty()
    val playingIndex = remember(own.channels, playingRef) { ChannelListNavigation.channelFocusIndex(own.channels, playingRef) }
    val bouquetIndex = remember(ui.bouquets, own.bouquetReference) { ChannelListNavigation.bouquetFocusIndex(ui.bouquets, own.bouquetReference) }
    channelList.KeepAnchored(playingIndex, own.channels.size, hidden = !ui.channelList)
    bouquetList.KeepAnchored(bouquetIndex, ui.bouquets.size, hidden = !(ui.channelList && ui.bouquetPanel))
    Box(modifier = Modifier.fillMaxSize()) {
        val channel = own.channel
        if (own.loadingChannels) {
            ChannelsLoadingDialog(
                own.loadingLabel.asString().ifBlank { stringResource(R.string.load_channels) },
                own.loadingProgress,
            )
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
                scrim = scrim,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (ui.playerState.timeshiftPaused && !ui.channelList && !tunerProblem) {
            Text(
                "❚❚",
                color = Color.White,
                fontSize = 28.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(scrim, skinShape(12.dp)).glassSheen(skinShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
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
                    .background(scrim, skinShape(12.dp)).glassSheen(skinShape(12.dp))
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
                    .background(scrim, skinShape(10.dp)).glassSheen(skinShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        val radio = channel != null && BouquetChannels.isRadioBouquet(own.bouquetReference)
        if (ui.banner && !ui.quickBar && !ui.bannerFromZap && !ui.channelList && ui.numberEntry.isEmpty() && channel != null && !radio) {
            OsdClock(
                scrim = scrim,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = EnigmaColors.SafeHorizontal, top = EnigmaColors.SafeVertical),
            )
        }
        if (radio && ui.quickBar && !ui.channelList) {
            QuickOptionsBar(
                ui = ui,
                actions = quickBarActions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
            )
        }
        val showBanner = ui.banner && !ui.quickBar && channel != null && !ui.channelList && !radio
        val showStrip = channel != null && !ui.channelList && !radio && (ui.banner || ui.quickBar)
        EnigmaAnimatedOsd(visible = showStrip, modifier = Modifier.align(Alignment.BottomCenter)) {
            if (channel != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (showBanner) ChannelBanner(
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
                    scrim = scrim,
                    docked = !ui.bannerFromZap,
                )
                if (!ui.bannerFromZap) {
                    QuickOptionsBar(
                        ui = ui,
                        actions = quickBarActions,
                        interactive = ui.quickBar,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
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
                scrim = scrim,
                channelList = channelList,
                bouquetList = bouquetList,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        ui.programmePrompt?.let { prompt -> ProgrammeDialog(prompt, guideActions) }
    }
}

@Composable
internal fun ChannelBanner(
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
    scrim: Color,
    /** Icons sit directly under the banner, so it does not keep the usual gap above the screen edge. */
    docked: Boolean = false,
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
    val loading = player.playbackState == "BUFFERING" ||
        tuner.status == TunerStatus.CHECKING ||
        tuner.status == TunerStatus.STARTING
    val layout = ActiveSkin.current.osd
    val info: @Composable () -> Unit = { BannerInfo(player, osdInfo, osdFields, osdSkin, optionsHint) }
    when (layout) {
        OsdLayout.PLI_BAR -> {
            PliHdInfoBar(channel, number, current, next, nowSec, loading, recording, tuner, details, picon, cachedPicon, OsdInfoPresenter.present(osdInfo, osdFields), osdInfo, player, modifier)
            return
        }
        OsdLayout.FLAT -> {
            FlatBanner(channel, number, current, next, nowSec, loading, recording, tuner, details, picon, cachedPicon, scrim, info, docked, modifier)
            return
        }
        OsdLayout.ORBIT -> {
            OrbitBanner(channel, number, current, next, nowSec, loading, recording, tuner, details, picon, cachedPicon, scrim, info, docked, modifier)
            return
        }
        OsdLayout.WAVE -> {
            WaveBanner(channel, number, current, next, nowSec, loading, recording, tuner, details, picon, cachedPicon, scrim, info, docked, modifier)
            return
        }
        else -> Unit
    }
    val cardShape = skinShape(16.dp)
    val fill = if (layout == OsdLayout.GLASS) {
        Brush.linearGradient(listOf(EnigmaColors.scrimOf(ActiveSkin.current.palette.osdFill, scrim), EnigmaColors.scrimOf(ActiveSkin.current.palette.osdFillEnd, scrim)))
    } else {
        androidx.compose.ui.graphics.SolidColor(scrim)
    }
    Row(
        modifier = modifier
            .padding(
                start = EnigmaColors.SafeHorizontal,
                end = EnigmaColors.SafeHorizontal,
                top = EnigmaColors.SafeVertical,
                bottom = if (docked) 0.dp else EnigmaColors.SafeVertical,
            )
            .fillMaxWidth()
            .background(fill, cardShape)
            .then(if (layout == OsdLayout.MINIMAL) Modifier else Modifier.border(1.dp, EnigmaColors.GlassEdge, cardShape))
            // Glass skins sit over video here: no photo behind it, so the sheen and hairline only.
            .glassSheen(cardShape)
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
                    fontWeight = EnigmaType.Strong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
                }
                if (recording) {
                    RecordingDot(Modifier.padding(start = 8.dp))
                }
                if (tuner.status == TunerStatus.FULL) {
                    StatusPill(stringResource(R.string.osd_tuners_busy), EnigmaColors.Danger)
                }
            }
            if (current != null) {
                EventLine(current, emphasised = true)
                EnigmaProgramProgress(progressOf(current, nowSec))
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
            info()
        }
    }
}

/** Right-hand facts of the banner (receiver info strip, audio, subtitles, key hint); every layout places it. */
@Composable
private fun BannerInfo(player: PlayerState, osdInfo: OsdInfo, osdFields: OsdInfoFields, osdSkin: OsdSkinId, optionsHint: Boolean) {
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
    val hint = when {
        player.timeshiftPaused && optionsHint -> R.string.osd_banner_hint_options_paused
        player.timeshiftPaused -> R.string.osd_banner_hint_paused
        optionsHint -> R.string.osd_banner_hint_options
        else -> R.string.osd_banner_hint
    }
    Text(
        stringResource(hint),
        color = EnigmaColors.Subtle,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

private fun progressOf(event: EpgEvent, nowSec: Long): Float {
    if (event.durationSec <= 0 || event.beginEpochSec <= 0) return 0f
    return ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
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
            .background(EnigmaColors.Raised, skinShape(6.dp))
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
        if (bitmap != null) return@LaunchedEffect
        // A row that is only scrolled past leaves before this and never loads its picon.
        kotlinx.coroutines.delay(com.enigmacast.domain.ui.FocusSettle.PICON_MS)
        bitmap = picon(entry)
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
private fun TunerNotice(tuner: TunerState, channelName: String, scrim: Color, modifier: Modifier = Modifier) {
    val title = when (tuner.status) {
        TunerStatus.FULL -> stringResource(R.string.osd_no_tuner_free)
        TunerStatus.NO_PICTURE -> stringResource(R.string.osd_no_picture)
        else -> stringResource(R.string.osd_stream_not_started)
    }
    Column(
        modifier = modifier
            .widthIn(max = 440.dp)
            .background(scrim, skinShape(12.dp)).glassSheen(skinShape(12.dp))
            .border(1.dp, EnigmaColors.GlassEdge, skinShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (tuner.status == TunerStatus.NO_PICTURE) EnigmaColors.Accent else EnigmaColors.Danger, skinShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = EnigmaType.Strong)
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
    scrim: Color,
    channelList: com.enigmacast.ui.components.PresetList,
    bouquetList: com.enigmacast.ui.components.PresetList,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(scrim),
    ) {
        if (ui.bouquetPanel) {
            BouquetPanel(
                bouquets = ui.bouquets,
                activeReference = ui.own.bouquetReference,
                onChoose = onChooseBouquet,
                preset = bouquetList,
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
            preset = channelList,
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
    preset: com.enigmacast.ui.components.PresetList = com.enigmacast.ui.components.rememberPresetList(),
) {
    val listState = preset.state
    preset.TrackRows()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val lastIndex = bouquets.lastIndex
    val anchor = ChannelListNavigation.bouquetFocusIndex(bouquets, activeReference)
    LaunchedEffect(bouquets.size) {
        if (bouquets.isEmpty()) return@LaunchedEffect
        // Already at the current bouquet (kept while hidden); only moves when it is off screen.
        preset.ensureVisible(anchor, bouquets.size)
        focus.requestWhenReady()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(LivePanelWidths.CATEGORIES)
            .padding(horizontal = 10.dp, vertical = 20.dp),
    ) {
        Text(stringResource(R.string.osd_bouquets), color = Color.White, fontSize = 17.sp, fontWeight = EnigmaType.Strong)
        Text(stringResource(R.string.osd_bouquet_choose_hint), color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (bouquets.isEmpty()) {
            Text(stringResource(R.string.osd_bouquets_loading), color = EnigmaColors.Muted, fontSize = 13.sp)
        }
        // Focusing a visible row must not scroll (the TV default pulls it to ~30 % from the top).
        com.enigmacast.ui.theme.MinimalScrolling {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(bouquets, key = { index, entry -> "$index:${entry.reference}" }) { index, bouquet ->
                CompactListRow(
                    onClick = { onChoose(bouquet) },
                    selected = index == anchor && activeReference.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    modifier = Modifier
                        .focusRequesterIf(index == anchor, focus)
                        .focusRequesterIf(index == 0, firstFocus)
                        .focusRequesterIf(index == lastIndex && lastIndex > 0, lastFocus)
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
    preset: com.enigmacast.ui.components.PresetList,
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
    // channels arrive. The list was kept at the playing channel while hidden, so it opens there;
    // it only moves when that channel is off screen (another bouquet, a closed programme panel).
    LaunchedEffect(ui.listFocusToken, own.bouquetReference, channels.size) {
        if (channels.isEmpty() || ui.bouquetPanel || epgOpen) return@LaunchedEffect
        preset.ensureVisible(anchor, channels.size)
        focus.requestWhenReady()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (epgOpen) LivePanelWidths.CHANNELS_WITH_GUIDE else LivePanelWidths.CHANNELS)
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(stringResource(R.string.osd_channels), color = Color.White, fontSize = 17.sp, fontWeight = EnigmaType.Strong)
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
        // Focusing a visible row must not scroll (the TV default pulls it to ~30 % from the top).
        com.enigmacast.ui.theme.MinimalScrolling {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(channels, key = { index, entry -> "$index:${entry.reference}" }) { index, entry ->
                CompactListRow(
                    onClick = { onZap(entry) },
                    selected = if (playingIndex >= 0) index == playingIndex else BouquetChannels.sameService(entry.reference, current),
                    modifier = Modifier
                        .focusRequesterIf(index == anchor, focus)
                        .focusRequesterIf(index == 0, firstFocus)
                        .focusRequesterIf(index == lastIndex && lastIndex > 0, lastFocus)
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
            EnigmaProgramProgress(
                fraction = event.progress(nowSec),
                modifier = Modifier.padding(start = 36.dp, top = 2.dp),
                emphasized = !focused,
            )
        }
    }
}

@Composable
private fun OsdClock(scrim: Color, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay((60 - now.second).coerceAtLeast(1) * 1000L)
        }
    }
    Column(
        modifier = modifier
            .background(scrim, skinShape(6.dp)).glassSheen(skinShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm", locale)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(now.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)), color = EnigmaColors.Muted, fontSize = 10.sp, maxLines = 1)
    }
}

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun timeLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(ClockFormat)
