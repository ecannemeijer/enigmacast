package com.enigmacast.ui.xtream

import com.enigmacast.ui.theme.trapVertical
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.xtream.IptvVod
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.xtream.XtreamEpisode
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.home.Backdrop
import com.enigmacast.ui.home.FilmInfoDialog
import com.enigmacast.ui.home.PosterCard
import com.enigmacast.ui.home.VodDetail
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import kotlinx.coroutines.flow.distinctUntilChanged

private val CATEGORIES_WIDTH = 240.dp
private const val CARD_DP = 132f
private const val GAP_DP = 16f

/**
 * IPTV Films / Series: the categories on the left and, to their right, the poster grid of the
 * Enigma2 Movies and TV Series pages (same cards, TMDB artwork, backdrop of the focused title).
 * OK on a category loads it; OK on a card opens the same info page, with Play / Resume and,
 * for a series, its seasons and episodes from the playlist.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun XtreamVodPage(
    model: XtreamViewModel,
    gridActive: Boolean,
    categoryFocus: FocusRequester,
    gridFocus: FocusRequester,
    onGrid: () -> Unit,
    onCategories: () -> Unit,
    onSections: () -> Unit,
    onGridFocused: () -> Unit,
    onPlayed: () -> Unit,
) {
    val art by model.artwork.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf<HomeFilm?>(null) }
    var focusedIndex by remember(model.cards) { mutableStateOf<Int?>(null) }
    // Built once per category (off the main thread); an artwork update only changes its own card.
    val cards = model.cards
    Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
        Box(Modifier.padding(start = 260.dp)) { Backdrop(focused) }
        // After a category is chosen the column slides away. It stays laid out at zero width so Left
        // from the first poster can focus it and it slides back open.
        val categoriesWidth by androidx.compose.animation.core.animateDpAsState(
            if (VodPanelNav.categoriesShown(gridActive)) CATEGORIES_WIDTH else 0.dp,
            androidx.compose.animation.core.tween(220),
            label = "vodCategories",
        )
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = EnigmaColors.SafeHorizontal / 2, vertical = EnigmaColors.SafeVertical),
        ) {
        val gridWidth = maxWidth - if (VodPanelNav.categoriesShown(gridActive)) CATEGORIES_WIDTH + 16.dp else 0.dp
        val columns = VodPanelNav.columns(gridWidth.value, CARD_DP, GAP_DP)
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.width(categoriesWidth).fillMaxHeight().clipToBounds()
                    .graphicsLayer { alpha = (categoriesWidth / CATEGORIES_WIDTH).coerceIn(0f, 1f) },
            ) {
            // Categories; Right enters a grid that is already there.
            Column(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(CATEGORIES_WIDTH).fillMaxHeight().background(EnigmaColors.Canvas.copy(alpha = 0.9f)).onFocusChanged { if (it.hasFocus) onCategories() }.columnKeysVod(
                    onLeft = onSections,
                    onRight = { if (VodPanelNav.canEnterGrid(model.cards.size)) onGrid() },
                ),
            ) {
                VodHeader(stringResource(if (model.section == XtreamSection.SERIES) R.string.xtream_series else R.string.xtream_movies), model.loading && model.categories.isEmpty())
                val state = rememberLazyListState()
                val selectedIndex = model.categories.indexOfFirst { it.id == model.category?.id }.coerceAtLeast(0)
                LaunchedEffect(model.categories) { if (model.categories.isNotEmpty()) state.scrollToItem(selectedIndex) }
                val keyedCategories = remember(model.categories) { com.enigmacast.domain.xtream.LazyKeys.keyed(model.categories, "cat") { it.id } }
                LazyColumn(state = state, modifier = Modifier.fillMaxHeight()) {
                    itemsIndexed(keyedCategories, key = { _, k -> k.key }) { index, k ->
                        val c = k.value
                        CompactListRow(
                            onClick = { model.selectCategory(c); onGrid() },
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
                            else model.categoryCounts[c.id]?.let { Text(it.toString(), fontSize = 12.sp, color = EnigmaColors.Subtle, modifier = Modifier.padding(start = 6.dp)) }
                        }
                    }
                }
            }
            }
            if (categoriesWidth > 0.dp) Spacer(Modifier.width(16.dp * (categoriesWidth / CATEGORIES_WIDTH).coerceIn(0f, 1f)))
            // The poster grid.
            Column(Modifier.weight(1f).fillMaxHeight().trapVertical()) {
                VodHeader(model.category?.name.orEmpty(), model.loading, VodPanelNav.position(focusedIndex.takeIf { gridActive }, cards.size))
                if (model.error) Text(stringResource(R.string.xtream_load_error), color = EnigmaColors.Danger, fontSize = 13.sp)
                if (!model.loading && model.category != null && cards.isEmpty()) {
                    Text(stringResource(R.string.iptv_vod_empty), color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                }
                val grid = rememberLazyGridState()
                // Where focus is restored (enter the grid, close info). It does not follow every poster:
                // moving the requester onto the focused card drops focus for a frame and the page jumps.
                var pinFocus by remember { mutableIntStateOf(-1) }
                suspend fun pin(index: Int) {
                    if (cards.isEmpty()) return
                    val target = index.coerceIn(0, cards.lastIndex)
                    grid.scrollToItem(target)
                    pinFocus = target
                    gridFocus.requestWhenReady(24)
                }
                LaunchedEffect(model.category?.id, model.itemsReady) {
                    if (model.category == null || cards.isEmpty() || !gridActive) return@LaunchedEffect
                    pin(model.gridAnchor)
                }
                var detailWasOpen by remember { mutableStateOf(false) }
                LaunchedEffect(model.detail) {
                    val open = model.detail != null
                    if (detailWasOpen && !open && gridActive) pin(model.gridAnchor)
                    detailWasOpen = open
                }
                // Artwork for the cards on screen only; a scroll cancels the waiting batch.
                LaunchedEffect(grid, cards) {
                    snapshotFlow { grid.layoutInfo.visibleItemsInfo.map { it.index } }
                        .distinctUntilChanged()
                        .collect { rows -> model.requestArtwork(rows.mapNotNull { cards.getOrNull(it)?.item }) }
                }
                LaunchedEffect(gridActive) { if (gridActive) pin(model.gridAnchor) }
                // TV's default scroll pulls the focused poster toward the middle, so every move
                // shifts the grid and then settles. Only scroll when a poster is actually off screen.
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.enigmacast.ui.theme.MinimalBringIntoView,
                ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onGridFocused() }.focusGroup(),
                ) {
                    itemsIndexed(cards, key = { _, card -> card.key }, contentType = { _, _ -> "poster" }) { index, card ->
                        val item = card.item
                        val base = card.base
                        val movie = art[IptvVod.key(item)]
                        val film = remember(base, movie) { if (movie == null) base else IptvVod.film(item, movie) }
                        val lastRowStart = (cards.size - 1) / columns * columns
                        PosterCard(
                            film = film,
                            modifier = Modifier
                                .then(if (index == pinFocus) Modifier.focusRequester(gridFocus) else Modifier)
                                .focusProperties {
                                    if (index % columns == 0) left = categoryFocus
                                    if (index % columns == columns - 1 || index == cards.lastIndex) right = FocusRequester.Cancel
                                    if (index < columns) up = FocusRequester.Cancel
                                    if (index >= lastRowStart) down = FocusRequester.Cancel
                                },
                            onFocus = { focused = film; focusedIndex = index; model.rememberGridAnchor(index) },
                            onClick = { model.openDetail(item) },
                            overlay = {
                                val progress = remember(item, model.resumeVersion) { model.progressOf(item) }
                                if (progress > 0f) {
                                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f)))
                                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(com.enigmacast.ui.recording.RecordRed))
                                }
                            },
                            lines = {
                                film.movie.year?.let { Text(it.toString(), color = EnigmaColors.Subtle, fontSize = 11.sp) }
                                if (model.resumeOf(item) > 0) Text(stringResource(R.string.iptv_resume_badge), color = EnigmaColors.Accent, fontSize = 11.sp)
                            },
                        )
                    }
                }
                }
            }
        }
        }
    }
}

/**
 * IPTV Films or Series inside the dashboard: the same poster grid as Enigma Movies.
 * The category column hides after a choice and opens again on Left. Playing a title opens the full player.
 */
@Composable
internal fun IptvLibraryPage(
    model: XtreamViewModel,
    menuFocus: FocusRequester,
    sectionEnter: Int,
    section: XtreamSection,
    onRetreatTo: (FocusRequester) -> Unit,
    onPlay: () -> Unit,
) {
    val categoryFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var gridActive by remember { mutableStateOf(false) }
    val detection by model.detection.collectAsStateWithLifecycle()
    LaunchedEffect(section) {
        model.requestSection(section)
        model.prepareLibrary()
        if (model.detail != null) return@LaunchedEffect
        if (model.vodFromDashboard && model.cards.isNotEmpty()) {
            gridActive = true
            gridFocus.requestWhenReady(24)
            model.consumeVodFromDashboard()
        } else {
            gridActive = false
            categoryFocus.requestWhenReady()
        }
    }
    LaunchedEffect(sectionEnter) {
        if (sectionEnter == 0) return@LaunchedEffect
        gridActive = false
        categoryFocus.requestWhenReady()
    }
    LaunchedEffect(model.categoriesReady) {
        if (model.categoriesReady > 0 && !gridActive && model.detail == null) {
            categoryFocus.requestWhenReady()
        }
    }
    LaunchedEffect(model.itemsReady) {
        if (gridActive && model.itemsReady > 0 && model.cards.isNotEmpty() && model.detail == null) {
            gridFocus.requestWhenReady(24)
        }
    }
    val accounts = detection?.accounts.orEmpty()
    Box(Modifier.fillMaxSize()) {
        when {
            detection == null -> EnigmaLoadingIndicator(Modifier.align(Alignment.Center))
            accounts.isEmpty() -> Text(
                stringResource(R.string.xtream_none),
                color = EnigmaColors.Muted,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            model.account == null && accounts.size > 1 -> Unit
            else -> XtreamVodPage(
                model = model,
                gridActive = gridActive,
                categoryFocus = categoryFocus,
                gridFocus = gridFocus,
                onGrid = { gridActive = true },
                onCategories = {
                    gridActive = false
                    onRetreatTo(menuFocus)
                },
                onSections = { runCatching { menuFocus.requestFocus() } },
                onGridFocused = {
                    gridActive = true
                    onRetreatTo(categoryFocus)
                },
                onPlayed = onPlay,
            )
        }
    }
    if (model.account == null && accounts.size > 1) {
        com.enigmacast.ui.theme.TvChoiceDialog(
            title = stringResource(R.string.xtream_choose_playlist),
            message = stringResource(R.string.xtream_choose_playlist_desc),
            choices = accounts.map { it.name },
            onChoose = { model.choose(accounts[it], startLive = false) },
            onDismiss = { runCatching { menuFocus.requestFocus() } },
            stacked = true,
        )
    }
    IptvInfoDialog(model, onPlayed = onPlay)
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
}

/** Next episode, a small half-transparent card in the bottom-right corner. */
@Composable
internal fun NextEpisodeCard(
    episode: com.enigmacast.domain.xtream.XtreamEpisode,
    seconds: Int,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.requestWhenReady() }
    Row(
        modifier
            .width(240.dp)
            .background(EnigmaColors.Selected.copy(alpha = 0.5f), skinShape(8.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(42.dp)
                .height(62.dp)
                .clip(skinShape(4.dp))
                .background(EnigmaColors.Card.copy(alpha = 0.5f)),
        ) {
            if (episode.cover.isNotBlank()) {
                AsyncImage(
                    model = episode.cover,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                episode.title.ifBlank { stringResource(R.string.iptv_next) },
                color = EnigmaColors.Text,
                fontSize = 12.sp,
                fontWeight = EnigmaType.Strong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.iptv_next_in, seconds), color = EnigmaColors.Text, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NextCardButton(stringResource(R.string.iptv_next), onPlay, edgeLeft = true, modifier = Modifier.focusRequester(playFocus).weight(1f))
                NextCardButton(stringResource(R.string.iptv_next_cancel), onCancel, edgeRight = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NextCardButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    edgeLeft: Boolean = false,
    edgeRight: Boolean = false,
) {
    CompactListRow(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = modifier.focusProperties {
            up = FocusRequester.Cancel
            down = FocusRequester.Cancel
            if (edgeLeft) left = FocusRequester.Cancel
            if (edgeRight) right = FocusRequester.Cancel
        },
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Info page of a film or series, above the grid so Search can open it at once. */
@Composable
internal fun IptvInfoDialog(model: XtreamViewModel, onPlayed: () -> Unit) {
    val item = model.detail ?: return
    val film = model.detailFilm ?: return
    val isSeries = item.section == XtreamSection.SERIES
    FilmInfoDialog(
        film = film,
        nowSec = System.currentTimeMillis() / 1000,
        autoSwitchSet = false,
        recordingSet = false,
        onWatch = {},
        onSchedule = {},
        onRecord = {},
        onClose = model::closeDetail,
        picon = { null },
        cachedPicon = { null },
        channel = BouquetEntry(item.name, "iptv:${item.id}"),
        vod = VodDetail(
            onPlay = if (isSeries) null else ({ model.closeDetail(); model.requestFilm(item, onPlayed) }),
            resumeLabel = null,
            onResume = null,
            below = if (isSeries) ({ Episodes(model, item, onPlayed) }) else null,
            favorite = remember(item, model.favoriteVersion) { model.isFavorite(item) },
            onFavorite = { model.toggleFavorite(item) },
        ),
    )
}

/** Seasons as chips, then that season's episodes as a row; OK plays (resuming where left). */
@Composable
private fun Episodes(model: XtreamViewModel, show: XtreamItem, onPlayed: () -> Unit) {
    val episodes = model.detailEpisodes
    if (episodes.isEmpty()) {
        if (model.detailLoading) EnigmaLoadingIndicator() else Text(stringResource(R.string.iptv_no_episodes), color = EnigmaColors.Muted, fontSize = 13.sp)
        return
    }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    var season by remember(episodes) { mutableIntStateOf(seasons.first()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(seasons) { index, s ->
                CompactListRow(
                    onClick = { season = s },
                    selected = s == season,
                    modifier = Modifier.focusProperties {
                        if (index == 0) left = FocusRequester.Cancel
                        if (index == seasons.lastIndex) right = FocusRequester.Cancel
                    },
                ) { Text(stringResource(R.string.iptv_season, s), fontSize = 13.sp) }
            }
        }
        val list = remember(episodes, season) { com.enigmacast.domain.xtream.LazyKeys.keyed(episodes.filter { it.season == season }, "ep") { it.id } }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list, key = { _, k -> k.key }) { index, k ->
                val e = k.value
                EpisodeCard(e, model.resumeOf(e) > 0, Modifier.focusProperties {
                    if (index == 0) left = FocusRequester.Cancel
                    if (index == list.lastIndex) right = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }) {
                    model.holdEpisodes(episodes)
                    model.closeDetail()
                    model.requestEpisode(e, show, onPlayed)
                }
            }
        }
    }
}

private val EPISODE_WIDTH = 88.dp

@Composable
private fun EpisodeCard(e: XtreamEpisode, started: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .width(EPISODE_WIDTH)
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .focusable(interactionSource = interaction),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(EPISODE_WIDTH * 1.5f)
                .clip(shape)
                .background(EnigmaColors.Card)
                .border(if (focused) 2.dp else 1.dp, if (focused) EnigmaColors.Accent else EnigmaColors.CardBorder, shape),
        ) {
            if (e.cover.isNotBlank()) {
                AsyncImage(
                    model = e.cover,
                    contentDescription = e.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    e.title,
                    color = EnigmaColors.Text,
                    fontSize = 12.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(6.dp),
                )
            }
            if (started) {
                Text(
                    stringResource(R.string.iptv_resume_badge),
                    fontSize = 10.sp,
                    color = EnigmaColors.Accent,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                )
            }
        }
        Text(
            if (e.season > 0) "S${e.season}E${e.episode}" else "${e.episode}",
            fontSize = 11.sp,
            color = EnigmaColors.Accent,
            maxLines = 1,
        )
        Text(
            e.title,
            fontSize = 12.sp,
            color = if (focused) EnigmaColors.Text else EnigmaColors.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VodHeader(text: String, loading: Boolean, position: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
        Text(text, color = EnigmaColors.Text, fontSize = 17.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (position != null) Text(position, color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
        if (loading) EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Left/Right of the categories column, before the focus search; KeyUp of a handled key is eaten too. */
private fun Modifier.columnKeysVod(onLeft: (() -> Unit)?, onRight: (() -> Unit)?): Modifier = onPreviewKeyEvent { e ->
    val handler = when (e.key) {
        Key.DirectionLeft -> onLeft
        Key.DirectionRight -> onRight
        else -> null
    } ?: return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) handler()
    true
}

/**
 * Films / Series columns: Right from the categories enters the grid whenever cards are shown
 * (auto-loaded or loaded on OK); Back walks grid → categories → section choice, one press each.
 */
internal object VodPanelNav {
    enum class Column { SECTIONS, CATEGORIES, GRID }

    fun canEnterGrid(cards: Int) = cards > 0

    /** "5/60" for the focused card; just the total while the grid has no focus; null when empty. */
    fun position(focusedIndex: Int?, total: Int): String? = when {
        total <= 0 -> null
        focusedIndex != null && focusedIndex in 0 until total -> "${focusedIndex + 1}/$total"
        else -> total.toString()
    }

    /** The categories column hides once the posters have focus, and Left opens it again. */
    fun categoriesShown(gridActive: Boolean) = !gridActive

    /** Poster columns that fit [widthDp] (minus the grid's 8 dp side padding), at least 1. */
    fun columns(widthDp: Float, cardDp: Float, gapDp: Float): Int =
        (((widthDp - 16f) + gapDp) / (cardDp + gapDp)).toInt().coerceAtLeast(1)

    fun back(column: Column): Column = when (column) {
        Column.GRID -> Column.CATEGORIES
        Column.CATEGORIES -> Column.SECTIONS
        Column.SECTIONS -> Column.SECTIONS
    }
}
