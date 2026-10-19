package com.enigmacast.ui.home

import coil3.request.crossfade
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.holdPageFocus
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.HomeRow
import com.enigmacast.domain.films.RowKind
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.components.EnigmaChannelCard
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.recording.RecordConfirmDialog
import com.enigmacast.ui.remote.Picon
import com.enigmacast.ui.remote.rememberGuideClock
import com.enigmacast.ui.settings.SettingsViewModel
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.util.asString
import com.enigmacast.util.isEmpty

private const val RECENTS_SECTION = 1000
private val PosterShape get() = skinShape(12.dp)
private const val CARD_WIDTH_DP = 132

/**
 * The film home page: a hero with today's best film still to come, then rows of films from
 * today's guide with TMDB artwork. OK on a card opens [FilmInfoDialog].
 */
@Composable
fun FilmHomePanel(
    contentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onRetreatTo: (FocusRequester) -> Unit,
    onWatch: (BouquetEntry) -> Unit,
    onContinue: (LastChannel) -> Unit,
    series: Boolean = false,
    model: HomeFilmsViewModel = hiltViewModel(key = if (series) "home-series" else "home-films"),
    settingsModel: SettingsViewModel = hiltViewModel(),
) {
    model.setSeries(series)
    val settings by settingsModel.settings.collectAsStateWithLifecycle()
    val receiverId = settings.activeReceiver()?.id.orEmpty()
    val recents = ChannelMemory.recentFor(settings, receiverId)
    val autoKeys by model.autoSwitchKeys.collectAsStateWithLifecycle()
    val recordKeys by model.recordingKeys.collectAsStateWithLifecycle()
    val nowSec = rememberGuideClock()
    val sync by model.progress.collectAsStateWithLifecycle()
    val hero = model.hero
    var backdropFilm by remember { mutableStateOf<HomeFilm?>(null) }
    val shown = backdropFilm ?: hero
    val left = Modifier.focusProperties { left = menuFocus }

    CompositionLocalProvider(LocalEdgeLeft provides menuFocus, LocalSeriesPage provides series) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(skinShape(16.dp))
                .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) }
                .holdPageFocus(contentFocus, listOf(model.state::class, hero?.key)),
        ) {
            Backdrop(shown)
            val sections = remember { PivotState() }
            // Explicit Up/Down between hero, film rows and recent channels: a plain focus search from
            // the hero found nothing below in the shifted column and went to the left menu.
            val chain = com.enigmacast.ui.soccer.rememberFocusChain()
            val ready = model.state as? HomeFilmsViewModel.State.Ready
            chain.keys = buildList {
                add("hero")
                if (ready != null) model.rows.indices.forEach { add("row-$it") }
                if (recents.isNotEmpty()) add("recents")
            }
            PivotColumn(sections, Modifier.fillMaxSize()) {
                when (val state = model.state) {
                    HomeFilmsViewModel.State.Loading -> Box(sections.section(0)) { LoadingSkeleton(contentFocus, left, sync?.percent, sync?.let { running -> running.step.asString() + (running.total?.let { " (${running.done ?: 0}/$it)" } ?: "") }) }
                    is HomeFilmsViewModel.State.Ready -> {
                        Box(sections.section(0).then(chain.section("hero"))) {
                            if (hero != null) {
                                Hero(
                                    film = hero,
                                    nowSec = nowSec,
                                    autoSwitchSet = hero.key in autoKeys,
                                    recordingSet = hero.key in recordKeys,
                                    watchModifier = Modifier.focusRequester(contentFocus).then(left),
                                    onFocused = { backdropFilm = null },
                                    onWatch = { onWatch(model.channelOf(hero)) },
                                    onInfo = { model.openInfo(hero) },
                                    onSchedule = { model.toggleAutoSwitch(hero) },
                                )
                            } else {
                                Spacer(Modifier.size(1.dp).focusRequester(contentFocus).then(left).focusable())
                            }
                        }
                        model.rows.forEachIndexed { index, row ->
                            Box(sections.section(index + 1).then(chain.section("row-$index"))) {
                                FilmRow(
                                    row = row,
                                    nowSec = nowSec,
                                    autoKeys = autoKeys,
                                    recordKeys = recordKeys,
                                    firstModifier = left,
                                    onFocus = { backdropFilm = it },
                                    onOpen = model::openInfo,
                                    model = model,
                                )
                            }
                        }
                    }
                    else -> Box(sections.section(0)) { EmptyState(state, contentFocus, left, onRetry = { model.load(true) }) }
                }
                if (recents.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = sections.section(RECENTS_SECTION).then(chain.section("recents")).padding(start = 24.dp),
                    ) {
                        RowTitle(stringResource(R.string.home_recent))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 24.dp)) {
                            itemsIndexed(recents, key = { _, item -> item.reference }) { index, item ->
                                EnigmaChannelCard(
                                    title = item.name.ifBlank { item.reference },
                                    subtitle = "",
                                    onClick = { onContinue(item) },
                                    modifier = Modifier.width(220.dp).then(if (index == 0) left else Modifier),
                                )
                            }
                        }
                    }
                }
            }
            // A sync running behind a filled Home shows in the app-wide status window at the top.
            if (!model.notice.isEmpty()) {
                Text(
                    model.notice.asString(),
                    color = EnigmaColors.Text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .background(EnigmaColors.Selected, skinShape(10.dp))
                        .border(1.dp, EnigmaColors.CardBorder, skinShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }

    model.info?.let { film ->
        FilmInfoDialog(
            film = film,
            nowSec = nowSec,
            autoSwitchSet = film.key in autoKeys,
            recordingSet = film.key in recordKeys,
            onWatch = {
                model.closeInfo()
                onWatch(model.channelOf(film))
            },
            onSchedule = { model.toggleAutoSwitch(film) },
            onRecord = { model.record(film) },
            onClose = model::closeInfo,
            picon = { model.picon(it) },
            cachedPicon = model::cachedPicon,
            channel = model.channelOf(film),
        )
    }
    model.recordAsk?.let { ask ->
        RecordConfirmDialog(ask, onConfirm = model::confirmRecord, onDismiss = model::dismissRecord)
    }
}

@Composable
internal fun Backdrop(film: HomeFilm?) {
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        Crossfade(targetState = film?.movie?.backdropPath, animationSpec = tween(450), label = "homeBackdrop") { path ->
            if (path != null) {
                AsyncImage(
                    model = TmdbJson.image(path, "w1280"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(EnigmaColors.Navy))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to EnigmaColors.Canvas,
                    0.45f to EnigmaColors.Canvas.copy(alpha = 0.75f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to EnigmaColors.Canvas),
            ),
        )
    }
}

@Composable
private fun Hero(
    film: HomeFilm,
    nowSec: Long,
    autoSwitchSet: Boolean,
    recordingSet: Boolean,
    watchModifier: Modifier,
    onFocused: () -> Unit,
    onWatch: () -> Unit,
    onInfo: () -> Unit,
    onSchedule: () -> Unit,
) {
    val movie = film.movie
    val airing = film.event.isAiring(nowSec)
    Column(
        Modifier
            .fillMaxWidth(0.62f)
            .heightIn(min = 300.dp)
            .padding(start = 28.dp, top = 26.dp)
            .onFocusChanged { if (it.hasFocus) onFocused() },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (LocalSeriesPage.current) R.string.series_hero_label else R.string.home_hero_label), color = EnigmaColors.Cyan, fontSize = EnigmaType.Label, fontWeight = EnigmaType.Strong)
            if (airing) Badge(stringResource(R.string.home_live), LiveRed)
        }
        TitleOrLogo(film, height = 72.dp, fontSize = 36.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RatingStars(movie.voteAverage)
            movie.year?.let { Text(it.toString(), color = EnigmaColors.Muted, fontSize = 14.sp) }
            if (movie.genres.isNotEmpty()) {
                Text(movie.genres.take(3).joinToString(" · "), color = EnigmaColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val plot = movie.overview.ifBlank { film.event.description }
        if (plot.isNotBlank()) {
            Text(plot, color = EnigmaColors.Muted, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(film.candidate.channelName, color = EnigmaColors.Text, fontSize = 14.sp, fontWeight = EnigmaType.Strong)
            Text(FilmFormat.slot(film), color = EnigmaColors.Accent, fontSize = 14.sp)
            if (autoSwitchSet) Badge(stringResource(R.string.home_scheduled), EnigmaColors.Accent)
            if (recordingSet) Badge(stringResource(R.string.home_recording_set), LiveRed)
        }
        if (airing) EnigmaProgramProgress(film.event.progress(nowSec), Modifier.width(320.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
            EnigmaGlassButton(stringResource(R.string.home_watch), onWatch, watchModifier)
            EnigmaGlassButton(stringResource(R.string.home_info), onInfo)
            if (!airing) {
                EnigmaGlassButton(
                    stringResource(if (autoSwitchSet) R.string.autoswitch_cancel_action else R.string.home_schedule),
                    onSchedule,
                )
            }
        }
    }
}

@Composable
internal fun TitleOrLogo(film: HomeFilm, height: androidx.compose.ui.unit.Dp, fontSize: androidx.compose.ui.unit.TextUnit) {
    val logo = film.movie.logoPath
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) {
        AsyncImage(
            model = TmdbJson.image(logo, "w500"),
            contentDescription = film.displayTitle,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.height(height).fillMaxWidth(0.8f),
        )
    } else {
        Text(
            film.displayTitle,
            color = EnigmaColors.Text,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = fontSize * 1.1f,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(text, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = EnigmaType.Strong)
}

@Composable
private fun rowTitle(row: HomeRow): String = when (row.kind) {
    RowKind.NOW -> stringResource(R.string.home_row_now)
    RowKind.TONIGHT -> stringResource(R.string.home_row_tonight)
    RowKind.TOP_RATED -> stringResource(R.string.home_row_top)
    RowKind.LATER -> stringResource(R.string.home_row_later)
    RowKind.GENRE -> row.films.firstNotNullOfOrNull { film ->
        film.movie.genreIds.indexOf(row.genreId).takeIf { it >= 0 }?.let { film.movie.genres.getOrNull(it) }
    } ?: stringResource(R.string.home_row_genre)
}

@Composable
private fun FilmRow(
    row: HomeRow,
    nowSec: Long,
    autoKeys: Set<String>,
    recordKeys: Set<String>,
    firstModifier: Modifier,
    onFocus: (HomeFilm) -> Unit,
    onOpen: (HomeFilm) -> Unit,
    model: HomeFilmsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 24.dp)) {
        RowTitle(rowTitle(row))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
        ) {
            itemsIndexed(row.films, key = { _, film -> film.key }) { index, film ->
                FilmCard(
                    film = film,
                    nowSec = nowSec,
                    autoSwitchSet = film.key in autoKeys,
                    recordingSet = film.key in recordKeys,
                    modifier = if (index == 0) firstModifier else Modifier,
                    onFocus = { onFocus(film) },
                    onClick = { onOpen(film) },
                    model = model,
                )
            }
        }
    }
}

@Composable
private fun FilmCard(
    film: HomeFilm,
    nowSec: Long,
    autoSwitchSet: Boolean,
    recordingSet: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    model: HomeFilmsViewModel,
) {
    val airing = film.event.isAiring(nowSec)
    PosterCard(
        film = film,
        modifier = modifier,
        onFocus = onFocus,
        onClick = onClick,
        overlay = {
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (airing) Badge(stringResource(R.string.home_live), LiveRed)
                if (autoSwitchSet) Badge("⏰", EnigmaColors.Accent)
                if (recordingSet) Badge("●", LiveRed)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(width = 46.dp, height = 28.dp)
                    .background(Color(0xCC080D1C), skinShape(6.dp))
                    .padding(3.dp),
            ) {
                Picon(
                    entry = model.channelOf(film),
                    picon = { model.picon(it) },
                    cachedPicon = model::cachedPicon,
                    modifier = Modifier.fillMaxSize(),
                    fallbackSize = 8.sp,
                )
            }
            if (!airing) {
                Text(
                    FilmFormat.time(film.event.beginEpochSec),
                    color = EnigmaColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0xE0080D1C), skinShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            if (airing) {
                EnigmaProgramProgress(
                    film.event.progress(nowSec),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    emphasized = false,
                )
            }
        },
        lines = {
            Text(film.candidate.channelName, color = EnigmaColors.Subtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (airing) stringResource(R.string.home_card_live_left, FilmFormat.minutesLeft(film, nowSec)) else FilmFormat.slot(film),
                color = if (airing) LiveRed else EnigmaColors.Accent,
                fontSize = 12.sp,
                fontWeight = EnigmaType.Strong,
                maxLines = 1,
            )
        },
    )
}

/**
 * The poster card of the Movies and TV Series pages: TMDB poster (or the title), focus
 * scale and glow, title, the caller's [lines] and the rating. [overlay] draws on the poster.
 */
@Composable
internal fun PosterCard(
    film: HomeFilm,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    lines: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val spec = LocalMotionSpec.current
    val scale by animateFloatAsState(if (focused && spec.animate) 1.08f else 1f, tween(spec.cardMs), label = "filmCard")
    Column(
        modifier
            .width(CARD_WIDTH_DP.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .focusable(interactionSource = interaction),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height((CARD_WIDTH_DP * 1.5f).dp)
                .then(
                    if (focused && spec.shadows) {
                        Modifier.shadow(16.dp, PosterShape, ambientColor = EnigmaColors.Accent.copy(alpha = 0.35f), spotColor = EnigmaColors.Cyan.copy(alpha = 0.28f))
                    } else {
                        Modifier
                    },
                )
                .clip(PosterShape)
                .background(EnigmaColors.Card)
                .border(if (focused) 2.dp else 1.dp, if (focused) EnigmaColors.Cyan else EnigmaColors.CardBorder, PosterShape),
        ) {
            val poster = film.movie.posterPath
            var failed by remember(poster) { mutableStateOf(false) }
            if (poster != null && !failed) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val request = remember(poster) {
                    // Decoded at card size (playlist covers are often full-size originals), faded in.
                    val w = with(density) { CARD_WIDTH_DP.dp.roundToPx() }
                    coil3.request.ImageRequest.Builder(context)
                        .data(TmdbJson.image(poster, "w342"))
                        .size(w, (w * 1.5f).toInt())
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = film.displayTitle,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    film.displayTitle,
                    color = EnigmaColors.Text,
                    fontSize = 15.sp,
                    fontWeight = EnigmaType.Strong,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            }
            overlay()
        }
        Text(
            film.displayTitle,
            color = if (focused) EnigmaColors.Text else EnigmaColors.Muted,
            fontSize = 13.sp,
            fontWeight = EnigmaType.Strong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        lines()
        // The rating line is always laid out (invisible without a rating), so the card's bounds,
        // which focus brings into view, always include it: a rating that arrives with the artwork
        // after the card was focused and scrolled to no longer grows the card past the grid edge.
        val rating = film.movie.voteAverage
        if (rating > 0.0) {
            RatingStars(rating, size = 10.sp)
        } else {
            RatingStars(10.0, size = 10.sp, modifier = Modifier.alpha(0f).clearAndSetSemantics {})
        }
    }
}

@Composable
private fun LoadingSkeleton(contentFocus: FocusRequester, left: Modifier, percent: Int?, step: String?) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    val block = EnigmaColors.Raised.copy(alpha = pulse)
    Column(Modifier.padding(start = 28.dp, top = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            EnigmaLoadingIndicator(Modifier.size(22.dp))
            Text(
                if (percent != null) stringResource(R.string.sync_preparing, percent) + (step?.let { "  ·  $it" } ?: "") else stringResource(R.string.home_loading),
                color = EnigmaColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.focusRequester(contentFocus).then(left).focusable(),
            )
        }
        Box(Modifier.width(380.dp).height(44.dp).background(block, skinShape(8.dp)))
        Box(Modifier.width(260.dp).height(16.dp).background(block, skinShape(6.dp)))
        Box(Modifier.width(420.dp).height(48.dp).background(block, skinShape(6.dp)))
        Spacer(Modifier.height(80.dp))
        repeat(2) {
            Box(Modifier.width(160.dp).height(18.dp).background(block, skinShape(6.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) { Box(Modifier.width(CARD_WIDTH_DP.dp).height((CARD_WIDTH_DP * 1.5f).dp).background(block, PosterShape)) }
            }
        }
    }
}

@Composable
private fun EmptyState(state: HomeFilmsViewModel.State, contentFocus: FocusRequester, left: Modifier, onRetry: () -> Unit) {
    val (title, body) = when (state) {
        HomeFilmsViewModel.State.NoReceiver -> stringResource(R.string.home_empty) to ""
        HomeFilmsViewModel.State.MissingKey -> stringResource(R.string.home_missing_key) to stringResource(R.string.home_missing_key_hint)
        HomeFilmsViewModel.State.NoBouquets -> stringResource(R.string.home_no_bouquets) to stringResource(R.string.home_no_bouquets_hint)
        else -> if (LocalSeriesPage.current) stringResource(R.string.series_none) to stringResource(R.string.series_none_hint)
        else stringResource(R.string.home_no_films) to stringResource(R.string.home_no_films_hint)
    }
    Column(Modifier.padding(start = 28.dp, top = 60.dp).fillMaxWidth(0.7f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = EnigmaType.Strong)
        if (body.isNotBlank()) Text(body, color = EnigmaColors.Muted, fontSize = 14.sp)
        EnigmaGlassButton(stringResource(R.string.home_try_again), onRetry, Modifier.focusRequester(contentFocus).then(left))
        Spacer(Modifier.height(140.dp))
    }
}

/** True on the TV Series page, which reuses this layout. */
internal val LocalSeriesPage = androidx.compose.runtime.staticCompositionLocalOf { false }
