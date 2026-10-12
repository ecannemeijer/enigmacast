package com.enigmacast.ui.soccer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.domain.soccer.pitch.PitchMatch
import com.enigmacast.domain.soccer.pitch.PitchStats
import com.enigmacast.domain.soccer.pitch.PitchStatus
import com.enigmacast.domain.soccer.pitch.TableRow
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.home.Badge
import com.enigmacast.ui.home.LiveRed
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import java.util.Locale

internal val PitchShape = RoundedCornerShape(12.dp)
internal val WinGreen = Color(0xFF2FBF71)

/** A .webp crest or player photo from cdn.pitchapi.dev; [fallback] text while loading or on error. */
@Composable
fun PitchImage(url: String, fallback: String, size: Dp, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (url.isBlank() || failed) {
            Box(Modifier.size(size).background(EnigmaColors.Raised, shape), contentAlignment = Alignment.Center) {
                Text(fallback, color = EnigmaColors.Muted, fontSize = (size.value / 3.2f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(size).clip(shape))
        }
    }
}

/** Focus source, whether it is focused, and whether OK went down on this element (for [tvClick]). */
class TvFocus(val source: MutableInteractionSource, private val state: androidx.compose.runtime.State<Boolean>) {
    val focused: Boolean get() = state.value
    var pressed = false
}

@Composable
fun rememberTvFocus(): TvFocus {
    val source = remember { MutableInteractionSource() }
    val focused = source.collectIsFocusedAsState()
    return remember(source) { TvFocus(source, focused) }
}

/**
 * Focusable; OK / Enter runs [onClick] on key up, but only when the key also went down here, so
 * the key-up of an OK that closed a dialog never opens the item that gets focus afterwards.
 */
fun Modifier.tvClick(focus: TvFocus, onClick: () -> Unit): Modifier = this
    .onKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (ok && e.type == KeyEventType.KeyDown) focus.pressed = true
        if (ok && e.type == KeyEventType.KeyUp) {
            if (focus.pressed) onClick()
            focus.pressed = false
        }
        ok
    }
    .focusable(interactionSource = focus.source)

/** Card / row look: raised when focused, cyan border. */
fun focusLook(focused: Boolean, shape: Shape = PitchShape): Modifier = Modifier
    .clip(shape)
    .background(if (focused) EnigmaColors.Selected else EnigmaColors.Card)
    .border(if (focused) 2.dp else 1.dp, if (focused) EnigmaColors.Cyan else EnigmaColors.CardBorder, shape)

fun pitchScore(m: PitchMatch, locale: Locale): String = when {
    m.hasScore && m.status != PitchStatus.SCHEDULED -> "${m.scoreHome} – ${m.scoreAway}"
    m.status == PitchStatus.LIVE -> "0 – 0"
    else -> SoccerFormat.time(m.kickoffSec, locale)
}

@Composable
fun PitchStatusBadge(m: PitchMatch, nowSec: Long) {
    when (m.status) {
        PitchStatus.LIVE -> Badge(stringResource(R.string.soccer_live) + " " + PitchStats.liveMinute(m, nowSec), LiveRed)
        PitchStatus.FINISHED -> Badge(stringResource(R.string.soccer_ft), EnigmaColors.Muted)
        PitchStatus.POSTPONED -> Badge(stringResource(R.string.soccer_postponed), EnigmaColors.Accent)
        PitchStatus.CANCELLED -> Badge(stringResource(R.string.soccer_cancelled), EnigmaColors.Accent)
        PitchStatus.SCHEDULED -> Unit
    }
}

@Composable
fun PitchMatchCard(m: PitchMatch, nowSec: Long, locale: Locale, modifier: Modifier, onClick: () -> Unit) {
    val spec = LocalMotionSpec.current
    val focus = rememberTvFocus()
    val scale by animateFloatAsState(if (focus.focused && spec.animate) 1.06f else 1f, tween(spec.cardMs), label = "pitchCard")
    Column(
        modifier
            .width(212.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .tvClick(focus, onClick)
            .then(focusLook(focus.focused))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(SoccerFormat.date(m.kickoffSec, locale), color = EnigmaColors.Subtle, fontSize = 11.sp)
            PitchStatusBadge(m, nowSec)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            PitchImage(m.home.image, m.home.initials, 40.dp)
            Text(pitchScore(m, locale), color = if (m.status == PitchStatus.LIVE) LiveRed else EnigmaColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            PitchImage(m.away.image, m.away.initials, 40.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(m.home.name, color = EnigmaColors.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(m.away.name, color = EnigmaColors.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PitchRow(title: String, matches: List<PitchMatch>, nowSec: Long, locale: Locale, left: Modifier, onOpen: (PitchMatch) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.pitch_hint_match), color = EnigmaColors.Subtle, fontSize = 12.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 6.dp, bottom = 6.dp)) {
            itemsIndexed(matches, key = { _, m -> m.id }) { i, m -> PitchMatchCard(m, nowSec, locale, if (i == 0) left else Modifier) { onOpen(m) } }
        }
    }
}

/**
 * PitchAPI sections of the Soccer page, in visual order: per league of the followed country
 * live, results and upcoming rows and a "Clubs" row of crests (OK → club page). The official
 * table stays football-data.org's; calculated numbers are only on the club page.
 */
fun pitchSections(model: PitchViewModel, nowSec: Long, locale: Locale, left: Modifier): List<PageSection> = buildList {
    model.blocks.forEachIndexed { bi, b ->
        val name = b.league.name
        val id = b.league.id
        val last = bi == model.blocks.lastIndex
        if (b.live.isNotEmpty()) add(PageSection("p-live-$id") { PitchRow(stringResource(R.string.pitch_live, name), b.live, nowSec, locale, left, model::openMatch) })
        if (b.recent.isNotEmpty()) add(PageSection("p-res-$id") { PitchRow(stringResource(R.string.pitch_results, name), b.recent, nowSec, locale, left, model::openMatch) })
        if (b.upcoming.isNotEmpty()) add(PageSection("p-up-$id") { PitchRow(stringResource(R.string.pitch_upcoming, name), b.upcoming, nowSec, locale, left, model::openMatch) })
        val clubs = b.table.map { it.stats.team }.sortedBy { it.name }
        if (clubs.isNotEmpty()) add(PageSection("p-clubs-$id") {
            Column {
                ClubsRow(name, clubs, left) { model.openClub(it, id) }
                if (last) Text(stringResource(R.string.pitch_attribution), color = EnigmaColors.Subtle, fontSize = EnigmaType.Caption, modifier = Modifier.padding(start = 24.dp, top = 6.dp))
            }
        })
    }
}

/** "Clubs · Eredivisie": crest cards, OK opens the club page with statistics and squad. */
@Composable
private fun ClubsRow(league: String, clubs: List<com.enigmacast.domain.soccer.pitch.PitchTeam>, left: Modifier, onOpen: (com.enigmacast.domain.soccer.pitch.PitchTeam) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pitch_clubs, league), color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.pitch_hint_club), color = EnigmaColors.Subtle, fontSize = 12.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 6.dp, bottom = 6.dp)) {
            itemsIndexed(clubs, key = { _, t -> t.id }) { i, t ->
                val focus = rememberTvFocus()
                Column(
                    (if (i == 0) left else Modifier).width(104.dp).tvClick(focus) { onOpen(t) }.then(focusLook(focus.focused)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PitchImage(t.image, t.initials, 48.dp)
                    Text(t.name, color = EnigmaColors.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
internal fun Cell(text: String, width: Dp, color: Color, bold: Boolean = false) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.width(width))
}

@Composable
internal fun FormDots(form: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
        form.forEach { r ->
            val color = when (r) { "W" -> WinGreen; "L" -> LiveRed; else -> EnigmaColors.Muted }
            Box(Modifier.size(15.dp).background(color, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Text(r, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
