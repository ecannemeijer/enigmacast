package com.enigmacast.ui.xtream

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
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
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun time(sec: Long) = TimeFormat.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

/** Which columns the side panel shows, as in the Enigma2 channel list. */
private enum class PanelColumn { SECTIONS, CATEGORIES, CHANNELS, GUIDE, VOD_CATEGORIES, VOD_GRID }

/**
 * IPTV in full screen, laid out and driven like the Enigma2 channel player. The list opens on
 * the channels only; Right shows the focused channel's programmes, Left the categories, Left
 * again Live TV / Films / Series. Categories still load on OK. With the list closed the keys
 * follow [IptvPlayerKeys]: Up/Down zap (Up = next), OK shows the banner, Down on that banner
 * opens the options row.
 */
@Composable
fun XtreamScreen(onBack: () -> Unit) {
    val model: XtreamViewModel = hiltViewModel()
    val detection by model.detection.collectAsStateWithLifecycle()
    val bindings by model.keyBindings.collectAsStateWithLifecycle()
    var panelOpen by remember { mutableStateOf(true) }
    var column by remember { mutableStateOf(PanelColumn.CHANNELS) }
    var bannerUntil by remember { mutableStateOf(0L) }
    var bannerFromZap by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val sectionFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var openRequest by remember { mutableIntStateOf(0) }
    var quickBar by remember { mutableStateOf(false) }
    // Films and episodes: the recordings' seek bar (OK, Left/Right, FF/RW show it; Down opens the options).
    var vodBar by remember { mutableStateOf(false) }
    var vodBarTouch by remember { mutableIntStateOf(0) }
    LaunchedEffect(vodBar, vodBarTouch, quickBar) {
        if (vodBar && !quickBar) { delay(VOD_BAR_MS); vodBar = false; openRequest++ }
    }
    var quickToken by remember { mutableIntStateOf(0) }

    fun showBanner(fromZap: Boolean, withDetails: Boolean = false) {
        bannerFromZap = fromZap
        details = withDetails
        bannerUntil = System.currentTimeMillis() + if (withDetails) 10_000 else 5_000
    }
    fun openPanel() {
        val vod = model.section != XtreamSection.LIVE
        column = when {
            vod && model.items.isNotEmpty() -> PanelColumn.VOD_GRID
            vod && model.categories.isNotEmpty() -> PanelColumn.VOD_CATEGORIES
            vod -> PanelColumn.SECTIONS
            model.items.isNotEmpty() || model.series != null -> PanelColumn.CHANNELS
            model.categories.isNotEmpty() -> PanelColumn.CATEGORIES
            else -> PanelColumn.SECTIONS
        }
        model.closeGuide()
        panelOpen = true
        openRequest++
    }

    LaunchedEffect(detection) { model.open() }
    DisposableEffect(Unit) { onDispose { model.stop() } }
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
        if (!panelOpen) { rootFocus.requestWhenReady(); return@LaunchedEffect }
        when (column) {
            PanelColumn.GUIDE -> guideFocus.requestWhenReady()
            PanelColumn.CHANNELS -> if (!itemFocus.requestWhenReady(4)) { column = PanelColumn.CATEGORIES }
            PanelColumn.CATEGORIES -> if (!categoryFocus.requestWhenReady(4)) { column = PanelColumn.SECTIONS }
            PanelColumn.SECTIONS -> sectionFocus.requestWhenReady()
            PanelColumn.VOD_CATEGORIES -> if (!categoryFocus.requestWhenReady(4)) { column = PanelColumn.SECTIONS }
            PanelColumn.VOD_GRID -> if (!gridFocus.requestWhenReady(4)) { column = PanelColumn.VOD_CATEGORIES }
        }
    }
    BackHandler {
        when {
            quickBar -> { quickBar = false; vodBar = false; bannerUntil = 0L; openRequest++ }
            vodBar -> { vodBar = false; openRequest++ }
            panelOpen && column == PanelColumn.GUIDE -> { model.closeGuide(); column = PanelColumn.CHANNELS }
            // Films / Series: grid → categories → Live TV / Films / Series.
            panelOpen && column == PanelColumn.VOD_GRID -> { column = PanelColumn.VOD_CATEGORIES; openRequest++ }
            panelOpen && column == PanelColumn.VOD_CATEGORIES -> { column = PanelColumn.SECTIONS; openRequest++ }
            // A film or episode in full screen: Back stops it and shows the grid again.
            !panelOpen && model.playing != null && model.playing?.item?.section != XtreamSection.LIVE -> { model.stop(); openPanel() }
            model.series != null && panelOpen -> model.closeSeries()
            panelOpen && model.playing != null -> panelOpen = false
            else -> { model.stop(); onBack() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (panelOpen || quickBar || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                if (code == android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                val playingVod = model.playing?.let { it.episode != null || it.item?.section == XtreamSection.MOVIES } == true
                if (playingVod) {
                    when (VodBarKeys.action(code, vodBar)) {
                        VodBarKeys.Action.SHOW -> { if (e.nativeKeyEvent.repeatCount == 0) { vodBar = true; vodBarTouch++ }; return@onPreviewKeyEvent true }
                        VodBarKeys.Action.FORWARD -> { model.seekBy(VodBarKeys.FF_MS); vodBar = true; vodBarTouch++; return@onPreviewKeyEvent true }
                        VodBarKeys.Action.REWIND -> { model.seekBy(-VodBarKeys.FF_MS); vodBar = true; vodBarTouch++; return@onPreviewKeyEvent true }
                        VodBarKeys.Action.PAUSE -> { if (e.nativeKeyEvent.repeatCount == 0) { model.togglePause(); vodBarTouch++ }; return@onPreviewKeyEvent true }
                        VodBarKeys.Action.BAR -> { vodBarTouch++; return@onPreviewKeyEvent false }
                        VodBarKeys.Action.PLAYER -> Unit
                    }
                }
                val bannerShown = model.playing != null && System.currentTimeMillis() < bannerUntil
                val playing = model.playing
                val vod = playing != null && (playing.episode != null || playing.item?.section == XtreamSection.MOVIES)
                val repeat = e.nativeKeyEvent.repeatCount
                when (val action = IptvPlayerKeys.action(code, bindings, bannerShown, bannerFromZap, vod)) {
                    is IptvKeyAction.Zap -> if (repeat % 4 == 0) { model.zap(action.step); showBanner(fromZap = true) }
                    IptvKeyAction.Banner -> if (repeat == 0 && model.playing != null) showBanner(fromZap = false)
                    IptvKeyAction.Details -> if (repeat == 0 && model.playing != null) {
                        if (bannerShown && details) bannerUntil = 0L else showBanner(fromZap = false, withDetails = true)
                    }
                    IptvKeyAction.Options -> if (repeat == 0 && model.playing != null) { quickBar = true; quickToken++ }
                    IptvKeyAction.ChannelList -> if (repeat == 0) openPanel()
                    IptvKeyAction.Pause -> if (repeat == 0) { model.togglePause(); showBanner(fromZap = false) }
                    IptvKeyAction.None -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable(),
    ) {
        if (model.playing != null) {
            val ps = model.playerState
            com.enigmacast.ui.remote.EnigmaVideoPlayer(
                player = model.player(),
                frame = null,
                debugBorders = false,
                cues = emptyList(),
                picture = com.enigmacast.ui.remote.VideoShape(model.pictureMode, ps.videoWidth, ps.videoHeight, ps.pixelRatio),
                modifier = Modifier.fillMaxSize(),
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
                onChoose = { model.choose(accounts[it]) },
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
                onSections = { column = PanelColumn.SECTIONS; openRequest++ },
                onGridFocused = { column = PanelColumn.VOD_GRID },
            ) { panelOpen = false; showBanner(fromZap = false) }
            panelOpen -> SidePanel(
                model = model,
                column = column,
                onColumn = { column = it },
                sectionFocus = sectionFocus,
                categoryFocus = categoryFocus,
                itemFocus = itemFocus,
                guideFocus = guideFocus,
            ) { panelOpen = false; model.closeGuide(); showBanner(fromZap = true) }
        }

        model.playing?.let { playing ->
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(bannerUntil) { while (true) { now = System.currentTimeMillis(); if (now > bannerUntil) break; delay(500) } }
            if (!panelOpen && vodBar) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    com.enigmacast.ui.remote.RecordingBar(
                        title = playing.title,
                        positionMs = model.playerState.positionMs,
                        durationMs = model.playerState.durationMs,
                        playing = model.playerState.playing,
                        onSeek = { model.seekTo(it); vodBarTouch++ },
                        onTogglePause = { model.togglePause(); vodBarTouch++ },
                        onOptions = { quickBar = true; quickToken++ },
                        onTouch = { vodBarTouch++ },
                    )
                    if (quickBar) IptvOsd(model, playing, true, quickToken, details, Modifier)
                }
            } else if (!panelOpen && (quickBar || now < bannerUntil)) {
                IptvOsd(model, playing, quickBar, quickToken, details, Modifier.align(Alignment.BottomCenter))
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
    sectionFocus: FocusRequester,
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
        // Column 1: Live / Films / Series and the playlist name (Left from the categories).
        if (column == PanelColumn.SECTIONS) Column(
            Modifier.width(150.dp).columnKeys(onLeft = {}, onRight = {
                if (model.categories.isNotEmpty()) onColumn(if (model.section == XtreamSection.LIVE) PanelColumn.CATEGORIES else PanelColumn.VOD_CATEGORIES)
            }),
        ) {
            Text(model.account?.name.orEmpty(), color = EnigmaColors.Accent, fontSize = 13.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            XtreamSection.entries.forEachIndexed { index, s ->
                val label = stringResource(
                    when (s) {
                        XtreamSection.LIVE -> R.string.xtream_live
                        XtreamSection.MOVIES -> R.string.xtream_movies
                        XtreamSection.SERIES -> R.string.xtream_series
                    },
                )
                CompactListRow(
                    onClick = {
                        // OK on the section that is already loaded only moves on.
                        if (s == model.section && model.categories.isNotEmpty()) {
                            onColumn(if (s == XtreamSection.LIVE) PanelColumn.CATEGORIES else PanelColumn.VOD_CATEGORIES)
                        } else {
                            model.selectSection(s)
                        }
                    },
                    selected = s == model.section,
                    modifier = Modifier
                        .then(if (s == model.section) Modifier.focusRequester(sectionFocus) else Modifier)
                        .focusProperties { if (index == 0) up = FocusRequester.Cancel },
                ) { Text(label, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            CompactListRow(
                onClick = model::refresh,
                modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
            ) { Text(stringResource(R.string.xtream_refresh), fontSize = 13.sp, color = EnigmaColors.Muted) }
        }
        // Column 2: categories (Left from the channels). OK loads one.
        if (model.section == XtreamSection.LIVE && (column == PanelColumn.SECTIONS || column == PanelColumn.CATEGORIES)) Column(
            Modifier.width(240.dp).columnKeys(
                onLeft = { onColumn(PanelColumn.SECTIONS) },
                // Right only enters a list that is already there.
                onRight = { if (hasList) onColumn(PanelColumn.CHANNELS) },
            ),
        ) {
            Header(stringResource(R.string.xtream_categories), model.loading && model.categories.isEmpty())
            val state = rememberLazyListState()
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
                            .then(if (index == selectedIndex) Modifier.focusRequester(categoryFocus) else Modifier)
                            .focusProperties {
                                if (index == 0) up = FocusRequester.Cancel
                                if (index == model.categories.lastIndex) down = FocusRequester.Cancel
                            },
                    ) {
                        Text(c.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (model.pendingCategory?.id == c.id) EnigmaLoadingIndicator(Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        // Films / Series show only the sections here; their categories and grid are XtreamVodPage.
        if (model.section == XtreamSection.LIVE) {
        // Column 3: channels, films, series or the episodes of the opened series.
        var focused by remember { mutableStateOf<XtreamItem?>(null) }
        Column(
            Modifier.width(420.dp).columnKeys(
                onLeft = {
                    when {
                        model.series != null -> model.closeSeries()
                        column == PanelColumn.CHANNELS || column == PanelColumn.GUIDE -> onColumn(PanelColumn.CATEGORIES)
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
            Header(series?.name ?: model.category?.name.orEmpty(), model.loading)
            if (model.error) Text(stringResource(R.string.xtream_load_error), color = EnigmaColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            val state = rememberLazyListState()
            if (series != null) {
                val keyedEpisodes = remember(model.episodes) { com.enigmacast.domain.xtream.LazyKeys.keyed(model.episodes, "ep") { it.id } }
                LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                    itemsIndexed(keyedEpisodes, key = { _, k -> k.key }) { index, k ->
                        val e = k.value
                        CompactListRow(
                            onClick = { model.requestEpisode(e, onStarted = onPlayed) },
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
                var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
                LaunchedEffect(Unit) { while (true) { delay(30_000); nowSec = System.currentTimeMillis() / 1000 } }
                val keyedItems = remember(model.items) { com.enigmacast.domain.xtream.LazyKeys.keyed(model.items, "live") { "${it.section.name}:${it.id}" } }
                LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                    itemsIndexed(keyedItems, key = { _, k -> k.key }) { index, k ->
                        val item = k.value
                        CompactListRow(
                            onClick = { model.play(item); if (item.section != XtreamSection.SERIES) onPlayed() },
                            selected = model.playing?.item?.id == item.id || model.guideItem?.id == item.id,
                            modifier = Modifier
                                .then(if (index == playingIndex) Modifier.focusRequester(itemFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) { focused = item; model.focusLive(item) } }
                                .focusProperties {
                                    if (index == 0) up = FocusRequester.Cancel
                                    if (index == model.items.lastIndex) down = FocusRequester.Cancel
                                },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (item.number > 0 && item.section == XtreamSection.LIVE) {
                                        Text("${item.number}", fontSize = 13.sp, color = EnigmaColors.Accent, modifier = Modifier.width(44.dp))
                                    }
                                    Text(item.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                model.epg[item.id]?.let { nowNext(it, nowSec) }?.first?.let { now ->
                                    Text("${time(now.startSec)} ${now.title}", fontSize = 11.sp, color = EnigmaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val total = (now.endSec - now.startSec).coerceAtLeast(1)
                                    EnigmaProgramProgress(((nowSec - now.startSec).toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        // Column 4: the programmes of the channel Right was pressed on.
        if (column == PanelColumn.GUIDE) Column(
            Modifier.width(380.dp).columnKeys(onLeft = { model.closeGuide(); onColumn(PanelColumn.CHANNELS) }, onRight = {}),
        ) {
            Header(model.guideItem?.name.orEmpty(), model.guideLoading)
            val events = model.guideEvents
            if (events.isEmpty() && !model.guideLoading) {
                Text(stringResource(R.string.osd_no_programme_data), color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp).focusRequester(guideFocus).focusable())
            }
            LazyColumn(Modifier.fillMaxHeight()) {
                itemsIndexed(events, key = { i, e -> "${e.startSec}/$i" }) { index, e ->
                    CompactListRow(
                        onClick = { model.guideItem?.let { model.play(it) }; onPlayed() },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(guideFocus) else Modifier)
                            .focusProperties {
                                if (index == 0) up = FocusRequester.Cancel
                                if (index == events.lastIndex) down = FocusRequester.Cancel
                            },
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row {
                                Text("${time(e.startSec)} – ${time(e.endSec)}", fontSize = 13.sp, color = EnigmaColors.Accent, modifier = Modifier.width(110.dp))
                                Text(e.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (e.description.isNotBlank()) Text(e.description, fontSize = 11.sp, color = EnigmaColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
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
private fun IptvOsd(model: XtreamViewModel, playing: XtreamPlaying, quickBar: Boolean, quickToken: Int, details: Boolean, modifier: Modifier) {
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
    Column(modifier) {
        if (!quickBar) {
            com.enigmacast.ui.remote.ChannelBanner(
                channel = entry,
                number = entry.number,
                guide = guide,
                player = model.playerState,
                tuner = com.enigmacast.live.TunerState(),
                details = details,
                optionsHint = true,
                recording = false,
                osdInfo = IptvOsdModel.INFO,
                osdFields = IptvOsdModel.FIELDS,
                osdSkin = look.first,
                picon = { model.logo(it.picon) },
                cachedPicon = { model.cachedLogo(it.picon) },
                scrim = scrim,
            )
        } else {
            val ui = com.enigmacast.ui.remote.RemoteDisplayUiState(
                playerState = model.playerState,
                tracks = model.tracks,
                pictureMode = model.pictureMode,
                quickBarToken = quickToken,
            )
            com.enigmacast.ui.remote.QuickOptionsBar(
                ui = ui,
                actions = remember(model) {
                    com.enigmacast.ui.remote.QuickBarActions(
                        onSubtitles = model::chooseSubtitles,
                        onAudio = model::chooseAudio,
                        onPassthrough = {},
                        onPictureMode = model::choosePictureMode,
                        onAudioDelay = {},
                        onGuide = {},
                        onRecent = {},
                        onSleep = {},
                        onRestart = model::restart,
                        onQuality = {},
                        onRecord = {},
                        onSearch = {},
                    )
                },
                iptv = true,
                modifier = Modifier
                    .padding(start = 44.dp, end = 44.dp, bottom = 24.dp)
                    .background(scrim, skinShape(14.dp))
                    .padding(8.dp),
            )
        }
    }
}

private const val VOD_BAR_MS = 5_000L

/**
 * Keys while a film or episode plays in full screen. Without the bar, OK / Left / Right show it;
 * with it, the D-pad belongs to the bar (line, ±30, pause, Down to the options). FF / RW skip
 * and Play/Pause toggles in both cases; everything else goes to the IPTV player keys.
 */
internal object VodBarKeys {
    const val FF_MS = 30_000L

    enum class Action { SHOW, FORWARD, REWIND, PAUSE, BAR, PLAYER }

    fun action(keyCode: Int, barShown: Boolean): Action = when (keyCode) {
        android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.FORWARD
        android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> Action.REWIND
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> Action.PAUSE
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (barShown) Action.BAR else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) Action.PLAYER else Action.SHOW
        else -> if (barShown) Action.BAR else Action.PLAYER
    }
}
