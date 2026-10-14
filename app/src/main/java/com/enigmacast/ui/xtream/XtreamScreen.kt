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
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.remote.EnigmaVideoTexture
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

/**
 * IPTV (XStreamity playlists) in full screen. The side panel has three columns like the
 * player's bouquet panel: Live/Films/Series, the Xtream categories, and the channels, films,
 * series or episodes. Left walks out one column, Right walks in; Up/Down stay in a column.
 * With the panel closed, Left or OK opens it and Channel up/down zap live channels.
 */
@Composable
fun XtreamScreen(onBack: () -> Unit) {
    val model: XtreamViewModel = hiltViewModel()
    val detection by model.detection.collectAsStateWithLifecycle()
    var panelOpen by remember { mutableStateOf(true) }
    var bannerUntil by remember { mutableStateOf(0L) }
    val sectionFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var openRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(detection) { model.open() }
    DisposableEffect(Unit) { onDispose { model.stop() } }
    LaunchedEffect(panelOpen, openRequest, model.items.isNotEmpty(), model.categories.isNotEmpty()) {
        if (panelOpen) {
            if (!itemFocus.requestWhenReady(4) && !categoryFocus.requestWhenReady(4)) sectionFocus.requestWhenReady()
        } else {
            rootFocus.requestWhenReady()
        }
    }
    BackHandler {
        when {
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
                if (panelOpen || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionCenter, Key.Enter, Key.Menu -> { panelOpen = true; openRequest++; true }
                    Key.DirectionUp, Key.ChannelUp -> { model.zap(-1); bannerUntil = System.currentTimeMillis() + 5_000; true }
                    Key.DirectionDown, Key.ChannelDown -> { model.zap(1); bannerUntil = System.currentTimeMillis() + 5_000; true }
                    Key.Info, Key.DirectionRight -> { bannerUntil = System.currentTimeMillis() + 5_000; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (model.playing != null) EnigmaVideoTexture(model.player(), emptyList(), Modifier.fillMaxSize())

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
            panelOpen -> SidePanel(model, sectionFocus, categoryFocus, itemFocus) { panelOpen = false; bannerUntil = System.currentTimeMillis() + 5_000 }
        }

        model.playing?.let { playing ->
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(bannerUntil) { while (true) { now = System.currentTimeMillis(); if (now > bannerUntil) break; delay(500) } }
            if (!panelOpen && now < bannerUntil) {
                Banner(playing, model.playing?.item?.id?.let { model.epg[it] }.orEmpty(), Modifier.align(Alignment.BottomCenter))
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

@Composable
private fun SidePanel(
    model: XtreamViewModel,
    sectionFocus: FocusRequester,
    categoryFocus: FocusRequester,
    itemFocus: FocusRequester,
    onPlayed: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxHeight()
            .background(EnigmaColors.Canvas.copy(alpha = 0.92f))
            .padding(horizontal = EnigmaColors.SafeHorizontal / 2, vertical = EnigmaColors.SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Column 1: Live / Films / Series and the playlist name.
        Column(Modifier.width(150.dp)) {
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
                    onClick = { model.selectSection(s) },
                    selected = s == model.section,
                    modifier = Modifier
                        .then(if (s == model.section) Modifier.focusRequester(sectionFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) model.selectSection(s) }
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = categoryFocus
                            if (index == 0) up = FocusRequester.Cancel
                            if (index == XtreamSection.entries.lastIndex) down = FocusRequester.Cancel
                        },
                ) { Text(label, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            CompactListRow(
                onClick = model::refresh,
                modifier = Modifier.focusProperties { left = FocusRequester.Cancel; right = categoryFocus; down = FocusRequester.Cancel },
            ) { Text(stringResource(R.string.xtream_refresh), fontSize = 13.sp, color = EnigmaColors.Muted) }
        }
        // Column 2: categories.
        Column(Modifier.width(240.dp)) {
            Header(stringResource(R.string.xtream_categories), model.loading && model.categories.isEmpty())
            val state = rememberLazyListState()
            val selectedIndex = model.categories.indexOfFirst { it.id == model.category?.id }.coerceAtLeast(0)
            LaunchedEffect(model.categories) { if (model.categories.isNotEmpty()) state.scrollToItem(selectedIndex) }
            LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                itemsIndexed(model.categories, key = { _, c -> c.id }) { index, c ->
                    CompactListRow(
                        onClick = { model.selectCategory(c) },
                        selected = c.id == model.category?.id,
                        modifier = Modifier
                            .then(if (index == selectedIndex) Modifier.focusRequester(categoryFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) model.selectCategory(c) }
                            .focusProperties {
                                left = sectionFocus
                                right = itemFocus
                                if (index == 0) up = FocusRequester.Cancel
                                if (index == model.categories.lastIndex) down = FocusRequester.Cancel
                            },
                    ) { Text(c.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        // Column 3: channels, films, series or the episodes of the opened series.
        Column(Modifier.width(380.dp)) {
            val series = model.series
            Header(series?.name ?: model.category?.name.orEmpty(), model.loading)
            if (model.error) Text(stringResource(R.string.xtream_load_error), color = EnigmaColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            val state = rememberLazyListState()
            if (series != null) {
                LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                    itemsIndexed(model.episodes, key = { _, e -> e.id }) { index, e ->
                        CompactListRow(
                            onClick = { model.playEpisode(e); onPlayed() },
                            selected = model.playing?.episode?.id == e.id,
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(itemFocus) else Modifier)
                                .focusProperties {
                                    left = categoryFocus
                                    right = FocusRequester.Cancel
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
                LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                    itemsIndexed(model.items, key = { _, i -> i.id }) { index, item ->
                        CompactListRow(
                            onClick = { model.play(item); if (item.section != XtreamSection.SERIES) onPlayed() },
                            selected = model.playing?.item?.id == item.id,
                            modifier = Modifier
                                .then(if (index == playingIndex) Modifier.focusRequester(itemFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) model.focusLive(item) }
                                .focusProperties {
                                    left = categoryFocus
                                    right = FocusRequester.Cancel
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
                                model.epg[item.id]?.let { nowNext(it) }?.first?.let { now ->
                                    Text("${time(now.startSec)} ${now.title}", fontSize = 11.sp, color = EnigmaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
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

@Composable
private fun Banner(playing: XtreamPlaying, epg: List<XtreamEpg>, modifier: Modifier) {
    val (now, next) = nowNext(epg)
    Column(
        modifier
            .padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical)
            .fillMaxWidth()
            .background(EnigmaColors.scrim(10), skinShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(playing.title, color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (now != null) {
            Text("${time(now.startSec)} – ${time(now.endSec)}  ${now.title}", color = EnigmaColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val total = (now.endSec - now.startSec).coerceAtLeast(1)
            EnigmaProgramProgress(((System.currentTimeMillis() / 1000 - now.startSec).toFloat() / total).coerceIn(0f, 1f))
        }
        if (next != null) Text("${time(next.startSec)}  ${next.title}", color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.xtream_banner_hint), color = EnigmaColors.Subtle, fontSize = 11.sp)
    }
}

