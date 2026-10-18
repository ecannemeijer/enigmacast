package com.enigmacast.ui.xtream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.xtream.IptvKeyAction
import com.enigmacast.domain.xtream.IptvPlayerKeys
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.domain.xtream.XtreamItem
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.focusListRow
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.wrapListEnds
import com.enigmacast.ui.theme.skinShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun time(sec: Long) = TimeFormat.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

/** Clock, or day and clock when the programme is not today, so the past stays readable. */
private fun guideWhen(sec: Long, nowSec: Long): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(sec).atZone(zone)
    val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
    val clock = TimeFormat.format(start)
    return if (start.toLocalDate() == today) clock else "${start.dayOfMonth}/${start.monthValue} $clock"
}

/** Which columns the side panel shows, as in the Enigma2 channel list. */
private enum class PanelColumn { CATEGORIES, CHANNELS, GUIDE, VOD_CATEGORIES, VOD_GRID }

/**
 * IPTV in full screen, laid out and driven like the Enigma2 channel player. The list opens on
 * the channels only; Right shows the focused channel's programmes, Left the categories.
 * While a live stream plays, one Back closes the channel list or the categories and stays full
 * screen; Back on the banner hides it, and Back on the bare picture leaves. Films keep their
 * own Back. With the list closed the keys follow [IptvPlayerKeys]: Up/Down zap (Up = next),
 * OK shows the banner, Down on that banner opens the options row.
 */
@Composable
fun XtreamScreen(onBack: () -> Unit, onSearch: () -> Unit = {}) {
    val model: XtreamViewModel = hiltViewModel()
    val detection by model.detection.collectAsStateWithLifecycle()
    val bindings by model.keyBindings.collectAsStateWithLifecycle()
    // Live TV from the menu starts on the last channel, so the list stays closed until playback fails.
    // A film or episode that is already playing (or about to, from the dashboard) keeps the list closed.
    val vodOnScreen = model.vodFromDashboard || model.playing?.item?.section.let { it != null && it != XtreamSection.LIVE } == true
    var panelOpen by remember { mutableStateOf(!model.openLiveFullscreen && !vodOnScreen) }
    var column by remember { mutableStateOf(PanelColumn.CATEGORIES) }
    var bannerUntil by remember { mutableStateOf(0L) }
    var bannerFromZap by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val categoryFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var openRequest by remember { mutableIntStateOf(0) }
    var quickBar by remember { mutableStateOf(false) }
    // Films, episodes and catch-up: the Stremio-style VOD OSD (keys in VodOsdKeys).
    val vodOsd = remember { com.enigmacast.ui.player.VodOsdController() }
    fun playingVod() = model.playing?.let { it.episode != null || it.item?.section == XtreamSection.MOVIES } == true || model.catchup
    LaunchedEffect(model.catchup) { if (model.catchup) vodOsd.show() }
    // Hidden again: the keys come back to the player.
    LaunchedEffect(vodOsd.visible) { if (!vodOsd.visible && vodOsd.showToken > 0) openRequest++ }
    var quickToken by remember { mutableIntStateOf(0) }

    fun showBanner(fromZap: Boolean, withDetails: Boolean = false) {
        // A film or episode has no channel banner: its OSD comes up instead.
        if (playingVod()) { vodOsd.show(); return }
        bannerFromZap = fromZap
        details = withDetails
        bannerUntil = System.currentTimeMillis() + if (withDetails) 10_000 else 5_000
    }
    fun openPanel() {
        val vod = model.section != XtreamSection.LIVE
        column = when {
            vod && model.items.isNotEmpty() -> PanelColumn.VOD_GRID
            vod -> PanelColumn.VOD_CATEGORIES
            model.items.isNotEmpty() || model.series != null -> PanelColumn.CHANNELS
            else -> PanelColumn.CATEGORIES
        }
        model.closeGuide()
        panelOpen = true
        openRequest++
    }
    val openPlayingGuide: () -> Unit = {
        val item = model.playing?.item?.takeIf { it.section == XtreamSection.LIVE }
        if (item != null) {
            model.openGuide(item)
            column = PanelColumn.GUIDE
            panelOpen = true
            quickBar = false
            openRequest++
        }
    }

    LaunchedEffect(detection) { model.open() }
    // Opened from Library: a film plays full screen, a series shows its info page over the grid.
    // The end of a film started from Home/Library also returns there.
    LaunchedEffect(model.playerState.playbackState) {
        if (model.playerState.playbackState == "ENDED" && model.launchOrigin != null && model.playing?.item?.section != XtreamSection.LIVE) {
            model.stop(); model.exitToOrigin(); onBack()
        }
    }
    LaunchedEffect(model.libraryPlayed) {
        if (model.libraryPlayed > 0 && model.playing != null) {
            panelOpen = false
            showBanner(fromZap = false)
        }
    }
    LaunchedEffect(model.liveListNeeded) {
        if (!model.liveListNeeded) return@LaunchedEffect
        panelOpen = true
        openRequest++
        model.consumeLiveListNeeded()
    }
    LaunchedEffect(model.libraryDetail) {
        // Search and Library info open the page itself; the grid must not take that focus.
        if (model.libraryDetail > 0 && model.detail == null) { panelOpen = true; column = PanelColumn.VOD_GRID; openRequest++ }
    }
    DisposableEffect(Unit) { onDispose { model.stop(); model.pip.stop() } }
    // Focus follows OK only: into the categories when a section loaded, into the list when it loaded.
    LaunchedEffect(model.categoriesReady) {
        if (model.categoriesReady > 0 && panelOpen) {
            column = if (model.section != XtreamSection.LIVE) PanelColumn.VOD_CATEGORIES
            else if (column == PanelColumn.CHANNELS || column == PanelColumn.GUIDE) PanelColumn.CATEGORIES else column
            categoryFocus.requestWhenReady()
        }
    }
    LaunchedEffect(model.itemsReady) {
        if (model.itemsReady > 0 && panelOpen) {
            if (model.section != XtreamSection.LIVE) {
                column = PanelColumn.VOD_GRID
                openRequest++
            } else {
                column = PanelColumn.CHANNELS
                itemFocus.requestWhenReady()
            }
        }
    }
    LaunchedEffect(panelOpen, openRequest, column) {
        if (model.detail != null) return@LaunchedEffect
        if (!panelOpen) { rootFocus.requestWhenReady(); return@LaunchedEffect }
        when (column) {
            PanelColumn.GUIDE -> guideFocus.requestWhenReady()
            PanelColumn.CHANNELS -> if (!itemFocus.requestWhenReady(4)) { column = PanelColumn.CATEGORIES }
            PanelColumn.CATEGORIES -> categoryFocus.requestWhenReady()
            PanelColumn.VOD_CATEGORIES -> categoryFocus.requestWhenReady()
            PanelColumn.VOD_GRID -> if (!gridFocus.requestWhenReady(4)) { column = PanelColumn.VOD_CATEGORIES }
        }
    }
    // The IPTV page is in front: background Enigma2 starts may not take the decoder from it.
    DisposableEffect(Unit) {
        model.setForeground(true)
        onDispose { model.setForeground(false) }
    }
    fun leave() {
        if (model.launchOrigin == null) model.markReturnToMenu()
        model.stop()
        model.exitToOrigin()
        onBack()
    }
    fun playingLive() = model.playing?.item?.section == XtreamSection.LIVE
    // Preview and BackHandler can both see one press; the second call in that window is ignored.
    val liveBackAt = remember { longArrayOf(0L) }
    fun liveBack() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - liveBackAt[0] < 80L) return
        liveBackAt[0] = now
        val bannerShown = System.currentTimeMillis() < bannerUntil
        when {
            quickBar -> { quickBar = false; showBanner(fromZap = false); openRequest++ }
            panelOpen && column == PanelColumn.GUIDE -> { model.closeGuide(); column = PanelColumn.CHANNELS; openRequest++ }
            panelOpen && column == PanelColumn.CHANNELS && model.series != null -> { model.closeSeries(); openRequest++ }
            panelOpen && (column == PanelColumn.CHANNELS || column == PanelColumn.CATEGORIES) -> {
                panelOpen = false
                model.closeGuide()
                openRequest++
            }
            bannerShown -> { bannerUntil = 0L; details = false; openRequest++ }
            model.pip.playing() -> model.pip.stop()
            else -> leave()
        }
    }
    BackHandler {
        if (playingLive()) {
            liveBack()
            return@BackHandler
        }
        when {
            !panelOpen && model.nextOffer() != null -> model.dismissUpcoming()
            quickBar -> { quickBar = false; openRequest++ }
            vodOsd.visible -> vodOsd.hide()
            panelOpen && column == PanelColumn.GUIDE -> { model.closeGuide(); column = PanelColumn.CHANNELS; openRequest++ }
            panelOpen && column == PanelColumn.VOD_GRID -> { column = PanelColumn.VOD_CATEGORIES; openRequest++ }
            // Channel list or an open series: one step back to the categories.
            panelOpen && column == PanelColumn.CHANNELS -> {
                if (model.series != null) model.closeSeries()
                column = PanelColumn.CATEGORIES
                openRequest++
            }
            panelOpen && (column == PanelColumn.CATEGORIES || column == PanelColumn.VOD_CATEGORIES) -> leave()
            // A film or episode in full screen: Back stops it and shows the grid again.
            // Started from Home/Library: stopping goes back there (focus on that card).
            model.launchOrigin != null && !panelOpen && model.playing != null && model.playing?.item?.section != XtreamSection.LIVE -> leave()
            model.vodFromDashboard && !panelOpen && model.playing != null && model.playing?.item?.section != XtreamSection.LIVE -> {
                model.stop()
                onBack()
            }
            !panelOpen && model.playing != null && model.playing?.item?.section != XtreamSection.LIVE -> { model.stop(); openPanel() }
            else -> leave()
        }
    }

    var nextCardFocused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                // Consume Back before the focused row can drop focus, then act once on release.
                if (model.showPipPicker || model.pip.listOpen.value) return@onPreviewKeyEvent false
                // Volgende / Annuleren have focus: Left, Right and OK belong to those buttons.
                if (nextCardFocused && model.nextOffer() != null) {
                    val steer = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                        code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                        code == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                        code == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                        code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == android.view.KeyEvent.KEYCODE_ENTER ||
                        code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (steer) return@onPreviewKeyEvent false
                }
                if (playingLive() && code == android.view.KeyEvent.KEYCODE_BACK) {
                    if (e.type == KeyEventType.KeyUp && e.nativeKeyEvent.repeatCount == 0) liveBack()
                    return@onPreviewKeyEvent true
                }
                if (panelOpen || quickBar || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (code == android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (playingVod()) {
                    if (vodOsd.onKeyDown(code, e.nativeKeyEvent.repeatCount, model::togglePause)) return@onPreviewKeyEvent true
                    // Shown: the focused OSD control takes the key.
                    if (vodOsd.visible) return@onPreviewKeyEvent false
                }
                val bannerShown = model.playing != null && System.currentTimeMillis() < bannerUntil
                val playing = model.playing
                val vod = playing != null && (playing.episode != null || playing.item?.section == XtreamSection.MOVIES)
                val repeat = e.nativeKeyEvent.repeatCount
                when (val action = IptvPlayerKeys.action(code, bindings, bannerShown, bannerFromZap, vod)) {
                    is IptvKeyAction.Zap -> if (repeat % 4 == 0) {
                        if (model.pip.playing() && model.pip.swapped.value) model.pip.zap(action.step)
                        else { model.zap(action.step); showBanner(fromZap = true) }
                    }
                    IptvKeyAction.Banner -> if (repeat == 0 && model.pip.playing() && !panelOpen) model.pip.swap()
                    else if (repeat == 0 && model.playing != null) showBanner(fromZap = false)
                    IptvKeyAction.Details -> if (repeat == 0 && model.playing != null) {
                        if (bannerShown && details) bannerUntil = 0L else showBanner(fromZap = false, withDetails = true)
                    }
                    IptvKeyAction.Options -> if (repeat == 0 && model.playing != null) {
                        if (playingVod()) vodOsd.show() else { quickBar = true; quickToken++ }
                    }
                    IptvKeyAction.ChannelList -> if (repeat == 0) {
                        if (model.pip.playing() && model.pip.swapped.value) model.pip.openList() else openPanel()
                    }
                    IptvKeyAction.Pause -> if (repeat == 0) { model.togglePause(); showBanner(fromZap = false) }
                    IptvKeyAction.None -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable(),
    ) {
        if (model.playing != null) {
            val ps = model.playerState
            val pipOn by model.pip.active.collectAsStateWithLifecycle()
            val pipSwap by model.pip.swapped.collectAsStateWithLifecycle()
            @Suppress("UNUSED_EXPRESSION") model.playerVersion
            val main = model.player()
            com.enigmacast.ui.remote.PipAudio(main, model.pip)
            com.enigmacast.ui.remote.EnigmaVideoPlayer(
                player = if (pipOn && pipSwap) model.pip.player() else main,
                frame = null,
                debugBorders = false,
                cues = model.cues,
                picture = com.enigmacast.ui.remote.VideoShape(model.pictureMode, ps.videoWidth, ps.videoHeight, ps.pixelRatio),
                modifier = Modifier.fillMaxSize(),
            )
            if (pipOn) {
                com.enigmacast.ui.remote.PipInset(
                    player = if (pipSwap) main else model.pip.player(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).size(width = 320.dp, height = 180.dp),
                )
            }
        }
        val pipList by model.pip.listOpen.collectAsStateWithLifecycle()
        val pipNames by model.pip.names.collectAsStateWithLifecycle()
        if (pipList) {
            com.enigmacast.ui.remote.PipChannelList(pipNames, model.pip::choose, model.pip::closeList)
        }
        if (model.showPipPicker) {
            com.enigmacast.ui.remote.PipPicker(
                loadCategories = model.pip::categories,
                loadChannels = model.pip::channels,
                onStart = { list, index -> model.pip.startResolved(list, index) },
                onDismiss = { model.showPipPicker = false },
            )
        }
        if (model.notice.isNotBlank()) {
            Text(
                model.notice,
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(32.dp).background(EnigmaColors.Card, skinShape(10.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        model.resumeAsk?.let { ask ->
            com.enigmacast.ui.theme.TvChoiceDialog(
                title = stringResource(R.string.resume_title),
                message = ask.title,
                choices = listOf(
                    stringResource(R.string.resume_from, com.enigmacast.domain.resume.ResumePolicy.clock(ask.positionMs)),
                    stringResource(R.string.resume_start_over),
                ),
                onChoose = { i -> model.answerResume(fromStart = i == 1) },
                onDismiss = model::dismissResume,
                compact = true,
            )
        }
        val accounts = detection?.accounts.orEmpty()
        when {
            detection == null -> Centered { EnigmaLoadingIndicator() }
            accounts.isEmpty() -> Centered { Text(stringResource(R.string.xtream_none), color = EnigmaColors.Muted, fontSize = 16.sp) }
            model.account == null -> TvChoiceDialog(
                title = stringResource(R.string.xtream_choose_playlist),
                message = stringResource(R.string.xtream_choose_playlist_desc),
                choices = accounts.map { it.name },
                onChoose = { model.choose(accounts[it], startLive = model.choosingStartsLive()) },
                onDismiss = onBack,
                stacked = true,
            )
            panelOpen && (column == PanelColumn.VOD_CATEGORIES || column == PanelColumn.VOD_GRID) -> XtreamVodPage(
                model = model,
                gridActive = column == PanelColumn.VOD_GRID,
                categoryFocus = categoryFocus,
                gridFocus = gridFocus,
                // openRequest re-runs the focus effect even when the column did not change.
                onGrid = { column = PanelColumn.VOD_GRID; openRequest++ },
                onCategories = { column = PanelColumn.VOD_CATEGORIES },
                onSections = { leave() },
                onGridFocused = { column = PanelColumn.VOD_GRID },
            ) { panelOpen = false; showBanner(fromZap = false) }
            panelOpen -> SidePanel(
                model = model,
                column = column,
                onColumn = { column = it },
                onLeave = { leave() },
                categoryFocus = categoryFocus,
                itemFocus = itemFocus,
                guideFocus = guideFocus,
            ) { panelOpen = false; model.closeGuide(); showBanner(fromZap = true) }
        }

        IptvInfoDialog(model) { panelOpen = false; showBanner(fromZap = false) }

        model.playing?.let { playing ->
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(bannerUntil) { while (true) { now = System.currentTimeMillis(); if (now > bannerUntil) break; delay(500) } }
            if (!panelOpen && playingVod()) {
                val ep = playing.episode
                com.enigmacast.ui.player.VodPlayerOsd(
                    controller = vodOsd,
                    model = com.enigmacast.ui.player.VodOsdModel(
                        title = if (ep != null) com.enigmacast.domain.player.VodOsdKeys.title(playing.item?.name ?: playing.title, null, ep.season, ep.episode, ep.title) else playing.title,
                        player = model.playerState,
                        tracks = model.tracks,
                        pictureMode = model.pictureMode,
                        audioDelayMs = model.audioDelayMs,
                        subtitleDelayMs = model.subtitleDelayMs,
                        speed = model.playbackSpeed,
                        sleepEndsAtMs = model.sleepEndsAtMs,
                    ),
                    actions = com.enigmacast.ui.player.VodOsdActions(
                        onTogglePause = model::togglePause,
                        onSeek = model::seekTo,
                        onSubtitles = model::chooseSubtitles,
                        onAudio = model::chooseAudio,
                        onPictureMode = model::choosePictureMode,
                        onAudioDelay = model::setAudioDelay,
                        onSubtitleDelay = model::setSubtitleDelay,
                        onRestart = model::restart,
                        onSpeed = model::choosePlaybackSpeed,
                        onNext = model.nextEpisode()?.let { { model.playUpcoming(); vodOsd.show() } },
                        onSleep = model::setSleepTimer,
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else if (!panelOpen && (quickBar || now < bannerUntil)) {
                IptvOsd(
                    model, playing, quickBar, quickToken, details,
                    Modifier.align(Alignment.BottomCenter),
                    showChannelBanner = !quickBar,
                    showIcons = !bannerFromZap || quickBar,
                    onGuide = openPlayingGuide,
                    onSearch = onSearch,
                )
            }
            val upcoming = model.nextOffer()
            val episodeId = playing.episode?.id
            var secondsLeft by remember(episodeId) { mutableIntStateOf(60) }
            LaunchedEffect(episodeId, upcoming?.id) {
                if (upcoming == null) return@LaunchedEffect
                secondsLeft = 60
                var left = 60
                while (left > 0) {
                    delay(1000)
                    left -= 1
                    secondsLeft = left
                }
                model.playUpcoming()
            }
            if (upcoming != null && !panelOpen) {
                NextEpisodeCard(
                    episode = upcoming,
                    seconds = secondsLeft,
                    onPlay = model::playUpcoming,
                    onCancel = model::dismissUpcoming,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .onFocusChanged { nextCardFocused = it.hasFocus }
                        .padding(end = 28.dp, bottom = if (vodOsd.visible) 230.dp else 36.dp),
                )
            }
            if (model.buffering) EnigmaLoadingIndicator(Modifier.align(Alignment.Center))
            model.playerError?.let {
                Text(
                    stringResource(R.string.xtream_play_error, it),
                    color = EnigmaColors.Danger,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).background(EnigmaColors.Card, skinShape(8.dp)).padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas), contentAlignment = Alignment.Center) { content() }
}

/** Left/Right of one column, handled before the focus search (like the Enigma2 list). */
private fun Modifier.columnKeys(onLeft: (() -> Unit)?, onRight: (() -> Unit)?): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key == Key.DirectionLeft && onLeft != null || e.key == Key.DirectionRight && onRight != null
    when (e.key) {
        Key.DirectionLeft -> { onLeft?.invoke(); true }
        Key.DirectionRight -> { onRight?.invoke(); true }
        else -> false
    }
}

@Composable
private fun SidePanel(
    model: XtreamViewModel,
    column: PanelColumn,
    onColumn: (PanelColumn) -> Unit,
    onLeave: () -> Unit,
    categoryFocus: FocusRequester,
    itemFocus: FocusRequester,
    guideFocus: FocusRequester,
    onPlayed: () -> Unit,
) {
    val hasList = if (model.series != null) model.episodes.isNotEmpty() else model.items.isNotEmpty()
    Row(
        Modifier
            .fillMaxHeight()
            .background(EnigmaColors.Canvas.copy(alpha = 0.92f))
            .padding(horizontal = EnigmaColors.SafeHorizontal / 2, vertical = EnigmaColors.SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Categories stay beside the channel list, the same way Enigma2 keeps the channels next to the bouquets.
        val liveList = model.section == XtreamSection.LIVE &&
            (column == PanelColumn.CATEGORIES || column == PanelColumn.CHANNELS || column == PanelColumn.GUIDE)
        if (liveList && column == PanelColumn.CATEGORIES) Column(
            Modifier.width(240.dp).columnKeys(
                onLeft = if (model.playing?.item?.section == XtreamSection.LIVE) null else onLeave,
                // Right only enters a list that is already there.
                onRight = { if (hasList) onColumn(PanelColumn.CHANNELS) },
            ),
        ) {
            Header(stringResource(R.string.xtream_categories), model.loading && model.categories.isEmpty())
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val firstFocus = remember { FocusRequester() }
            val lastFocus = remember { FocusRequester() }
            val lastIndex = model.categories.lastIndex
            val selectedIndex = model.categories.indexOfFirst { it.id == model.category?.id }.coerceAtLeast(0)
            LaunchedEffect(model.categories) { if (model.categories.isNotEmpty()) state.scrollToItem(selectedIndex) }
            val keyedCategories = remember(model.categories) { com.enigmacast.domain.xtream.LazyKeys.keyed(model.categories, "cat") { it.id } }
            LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                itemsIndexed(keyedCategories, key = { _, k -> k.key }) { index, k ->
                    val c = k.value
                    CompactListRow(
                        onClick = { model.selectCategory(c) },
                        selected = c.id == model.category?.id,
                        modifier = Modifier
                            .focusRequesterIf(index == selectedIndex, categoryFocus)
                            .focusRequesterIf(index == 0, firstFocus)
                            .focusRequesterIf(index == lastIndex && lastIndex > 0, lastFocus)
                            .wrapListEnds(
                                index = index,
                                lastIndex = lastIndex,
                                onWrapToFirst = { scope.launch { firstFocus.focusListRow(state, 0) } },
                                onWrapToLast = { scope.launch { lastFocus.focusListRow(state, lastIndex) } },
                            ),
                    ) {
                        Text(c.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (model.pendingCategory?.id == c.id) EnigmaLoadingIndicator(Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        // Films / Series show only the sections here; their categories and grid are XtreamVodPage.
        if (liveList) {
        // Column 3: channels, films, series or the episodes of the opened series.
        var focused by remember { mutableStateOf<XtreamItem?>(null) }
        val guideOpen = column == PanelColumn.GUIDE
        Column(
            Modifier
                .width(if (model.series != null) 420.dp else if (guideOpen) 460.dp else 600.dp)
                .then(if (model.series == null) Modifier.padding(horizontal = 12.dp, vertical = 20.dp) else Modifier)
                .columnKeys(
                onLeft = {
                    when {
                        model.series != null -> model.closeSeries()
                        column == PanelColumn.CHANNELS || column == PanelColumn.GUIDE -> {
                            model.closeGuide()
                            onColumn(PanelColumn.CATEGORIES)
                        }
                        else -> runCatching { categoryFocus.requestFocus() }
                    }
                },
                onRight = {
                    val item = focused
                    if (model.series == null && item != null && item.section == XtreamSection.LIVE) {
                        model.openGuide(item)
                        onColumn(PanelColumn.GUIDE)
                    }
                },
            ),
        ) {
            val series = model.series
            if (series != null) Header(series.name, model.loading)
            else LiveListHeader(model.category?.name.orEmpty(), model.loading, guideOpen || column == PanelColumn.CATEGORIES)
            if (model.error) Text(stringResource(R.string.xtream_load_error), color = EnigmaColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            val state = rememberLazyListState()
            if (series != null) {
                val keyedEpisodes = remember(model.episodes) { com.enigmacast.domain.xtream.LazyKeys.keyed(model.episodes, "ep") { it.id } }
                LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                    itemsIndexed(keyedEpisodes, key = { _, k -> k.key }) { index, k ->
                        val e = k.value
                        CompactListRow(
                            onClick = { model.holdEpisodes(model.episodes); model.requestEpisode(e, onStarted = onPlayed) },
                            selected = model.playing?.episode?.id == e.id,
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(itemFocus) else Modifier)
                                .focusProperties {
                                    if (index == 0) up = FocusRequester.Cancel
                                    if (index == model.episodes.lastIndex) down = FocusRequester.Cancel
                                },
                        ) {
                            Text(if (e.season > 0) "S${e.season}E${e.episode}" else "${e.episode}", fontSize = 13.sp, color = EnigmaColors.Accent, modifier = Modifier.width(64.dp))
                            Text(e.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                val playingIndex = model.items.indexOfFirst { it.id == model.playing?.item?.id }.coerceAtLeast(0)
                LaunchedEffect(model.items) { if (model.items.isNotEmpty()) state.scrollToItem(playingIndex) }
                // Now/next for the rows on screen only; a scroll cancels the batch that was waiting.
                LaunchedEffect(state, model.items) {
                    snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }
                        .distinctUntilChanged()
                        .collect { rows -> model.requestEpg(rows.mapNotNull { model.items.getOrNull(it) }) }
                }
                var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
                LaunchedEffect(Unit) { while (true) { delay(30_000); nowSec = System.currentTimeMillis() / 1000 } }
                val scope = rememberCoroutineScope()
                val firstFocus = remember { FocusRequester() }
                val lastFocus = remember { FocusRequester() }
                val lastIndex = model.items.lastIndex
                val keyedItems = remember(model.items) { com.enigmacast.domain.xtream.LazyKeys.keyed(model.items, "live") { "${it.section.name}:${it.id}" } }
                LazyColumn(state = state, modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    itemsIndexed(keyedItems, key = { _, k -> k.key }) { index, k ->
                        val item = k.value
                        val now = model.epg[item.id]?.let { nowNext(it, nowSec) }?.first
                        CompactListRow(
                            onClick = { model.play(item); if (item.section != XtreamSection.SERIES) onPlayed() },
                            selected = model.playing?.item?.id == item.id || model.guideItem?.id == item.id,
                            modifier = Modifier
                                .focusRequesterIf(index == playingIndex, itemFocus)
                                .focusRequesterIf(index == 0, firstFocus)
                                .focusRequesterIf(index == lastIndex && lastIndex > 0, lastFocus)
                                .onFocusChanged { if (it.isFocused) { focused = item; model.focusLive(item) } }
                                .wrapListEnds(
                                    index = index,
                                    lastIndex = lastIndex,
                                    onWrapToFirst = { scope.launch { firstFocus.focusListRow(state, 0) } },
                                    onWrapToLast = { scope.launch { lastFocus.focusListRow(state, lastIndex) } },
                                ),
                        ) { rowFocused ->
                            IptvChannelRow(model, item, now, nowSec, rowFocused)
                        }
                    }
                }
            }
        }
        }
        if (column == PanelColumn.GUIDE) {
            Box(Modifier.fillMaxHeight().width(1.dp).background(EnigmaColors.Raised))
            IptvEpgPanel(
                model,
                guideFocus,
                onLeft = { model.closeGuide(); onColumn(PanelColumn.CHANNELS) },
            ) { event ->
                val item = model.guideItem
                if (item != null && com.enigmacast.domain.xtream.Catchup.canPlay(item, event, System.currentTimeMillis() / 1000)) model.playCatchup(item, event)
                else item?.let { model.play(it) }
                onPlayed()
            }
        }
    }
}

@Composable
private fun Header(text: String, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
        Text(text, color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (loading) EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
    }
}

/** Same header as the Enigma2 channel list: title, category, and the Left/Right hint. */
@Composable
private fun LiveListHeader(category: String, loading: Boolean, guideOpen: Boolean) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
        Text(stringResource(R.string.osd_channels), color = Color.White, fontSize = 17.sp, fontWeight = EnigmaType.Strong)
        Text(
            category,
            color = EnigmaColors.Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp, bottom = 2.dp),
        )
        if (loading) EnigmaLoadingIndicator(Modifier.padding(end = 8.dp, bottom = 2.dp))
        if (!guideOpen) {
            Text(stringResource(R.string.osd_bouquets_hint), color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** One dense channel row: number, logo, name, start time, title and the progress bar. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.IptvChannelRow(
    model: XtreamViewModel,
    item: XtreamItem,
    now: XtreamEpg?,
    nowSec: Long,
    focused: Boolean,
) {
    val secondary = if (focused) EnigmaColors.OnFocus.copy(alpha = 0.8f) else EnigmaColors.Muted
    Text(
        if (item.number > 0) "${item.number}" else "",
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier.width(34.dp),
    )
    ChannelLogo(model, item.icon, item.name)
    Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(0.38f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (item.hasArchive) {
                Text(
                    stringResource(R.string.catchup_badge),
                    color = EnigmaColors.OnAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .background(EnigmaColors.Accent, skinShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    Spacer(Modifier.width(8.dp))
    Column(modifier = Modifier.weight(0.62f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                now?.let { time(it.startSec) }.orEmpty(),
                color = secondary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.width(36.dp),
            )
            Text(
                now?.title.orEmpty(),
                color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (now != null) {
            val total = (now.endSec - now.startSec).coerceAtLeast(1)
            EnigmaProgramProgress(
                fraction = ((nowSec - now.startSec).toFloat() / total).coerceIn(0f, 1f),
                modifier = Modifier.padding(start = 36.dp, top = 2.dp),
                emphasized = !focused,
            )
        }
    }
}

@Composable
private fun ChannelLogo(model: XtreamViewModel, url: String, name: String) {
    val bitmap by produceState<ImageBitmap?>(model.cachedLogo(url), url) {
        if (value == null && url.isNotBlank()) value = model.logo(url)
    }
    Box(Modifier.width(36.dp).height(22.dp), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(10), color = EnigmaColors.Muted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The channel's programmes, laid out like the Enigma2 panel. Left closes it.
 * OK plays the channel; IPTV has no receiver recording from this list.
 */
@Composable
private fun IptvEpgPanel(
    model: XtreamViewModel,
    guideFocus: FocusRequester,
    onLeft: () -> Unit,
    onPlay: (XtreamEpg) -> Unit,
) {
    val events = model.guideEvents
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    val nowIndex = events.indexOfFirst { it.startSec <= nowSec && nowSec < it.endSec }
        .let { if (it >= 0) it else events.indexOfFirst { event -> event.startSec >= nowSec }.coerceAtLeast(0) }
    var focusedIndex by remember(model.guideItem?.id) { mutableIntStateOf(nowIndex) }
    val listState = rememberLazyListState()
    LaunchedEffect(model.guideItem?.id, events.size) {
        if (events.isEmpty()) return@LaunchedEffect
        focusedIndex = nowIndex
        listState.scrollToItem(nowIndex)
        guideFocus.requestWhenReady()
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(480.dp)
            .padding(horizontal = 12.dp, vertical = 20.dp)
            .columnKeys(onLeft = onLeft, onRight = {}),
    ) {
        Text(
            model.guideItem?.name.orEmpty(),
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = EnigmaType.Strong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(stringResource(R.string.epg_panel_hint), color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (model.guideLoading) EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
        }
        if (events.isEmpty()) {
            CompactListRow(onClick = {}, modifier = Modifier.focusRequester(guideFocus)) {
                Text(
                    stringResource(if (model.guideLoading) R.string.epg_loading else R.string.osd_no_programme_data),
                    fontSize = 13.sp,
                )
            }
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(events, key = { i, e -> "${e.startSec}/$i" }) { index, event ->
                val airing = event.startSec <= nowSec && nowSec < event.endSec
                val catchup = model.guideItem?.let { com.enigmacast.domain.xtream.Catchup.canPlay(it, event, nowSec) } == true
                CompactListRow(
                    onClick = { onPlay(event) },
                    selected = airing,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .then(if (index == nowIndex) Modifier.focusRequester(guideFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedIndex = index }
                        .focusProperties {
                            if (index == 0) up = FocusRequester.Cancel
                            if (index == events.lastIndex) down = FocusRequester.Cancel
                        },
                ) { rowFocused ->
                    val secondary = if (rowFocused) EnigmaColors.OnFocus.copy(alpha = 0.8f) else EnigmaColors.Muted
                    Text(
                        guideWhen(event.startSec, nowSec),
                        color = if (catchup && !rowFocused) EnigmaColors.Accent else secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.width(78.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                event.title,
                                fontSize = 13.sp,
                                fontWeight = if (airing) EnigmaType.Strong else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.quick_minutes, ((event.endSec - event.startSec) / 60).toInt()),
                                color = secondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (airing) {
                            val total = (event.endSec - event.startSec).coerceAtLeast(1)
                            GuideBar(((nowSec - event.startSec).toFloat() / total).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(top = 3.dp))
                        }
                    }
                }
            }
        }
        val selected = events.getOrNull(focusedIndex) ?: events.first()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(top = 10.dp)
                .background(EnigmaColors.Card, skinShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(selected.title, color = Color.White, fontSize = 15.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(programmeWhen(selected, nowSec), color = EnigmaColors.Accent, fontSize = 12.sp)
            Text(
                selected.description,
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GuideBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).background(EnigmaColors.Raised, skinShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(EnigmaColors.Accent, skinShape(2.dp)))
    }
}

@Composable
private fun programmeWhen(event: XtreamEpg, nowSec: Long): String {
    val range = stringResource(
        R.string.epg_time_range,
        time(event.startSec),
        time(event.endSec),
        ((event.endSec - event.startSec) / 60).toInt(),
    )
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochSecond(event.startSec).atZone(zone).toLocalDate()
    val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
    val label = when (day) {
        today -> stringResource(R.string.epg_today)
        today.plusDays(1) -> stringResource(R.string.epg_tomorrow)
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", LocalConfiguration.current.locales[0] ?: Locale.getDefault()))
    }
    return label + "  " + range
}

/** The programme on now and the next one. */
internal fun nowNext(list: List<XtreamEpg>, nowSec: Long = System.currentTimeMillis() / 1000): Pair<XtreamEpg?, XtreamEpg?> {
    val now = list.firstOrNull { it.startSec <= nowSec && nowSec < it.endSec }
    val next = list.firstOrNull { it.startSec >= (now?.endSec ?: nowSec) }
    return now to next
}

/**
 * The same OSD as for Enigma2 channels, in the selected GUI skin, with the options row under
 * it. Satellite slots (position, signal/SNR, tuner, CA/FTA, transponder) are switched off and the
 * options row only has the player's own options.
 */
@Composable
private fun IptvOsd(
    model: XtreamViewModel,
    playing: XtreamPlaying,
    quickBar: Boolean,
    quickToken: Int,
    details: Boolean,
    modifier: Modifier,
    showChannelBanner: Boolean = true,
    showIcons: Boolean = true,
    onGuide: () -> Unit,
    onSearch: () -> Unit,
) {
    val look by model.osdLook.collectAsStateWithLifecycle()
    val scrim = EnigmaColors.scrim(look.second)
    val item = playing.item
    val logo = item?.icon.orEmpty()
    val entry = com.enigmacast.domain.model.BouquetEntry(
        name = playing.title,
        reference = "iptv:${item?.id.orEmpty()}:${playing.episode?.id.orEmpty()}",
        number = item?.number?.takeIf { item.section == XtreamSection.LIVE } ?: 0,
        picon = logo,
    )
    val guide = item?.takeIf { it.section == XtreamSection.LIVE }?.let { model.epg[it.id] }?.let { IptvOsdModel.guide(entry, it) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showChannelBanner) {
            com.enigmacast.ui.remote.ChannelBanner(
                channel = entry,
                number = entry.number,
                guide = guide,
                player = model.playerState,
                tuner = com.enigmacast.live.TunerState(),
                details = details,
                optionsHint = !quickBar,
                recording = false,
                osdInfo = IptvOsdModel.INFO,
                osdFields = IptvOsdModel.FIELDS,
                osdSkin = look.first,
                picon = { model.logo(it.picon) },
                cachedPicon = { model.cachedLogo(it.picon) },
                scrim = scrim,
                docked = showIcons,
            )
        }
        if (showIcons) {
            val ui = com.enigmacast.ui.remote.RemoteDisplayUiState(
                playerState = model.playerState,
                tracks = model.tracks,
                pictureMode = model.pictureMode,
                audioDelayMs = model.audioDelayMs,
                audioDelayPassthrough = model.audioDelayPassthrough,
                subtitleDelayMs = model.subtitleDelayMs,
                sleepEndsAtMs = model.sleepEndsAtMs,
                recent = model.recentChannels,
                quickBarToken = quickToken,
            )
            com.enigmacast.ui.remote.QuickOptionsBar(
                ui = ui,
                actions = com.enigmacast.ui.remote.QuickBarActions(
                    onSubtitles = model::chooseSubtitles,
                    onAudio = model::chooseAudio,
                    onPassthrough = {},
                    onPictureMode = model::choosePictureMode,
                    onAudioDelay = model::setAudioDelay,
                    onSubtitleDelay = model::setSubtitleDelay,
                    onGuide = onGuide,
                    onRecent = model::playRecent,
                    onSleep = model::setSleepTimer,
                    onRestart = model::restart,
                    onQuality = {},
                    onRecord = {},
                    onSearch = {
                        model.stop()
                        onSearch()
                    },
                    onPip = model::openPipPicker,
                ),
                iptv = true,
                interactive = quickBar,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}
