package com.enigmacast.ui.xtream

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.FocusRequester
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
import kotlinx.coroutines.flow.distinctUntilChanged

private const val GRID_COLUMNS = 5

/**
 * IPTV Films / Series: the categories on the left and, to their right, the poster grid of the
 * Enigma2 Movies and TV Series pages (same cards, TMDB artwork, backdrop of the focused title).
 * OK on a category loads it; OK on a card opens the same info page, with Play / Resume and,
 * for a series, its seasons and episodes from the playlist.
 */
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
    // Built once per category (off the main thread); an artwork update only changes its own card.
    val cards = model.cards
    Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
        Box(Modifier.padding(start = 260.dp)) { Backdrop(focused) }
        Row(
            Modifier.fillMaxSize().padding(horizontal = EnigmaColors.SafeHorizontal / 2, vertical = EnigmaColors.SafeVertical),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Categories; Right enters a grid that is already there.
            Column(
                Modifier.width(240.dp).fillMaxHeight().background(EnigmaColors.Canvas.copy(alpha = 0.9f)).onFocusChanged { if (it.hasFocus) onCategories() }.columnKeysVod(
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
            // The poster grid.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                VodHeader(model.category?.name.orEmpty(), model.loading)
                if (model.error) Text(stringResource(R.string.xtream_load_error), color = EnigmaColors.Danger, fontSize = 13.sp)
                if (!model.loading && model.category != null && cards.isEmpty()) {
                    Text(stringResource(R.string.iptv_vod_empty), color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                }
                val grid = rememberLazyGridState()
                LaunchedEffect(cards) { if (cards.isNotEmpty()) grid.scrollToItem(0) }
                // Artwork for the cards on screen only; a scroll cancels the waiting batch.
                LaunchedEffect(grid, cards) {
                    snapshotFlow { grid.layoutInfo.visibleItemsInfo.map { it.index } }
                        .distinctUntilChanged()
                        .collect { rows -> model.requestArtwork(rows.mapNotNull { cards.getOrNull(it)?.item }) }
                }
                LaunchedEffect(gridActive) { if (gridActive) gridFocus.requestWhenReady() }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
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
                        val lastRowStart = (cards.size - 1) / GRID_COLUMNS * GRID_COLUMNS
                        PosterCard(
                            film = film,
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(gridFocus) else Modifier)
                                .focusProperties {
                                    if (index % GRID_COLUMNS == 0) left = categoryFocus
                                    if (index % GRID_COLUMNS == GRID_COLUMNS - 1 || index == cards.lastIndex) right = FocusRequester.Cancel
                                    if (index < GRID_COLUMNS) up = FocusRequester.Cancel
                                    if (index >= lastRowStart) down = FocusRequester.Cancel
                                },
                            onFocus = { focused = film },
                            onClick = { model.openDetail(item) },
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

    val item = model.detail
    val film = model.detailFilm
    if (item != null && film != null) {
        val resume = model.resumeOf(item)
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
                onPlay = if (isSeries) null else ({ model.closeDetail(); model.playFilm(item, fromStart = true); onPlayed() }),
                resumeLabel = if (!isSeries && resume > 0) stringResource(R.string.iptv_resume, clock(resume)) else null,
                onResume = if (!isSeries && resume > 0) ({ model.closeDetail(); model.playFilm(item, fromStart = false); onPlayed() }) else null,
                below = if (isSeries) ({ Episodes(model, item, onPlayed) }) else null,
            ),
        )
    }
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
                    model.closeDetail()
                    model.playEpisode(e, show)
                    onPlayed()
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(e: XtreamEpisode, started: Boolean, modifier: Modifier, onClick: () -> Unit) {
    CompactListRow(onClick = onClick, modifier = modifier.width(200.dp)) {
        Column {
            Text(if (e.season > 0) "S${e.season}E${e.episode}" else "${e.episode}", fontSize = 12.sp, color = EnigmaColors.Accent)
            Text(e.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (started) Text(stringResource(R.string.iptv_resume_badge), fontSize = 11.sp, color = EnigmaColors.Accent)
        }
    }
}

@Composable
private fun VodHeader(text: String, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
        Text(text, color = EnigmaColors.Text, fontSize = 17.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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

    fun back(column: Column): Column = when (column) {
        Column.GRID -> Column.CATEGORIES
        Column.CATEGORIES -> Column.SECTIONS
        Column.SECTIONS -> Column.SECTIONS
    }
}
