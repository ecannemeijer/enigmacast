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
import androidx.compose.ui.input.key.KeyEventType
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
        when (val o = model.overlay) {
            is TorrentOverlay.Seasons -> SeasonPicker(o, model)
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
    LaunchedEffect(Unit) { moviesFocus.requestWhenReady() }
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
            LaunchedEffect(Unit) { fieldFocus.requestWhenReady() }
            TvTextField(
                label = stringResource(if (model.kind == TorrentKind.MOVIE) R.string.torrent_search_movies else R.string.torrent_search_series),
                value = model.query,
                onValueChange = model::onQuery,
                fieldFocus = fieldFocus,
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
                    film = remember(title) { filmOf(title) },
                    modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
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

/** TMDB title as the app's poster card data (poster URL, title, year). */
private fun filmOf(t: TmdbTitle) = com.enigmacast.data.xtream.IptvVod.film(
    XtreamItem("tmdb${t.id}", t.title, if (t.kind == TorrentKind.TV) XtreamSection.SERIES else XtreamSection.MOVIES, "", t.poster.orEmpty()),
    com.enigmacast.domain.films.TmdbMovie(id = t.id, title = t.title, year = t.year, posterPath = t.poster, isSeries = t.kind == TorrentKind.TV),
)

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

@Composable
private fun SeasonPicker(o: TorrentOverlay.Seasons, model: TorrentViewModel) {
    val seasonFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { seasonFocus.requestWhenReady() }
    DialogFrame(model::dismissOverlay) {
        Text(o.title.title, color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = EnigmaType.Strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
            itemsIndexed(o.seasons, key = { _, s -> s.number }) { i, s ->
                ChoiceChip(
                    label = s.name,
                    selected = s.number == o.season,
                    onClick = { model.chooseSeason(s.number) },
                    modifier = Modifier
                        .then(if (s.number == o.season) Modifier.focusRequester(seasonFocus) else Modifier)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            if (i == 0) left = FocusRequester.Cancel
                            if (i == o.seasons.lastIndex) right = FocusRequester.Cancel
                            down = episodeFocus
                        },
                )
            }
        }
        if (o.episodes.isEmpty()) EnigmaLoadingIndicator(Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(o.episodes, key = { _, e -> e.number }) { i, e ->
                CompactListRow(
                    onClick = { model.chooseEpisode(e) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .then(if (i == 0) Modifier.focusRequester(episodeFocus) else Modifier)
                        .focusProperties {
                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            if (i == 0) up = seasonFocus
                            if (i == o.episodes.lastIndex) down = FocusRequester.Cancel
                        },
                ) {
                    Column {
                        Text("${e.number}. ${e.name}", color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.overview.isNotBlank()) Text(e.overview, color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
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
