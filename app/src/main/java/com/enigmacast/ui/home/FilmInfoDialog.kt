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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical + 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Box(
                        Modifier
                            .width(190.dp)
                            .height(285.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(EnigmaColors.Card)
                            .border(1.dp, EnigmaColors.GlassEdge, RoundedCornerShape(14.dp)),
                    ) {
                        if (movie.posterPath != null) {
                            AsyncImage(
                                model = TmdbJson.image(movie.posterPath, "w500"),
                                contentDescription = film.displayTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.fillMaxWidth(0.72f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TitleOrLogo(film, height = 80.dp, fontSize = 34.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RatingStars(movie.voteAverage)
                            movie.year?.let { Text(it.toString(), color = EnigmaColors.Muted, fontSize = 14.sp) }
                            movie.runtimeMin?.let { Text(stringResource(R.string.home_runtime, it), color = EnigmaColors.Muted, fontSize = 14.sp) }
                            if (movie.certification.isNotBlank()) {
                                Text(
                                    movie.certification,
                                    color = EnigmaColors.Text,
                                    fontSize = 12.sp,
                                    modifier = Modifier.border(1.dp, EnigmaColors.Muted, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                            if (airing) Badge(stringResource(R.string.home_live), LiveRed)
                        }
                        if (movie.genres.isNotEmpty()) {
                            Text(movie.genres.joinToString(" · "), color = EnigmaColors.Cyan, fontSize = 14.sp)
                        }
                        if (movie.tagline.isNotBlank()) {
                            Text(movie.tagline, color = EnigmaColors.Text, fontSize = 15.sp, fontStyle = FontStyle.Italic)
                        }
                        val plot = movie.overview.ifBlank { film.event.description }
                        if (plot.isNotBlank()) {
                            Text(plot, color = EnigmaColors.Muted, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                        if (movie.directors.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.home_director), color = EnigmaColors.Subtle, fontSize = 14.sp)
                                Text(movie.directors.joinToString(", "), color = EnigmaColors.Text, fontSize = 14.sp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Picon(channel, picon, cachedPicon, Modifier.size(width = 52.dp, height = 32.dp), fallbackSize = 9.sp)
                            Text(film.candidate.channelName, color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(FilmFormat.slot(film), color = EnigmaColors.Accent, fontSize = 15.sp)
                            if (autoSwitchSet) Badge(stringResource(R.string.home_scheduled), EnigmaColors.Accent)
                            if (recordingSet) Badge(stringResource(R.string.home_recording_set), LiveRed)
                        }
                        if (airing) EnigmaProgramProgress(film.event.progress(nowSec), Modifier.width(360.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
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
                            EnigmaGlassButton(stringResource(R.string.home_close), onClose)
                        }
                    }
                }
                if (movie.cast.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.home_cast), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                            items(movie.cast, key = { it.name + it.character }) { member -> CastCard(member) }
                        }
                    }
                }
                Text(stringResource(R.string.home_tmdb_attribution), color = EnigmaColors.Subtle, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CastCard(member: TmdbCastMember) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.width(104.dp).focusable(interactionSource = interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(84.dp)
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
                Text(member.name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color = EnigmaColors.Muted, fontSize = 22.sp)
            }
        }
        Text(member.name, color = if (focused) EnigmaColors.Text else EnigmaColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (member.character.isNotBlank()) {
            Text(member.character, color = EnigmaColors.Subtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
