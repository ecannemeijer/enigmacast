package com.enigmacast.ui.remote

import com.enigmacast.ui.theme.glassSheen
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.enigmacast.ui.theme.focusListRow
import com.enigmacast.ui.theme.wrapRowEnds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.network.PlainHttpRisk
import com.enigmacast.domain.network.QualityReason
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.player.AudioDelayChoices
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.domain.player.QuickOption
import com.enigmacast.domain.player.QuickOptions
import com.enigmacast.domain.player.SleepTimerChoices
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.playback.AudioSync
import com.enigmacast.playback.SubtitleSync
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty
import kotlinx.coroutines.delay
import java.util.Locale

/** What the quick options bar can change; wired to [RemoteDisplayViewModel]. */
data class QuickBarActions(
    val onSubtitles: (String?) -> Unit,
    val onAudio: (String) -> Unit,
    val onPassthrough: (Boolean) -> Unit,
    val onPictureMode: (PictureMode) -> Unit,
    val onAudioDelay: (Int) -> Unit,
    val onSubtitleDelay: (Int) -> Unit,
    val onGuide: () -> Unit,
    val onRecent: (LastChannel) -> Unit,
    val onSleep: (Int) -> Unit,
    val onRestart: () -> Unit,
    val onQuality: (StreamLevel?) -> Unit,
    val onRecord: () -> Unit,
    val onSearch: () -> Unit,
    val onEquivalent: () -> Unit,
    val onRecentBar: () -> Unit,
    val onPip: () -> Unit = {},
)

/**
 * Options under the channel banner: a row of small icons. Left/Right to move, OK to open
 * a picker above the icon, Back to leave the row. The name and value show only on the
 * icon that has the focus.
 */
@Composable
fun QuickOptionsBar(
    ui: RemoteDisplayUiState,
    actions: QuickBarActions,
    modifier: Modifier = Modifier,
    /** IPTV playback: only player options, nothing that belongs to the receiver. */
    iptv: Boolean = false,
    /** False while the icons only sit under the OSD; Down makes them take the focus. */
    interactive: Boolean = true,
) {
    val tiles = if (iptv) QuickOptions.iptvTiles(ui.playerState.filePlayback) else QuickOptions.tiles(ui.ownTuner, ui.playerState.filePlayback)
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    val rowScope = rememberCoroutineScope()
    var open by remember { mutableStateOf<QuickOption?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(ui.quickBarToken, interactive) {
        if (!interactive) return@LaunchedEffect
        open = null
        firstFocus.requestWhenReady()
    }
    LazyRow(
        state = rowState,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (interactive && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                actions.onRecentBar()
                true
            } else {
                false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        itemsIndexed(tiles, key = { _, option -> option.name }) { index, option ->
            Box {
                QuickTile(
                    icon = option.icon(),
                    label = when {
                        option == QuickOption.RECORD && ui.recordingTitle.isNotBlank() -> stringResource(R.string.my_items_stop)
                        option == QuickOption.RESTART && ui.playerState.filePlayback -> stringResource(R.string.file_from_start)
                        else -> option.label()
                    },
                    value = tileValue(option, ui, nowMs),
                    enabled = interactive,
                    large = interactive,
                    caption = open != option,
                    onClick = {
                        when (option) {
                            QuickOption.EQUIVALENT -> actions.onEquivalent()
                            QuickOption.SEARCH -> actions.onSearch()
                            QuickOption.GUIDE -> actions.onGuide()
                            QuickOption.RESTART -> actions.onRestart()
                            QuickOption.RECORD -> actions.onRecord()
                            QuickOption.PIP -> actions.onPip()
                            else -> open = option
                        }
                    },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (index == tiles.lastIndex) Modifier.focusRequester(lastFocus) else Modifier)
                        // Left on the first icon goes to the last, Right on the last to the first.
                        .wrapRowEnds(
                            index, tiles.lastIndex,
                            onWrapToFirst = { rowScope.launch { firstFocus.focusListRow(rowState, 0) } },
                            onWrapToLast = { rowScope.launch { lastFocus.focusListRow(rowState, tiles.lastIndex) } },
                        ),
                )
                if (open == option) {
                    QuickPicker(option, ui, actions, iptv, onClose = { open = null })
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    value: String,
    enabled: Boolean,
    large: Boolean,
    caption: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val captionAnchor = remember(density) {
        with(density) { AboveAnchor(gapPx = 6.dp.roundToPx(), marginPx = 12.dp.roundToPx()) }
    }
    val tileWidth by animateDpAsState(if (large) 48.dp else 36.dp, label = "tileWidth")
    val tileHeight by animateDpAsState(if (large) 40.dp else 28.dp, label = "tileHeight")
    val iconSize by animateDpAsState(if (large) 28.dp else 20.dp, label = "iconSize")
    Box(
        modifier
            .size(width = tileWidth, height = tileHeight)
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .focusable(enabled = enabled, interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
        if (focused) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(width = if (large) 20.dp else 14.dp, height = 2.dp)
                    .background(Color.White),
            )
        }
    }
    if (focused && caption) {
        Popup(popupPositionProvider = captionAnchor, properties = PopupProperties(focusable = false)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 220.dp)) {
                Text(
                    label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = CaptionStyle,
                )
                if (value.isNotBlank()) {
                    Text(value, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CaptionStyle)
                }
            }
        }
    }
}

private val CaptionStyle = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 1f), 6f))

private class PickerItem(val label: String, val selected: Boolean, val onSelect: () -> Unit)

@Composable
private fun QuickPicker(option: QuickOption, ui: RemoteDisplayUiState, actions: QuickBarActions, iptv: Boolean, onClose: () -> Unit) {
    val density = LocalDensity.current
    val provider = remember(density) {
        with(density) { AboveAnchor(gapPx = 8.dp.roundToPx(), marginPx = 16.dp.roundToPx()) }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        NavigationSoundsForWindow()
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp, max = if (option == QuickOption.SUBTITLES) 640.dp else 340.dp)
                .background(EnigmaColors.Selected, skinShape(12.dp)).glassSheen(skinShape(12.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(option.label(), color = Color.White, fontSize = 15.sp, fontWeight = EnigmaType.Strong)
            when (option) {
                QuickOption.STREAM_INFO -> StreamInfo(ui, iptv, onClose)
                QuickOption.AUDIO_DELAY -> {
                    Text(
                        stringResource(if (ui.audioDelayPassthrough) R.string.quick_delay_for_hdmi else R.string.quick_delay_for_stereo),
                        color = EnigmaColors.Subtle,
                        fontSize = 11.sp,
                    )
                    PickerList(pickerItems(option, ui, actions, iptv, onClose), onClose, wrap = option == QuickOption.SUBTITLES)
                }
                QuickOption.SUBTITLE_DELAY -> {
                    Text(
                        stringResource(R.string.quick_subtitle_delay_hint),
                        color = EnigmaColors.Subtle,
                        fontSize = 11.sp,
                    )
                    PickerList(pickerItems(option, ui, actions, iptv, onClose), onClose)
                }
                else -> {
                    val items = pickerItems(option, ui, actions, iptv, onClose)
                    if (items.isEmpty()) {
                        Text(emptyText(option), color = EnigmaColors.Muted, fontSize = 13.sp)
                        CloseOnOk(onClose)
                    } else {
                        PickerList(items, onClose, wrap = option == QuickOption.SUBTITLES)
                    }
                }
            }
        }
    }
}

@Composable
private fun pickerItems(option: QuickOption, ui: RemoteDisplayUiState, actions: QuickBarActions, iptv: Boolean, onClose: () -> Unit): List<PickerItem> {
    fun pick(action: () -> Unit): () -> Unit = {
        action()
        onClose()
    }
    return when (option) {
        QuickOption.SUBTITLES -> {
            if (ui.tracks.subtitles.isEmpty()) {
                emptyList()
            } else {
                listOf(PickerItem(stringResource(R.string.off), !ui.tracks.subtitlesOn, pick { actions.onSubtitles(null) })) +
                    ui.tracks.subtitles.map { track -> PickerItem(track.label.asString(), track.selected, pick { actions.onSubtitles(track.id) }) }
            }
        }
        QuickOption.AUDIO -> {
            val tracks = ui.tracks.audio.map { track -> PickerItem(track.label.asString(), track.selected && ui.tracks.audio.size > 1, pick { actions.onAudio(track.id) }) }
            if (iptv) tracks else tracks + listOf(
                PickerItem(stringResource(R.string.quick_output_stereo), !ui.hdmiPassthrough, pick { actions.onPassthrough(false) }),
                PickerItem(stringResource(R.string.quick_output_hdmi), ui.hdmiPassthrough, pick { actions.onPassthrough(true) }),
            )
        }
        QuickOption.PICTURE -> PictureMode.entries.map { mode ->
            PickerItem(pictureLabel(mode), mode == ui.pictureMode, pick { actions.onPictureMode(mode) })
        }
        QuickOption.AUDIO_DELAY -> AudioDelayChoices.values(AudioSync.MIN_MS, AudioSync.MAX_MS, AudioSync.STEP_MS).map { ms ->
            PickerItem(AudioSync.label(ms), ms == ui.audioDelayMs, pick { actions.onAudioDelay(ms) })
        }
        QuickOption.SUBTITLE_DELAY -> AudioDelayChoices.values(SubtitleSync.MIN_MS, SubtitleSync.MAX_MS, SubtitleSync.STEP_MS).map { ms ->
            PickerItem(SubtitleSync.label(ms), ms == ui.subtitleDelayMs, pick { actions.onSubtitleDelay(ms) })
        }
        QuickOption.RECENT -> {
            val current = ui.own.channel?.reference?.let(NowEvents::key)
            ui.recent.filter { NowEvents.key(it.reference) != current }.map { channel ->
                PickerItem(channel.name.ifBlank { channel.reference }, false, pick { actions.onRecent(channel) })
            }
        }
        QuickOption.SLEEP -> {
            val left = SleepTimerChoices.minutesLeft(ui.sleepEndsAtMs, System.currentTimeMillis())
            SleepTimerChoices.minutes.map { minutes ->
                val label = if (minutes == 0) stringResource(R.string.off) else stringResource(R.string.quick_minutes, minutes)
                PickerItem(label, (minutes == 0 && left == 0), pick { actions.onSleep(minutes) })
            }
        }
        QuickOption.QUALITY -> {
            val manual = ui.quality.manual
            // Transcoded levels are always offered: picking one tries the receiver's encoder (once) even when
            // /api/about does not report it, and falls back to Normal with a message if it fails.
            val levels = StreamLevel.entries
            listOf(PickerItem(stringResource(R.string.quality_auto_setting), manual == null, pick { actions.onQuality(null) })) +
                levels.map { level -> PickerItem(qualityLabel(level), manual == level, pick { actions.onQuality(level) }) }
        }
        QuickOption.SEARCH, QuickOption.EQUIVALENT, QuickOption.GUIDE, QuickOption.RESTART, QuickOption.STREAM_INFO, QuickOption.RECORD, QuickOption.PIP -> emptyList()
    }
}

/** "Original" or "720p · 2.5 Mbit/s". */
@Composable
fun qualityLabel(level: StreamLevel): String {
    val preset = level.preset ?: return stringResource(R.string.quality_direct)
    return stringResource(R.string.quality_transcoded, preset.height, preset.bitrateKbps / 1000f)
}

@Composable
private fun qualityReason(reason: QualityReason): String = stringResource(
    when (reason) {
        QualityReason.START -> R.string.quality_reason_setting
        QualityReason.STALLS -> R.string.quality_reason_stalls
        QualityReason.STABLE -> R.string.quality_reason_stable
        QualityReason.MANUAL -> R.string.quality_reason_manual
        QualityReason.FALLBACK -> R.string.quality_reason_fallback
    },
)

@Composable
private fun PickerList(items: List<PickerItem>, onClose: () -> Unit, wrap: Boolean = false) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val start = items.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        listState.scrollToItem((start - 3).coerceAtLeast(0))
        focus.requestWhenReady()
    }
    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        itemsIndexed(items) { index, item ->
            CompactListRow(
                onClick = item.onSelect,
                selected = item.selected,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = if (index == start) Modifier.focusRequester(focus) else Modifier,
            ) { focused ->
                Box(Modifier.width(20.dp)) {
                    if (item.selected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) EnigmaColors.OnFocus else EnigmaColors.Accent, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    item.label,
                    modifier = if (wrap) Modifier.weight(1f) else Modifier,
                    fontSize = 13.sp,
                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                    overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A focus target so OK (and Back) closes a picker that has nothing to choose. */
@Composable
private fun CloseOnOk(onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestWhenReady() }
    Spacer(
        Modifier
            .size(1.dp)
            .focusRequester(focus)
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (confirm && event.type == KeyEventType.KeyUp) onClose()
                confirm
            }
            .focusable(),
    )
}

private fun routeLabel(route: ReceiverRoute): Int = when (route) {
    ReceiverRoute.HOME -> R.string.route_home
    ReceiverRoute.REMOTE -> R.string.route_remote
    ReceiverRoute.TUNNEL -> R.string.route_tunnel
}

@Composable
private fun StreamInfo(ui: RemoteDisplayUiState, iptv: Boolean, onClose: () -> Unit) {
    val player = ui.playerState
    val video = listOf(
        if (player.videoWidth > 0) "${player.videoWidth}×${player.videoHeight}" else "",
        videoCodecName(player.videoCodec),
        if (player.frameRate > 0f) "${player.frameRate.toInt()} fps" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
    val lines = buildList {
        if (video.isNotBlank()) add(stringResource(R.string.quick_info_video, video))
        if (player.audioLabel.isNotEmpty()) add(stringResource(R.string.quick_info_audio, player.audioLabel.asString()))
        if (iptv) return@buildList
        add(stringResource(R.string.quick_info_bitrate, bitrateLabel(ui.stats.bitrateKbps)))
        add(stringResource(R.string.quick_info_buffer, ui.stats.bufferMs / 1000f))
        add(stringResource(R.string.quick_info_dropped, ui.stats.droppedFrames))
        add(stringResource(R.string.quick_info_stalls, ui.stats.stalls))
        val mode = stringResource(if (ui.ownTuner) R.string.viewing_mode_own else R.string.viewing_mode_follow)
        add(stringResource(R.string.quick_info_source, "${ui.receiverAddress} · $mode"))
        add(stringResource(R.string.quick_info_route, stringResource(routeLabel(ui.route))))
        add(stringResource(R.string.quick_info_quality, qualityLabel(ui.quality.level), qualityReason(ui.quality.reason)))
        when (ui.routeRisk) {
            PlainHttpRisk.WEB_AND_STREAM -> add(stringResource(R.string.route_risk_web_and_stream))
            PlainHttpRisk.STREAM -> add(stringResource(R.string.route_risk_stream))
            PlainHttpRisk.NONE -> Unit
        }
    }
    lines.forEach { line ->
        Text(line, color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    CloseOnOk(onClose)
}

@Composable
fun bitrateLabel(kbps: Int): String = when {
    kbps <= 0 -> "–"
    kbps >= 1000 -> stringResource(R.string.bitrate_mbps, kbps / 1000f)
    else -> stringResource(R.string.bitrate_kbps, kbps)
}

private fun videoCodecName(mime: String): String = when (mime) {
    "" -> ""
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/mpeg2" -> "MPEG-2"
    "video/av01" -> "AV1"
    else -> mime.substringAfter('/').uppercase(Locale.ROOT)
}

@Composable
private fun tileValue(option: QuickOption, ui: RemoteDisplayUiState, nowMs: Long): String = when (option) {
    QuickOption.SUBTITLES -> when {
        ui.tracks.subtitles.isEmpty() -> stringResource(R.string.quick_none)
        !ui.tracks.subtitlesOn -> stringResource(R.string.off)
        else -> ui.tracks.subtitles.firstOrNull { it.selected }?.label?.asString() ?: stringResource(R.string.on)
    }
    QuickOption.AUDIO -> ui.playerState.audioLabel.asString().ifBlank { "–" }
    QuickOption.PICTURE -> pictureLabel(ui.pictureMode)
    QuickOption.AUDIO_DELAY -> AudioSync.label(ui.audioDelayMs)
    QuickOption.SUBTITLE_DELAY -> SubtitleSync.label(ui.subtitleDelayMs)
    QuickOption.GUIDE -> ui.own.guide?.currentEvent?.title.orEmpty()
    QuickOption.RECENT -> ui.recent.firstOrNull { NowEvents.key(it.reference) != ui.own.channel?.reference?.let(NowEvents::key) }?.name.orEmpty()
    QuickOption.SLEEP -> {
        val left = SleepTimerChoices.minutesLeft(ui.sleepEndsAtMs, nowMs)
        if (left == 0) stringResource(R.string.off) else stringResource(R.string.quick_minutes, left)
    }
    QuickOption.STREAM_INFO -> bitrateLabel(ui.stats.bitrateKbps)
    QuickOption.QUALITY -> qualityLabel(ui.quality.level)
    QuickOption.SEARCH -> ""
    QuickOption.EQUIVALENT -> ""
    QuickOption.RESTART -> ""
    QuickOption.PIP -> ""
    QuickOption.RECORD -> if (ui.recordingTitle.isNotBlank()) ui.recordingTitle else {
        val nowSec = nowMs / 1000
        val reference = ui.own.channel?.reference ?: ui.currentService?.reference.orEmpty()
        ui.own.guide?.currentEvent?.title
            ?: NowEvents.lookup(ui.nowEvents, reference, nowSec)?.title
            .orEmpty()
    }
}

@Composable
private fun QuickOption.label(): String = stringResource(
    when (this) {
        QuickOption.SUBTITLES -> R.string.subtitles
        QuickOption.AUDIO -> R.string.quick_audio
        QuickOption.PICTURE -> R.string.quick_picture
        QuickOption.AUDIO_DELAY -> R.string.quick_audio_delay
        QuickOption.SUBTITLE_DELAY -> R.string.quick_subtitle_delay
        QuickOption.GUIDE -> R.string.quick_guide
        QuickOption.RECENT -> R.string.quick_recent
        QuickOption.SLEEP -> R.string.sleep_title
        QuickOption.STREAM_INFO -> R.string.quick_stream_info
        QuickOption.QUALITY -> R.string.quick_quality
        QuickOption.RESTART -> R.string.quick_restart
        QuickOption.SEARCH -> R.string.nav_search
        QuickOption.EQUIVALENT -> R.string.quick_equivalent
        QuickOption.RECORD -> R.string.epg_record
        QuickOption.PIP -> R.string.pip
    },
)

private fun QuickOption.icon(): ImageVector = when (this) {
    QuickOption.SUBTITLES -> EnigmaIcons.Subtitles
    QuickOption.AUDIO -> EnigmaIcons.Audiotrack
    QuickOption.PICTURE -> EnigmaIcons.AspectRatio
    QuickOption.AUDIO_DELAY -> EnigmaIcons.Sync
    QuickOption.SUBTITLE_DELAY -> EnigmaIcons.Timer
    QuickOption.GUIDE -> EnigmaIcons.Guide
    QuickOption.RECENT -> EnigmaIcons.History
    QuickOption.SLEEP -> EnigmaIcons.Bedtime
    QuickOption.STREAM_INFO -> Icons.Filled.Info
    QuickOption.QUALITY -> EnigmaIcons.Speed
    QuickOption.RESTART -> Icons.Filled.Refresh
    QuickOption.SEARCH -> Icons.Filled.Search
    QuickOption.EQUIVALENT -> EnigmaIcons.Sync
    QuickOption.RECORD -> EnigmaIcons.Record
    QuickOption.PIP -> EnigmaIcons.Tv
}

@Composable
private fun pictureLabel(mode: PictureMode): String = stringResource(
    when (mode) {
        PictureMode.AUTO -> R.string.picture_auto
        PictureMode.WIDE -> R.string.picture_wide
        PictureMode.NORMAL -> R.string.picture_normal
        PictureMode.ZOOM -> R.string.picture_zoom
        PictureMode.STRETCH -> R.string.picture_stretch
    },
)

@Composable
private fun emptyText(option: QuickOption): String = stringResource(
    when (option) {
        QuickOption.SUBTITLES -> R.string.quick_no_subtitles
        QuickOption.RECENT -> R.string.quick_no_recent
        else -> R.string.quick_none
    },
)

/** Places a picker centred above its tile, kept inside the screen. */
private class AboveAnchor(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(marginPx, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(marginPx)
        return IntOffset(x, y)
    }
}
