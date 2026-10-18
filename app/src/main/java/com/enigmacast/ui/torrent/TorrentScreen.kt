package com.enigmacast.ui.torrent

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.delay

/**
 * Torrent: TMDB popular / search as a poster grid (Movies / Series tabs), streams from the
 * configured Stremio addon, a full-screen loading page while the torrent buffers, then the
 * VOD player with the recordings' seek bar and options row.
 */
@Composable
fun TorrentScreen(
    onBack: () -> Unit,
    model: TorrentViewModel = hiltViewModel(),
    /** Inside the dashboard: the left menu stays; the loading page and player open full screen on top. */
    dash: TorrentDash? = null,
) {
    DisposableEffect(Unit) {
        model.setForeground(true)
        onDispose { model.setForeground(false) }
    }
    LaunchedEffect(model.leaveTo) {
        val origin = model.leaveTo ?: return@LaunchedEffect
        model.consumeLeave()
        dash?.onLeave?.invoke(origin)
    }
    Box(Modifier.fillMaxSize().then(if (dash == null) Modifier.background(EnigmaColors.Canvas) else Modifier)) {
        when {
            dash != null -> {
                TorrentBrowser(model, onBack, dash)
                if (model.playing != null) FullScreenLayer(onBack = model::stopPlayback) { TorrentPlayer(model) }
            }
            model.playing != null -> TorrentPlayer(model)
            else -> TorrentBrowser(model, onBack, null)
        }
        // The info page stays under the streams dialog; it closes when playback starts.
        model.info?.let { i -> if (model.playing == null && model.overlay !is TorrentOverlay.Loading) TorrentInfoPage(i, model) }
        when (val o = model.overlay) {
            is TorrentOverlay.Streams -> StreamsDialog(o, model)
            is TorrentOverlay.ResumeSource -> TvChoiceDialog(
                title = stringResource(R.string.resume_title),
                message = listOf(o.title.title, o.subtitle, StremioStreams.release(o.stream)).filter { it.isNotBlank() }.joinToString("\n"),
                choices = listOf(
                    stringResource(R.string.resume_from, com.enigmacast.domain.resume.ResumePolicy.clock(o.atMs)),
                    stringResource(R.string.resume_start_over),
                    stringResource(R.string.torrent_resume_other_source),
                ),
                onChoose = model::answerResumeSource,
                onDismiss = model::dismissOverlay,
                stacked = true,
            )
            is TorrentOverlay.Loading -> if (dash != null) FullScreenLayer(onBack = model::cancelLoading) { LoadingPage(o, model) } else LoadingPage(o, model)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TorrentBrowser(model: TorrentViewModel, onBack: () -> Unit, dash: TorrentDash?) {
    val moviesFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    val genreFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    var picker by remember { mutableStateOf<FilterPicker?>(null) }
    val grid = rememberLazyGridState()
    // Closing a filter list: focus back on its chip.
    var refocus by remember { mutableStateOf<FilterPicker?>(null) }
    LaunchedEffect(refocus) {
        val which = refocus ?: return@LaunchedEffect
        (if (which == FilterPicker.GENRE) genreFocus else sortFocus).requestWhenReady()
        refocus = null
    }
    picker?.let { which ->
        val close = { picker = null; refocus = which }
        when (which) {
            FilterPicker.GENRE -> FilterDialog(
                title = stringResource(R.string.torrent_genre),
                options = listOf(stringResource(R.string.torrent_genre_all)) + model.genres.map { it.name },
                selected = model.genre?.let { g -> model.genres.indexOfFirst { it.id == g.id } + 1 } ?: 0,
                onChoose = { i -> model.selectGenre(model.genres.getOrNull(i - 1)); close() },
                onDismiss = close,
            )
            FilterPicker.SORT -> {
                val sorts = com.enigmacast.domain.torrent.TorrentSort.entries
                FilterDialog(
                    title = stringResource(R.string.torrent_sort),
                    options = sorts.map { stringResource(sortLabel(it)) },
                    selected = sorts.indexOf(model.sort),
                    onChoose = { i -> model.selectSort(sorts[i]); close() },
                    onDismiss = close,
                )
            }
        }
    }
    // In the dashboard only an open search takes Back; the dashboard sends the rest to the menu.
    BackHandler(enabled = dash == null || model.searchOpen) {
        when {
            model.searchOpen -> { model.closeSearch(); runCatching { searchFocus.requestFocus() } }
            else -> onBack()
        }
    }
    val leftOut = dash?.menuFocus ?: FocusRequester.Cancel
    val restoreFocus = remember { FocusRequester() }
    // Back from the info page or the player: focus on the card that was opened, else the tabs.
    LaunchedEffect(Unit) {
        val index = model.focusKey?.let { k -> model.items.indexOfFirst { it.key == k } } ?: -1
        when {
            index >= 0 && (dash == null || dash.enter == 0) -> { grid.scrollToItem(index); restoreFocus.requestWhenReady() }
            dash == null -> moviesFocus.requestWhenReady()
        }
    }
    // OK on the menu item: into the page (the opened card when there is one, else the tabs).
    if (dash != null) LaunchedEffect(dash.enter) {
        if (dash.enter == 0) return@LaunchedEffect
        val index = model.focusKey?.let { k -> model.items.indexOfFirst { it.key == k } } ?: -1
        if (index >= 0) { grid.scrollToItem(index); restoreFocus.requestWhenReady() } else moviesFocus.requestWhenReady()
    }
    LaunchedEffect(model.restoreToken) {
        if (model.restoreToken == 0) return@LaunchedEffect
        val index = model.focusKey?.let { k -> model.items.indexOfFirst { it.key == k } } ?: -1
        if (index >= 0) { grid.scrollToItem(index); restoreFocus.requestWhenReady() }
        // Opened from Library or Home (not a card of this list): the tabs.
        else if (dash != null) moviesFocus.requestWhenReady()
    }
    LaunchedEffect(model.listToken) {
        if (model.listToken == 0) return@LaunchedEffect
        grid.scrollToItem(0)
    }
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (dash == null) Modifier.padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical)
                else Modifier
                    // Right from the menu comes back to the last focused tab, field or card.
                    .focusRequester(dash.contentFocus)
                    .onFocusChanged { if (it.hasFocus) dash.onRetreatTo(dash.menuFocus) }
                    .focusRestorer(moviesFocus)
                    .focusGroup(),
            ),
    ) {
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
                modifier = Modifier.focusRequester(moviesFocus).focusProperties { left = leftOut; up = FocusRequester.Cancel },
            )
            ChoiceChip(
                label = stringResource(R.string.torrent_series),
                selected = model.kind == TorrentKind.TV,
                onClick = { model.selectKind(TorrentKind.TV) },
                modifier = Modifier.focusProperties { up = FocusRequester.Cancel },
            )
            // Filters of the grid (a search ignores them): OK opens a list on the right.
            ChoiceChip(
                label = stringResource(R.string.torrent_filter_genre, model.genre?.name ?: stringResource(R.string.torrent_genre_all)),
                selected = model.genre != null,
                onClick = { picker = FilterPicker.GENRE },
                modifier = Modifier.padding(start = 12.dp).focusRequester(genreFocus).focusProperties { up = FocusRequester.Cancel },
            )
            ChoiceChip(
                label = stringResource(R.string.torrent_filter_sort, stringResource(sortLabel(model.sort))),
                selected = model.sort != com.enigmacast.domain.torrent.TorrentSort.POPULAR,
                onClick = { picker = FilterPicker.SORT },
                modifier = Modifier.focusRequester(sortFocus).focusProperties { up = FocusRequester.Cancel },
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
        if (model.searchOpen) androidx.compose.runtime.key(model.searchToken) {
            TvTextField(
                label = stringResource(if (model.kind == TorrentKind.MOVIE) R.string.torrent_search_movies else R.string.torrent_search_series),
                value = model.query,
                onValueChange = model::onQuery,
                fieldFocus = fieldFocus,
                // The search icon opens the field already typing, with the keyboard up.
                startEditing = true,
                leftFocus = leftOut,
                rightFocus = FocusRequester.Cancel,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        if (model.items.isEmpty() && !model.loading) {
            Text(stringResource(R.string.torrent_empty), color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        }
        // Same as the IPTV films grid (8b9db68): fixed columns, stable keys, one content type,
        // cards of one fixed height, and a bring-into-view that only scrolls when a poster is
        // off screen. TV's default pulls the focused poster to the middle, so every move
        // shifted the grid and then settled back (the bounce).
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = com.enigmacast.ui.xtream.VodPanelNav.columns(maxWidth.value, TorrentCards.CARD_WIDTH_DP, GRID_GAP_DP)
            val items = model.items
            val lastRowStart = (items.size - 1).coerceAtLeast(0) / columns * columns
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.enigmacast.ui.theme.MinimalBringIntoView,
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
                    modifier = Modifier.fillMaxSize().focusGroup(),
                ) {
                    itemsIndexed(items, key = { _, t -> t.key }, contentType = { _, _ -> "poster" }) { index, title ->
                        com.enigmacast.ui.home.PosterCard(
                            film = remember(title) { TorrentCards.film(title) },
                            modifier = Modifier
                                .height(TorrentCards.CARD_HEIGHT_DP.dp)
                                .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                                .then(if (title.key == model.focusKey) Modifier.focusRequester(restoreFocus) else Modifier)
                                .focusProperties {
                                    // Left from the first column: the menu (dashboard) or nowhere.
                                    if (index % columns == 0) left = leftOut
                                    if (index % columns == columns - 1 || index == items.lastIndex) right = FocusRequester.Cancel
                                    if (index >= lastRowStart) down = FocusRequester.Cancel
                                },
                            onFocus = { model.onFocusedIndex(index) },
                            onClick = { model.openTitle(title) },
                            lines = {
                                // Always one line, so every card has the same height.
                                Text(title.year?.toString() ?: " ", color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 1)
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val GRID_GAP_DP = 16f

private enum class FilterPicker { GENRE, SORT }

private fun sortLabel(s: com.enigmacast.domain.torrent.TorrentSort) = when (s) {
    com.enigmacast.domain.torrent.TorrentSort.POPULAR -> R.string.torrent_sort_popular
    com.enigmacast.domain.torrent.TorrentSort.YEAR -> R.string.torrent_sort_year
    com.enigmacast.domain.torrent.TorrentSort.SCORE -> R.string.torrent_sort_score
}

/** A filter's options in the side panel; focus starts on the current one, Back closes. */
@Composable
private fun FilterDialog(title: String, options: List<String>, selected: Int, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = remember { FocusRequester() }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (selected - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) { start.requestWhenReady() }
    DialogFrame(onDismiss) {
        Text(title, color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = EnigmaType.Strong)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(options) { i, label ->
                CompactListRow(
                    onClick = { onChoose(i) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier
                        .then(if (i == selected.coerceIn(0, options.lastIndex)) Modifier.focusRequester(start) else Modifier)
                        .focusProperties {
                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            if (i == 0) up = FocusRequester.Cancel
                            if (i == options.lastIndex) down = FocusRequester.Cancel
                        },
                ) {
                    Text(
                        (if (i == selected) "✓  " else "    ") + label,
                        color = if (i == selected) EnigmaColors.Accent else EnigmaColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Where the Torrent page sits in the dashboard: its menu item, its focus entry and the enter count. */
class TorrentDash(
    val menuFocus: FocusRequester,
    val contentFocus: FocusRequester,
    val enter: Int,
    val onRetreatTo: (FocusRequester) -> Unit,
    /** Back from an info page opened from Library or Home: return to that card ("home/…" or "<row>/…"). */
    val onLeave: (String) -> Unit = {},
)

/** The loading page and the player over the whole screen while the page is in the dashboard. */
@Composable
private fun FullScreenLayer(onBack: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
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
            // Library › favorites, as on the IPTV film and series pages.
            favorite = androidx.compose.runtime.remember(i.title.key, model.favoriteVersion) { model.isFavorite(i.title) },
            onFavorite = { model.toggleFavorite(i.title) },
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
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(holder).focusable(), contentAlignment = Alignment.Center) {
        o.title.backdrop?.let {
            // Static and dim, so the logo and the buffer line stay readable.
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.3f })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var logoFailed by remember(o.logo) { mutableStateOf(false) }
            if (o.logo != null && !logoFailed) {
                AsyncImage(
                    model = o.logo,
                    contentDescription = o.title.title,
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                    modifier = Modifier.fillMaxWidth(0.45f).height(170.dp),
                )
            } else {
                Text(
                    o.title.title,
                    color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }
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
    val osd = remember { com.enigmacast.ui.player.VodOsdController() }
    val root = remember { FocusRequester() }
    LaunchedEffect(Unit) { root.requestWhenReady() }
    // Hidden again (Back or 5 s without a key): the keys come back to the player.
    LaunchedEffect(osd.visible) { if (!osd.visible) runCatching { root.requestFocus() } }
    LaunchedEffect(model.playerState.playbackState) { if (model.playerState.playbackState == "ENDED") model.stopPlayback() }
    BackHandler { if (osd.visible) osd.hide() else model.stopPlayback() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(root)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || model.resumeAsk != null) return@onPreviewKeyEvent false
                osd.onKeyDown(e.nativeKeyEvent.keyCode, e.nativeKeyEvent.repeatCount, model::togglePause)
            }
            .focusable(),
    ) {
        val ps = model.playerState
        com.enigmacast.ui.remote.EnigmaVideoPlayer(
            player = model.player(),
            frame = null,
            debugBorders = false,
            cues = model.cues,
            picture = com.enigmacast.ui.remote.VideoShape(model.pictureMode, ps.videoWidth, ps.videoHeight, ps.pixelRatio),
            modifier = Modifier.fillMaxSize(),
        )
        if (ps.playbackState == "BUFFERING") EnigmaLoadingIndicator(Modifier.align(Alignment.Center))
        com.enigmacast.ui.player.VodPlayerOsd(
            controller = osd,
            model = com.enigmacast.ui.player.VodOsdModel(
                title = model.osdTitle.ifBlank { model.playing.orEmpty() },
                player = ps,
                tracks = model.tracks,
                pictureMode = model.pictureMode,
                audioDelayMs = model.audioDelayMs,
                subtitleDelayMs = model.subtitleDelayMs,
                speed = model.playbackSpeed,
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
                onSources = { osd.hide(); model.chooseOtherSource() },
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
