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
import com.enigmacast.ui.home.PivotState
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

/** Focus source and whether it is focused, for [tvClick]. */
class TvFocus(val source: MutableInteractionSource, val focused: Boolean)

@Composable
fun rememberTvFocus(): TvFocus {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return TvFocus(source, focused)
}

/** Focusable, OK / Enter runs [onClick] (as the other TV cards in the app). */
fun Modifier.tvClick(focus: TvFocus, onClick: () -> Unit): Modifier = this
    .onKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (ok && e.type == KeyEventType.KeyUp) onClick()
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
        Text(title, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 6.dp, bottom = 6.dp)) {
            itemsIndexed(matches, key = { _, m -> m.id }) { i, m -> PitchMatchCard(m, nowSec, locale, if (i == 0) left else Modifier) { onOpen(m) } }
        }
    }
}

/**
 * PitchAPI rows on the Soccer page: per league of the followed country live, results and
 * upcoming, then a table calculated from the results (for leagues football-data.org lacks, such
 * as the Eerste Divisie, and as a second view for the others).
 */
@Composable
fun ColumnScope.PitchSections(model: PitchViewModel, sections: PivotState, firstIndex: Int, nowSec: Long, locale: Locale, left: Modifier) {
    var index = firstIndex
    model.blocks.forEach { b ->
        val name = b.league.name
        if (b.live.isNotEmpty()) Box(sections.section(index++)) { PitchRow(stringResource(R.string.pitch_live, name), b.live, nowSec, locale, left, model::openMatch) }
        if (b.recent.isNotEmpty()) Box(sections.section(index++)) { PitchRow(stringResource(R.string.pitch_results, name), b.recent, nowSec, locale, left, model::openMatch) }
        if (b.upcoming.isNotEmpty()) Box(sections.section(index++)) { PitchRow(stringResource(R.string.pitch_upcoming, name), b.upcoming, nowSec, locale, left, model::openMatch) }
    }
    val tables = model.blocks.filter { it.table.isNotEmpty() }
    if (tables.isNotEmpty()) {
        val current = tables.firstOrNull { it.league.id == model.tableLeague } ?: tables.first()
        Box(sections.section(index)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pitch_table, current.league.name), color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = FontWeight.SemiBold)
                    if (tables.size > 1) tables.forEachIndexed { i, t ->
                        EnigmaGlassButton((if (t === current) "● " else "") + t.league.name, { model.tableLeague = t.league.id }, if (i == 0) left else Modifier)
                    }
                }
                TableHeader()
                LazyColumn(Modifier.fillMaxWidth().height(330.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(current.table, key = { it.stats.team.id }) { row ->
                        TableLine(row, left) { model.openClub(row.stats.team, current.league.id) }
                    }
                }
            }
        }
    }
    if (model.blocks.isNotEmpty()) {
        Text(stringResource(R.string.pitch_attribution), color = EnigmaColors.Subtle, fontSize = EnigmaType.Caption, modifier = Modifier.padding(start = 24.dp, top = 6.dp))
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Cell("#", 30.dp, EnigmaColors.Subtle)
        Spacer(Modifier.width(34.dp))
        Text(stringResource(R.string.soccer_col_team), color = EnigmaColors.Subtle, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Cell(stringResource(R.string.soccer_col_played), 36.dp, EnigmaColors.Subtle)
        Cell(stringResource(R.string.soccer_col_won), 30.dp, EnigmaColors.Subtle)
        Cell(stringResource(R.string.soccer_col_draw), 30.dp, EnigmaColors.Subtle)
        Cell(stringResource(R.string.soccer_col_lost), 30.dp, EnigmaColors.Subtle)
        Cell(stringResource(R.string.soccer_col_gd), 44.dp, EnigmaColors.Subtle)
        Cell(stringResource(R.string.soccer_col_points), 40.dp, EnigmaColors.Subtle)
        Cell(stringResource(R.string.soccer_col_form), 96.dp, EnigmaColors.Subtle)
    }
}

@Composable
private fun TableLine(row: TableRow, left: Modifier, onClick: () -> Unit) {
    val s = row.stats
    val focus = rememberTvFocus()
    Row(
        left.fillMaxWidth().tvClick(focus, onClick).then(focusLook(focus.focused, RoundedCornerShape(8.dp))).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(row.position.toString(), 30.dp, EnigmaColors.Text)
        PitchImage(s.team.image, s.team.initials, 26.dp, Modifier.padding(end = 8.dp))
        Text(s.team.name, color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Cell(s.played.toString(), 36.dp, EnigmaColors.Muted)
        Cell(s.won.toString(), 30.dp, EnigmaColors.Muted)
        Cell(s.draw.toString(), 30.dp, EnigmaColors.Muted)
        Cell(s.lost.toString(), 30.dp, EnigmaColors.Muted)
        Cell((if (s.goalDifference > 0) "+" else "") + s.goalDifference, 44.dp, EnigmaColors.Muted)
        Cell(s.points.toString(), 40.dp, EnigmaColors.Text, bold = true)
        FormDots(s.form, Modifier.width(96.dp))
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
