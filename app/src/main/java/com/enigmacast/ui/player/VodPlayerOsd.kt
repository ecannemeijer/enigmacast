package com.enigmacast.ui.player

import com.enigmacast.ui.theme.glassSheen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.player.AudioDelayChoices
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.domain.player.VodOsdKeys
import com.enigmacast.playback.AudioSync
import com.enigmacast.playback.SubtitleSync
import com.enigmacast.playback.TrackMenu
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.asString
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown/hidden state of the VOD OSD, shared by the IPTV, recording and torrent players.
 * The host passes key-downs to [onKeyDown] first (see [VodOsdKeys] for the mapping).
 */
@Stable
class VodOsdController {
    var visible by mutableStateOf(false)
        private set
    /** A menu (subtitles, audio…) is open: no auto-hide. */
    var menuOpen by mutableStateOf(false)
        internal set
    internal var touches by mutableIntStateOf(0)
    internal var showToken by mutableIntStateOf(0)
    internal var pendingScrub by mutableLongStateOf(0L)
    internal var scrubToken by mutableIntStateOf(0)

    fun show() {
        if (!visible) { visible = true; showToken++ }
        touches++
    }

    fun hide() {
        visible = false
        menuOpen = false
        pendingScrub = 0L
    }

    /** Any key while shown keeps it up for another 5 s. */
    fun touch() { touches++ }

    /** A key-down from the host: true when the OSD used it (see [VodOsdKeys]). */
    fun onKeyDown(keyCode: Int, repeatCount: Int, onTogglePause: () -> Unit): Boolean {
        when (val a = VodOsdKeys.action(keyCode, repeatCount, visible)) {
            is VodOsdKeys.Action.TogglePause -> { onTogglePause(); show() }
            is VodOsdKeys.Action.Scrub -> { show(); pendingScrub = a.deltaMs; scrubToken++ }
            VodOsdKeys.Action.Show -> show()
            VodOsdKeys.Action.Pass -> { if (visible) touch(); return false }
        }
        return true
    }
}

/** What the OSD's buttons and menus do; null hides that button or entry. */
class VodOsdActions(
    val onTogglePause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSubtitles: (String?) -> Unit,
    val onAudio: (String) -> Unit,
    val onPictureMode: (PictureMode) -> Unit,
    val onAudioDelay: (Int) -> Unit,
    val onSubtitleDelay: (Int) -> Unit,
    val onRestart: () -> Unit,
    val onSpeed: ((Float) -> Unit)? = null,
    /** Next episode (series with a following episode). */
    val onNext: (() -> Unit)? = null,
    /** Another source for this title (torrents): the globe menu offers it. */
    val onSources: (() -> Unit)? = null,
    /** Recordings: stereo or HDMI passthrough, under the audio tracks. */
    val onPassthrough: ((Boolean) -> Unit)? = null,
    /** Sleep timer (minutes, 0 = off) in the settings menu. */
    val onSleep: ((Int) -> Unit)? = null,
)

/** What the OSD shows. */
data class VodOsdModel(
    val title: String,
    val player: PlayerState,
    val tracks: TrackMenu = TrackMenu(),
    val pictureMode: PictureMode = PictureMode.AUTO,
    val audioDelayMs: Int = 0,
    val subtitleDelayMs: Int = 0,
    val speed: Float = 1f,
    val hdmiPassthrough: Boolean = false,
    val sleepEndsAtMs: Long = 0L,
)

private enum class VodMenu { SUBTITLES, AUDIO, VIDEO, SETTINGS, SPEED, SUBTITLE_DELAY, AUDIO_DELAY, SLEEP, SOURCES }

private val SettingsSubmenus = setOf(VodMenu.SPEED, VodMenu.SUBTITLE_DELAY, VodMenu.AUDIO_DELAY, VodMenu.SLEEP)

private val Scrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xE0000000)))
private val MenuBackground = Color(0xF2141418)

/** Glass skins tint the Stremio-style OSD in their navy; the others keep the black scrim. */
private fun vodScrim(): Brush {
    val skin = com.enigmacast.ui.theme.ActiveSkin.current
    if (skin.glass == null) return Scrim
    val fill = skin.palette.osdFill
    return Brush.verticalGradient(listOf(Color.Transparent, fill.copy(alpha = 0.6f), fill.copy(alpha = 0.88f)))
}

private fun vodMenuBackground(): Color {
    val skin = com.enigmacast.ui.theme.ActiveSkin.current
    return if (skin.glass == null) MenuBackground else skin.palette.osdFill.copy(alpha = 0.86f)
}

/**
 * The VOD OSD after the Stremio Android TV player: title, Play/Pause · Rewind (· Next), a thin
 * seek bar with a round thumb, and a row of small outline icons (subtitles, audio, video,
 * settings, sources) with elapsed / total on the right. White on a dark gradient; the focused
 * control is a filled white circle.
 */
@Composable
fun VodPlayerOsd(
    controller: VodOsdController,
    model: VodOsdModel,
    actions: VodOsdActions,
    modifier: Modifier = Modifier,
) {
    if (!controller.visible) return
    var menu by remember { mutableStateOf<VodMenu?>(null) }
    SideEffect { controller.menuOpen = menu != null }
    val playing = model.player.playing
    LaunchedEffect(controller.touches, playing, menu) {
        if (playing && menu == null) {
            delay(VodOsdKeys.HIDE_AFTER_MS)
            controller.hide()
        }
    }
    val barFocus = remember { FocusRequester() }
    val topRow = remember { FocusRequester() }
    val bottomRow = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val firstIcon = remember { FocusRequester() }
    val iconFocus = remember { VodMenu.entries.associateWith { FocusRequester() } }
    LaunchedEffect(controller.showToken) { barFocus.requestWhenReady() }
    var reopenFocus by remember { mutableStateOf<VodMenu?>(null) }
    LaunchedEffect(reopenFocus) {
        val m = reopenFocus ?: return@LaunchedEffect
        iconFocus[m]?.requestWhenReady()
        reopenFocus = null
    }
    fun open(m: VodMenu) { menu = m; controller.touch() }
    fun close() {
        val m = menu
        menu = null
        controller.touch()
        reopenFocus = when (m) {
            in SettingsSubmenus -> VodMenu.SETTINGS
            else -> m
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(vodScrim())
            .padding(start = 56.dp, end = 56.dp, top = 64.dp, bottom = 28.dp),
    ) {
        Text(
            model.title,
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))
        // Play/Pause · Rewind (· Next episode).
        Row(
            Modifier.focusRequester(topRow).focusRestorer(playFocus).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val down = Modifier.focusProperties { down = barFocus; up = FocusRequester.Cancel }
            OsdButton(
                icon = if (playing) VodIcons.Pause else VodIcons.Play,
                label = stringResource(if (playing) R.string.osd_pause else R.string.osd_play),
                size = 44.dp, iconSize = 24.dp,
                onClick = { actions.onTogglePause(); controller.touch() },
                modifier = down.focusRequester(playFocus).focusProperties { left = FocusRequester.Cancel },
            )
            OsdButton(
                icon = VodIcons.Rewind,
                label = stringResource(R.string.osd_rewind),
                size = 44.dp, iconSize = 24.dp,
                onClick = { actions.onSeek((model.player.positionMs - VodOsdKeys.SEEK_STEP_MS).coerceAtLeast(0)); controller.touch() },
                modifier = down.focusProperties { if (actions.onNext == null) right = FocusRequester.Cancel },
            )
            actions.onNext?.let { next ->
                OsdButton(
                    icon = VodIcons.Next,
                    label = stringResource(R.string.osd_next_episode),
                    size = 44.dp, iconSize = 24.dp,
                    onClick = { next() },
                    modifier = down.focusProperties { right = FocusRequester.Cancel },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SeekBar(
            controller = controller,
            positionMs = model.player.positionMs,
            durationMs = model.player.durationMs,
            bufferedMs = model.player.bufferedPositionMs,
            onSeek = actions.onSeek,
            onTogglePause = actions.onTogglePause,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(barFocus)
                .focusProperties { up = topRow; down = bottomRow },
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.focusRequester(bottomRow).focusRestorer(firstIcon).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icons = listOf(
                    Triple(VodMenu.SUBTITLES, VodIcons.Subtitles, stringResource(R.string.subtitles)),
                    Triple(VodMenu.AUDIO, VodIcons.Audio, stringResource(R.string.quick_audio)),
                    Triple(VodMenu.VIDEO, VodIcons.Video, stringResource(R.string.quick_picture)),
                    Triple(VodMenu.SETTINGS, VodIcons.Settings, stringResource(R.string.osd_settings)),
                    Triple(VodMenu.SOURCES, VodIcons.Globe, stringResource(if (actions.onSources != null) R.string.osd_sources else R.string.quick_stream_info)),
                )
                icons.forEachIndexed { i, (m, icon, label) ->
                    Box {
                        OsdButton(
                            icon = icon, label = label, size = 38.dp, iconSize = 20.dp,
                            onClick = { open(m) },
                            modifier = Modifier
                                .then(if (i == 0) Modifier.focusRequester(firstIcon) else Modifier)
                                .focusRequester(iconFocus.getValue(m))
                                .focusProperties {
                                    up = barFocus; down = FocusRequester.Cancel
                                    if (i == 0) left = FocusRequester.Cancel
                                    if (i == icons.lastIndex) right = FocusRequester.Cancel
                                },
                        )
                        val shown = menu
                        val anchored = shown == m || (m == VodMenu.SETTINGS && shown in SettingsSubmenus)
                        if (anchored && shown != null) VodMenuPopup(shown, model, actions, onOpen = ::open, onClose = ::close)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                VodOsdKeys.times(model.player.positionMs, model.player.durationMs),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A round icon button; OK acts on release and only after its own press (the press that opened the OSD is not a click). */
@Composable
private fun OsdButton(
    icon: ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed = remember { booleanArrayOf(false) }
    Box(
        modifier
            .size(size)
            .background(if (focused) Color.White else Color.Transparent, CircleShape)
            .onKeyEvent { e ->
                if (!isOk(e.key)) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) pressed[0] = true
                    KeyEventType.KeyUp -> { if (pressed[0]) { pressed[0] = false; onClick() } }
                }
                true
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(iconSize))
    }
}

private fun isOk(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** Left/Right move the place at once (held: faster); half a second after release the file plays from there. OK pauses. */
@Composable
private fun SeekBar(
    controller: VodOsdController,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableLongStateOf(positionMs) }
    var scrubbing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var commit by remember { mutableStateOf<Job?>(null) }
    val okPressed = remember { booleanArrayOf(false) }
    LaunchedEffect(positionMs, scrubbing) { if (!scrubbing) shown = positionMs }
    fun end() = durationMs.takeIf { it > 0 } ?: (shown + VodOsdKeys.SKIP_MS)
    fun nudge(delta: Long) {
        controller.touch()
        commit?.cancel()
        scrubbing = true
        shown = (shown + delta).coerceIn(0, end())
    }
    fun release() {
        if (!scrubbing) return
        val target = shown
        commit?.cancel()
        commit = scope.launch {
            delay(SEEK_COMMIT_MS)
            onSeek(target)
            // The player catches up a moment later; keep the preview until then.
            delay(300)
            scrubbing = false
        }
    }
    // Left/Right (or FF/RW) while the OSD was hidden: the first step, then release as usual.
    LaunchedEffect(controller.scrubToken) {
        val delta = controller.pendingScrub
        if (delta == 0L) return@LaunchedEffect
        controller.pendingScrub = 0L
        nudge(delta)
        release()
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fraction = if (durationMs > 0) (shown.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier
            .height(28.dp)
            .onKeyEvent { e ->
                when {
                    e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> {
                        val step = VodOsdKeys.scrubStep(e.nativeKeyEvent.repeatCount)
                        when (e.type) {
                            KeyEventType.KeyDown -> nudge(if (e.key == Key.DirectionLeft) -step else step)
                            KeyEventType.KeyUp -> release()
                        }
                        true
                    }
                    isOk(e.key) -> {
                        when (e.type) {
                            KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) okPressed[0] = true
                            KeyEventType.KeyUp -> if (okPressed[0]) { okPressed[0] = false; onTogglePause(); controller.touch() }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        val played = maxWidth * fraction
        // Track, then how far the file is available ahead (lighter), then the played part.
        val buffered = maxWidth * VodOsdKeys.bufferedFraction(positionMs, bufferedMs, durationMs)
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)))
        if (buffered > 0.dp) Box(Modifier.width(buffered).height(4.dp).background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
        Box(Modifier.width(played).height(4.dp).background(Color.White, RoundedCornerShape(2.dp)))
        val thumb = if (focused) 18.dp else 12.dp
        Box(
            Modifier
                .offset(x = (played - thumb / 2).coerceIn(0.dp, maxWidth - thumb))
                .size(thumb)
                .then(if (focused) Modifier.border(4.dp, Color.White.copy(alpha = 0.35f), CircleShape) else Modifier)
                .padding(if (focused) 2.dp else 0.dp)
                .background(Color.White, CircleShape),
        )
        if (scrubbing) {
            // Seek preview: the time the file will play from.
            val label = VodOsdKeys.clock(shown)
            Text(
                label,
                color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .offset(x = (played - 32.dp).coerceIn(0.dp, maxWidth - 64.dp), y = (-26).dp)
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

private const val SEEK_COMMIT_MS = 500L

private class MenuItem(val label: String, val selected: Boolean = false, val onSelect: () -> Unit)

/** A menu above its icon: dark rounded panel, the focused row a white pill. Back closes it. */
@Composable
private fun VodMenuPopup(
    menu: VodMenu,
    model: VodOsdModel,
    actions: VodOsdActions,
    onOpen: (VodMenu) -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val anchor = remember(density) { with(density) { AboveAnchor(gapPx = 12.dp.roundToPx(), marginPx = 24.dp.roundToPx()) } }
    fun pick(action: () -> Unit): () -> Unit = { action(); onClose() }
    val off = stringResource(R.string.off)
    val title: String
    val items: List<MenuItem>
    var info: List<String> = emptyList()
    when (menu) {
        VodMenu.SUBTITLES -> {
            title = stringResource(R.string.subtitles)
            items = if (model.tracks.subtitles.isEmpty()) emptyList() else
                listOf(MenuItem(off, !model.tracks.subtitlesOn, pick { actions.onSubtitles(null) })) +
                    model.tracks.subtitles.map { t -> MenuItem(t.label.asString(), t.selected && model.tracks.subtitlesOn, pick { actions.onSubtitles(t.id) }) }
            if (items.isEmpty()) info = listOf(stringResource(R.string.quick_no_subtitles))
        }
        VodMenu.AUDIO -> {
            title = stringResource(R.string.quick_audio)
            val tracks = model.tracks.audio.map { t -> MenuItem(t.label.asString(), t.selected && model.tracks.audio.size > 1, pick { actions.onAudio(t.id) }) }
            items = tracks + actions.onPassthrough?.let { set ->
                listOf(
                    MenuItem(stringResource(R.string.quick_output_stereo), !model.hdmiPassthrough, pick { set(false) }),
                    MenuItem(stringResource(R.string.quick_output_hdmi), model.hdmiPassthrough, pick { set(true) }),
                )
            }.orEmpty()
            if (items.isEmpty()) info = listOf(stringResource(R.string.quick_none))
        }
        VodMenu.VIDEO -> {
            title = stringResource(R.string.quick_picture)
            items = PictureMode.entries.map { m -> MenuItem(stringResource(pictureLabel(m)), m == model.pictureMode, pick { actions.onPictureMode(m) }) }
        }
        VodMenu.SETTINGS -> {
            title = stringResource(R.string.osd_settings)
            items = buildList {
                if (actions.onSpeed != null) add(MenuItem(stringResource(R.string.osd_speed_value, VodOsdKeys.speedLabel(model.speed))) { onOpen(VodMenu.SPEED) })
                add(MenuItem(stringResource(R.string.osd_subtitle_delay_value, SubtitleSync.label(model.subtitleDelayMs))) { onOpen(VodMenu.SUBTITLE_DELAY) })
                add(MenuItem(stringResource(R.string.osd_audio_delay_value, AudioSync.label(model.audioDelayMs))) { onOpen(VodMenu.AUDIO_DELAY) })
                if (actions.onSleep != null) {
                    val left = com.enigmacast.domain.player.SleepTimerChoices.minutesLeft(model.sleepEndsAtMs, System.currentTimeMillis())
                    val value = if (left == 0) off else stringResource(R.string.quick_minutes, left)
                    add(MenuItem("${stringResource(R.string.sleep_title)}: $value") { onOpen(VodMenu.SLEEP) })
                }
                add(MenuItem(stringResource(R.string.file_from_start), onSelect = pick(actions.onRestart)))
            }
        }
        VodMenu.SPEED -> {
            title = stringResource(R.string.osd_speed)
            items = VodOsdKeys.SPEEDS.map { s -> MenuItem(VodOsdKeys.speedLabel(s), s == model.speed, pick { actions.onSpeed?.invoke(s) }) }
        }
        VodMenu.SUBTITLE_DELAY -> {
            title = stringResource(R.string.quick_subtitle_delay)
            items = AudioDelayChoices.values(SubtitleSync.MIN_MS, SubtitleSync.MAX_MS, SubtitleSync.STEP_MS)
                .map { ms -> MenuItem(SubtitleSync.label(ms), ms == model.subtitleDelayMs, pick { actions.onSubtitleDelay(ms) }) }
        }
        VodMenu.AUDIO_DELAY -> {
            title = stringResource(R.string.quick_audio_delay)
            items = AudioDelayChoices.values(AudioSync.MIN_MS, AudioSync.MAX_MS, AudioSync.STEP_MS)
                .map { ms -> MenuItem(AudioSync.label(ms), ms == model.audioDelayMs, pick { actions.onAudioDelay(ms) }) }
        }
        VodMenu.SLEEP -> {
            title = stringResource(R.string.sleep_title)
            val left = com.enigmacast.domain.player.SleepTimerChoices.minutesLeft(model.sleepEndsAtMs, System.currentTimeMillis())
            val minutesLabel = com.enigmacast.domain.player.SleepTimerChoices.minutes.associateWith { m -> if (m == 0) off else stringResource(R.string.quick_minutes, m) }
            items = com.enigmacast.domain.player.SleepTimerChoices.minutes.map { m ->
                MenuItem(minutesLabel.getValue(m), m == 0 && left == 0, pick { actions.onSleep?.invoke(m) })
            }
        }
        VodMenu.SOURCES -> {
            title = stringResource(if (actions.onSources != null) R.string.osd_sources else R.string.quick_stream_info)
            items = listOfNotNull(actions.onSources?.let { s -> MenuItem(stringResource(R.string.osd_other_source), onSelect = pick(s)) })
            info = streamInfo(model.player)
        }
    }
    Popup(popupPositionProvider = anchor, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        NavigationSoundsForWindow()
        Column(
            Modifier
                .widthIn(min = 240.dp, max = if (menu == VodMenu.SUBTITLES) 560.dp else 380.dp)
                .background(vodMenuBackground(), RoundedCornerShape(14.dp))
                .glassSheen(RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp))
            info.forEach { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
            if (items.isEmpty()) {
                // Something to hold the focus, so OK and Back close the menu.
                val okFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { okFocus.requestWhenReady() }
                MenuRow(MenuItem(stringResource(R.string.ok), onSelect = onClose), okFocus, first = true, last = true)
            } else {
                val start = items.indexOfFirst { it.selected }.coerceAtLeast(0)
                val list = rememberLazyListState()
                val focus = remember { FocusRequester() }
                LaunchedEffect(menu) {
                    list.scrollToItem((start - 3).coerceAtLeast(0))
                    focus.requestWhenReady()
                }
                LazyColumn(state = list, modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    itemsIndexed(items) { i, item ->
                        MenuRow(item, if (i == start) focus else null, first = i == 0, last = i == items.lastIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, focus: FocusRequester?, first: Boolean, last: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed = remember { booleanArrayOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .focusProperties {
                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                if (first) up = FocusRequester.Cancel
                if (last) down = FocusRequester.Cancel
            }
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(50))
            .onKeyEvent { e ->
                if (!isOk(e.key)) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) pressed[0] = true
                    KeyEventType.KeyUp -> if (pressed[0]) { pressed[0] = false; item.onSelect() }
                }
                true
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (focused) Color.Black else Color.White
        Text(if (item.selected) "✓" else "", color = color, fontSize = 14.sp, modifier = Modifier.width(22.dp))
        Text(item.label, color = color, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun streamInfo(p: PlayerState): List<String> = buildList {
    val video = listOf(
        if (p.videoWidth > 0) "${p.videoWidth}×${p.videoHeight}" else "",
        codecName(p.videoCodec),
        if (p.frameRate > 0f) "${p.frameRate.toInt()} fps" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
    if (video.isNotBlank()) add(stringResource(R.string.quick_info_video, video))
    val audio = p.audioLabel.asString()
    if (audio.isNotBlank()) add(stringResource(R.string.quick_info_audio, audio))
}

private fun codecName(mime: String): String = when (mime) {
    "" -> ""
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/mpeg2" -> "MPEG-2"
    "video/av01" -> "AV1"
    else -> mime.substringAfter('/').uppercase(java.util.Locale.ROOT)
}

private fun pictureLabel(mode: PictureMode) = when (mode) {
    PictureMode.AUTO -> R.string.picture_auto
    PictureMode.WIDE -> R.string.picture_wide
    PictureMode.NORMAL -> R.string.picture_normal
    PictureMode.ZOOM -> R.string.picture_zoom
    PictureMode.STRETCH -> R.string.picture_stretch
}

/** Places a menu above its icon (left-aligned with it, as in Stremio), inside the screen. */
private class AboveAnchor(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = anchorBounds.left.coerceIn(marginPx, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(marginPx)
        return IntOffset(x, y)
    }
}
