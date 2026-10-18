package com.enigmacast.ui.torrent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.enigmacast.domain.torrent.TmdbEpisode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.domain.torrent.StremioStreams
import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.torrent.TorrentStream
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.ui.theme.ChoiceChip
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.xtream.VodBarKeys
import kotlinx.coroutines.delay

/**
 * Torrent: TMDB popular / search as a poster grid (Movies / Series tabs), streams from the
 * configured Stremio addon, a full-screen loading page while the torrent buffers, then the
 * VOD player with the recordings' seek bar and options row.
 */
@Composable
fun TorrentScreen(onBack: () -> Unit, model: TorrentViewModel = hiltViewModel()) {
    DisposableEffect(Unit) {
        model.setForeground(true)
        onDispose { model.setForeground(false) }
    }
    Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
        when {
            model.playing != null -> TorrentPlayer(model)
            else -> TorrentBrowser(model, onBack)
        }
        // The info page stays under the streams dialog; it closes when playback starts.
        model.info?.let { i -> if (model.playing == null && model.overlay !is TorrentOverlay.Loading) TorrentInfoPage(i, model) }
        when (val o = model.overlay) {
            is TorrentOverlay.Streams -> StreamsDialog(o, model)
            is TorrentOverlay.Loading -> LoadingPage(o, model)
            TorrentOverlay.None -> Unit
        }
        model.message?.let { m ->
            TvChoiceDialog(
                title = stringResource(R.string.nav_torrent),
                message = when {
                    m.startsWith("engine:") -> stringResource(R.string.torrent_error_engine, m.removePrefix("engine:"))
                    m == "imdb" -> stringResource(R.string.torrent_error_imdb)
                    m == "torrent" -> stringResource(R.string.torrent_error_torrent)
                    else -> stringResource(R.string.torrent_error_tmdb)
                },
                choices = listOf(stringResource(R.string.ok)),
                onChoose = { model.dismissMessage() },
                onDismiss = model::dismissMessage,
                compact = true,
            )
        }
    }
}

/** Full-screen page: only Up leaves the grid (to the tabs); edges stop focus. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.gridEdges(): Modifier = this
    .focusProperties {
        onExit = { if (requestedFocusDirection != FocusDirection.Up) cancelFocusChange() }
    }
    .focusGroup()

@Composable
private fun TorrentBrowser(model: TorrentViewModel, onBack: () -> Unit) {
    val moviesFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    BackHandler {
        when {
            model.searchOpen -> { model.closeSearch(); runCatching { searchFocus.requestFocus() } }
            else -> onBack()
        }
    }
    val restoreFocus = remember { FocusRequester() }
    // Back from the info page or the player: focus on the card that was opened, else the tabs.
    LaunchedEffect(Unit) {
        val index = model.focusKey?.let { k -> model.items.indexOfFirst { it.key == k } } ?: -1
        if (index >= 0) {
            grid.scrollToItem(index)
            restoreFocus.requestWhenReady()
        } else {
            moviesFocus.requestWhenReady()
        }
    }
    LaunchedEffect(model.restoreToken) {
        if (model.restoreToken == 0) return@LaunchedEffect
        val index = model.focusKey?.let { k -> model.items.indexOfFirst { it.key == k } } ?: -1
        if (index >= 0) { grid.scrollToItem(index); restoreFocus.requestWhenReady() }
    }
    LaunchedEffect(model.listToken) {
        if (model.listToken == 0) return@LaunchedEffect
        grid.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical)) {
        // Header: Movies · Series tabs, search on the right.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.nav_torrent), color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = EnigmaType.Strong, modifier = Modifier.padding(end = 12.dp))
            ChoiceChip(
                label = stringResource(R.string.torrent_movies),
                selected = model.kind == TorrentKind.MOVIE,
                onClick = { model.selectKind(TorrentKind.MOVIE) },
                modifier = Modifier.focusRequester(moviesFocus).focusProperties { left = FocusRequester.Cancel; up = FocusRequester.Cancel },
            )
            ChoiceChip(
                label = stringResource(R.string.torrent_series),
                selected = model.kind == TorrentKind.TV,
                onClick = { model.selectKind(TorrentKind.TV) },
                modifier = Modifier.focusProperties { up = FocusRequester.Cancel },
            )
            if (model.loading) EnigmaLoadingIndicator(Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            CompactButton(
                label = stringResource(R.string.torrent_search),
                icon = Icons.Filled.Search,
                selected = model.searchOpen,
                onClick = { model.openSearch() },
                modifier = Modifier.focusRequester(searchFocus).focusProperties { right = FocusRequester.Cancel; up = FocusRequester.Cancel },
            )
        }
        if (model.searchOpen) {
            TvTextField(
                label = stringResource(if (model.kind == TorrentKind.MOVIE) R.string.torrent_search_movies else R.string.torrent_search_series),
                value = model.query,
                onValueChange = model::onQuery,
                fieldFocus = fieldFocus,
                // The search icon opens the field already typing, with the keyboard up.
                startEditing = true,
                leftFocus = FocusRequester.Cancel,
                rightFocus = FocusRequester.Cancel,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        if (model.items.isEmpty() && !model.loading) {
            Text(stringResource(R.string.torrent_empty), color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(148.dp),
            state = grid,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().gridEdges(),
        ) {
            itemsIndexed(model.items, key = { _, t -> t.key }) { index, title ->
                com.enigmacast.ui.home.PosterCard(
                    film = remember(title) { TorrentCards.film(title) },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                        .then(if (title.key == model.focusKey) Modifier.focusRequester(restoreFocus) else Modifier),
                    onFocus = { model.onFocusedIndex(index) },
                    onClick = { model.openTitle(title) },
                    lines = {
                        title.year?.let { Text(it.toString(), color = EnigmaColors.Muted, fontSize = 12.sp) }
                    },
                )
            }
        }
    }
}

@Composable
private fun DialogFrame(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(560.dp).fillMaxSize().background(EnigmaColors.Canvas, skinShape(0.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

/**
 * The info page of a card: the app's film/series page (backdrop, poster, rating, runtime,
 * genres, overview, cast). Focus starts on Play; only Play (or an episode) asks the scraper
 * for streams. Series show their seasons and episodes below, as on the IPTV series page.
 */
@Composable
private fun TorrentInfoPage(i: TorrentInfo, model: TorrentViewModel) {
    val isSeries = i.title.kind == TorrentKind.TV
    com.enigmacast.ui.home.FilmInfoDialog(
        film = i.film,
        nowSec = System.currentTimeMillis() / 1000,
        autoSwitchSet = false,
        recordingSet = false,
        onWatch = {},
        onSchedule = {},
        onRecord = {},
        onClose = model::closeInfo,
        picon = { null },
        cachedPicon = { null },
        channel = com.enigmacast.domain.model.BouquetEntry(i.title.title, "tmdb:${i.title.key}"),
        vod = com.enigmacast.ui.home.VodDetail(
            onPlay = model::playInfo,
            below = if (isSeries) ({ TorrentEpisodes(i, model) }) else null,
        ),
    )
}

/** Seasons as chips, then the season's episodes as a row; OK on an episode searches its streams. */
@Composable
private fun TorrentEpisodes(i: TorrentInfo, model: TorrentViewModel) {
    if (i.seasons.isEmpty()) {
        if (i.loading) EnigmaLoadingIndicator() else Text(stringResource(R.string.iptv_no_episodes), color = EnigmaColors.Muted, fontSize = 13.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(i.seasons, key = { _, s -> s.number }) { index, s ->
                CompactListRow(
                    onClick = { model.chooseSeason(s.number) },
                    selected = s.number == i.season,
                    modifier = Modifier.focusProperties {
                        if (index == 0) left = FocusRequester.Cancel
                        if (index == i.seasons.lastIndex) right = FocusRequester.Cancel
                    },
                ) { Text(stringResource(R.string.iptv_season, s.number), fontSize = 13.sp) }
            }
        }
        when {
            i.episodes.isEmpty() && i.episodesLoading -> EnigmaLoadingIndicator()
            i.episodes.isEmpty() -> Text(stringResource(R.string.iptv_no_episodes), color = EnigmaColors.Muted, fontSize = 13.sp)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(i.episodes, key = { _, e -> "${i.season}:${e.number}" }) { index, e ->
                    TorrentEpisodeCard(
                        e,
                        Modifier.focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == i.episodes.lastIndex) right = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        },
                    ) { model.chooseEpisode(e) }
                }
            }
        }
    }
}

private val EPISODE_WIDTH = 150.dp

@Composable
private fun TorrentEpisodeCard(e: TmdbEpisode, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Column(
        modifier
            .width(EPISODE_WIDTH)
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .focusable(interactionSource = interaction),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(EPISODE_WIDTH * 9 / 16)
                .clip(shape)
                .background(EnigmaColors.Card)
                .border(if (focused) 2.dp else 1.dp, if (focused) EnigmaColors.Accent else EnigmaColors.CardBorder, shape),
        ) {
            if (e.still != null) {
                coil3.compose.AsyncImage(model = e.still, contentDescription = e.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text("E${e.number}", fontSize = 11.sp, color = EnigmaColors.Accent, maxLines = 1)
        Text(e.name, fontSize = 12.sp, color = if (focused) EnigmaColors.Text else EnigmaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StreamsDialog(o: TorrentOverlay.Streams, model: TorrentViewModel) {
    val first = remember { FocusRequester() }
    val holder = remember { FocusRequester() }
    LaunchedEffect(o.streams) { if (!o.streams.isNullOrEmpty()) first.requestWhenReady() else holder.requestWhenReady() }
    DialogFrame(model::dismissOverlay) {
        Text(o.title.title, color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = EnigmaType.Strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (o.subtitle.isNotBlank()) Text(o.subtitle, color = EnigmaColors.Muted, fontSize = 13.sp)
        Text(stringResource(R.string.torrent_streams_title), color = EnigmaColors.Cyan, fontSize = 13.sp)
        val streams = o.streams
        when {
            streams == null && !o.error -> EnigmaLoadingIndicator(Modifier.padding(8.dp).focusRequester(holder).focusable())
            streams.isNullOrEmpty() -> Text(
                stringResource(if (o.error) R.string.torrent_streams_error else R.string.torrent_no_streams),
                color = EnigmaColors.Muted, fontSize = 14.sp,
                modifier = Modifier.focusRequester(holder).focusable().padding(8.dp),
            )
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(streams, key = { i, s -> "${s.infoHash ?: s.url}#$i" }) { i, s ->
                    CompactListRow(
                        onClick = { model.chooseStream(s) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .focusProperties {
                                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                if (i == 0) up = FocusRequester.Cancel
                                if (i == streams.lastIndex) down = FocusRequester.Cancel
                            },
                    ) {
                        Column {
                            Text(StremioStreams.release(s), color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(streamLine(s), color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun streamLine(s: TorrentStream): String = buildList {
    if (s.resolution > 0) add(if (s.resolution >= 2160) "4K" else "${s.resolution}p")
    s.seeders?.let { add("👤 $it") }
    s.sizeBytes?.let { add("💾 ${TorrentFormat.size(it)}") }
    if (s.url != null) add(stringResource(R.string.torrent_direct))
    s.name.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
}.joinToString("  ·  ")

/** Stremio-like loading page: faded backdrop, the title pulsing, buffer / speed / peers. Back cancels. */
@Composable
private fun LoadingPage(o: TorrentOverlay.Loading, model: TorrentViewModel) {
    val progress by model.engine.progress.collectAsStateWithLifecycle()
    val holder = remember { FocusRequester() }
    LaunchedEffect(Unit) { holder.requestWhenReady() }
    BackHandler { model.cancelLoading() }
    val pulse by rememberInfiniteTransition(label = "torrentPulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(holder).focusable(), contentAlignment = Alignment.Center) {
        o.title.backdrop?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.25f })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                o.title.title,
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = pulse }.padding(horizontal = 48.dp),
            )
            if (o.subtitle.isNotBlank()) Text(o.subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
        }
        Text(
            TorrentFormat.status(progress, stringResource(R.string.torrent_status_metadata), stringResource(R.string.torrent_status_buffer)),
            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        )
    }
}

@Composable
private fun TorrentPlayer(model: TorrentViewModel) {
    var bar by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(false) }
    var optionsToken by remember { mutableIntStateOf(0) }
    val root = remember { FocusRequester() }
    LaunchedEffect(bar, touch, options) {
        if (bar && !options) { delay(5_000); bar = false; runCatching { root.requestFocus() } }
    }
    LaunchedEffect(Unit) { root.requestWhenReady() }
    LaunchedEffect(model.playerState.playbackState) { if (model.playerState.playbackState == "ENDED") model.stopPlayback() }
    BackHandler {
        when {
            options -> { options = false; bar = false; runCatching { root.requestFocus() } }
            bar -> { bar = false; runCatching { root.requestFocus() } }
            else -> model.stopPlayback()
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(root)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || options) return@onPreviewKeyEvent false
                when (VodBarKeys.action(e.nativeKeyEvent.keyCode, bar)) {
                    VodBarKeys.Action.SHOW -> { if (e.nativeKeyEvent.repeatCount == 0) { bar = true; touch++ }; true }
                    VodBarKeys.Action.FORWARD -> { model.seekTo(model.playerState.positionMs + VodBarKeys.FF_MS); bar = true; touch++; true }
                    VodBarKeys.Action.REWIND -> { model.seekTo(model.playerState.positionMs - VodBarKeys.FF_MS); bar = true; touch++; true }
                    VodBarKeys.Action.PAUSE -> { if (e.nativeKeyEvent.repeatCount == 0) { model.togglePause(); touch++ }; true }
                    VodBarKeys.Action.BAR -> { touch++; false }
                    VodBarKeys.Action.PLAYER -> false
                }
            }
            .focusable(),
    ) {
        val ps = model.playerState
        com.enigmacast.ui.remote.EnigmaVideoPlayer(
            player = model.player(),
            frame = null,
            debugBorders = false,
            cues = emptyList(),
            picture = com.enigmacast.ui.remote.VideoShape(model.pictureMode, ps.videoWidth, ps.videoHeight, ps.pixelRatio),
            modifier = Modifier.fillMaxSize(),
        )
        if (ps.playbackState == "BUFFERING") EnigmaLoadingIndicator(Modifier.align(Alignment.Center))
        if (bar) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                com.enigmacast.ui.remote.RecordingBar(
                    title = model.playing.orEmpty(),
                    positionMs = ps.positionMs,
                    durationMs = ps.durationMs,
                    playing = ps.playing,
                    onSeek = { model.seekTo(it); touch++ },
                    onTogglePause = { model.togglePause(); touch++ },
                    onOptions = { options = true; optionsToken++ },
                    onTouch = { touch++ },
                )
                if (options) {
                    com.enigmacast.ui.remote.QuickOptionsBar(
                        ui = com.enigmacast.ui.remote.RemoteDisplayUiState(
                            playerState = ps,
                            tracks = model.tracks,
                            pictureMode = model.pictureMode,
                            audioDelayMs = model.audioDelayMs,
                            subtitleDelayMs = model.subtitleDelayMs,
                            quickBarToken = optionsToken,
                        ),
                        actions = com.enigmacast.ui.remote.QuickBarActions(
                            onSubtitles = model::chooseSubtitles,
                            onAudio = model::chooseAudio,
                            onPassthrough = {},
                            onPictureMode = model::choosePictureMode,
                            onAudioDelay = model::setAudioDelay,
                            onSubtitleDelay = model::setSubtitleDelay,
                            onGuide = {},
                            onRecent = {},
                            onSleep = {},
                            onRestart = model::restart,
                            onQuality = {},
                            onRecord = {},
                            onSearch = {},
                        ),
                        iptv = true,
                        interactive = true,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
        model.resumeAsk?.let { at ->
            TvChoiceDialog(
                title = stringResource(R.string.resume_title),
                message = model.playing.orEmpty(),
                choices = listOf(stringResource(R.string.resume_from, com.enigmacast.domain.resume.ResumePolicy.clock(at)), stringResource(R.string.resume_start_over)),
                onChoose = { model.answerResume(it == 0) },
                onDismiss = { model.answerResume(false) },
            )
        }
        model.playError?.let {
            Text(stringResource(R.string.torrent_error_play, it), color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).background(Color.Black.copy(alpha = 0.6f)).padding(8.dp))
        }
    }
}

object TorrentFormat {
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(java.util.Locale.ROOT, bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    fun speed(bps: Int): String = size(bps.toLong()) + "/s"

    fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "Buffering 42% · 3.1 MB/s · 37 peers" (labels localized by the caller). */
    fun status(p: com.enigmacast.torrent.TorrentProgress, metadata: String, buffering: String): String = when (p.stage) {
        com.enigmacast.torrent.TorrentStage.METADATA, com.enigmacast.torrent.TorrentStage.IDLE -> metadata
        else -> "$buffering ${(p.buffer * 100).toInt()}%  ·  ${speed(p.downloadBps)}  ·  👤 ${p.peers}"
    }
}
