package com.enigmacast.ui.home

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.TmdbCastMember
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.remote.Picon
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.requestWhenReady

/**
 * Netflix/Disney+-style info page over the whole screen. Focus starts on Watch; Back closes.
 * Down from the buttons reaches the cast row, which scrolls sideways.
 */
@Composable
fun FilmInfoDialog(
    film: HomeFilm,
    nowSec: Long,
    autoSwitchSet: Boolean,
    recordingSet: Boolean,
    onWatch: () -> Unit,
    onSchedule: () -> Unit,
    onRecord: () -> Unit,
    onClose: () -> Unit,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    channel: BouquetEntry,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        val watchFocus = remember { FocusRequester() }
        LaunchedEffect(film.key) { watchFocus.requestWhenReady() }
        val movie = film.movie
        val airing = film.event.isAiring(nowSec)
        val ended = film.event.endEpochSec <= nowSec
        val plot = movie.overview.ifBlank { film.event.description }
        var showPlot by remember(film.key) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
            if (movie.backdropPath != null) {
                AsyncImage(
                    model = TmdbJson.image(movie.backdropPath, "w1280"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to EnigmaColors.Canvas, 0.55f to EnigmaColors.Canvas.copy(alpha = 0.82f), 1f to EnigmaColors.Canvas.copy(alpha = 0.2f)),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to EnigmaColors.Canvas)))
            // Fixed layout, no vertical scrolling: everything fits on one 960×540 dp screen.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(
                        Modifier
                            .width(150.dp)
                            .height(225.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(EnigmaColors.Card)
                            .border(1.dp, EnigmaColors.GlassEdge, RoundedCornerShape(12.dp)),
                    ) {
                        if (movie.posterPath != null) {
                            AsyncImage(
                                model = TmdbJson.image(movie.posterPath, "w342"),
                                contentDescription = film.displayTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        TitleOrLogo(film, height = 56.dp, fontSize = 28.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RatingStars(movie.voteAverage, size = 13.sp)
                            movie.year?.let { Text(it.toString(), color = EnigmaColors.Muted, fontSize = 13.sp) }
                            movie.runtimeMin?.let { Text(stringResource(R.string.home_runtime, it), color = EnigmaColors.Muted, fontSize = 13.sp) }
                            if (movie.certification.isNotBlank()) {
                                Text(
                                    movie.certification,
                                    color = EnigmaColors.Text,
                                    fontSize = 11.sp,
                                    modifier = Modifier.border(1.dp, EnigmaColors.Muted, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
                                )
                            }
                            if (airing) Badge(stringResource(R.string.home_live), LiveRed)
                        }
                        val genres = movie.genres.joinToString(" · ")
                        val line = listOf(genres, movie.tagline).filter { it.isNotBlank() }.joinToString("  —  ")
                        if (line.isNotBlank()) {
                            Text(line, color = EnigmaColors.Cyan, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (plot.isNotBlank()) {
                            Text(plot, color = EnigmaColors.Muted, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        if (movie.directors.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.home_director), color = EnigmaColors.Subtle, fontSize = 13.sp)
                                Text(movie.directors.joinToString(", "), color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Picon(channel, picon, cachedPicon, Modifier.size(width = 44.dp, height = 26.dp), fallbackSize = 9.sp)
                            Text(film.candidate.channelName, color = EnigmaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(FilmFormat.slot(film), color = EnigmaColors.Accent, fontSize = 14.sp)
                            if (autoSwitchSet) Badge(stringResource(R.string.home_scheduled), EnigmaColors.Accent)
                            if (recordingSet) Badge(stringResource(R.string.home_recording_set), LiveRed)
                        }
                        if (airing) EnigmaProgramProgress(film.event.progress(nowSec), Modifier.width(320.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                            EnigmaGlassButton(
                                stringResource(if (airing || ended) R.string.home_watch else R.string.home_watch_channel),
                                onWatch,
                                Modifier.focusRequester(watchFocus),
                            )
                            if (!airing && !ended) {
                                EnigmaGlassButton(
                                    stringResource(if (autoSwitchSet) R.string.autoswitch_cancel_action else R.string.home_schedule),
                                    onSchedule,
                                )
                            }
                            if (!ended) {
                                EnigmaGlassButton(stringResource(if (recordingSet) R.string.home_recording_set else R.string.home_record), onRecord)
                            }
                            if (plot.length > PLOT_MORE_CHARS) {
                                EnigmaGlassButton(stringResource(R.string.home_more), { showPlot = true })
                            }
                            EnigmaGlassButton(stringResource(R.string.home_close), onClose)
                        }
                    }
                }
                if (movie.cast.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.home_cast), color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp, horizontal = 2.dp)) {
                            items(movie.cast, key = { it.name + it.character }) { member -> CastCard(member) }
                        }
                    }
                }
                Text(stringResource(R.string.home_tmdb_attribution), color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1)
            }
            if (showPlot) {
                PlotDialog(film.displayTitle, plot, onClose = { showPlot = false })
            }
        }
    }
}

private const val PLOT_MORE_CHARS = 260

/** "More": the whole plot (and tagline) in a small window; Back or OK closes it. */
@Composable
private fun PlotDialog(title: String, plot: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val close = remember { FocusRequester() }
        LaunchedEffect(Unit) { close.requestWhenReady() }
        Column(
            Modifier
                .width(620.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(plot, color = EnigmaColors.Muted, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 14, overflow = TextOverflow.Ellipsis)
            EnigmaGlassButton(stringResource(R.string.home_close), onClose, Modifier.focusRequester(close))
        }
    }
}

@Composable
private fun CastCard(member: TmdbCastMember) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.width(84.dp).focusable(interactionSource = interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(EnigmaColors.Raised)
                .border(if (focused) 2.dp else 1.dp, if (focused) EnigmaColors.Cyan else EnigmaColors.CardBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (member.profilePath != null) {
                AsyncImage(
                    model = TmdbJson.image(member.profilePath, "w185"),
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(member.name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color = EnigmaColors.Muted, fontSize = 16.sp)
            }
        }
        Text(member.name, color = if (focused) EnigmaColors.Text else EnigmaColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (member.character.isNotBlank()) {
            Text(member.character, color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The same full-screen info page for a recording in My items: only Watch and Delete (Delete
 * asks first, in the caller). [detail] is channel · date · place; [progress] the watched part.
 */
@Composable
fun RecordingInfoDialog(
    film: HomeFilm,
    detail: String,
    progress: Float,
    watchLabel: String,
    onWatch: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        val watchFocus = remember { FocusRequester() }
        LaunchedEffect(film.key) { watchFocus.requestWhenReady() }
        val movie = film.movie
        val plot = movie.overview.ifBlank { film.event.description }
        var showPlot by remember(film.key) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
            if (movie.backdropPath != null) {
                AsyncImage(
                    model = TmdbJson.image(movie.backdropPath, "w1280"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to EnigmaColors.Canvas, 0.55f to EnigmaColors.Canvas.copy(alpha = 0.82f), 1f to EnigmaColors.Canvas.copy(alpha = 0.2f)),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to EnigmaColors.Canvas)))
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PosterBox(film, Modifier.width(150.dp).height(225.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        TitleOrLogo(film, height = 56.dp, fontSize = 28.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (movie.voteCount > 0) RatingStars(movie.voteAverage, size = 13.sp)
                            movie.year?.let { Text(it.toString(), color = EnigmaColors.Muted, fontSize = 13.sp) }
                            movie.runtimeMin?.let { Text(stringResource(R.string.home_runtime, it), color = EnigmaColors.Muted, fontSize = 13.sp) }
                        }
                        val genres = movie.genres.joinToString(" · ")
                        if (genres.isNotBlank()) Text(genres, color = EnigmaColors.Cyan, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (plot.isNotBlank()) {
                            Text(plot, color = EnigmaColors.Muted, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        if (movie.directors.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.home_director), color = EnigmaColors.Subtle, fontSize = 13.sp)
                                Text(movie.directors.joinToString(", "), color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(detail, color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (progress > 0f) EnigmaProgramProgress(progress, Modifier.width(320.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                            EnigmaGlassButton(watchLabel, onWatch, Modifier.focusRequester(watchFocus))
                            EnigmaGlassButton(stringResource(R.string.myitems_delete), onDelete)
                            if (plot.length > PLOT_MORE_CHARS) EnigmaGlassButton(stringResource(R.string.home_more), { showPlot = true })
                        }
                    }
                }
                if (movie.cast.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.home_cast), color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp, horizontal = 2.dp)) {
                            items(movie.cast, key = { it.name + it.character }) { member -> CastCard(member) }
                        }
                    }
                }
                if (movie.id > 0) Text(stringResource(R.string.home_tmdb_attribution), color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1)
            }
            if (showPlot) PlotDialog(film.displayTitle, plot, onClose = { showPlot = false })
        }
    }
}

/** TMDB poster, or a placeholder with the title when there is no match. */
@Composable
fun PosterBox(film: HomeFilm, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(EnigmaColors.Raised, EnigmaColors.Card)))
            .border(1.dp, EnigmaColors.GlassEdge, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val poster = film.movie.posterPath
        if (poster != null) {
            AsyncImage(
                model = TmdbJson.image(poster, "w342"),
                contentDescription = film.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                film.displayTitle,
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
