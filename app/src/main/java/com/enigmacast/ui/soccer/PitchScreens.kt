package com.enigmacast.ui.soccer

import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.soccer.pitch.EventKind
import com.enigmacast.domain.soccer.pitch.LineupPlayer
import com.enigmacast.domain.soccer.pitch.PitchEvent
import com.enigmacast.domain.soccer.pitch.PitchMatch
import com.enigmacast.domain.soccer.pitch.PitchMatchDetail
import com.enigmacast.domain.soccer.pitch.PitchPerson
import com.enigmacast.domain.soccer.pitch.PitchStats
import com.enigmacast.domain.soccer.pitch.PitchStatus
import com.enigmacast.domain.soccer.pitch.PitchTeam
import com.enigmacast.domain.soccer.pitch.TeamLineup
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.home.LiveRed
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.requestWhenReady
import java.util.Locale

private val YellowCard = Color(0xFFF2C94C)
private val PitchGreen = Color(0xFF14532D)

/** Hosts the PitchAPI screens (match → club → player …) in one full-screen dialog; Back pops one. */
@Composable
fun PitchScreenHost(model: PitchViewModel, nowSec: Long) {
    val top = model.stack.lastOrNull() ?: return
    Dialog(onDismissRequest = model::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF0B2A1E), 0.6f to EnigmaColors.Canvas))) {
            Box(Modifier.fillMaxSize().padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical).trapFocus()) {
                when (top) {
                    is PitchViewModel.Screen.Match -> model.detail?.takeIf { it.match.id == top.match.id }?.let { MatchScreen(model, it, nowSec) }
                    is PitchViewModel.Screen.Club -> model.club?.takeIf { it.team.id == top.team.id }?.let { ClubScreen(model, it) }
                    is PitchViewModel.Screen.Player -> model.player?.takeIf { it.person.id == top.person.id }?.let { PlayerScreen(model, it) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- match

@Composable
private fun MatchScreen(model: PitchViewModel, d: PitchMatchDetail, nowSec: Long) {
    val m = d.match
    val locale = currentLocale()
    val tabFocus = remember(m.id) { FocusRequester() }
    LaunchedEffect(m.id) { tabFocus.requestWhenReady() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            listOfNotNull(m.leagueName.ifBlank { null }, m.round?.let { stringResource(R.string.pitch_round, it) }, SoccerFormat.dateTime(m.kickoffSec, locale), m.stadium, m.referee?.let { "⚑ $it" }).joinToString(" · "),
            color = EnigmaColors.Cyan, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TeamButton(m.home) { model.openClub(m.home, m.leagueId) }
            Column(Modifier.width(200.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pitchScore(m, locale), color = if (m.status == PitchStatus.LIVE) LiveRed else EnigmaColors.Text, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                PitchStatusBadge(m, nowSec)
            }
            TeamButton(m.away) { model.openClub(m.away, m.leagueId) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PitchViewModel.Tab.entries.forEach { t ->
                val label = when (t) {
                    PitchViewModel.Tab.EVENTS -> stringResource(R.string.pitch_tab_events)
                    PitchViewModel.Tab.LINEUPS -> stringResource(R.string.pitch_tab_lineups)
                    PitchViewModel.Tab.STATS -> stringResource(R.string.pitch_tab_stats)
                    PitchViewModel.Tab.PLAYERS -> stringResource(R.string.pitch_tab_players)
                    PitchViewModel.Tab.H2H -> stringResource(R.string.pitch_tab_h2h)
                }
                EnigmaGlassButton((if (model.tab == t) "● " else "") + label, { model.tab = t }, if (model.tab == t) Modifier.focusRequester(tabFocus) else Modifier)
            }
            EnigmaGlassButton(stringResource(R.string.home_close), model::back)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (model.tab) {
                PitchViewModel.Tab.EVENTS -> Loading(d.events) { EventsTab(model, d, it) }
                PitchViewModel.Tab.LINEUPS -> LineupsTab(model, d)
                PitchViewModel.Tab.STATS -> Loading(d.stats) { StatsTab(it) }
                PitchViewModel.Tab.PLAYERS -> Loading(d.players) { PlayersTab(model, d, it) }
                PitchViewModel.Tab.H2H -> H2HTab(d, locale)
            }
        }
        Text(stringResource(R.string.pitch_attribution), color = EnigmaColors.Subtle, fontSize = 11.sp)
    }
}

@Composable
private fun <T> Loading(value: T?, content: @Composable (T) -> Unit) {
    if (value == null) EnigmaLoadingIndicator(Modifier.size(22.dp).padding(top = 8.dp)) else content(value)
}

@Composable
private fun Empty(text: String) = Text(text, color = EnigmaColors.Subtle, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun TeamButton(team: PitchTeam, onClick: () -> Unit) {
    val f = rememberTvFocus()
    Row(
        Modifier.width(300.dp).tvClick(f, onClick).then(if (f.focused) focusLook(true) else Modifier).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        PitchImage(team.image, team.initials, 56.dp)
        Text(team.name, color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A focusable list line (so D-pad scrolls the list), optional [onClick]. */
@Composable
private fun Line(modifier: Modifier = Modifier, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    val f = rememberTvFocus()
    Box(modifier.fillMaxWidth().tvClick(f, onClick).then(focusLook(f.focused, skinShape(8.dp))).padding(horizontal = 12.dp, vertical = 6.dp)) { content() }
}

@Composable
private fun eventLabel(e: PitchEvent): Pair<String, Color> = when (e.kind) {
    EventKind.GOAL -> "⚽ " + stringResource(R.string.pitch_event_goal) to WinGreen
    EventKind.PENALTY_GOAL -> "⚽ " + stringResource(R.string.pitch_event_penalty) to WinGreen
    EventKind.OWN_GOAL -> "⚽ " + stringResource(R.string.pitch_event_own_goal) to LiveRed
    EventKind.MISSED_PENALTY -> "✕ " + stringResource(R.string.pitch_event_missed_penalty) to EnigmaColors.Muted
    EventKind.YELLOW -> "▮ " + stringResource(R.string.pitch_event_yellow) to YellowCard
    EventKind.SECOND_YELLOW -> "▮▮ " + stringResource(R.string.pitch_event_second_yellow) to YellowCard
    EventKind.RED -> "▮ " + stringResource(R.string.pitch_event_red) to LiveRed
    EventKind.SUBSTITUTION -> "⇄ " + stringResource(R.string.pitch_event_sub) to EnigmaColors.Cyan
    EventKind.VAR -> stringResource(R.string.pitch_event_var) to EnigmaColors.Accent
    EventKind.OTHER -> e.rawType to EnigmaColors.Muted
}

@Composable
private fun EventsTab(model: PitchViewModel, d: PitchMatchDetail, events: List<PitchEvent>) {
    if (events.isEmpty()) return Empty(stringResource(R.string.pitch_no_events))
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(events.sortedWith(compareBy({ it.minute }, { it.added }))) { _, e ->
            val home = e.teamId == d.match.home.id
            val (label, color) = eventLabel(e)
            Line(onClick = { e.player?.let { model.openPlayer(it, d.match.id) } }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(e.minuteLabel, color = EnigmaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                    PitchImage(if (home) d.match.home.image else d.match.away.image, "", 22.dp)
                    Text(label, color = color, fontSize = 13.sp, modifier = Modifier.width(170.dp), maxLines = 1)
                    val who = if (e.kind == EventKind.SUBSTITUTION) "▲ ${e.subIn?.name.orEmpty()}   ▼ ${e.player?.name.orEmpty()}" else e.player?.name.orEmpty()
                    Text(who, color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (e.scoreHome != null && e.scoreAway != null && e.kind in setOf(EventKind.GOAL, EventKind.OWN_GOAL, EventKind.PENALTY_GOAL)) {
                        Text("${e.scoreHome} – ${e.scoreAway}", color = EnigmaColors.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineupsTab(model: PitchViewModel, d: PitchMatchDetail) {
    val lu = d.lineups
    if (lu == null) {
        if (d.events == null) EnigmaLoadingIndicator(Modifier.size(22.dp)) else Empty(stringResource(R.string.pitch_no_lineups))
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.pitch_hint_player), color = EnigmaColors.Subtle, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SideTitle(d.match.home.name, lu.home)
                SideTitle(d.match.away.name, lu.away, end = true)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(PitchGreen, skinShape(10.dp)).border(1.dp, Color(0x55FFFFFF), skinShape(10.dp))) {
                // Half-way line and centre circle.
                Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Color(0x55FFFFFF)))
                Box(Modifier.align(Alignment.Center).size(60.dp).border(1.dp, Color(0x55FFFFFF), CircleShape))
                val w = maxWidth; val h = maxHeight
                lu.home?.starters?.forEach { p -> PlayerDot(p, (p.x * 0.5f), p.y, w, h, true) { model.openPlayer(PitchPerson(p.id, p.name, ""), d.match.id) } }
                lu.away?.starters?.forEach { p -> PlayerDot(p, 1f - p.x * 0.5f, 1f - p.y, w, h, false) { model.openPlayer(PitchPerson(p.id, p.name, ""), d.match.id) } }
            }
        }
        LazyColumn(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(d.match.home to lu.home, d.match.away to lu.away).forEach { (team, side) ->
                val subs = side?.subs.orEmpty()
                if (subs.isEmpty()) return@forEach
                item(key = "t${team.id}") { Text(stringResource(R.string.pitch_subs) + " · " + team.name, color = EnigmaColors.Cyan, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                items(subs, key = { "s${team.id}${it.id}" }) { p ->
                    Line(onClick = { model.openPlayer(PitchPerson(p.id, p.name, ""), d.match.id) }) {
                        Text("${p.shirt.padStart(2)}  ${p.name}", color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SideTitle(team: String, side: TeamLineup?, end: Boolean = false) {
    side ?: return
    Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text("$team · ${side.formation}", color = EnigmaColors.Text, fontSize = 14.sp, fontWeight = EnigmaType.Strong)
        Text(
            listOfNotNull(stringResource(if (side.confirmed) R.string.pitch_confirmed else R.string.pitch_predicted), side.coach?.let { stringResource(R.string.pitch_coach, it) }).joinToString(" · "),
            color = if (side.confirmed) EnigmaColors.Muted else EnigmaColors.Accent, fontSize = 11.sp,
        )
    }
}

@Composable
private fun PlayerDot(p: LineupPlayer, x: Float, y: Float, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, home: Boolean, onClick: () -> Unit) {
    val f = rememberTvFocus()
    val dotW = 84.dp
    Column(
        Modifier
            .offset(x = (w * x.coerceIn(0.03f, 0.97f)) - dotW / 2, y = (h * y.coerceIn(0.06f, 0.94f)) - 20.dp)
            .width(dotW)
            .tvClick(f, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(26.dp).background(if (home) EnigmaColors.Accent else Color(0xFFE5E7EB), CircleShape)
                .border(if (f.focused) 2.dp else 0.dp, if (f.focused) EnigmaColors.Cyan else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(p.shirt, color = if (home) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            (if (p.captain) "© " else "") + p.name.substringAfterLast(' '),
            color = if (f.focused) EnigmaColors.Cyan else Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.background(if (f.focused) Color(0xCC000000) else Color.Transparent, skinShape(4.dp)).padding(horizontal = 3.dp),
        )
    }
}

@Composable
private fun StatsTab(groups: List<com.enigmacast.domain.soccer.pitch.StatGroup>) {
    if (groups.isEmpty()) return Empty(stringResource(R.string.pitch_no_stats))
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(groups, key = { it.name }) { g ->
            Line {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(g.name, color = EnigmaColors.Cyan, fontSize = 13.sp, fontWeight = EnigmaType.Strong)
                    g.items.forEach { i -> StatLine(i.title, i.home, i.away) }
                }
            }
        }
    }
}

@Composable
private fun StatLine(title: String, home: String, away: String) {
    val h = home.substringBefore(' ').removeSuffix("%").toFloatOrNull() ?: 0f
    val a = away.substringBefore(' ').removeSuffix("%").toFloatOrNull() ?: 0f
    val share = if (h + a > 0) h / (h + a) else 0.5f
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(home, color = EnigmaColors.Text, fontSize = 13.sp, fontWeight = EnigmaType.Strong, modifier = Modifier.width(90.dp))
            Text(title, color = EnigmaColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            Text(away, color = EnigmaColors.Text, fontSize = 13.sp, fontWeight = EnigmaType.Strong, textAlign = TextAlign.End, modifier = Modifier.width(90.dp))
        }
        Row(Modifier.fillMaxWidth().height(3.dp)) {
            if (share > 0f) Box(Modifier.weight(share).fillMaxHeight().background(EnigmaColors.Accent))
            if (share < 1f) Box(Modifier.weight(1f - share).fillMaxHeight().background(Color(0xFFE5E7EB)))
        }
    }
}

@Composable
private fun PlayersTab(model: PitchViewModel, d: PitchMatchDetail, players: List<com.enigmacast.domain.soccer.pitch.PlayerMatchStats>) {
    if (players.isEmpty()) return Empty(stringResource(R.string.pitch_no_players))
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { Text(stringResource(R.string.pitch_hint_player), color = EnigmaColors.Subtle, fontSize = 11.sp) }
        items(players.sortedByDescending { it.rating ?: 0.0 }, key = { it.player.id }) { p ->
            val team = if (p.teamId == d.match.home.id) d.match.home else d.match.away
            Line(onClick = { model.openPlayer(p.player, d.match.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(p.rating?.let { "%.1f".format(Locale.ROOT, it) } ?: "–", color = ratingColor(p.rating), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                    PitchImage(p.player.image, p.player.name.take(1), 30.dp)
                    Text(p.player.name, color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PitchImage(team.image, team.initials, 20.dp)
                    Text(
                        listOfNotNull(p.minutes?.let { stringResource(R.string.pitch_minutes, it) }, p.goals?.takeIf { it > 0 }?.let { "⚽ $it" }, p.assists?.takeIf { it > 0 }?.let { "🅰 $it" }).joinToString("   "),
                        color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.width(180.dp), textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun ratingColor(r: Double?): Color = when {
    r == null -> EnigmaColors.Muted
    r >= 8 -> Color(0xFF1E88E5)
    r >= 7 -> WinGreen
    r >= 6 -> YellowCard
    else -> LiveRed
}

@Composable
private fun H2HTab(d: PitchMatchDetail, locale: Locale) {
    val h = d.h2h ?: return if (d.players == null) EnigmaLoadingIndicator(Modifier.size(22.dp)) else Empty(stringResource(R.string.soccer_h2h_none))
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item {
            Text(
                stringResource(R.string.soccer_h2h_summary, d.match.home.name, h.homeWins, h.draws, d.match.away.name, h.awayWins) + " · " + stringResource(R.string.pitch_h2h_total, h.total),
                color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(h.recent, key = { it.id }) { m ->
            Line {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SoccerFormat.date(m.kickoffSec, locale) + " " + m.kickoffYear(), color = EnigmaColors.Subtle, fontSize = 13.sp, modifier = Modifier.width(150.dp))
                    Text(m.home.name, color = EnigmaColors.Text, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(if (m.hasScore) "  ${m.scoreHome} – ${m.scoreAway}  " else "  –  ", color = EnigmaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(m.away.name, color = EnigmaColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                }
            }
        }
    }
}

private fun PitchMatch.kickoffYear(): String = PitchViewModel.day(kickoffSec).year.toString()

// ---------------------------------------------------------------- club

@Composable
private fun ClubScreen(model: PitchViewModel, c: PitchViewModel.ClubView) {
    val locale = currentLocale()
    val first = remember(c.team.id) { FocusRequester() }
    LaunchedEffect(c.team.id) { first.requestWhenReady() }
    val s = c.stats
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PitchImage(c.team.image, c.team.initials, 72.dp)
            Column(Modifier.weight(1f)) {
                Text(c.team.name, color = EnigmaColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (c.position != null && c.league != null) Text(stringResource(R.string.pitch_club_position, c.position, c.league.name), color = EnigmaColors.Cyan, fontSize = 14.sp)
            }
            EnigmaGlassButton(stringResource(R.string.home_close), model::back, if (c.recent.isEmpty() && c.upcoming.isEmpty()) Modifier.focusRequester(first) else Modifier)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(stringResource(R.string.pitch_stat_played), s.played.toString())
                    Tile(stringResource(R.string.pitch_stat_points), s.points.toString())
                    Tile(stringResource(R.string.pitch_stat_gf), s.goalsFor.toString())
                    Tile(stringResource(R.string.pitch_stat_ga), s.goalsAgainst.toString())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(stringResource(R.string.pitch_stat_won), s.won.toString())
                    Tile(stringResource(R.string.pitch_stat_draw), s.draw.toString())
                    Tile(stringResource(R.string.pitch_stat_lost), s.lost.toString())
                    Tile(stringResource(R.string.soccer_col_gd), (if (s.goalDifference > 0) "+" else "") + s.goalDifference)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(stringResource(R.string.pitch_stat_clean), s.cleanSheets.toString())
                    Tile(stringResource(R.string.pitch_stat_fts), s.failedToScore.toString())
                }
                KeyValue(stringResource(R.string.pitch_stat_avg), "%.2f – %.2f".format(locale, s.avgFor(), s.avgAgainst()))
                KeyValue(stringResource(R.string.pitch_home), stringResource(R.string.pitch_record, s.home.first, s.home.second, s.home.third))
                KeyValue(stringResource(R.string.pitch_away), stringResource(R.string.pitch_record, s.away.first, s.away.second, s.away.third))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.pitch_form), color = EnigmaColors.Subtle, fontSize = 13.sp, modifier = Modifier.width(150.dp))
                    FormDots(s.form)
                }
                Text(stringResource(R.string.pitch_attribution), color = EnigmaColors.Subtle, fontSize = 11.sp)
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                var firstUsed = false
                fun mod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
                if (c.upcoming.isNotEmpty()) {
                    item { Heading(stringResource(R.string.pitch_next)) }
                    c.upcoming.forEachIndexed { i, m -> val fm = mod(); item(key = "u${m.id}") { ClubMatchLine(m, c.team, locale, fm) { model.openMatch(m) } } }
                }
                if (c.recent.isNotEmpty()) {
                    item { Heading(stringResource(R.string.pitch_recent)) }
                    c.recent.forEach { m -> val fm = mod(); item(key = "r${m.id}") { ClubMatchLine(m, c.team, locale, fm) { model.openMatch(m) } } }
                }
                item { Heading(stringResource(R.string.pitch_squad) + "  ·  " + stringResource(R.string.pitch_hint_player)) }
                val squad = c.squad
                when {
                    squad == null -> item { EnigmaLoadingIndicator(Modifier.size(18.dp)) }
                    squad.isEmpty() -> item { Text(stringResource(R.string.pitch_no_squad), color = EnigmaColors.Subtle, fontSize = 13.sp) }
                    else -> {
                        item { Text(stringResource(R.string.pitch_squad_from, PitchViewModel.SQUAD_MATCHES), color = EnigmaColors.Subtle, fontSize = 11.sp) }
                        squad.forEach { sp ->
                            val fm = mod()
                            item(key = "sq${sp.person.id}") {
                                Line(fm, onClick = { model.openPlayer(sp.person, sp.lastMatchId) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(sp.shirt, color = EnigmaColors.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                                        Text(sp.person.name, color = EnigmaColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(lineName(sp.line), color = EnigmaColors.Muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                                        Text(stringResource(R.string.pitch_apps, sp.apps, sp.starts), color = EnigmaColors.Subtle, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Heading(stringResource(R.string.pitch_scorers)) }
                val scorers = c.scorers
                if (scorers == null) item { EnigmaLoadingIndicator(Modifier.size(18.dp)) }
                else items(scorers.take(10), key = { "p${it.first.id}" }) { (p, n) ->
                    Line(onClick = { model.openPlayer(p, null) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PitchImage(p.image, p.name.take(1), 28.dp)
                            Text(p.name, color = EnigmaColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(stringResource(R.string.pitch_goals_count, n), color = EnigmaColors.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun lineName(l: PitchStats.Line): String = when (l) {
    PitchStats.Line.GK -> stringResource(R.string.pitch_pos_gk)
    PitchStats.Line.DEF -> stringResource(R.string.pitch_pos_def)
    PitchStats.Line.MID -> stringResource(R.string.pitch_pos_mid)
    PitchStats.Line.ATT -> stringResource(R.string.pitch_pos_att)
    PitchStats.Line.UNKNOWN -> ""
}

@Composable
private fun Heading(text: String) = Text(text, color = EnigmaColors.Cyan, fontSize = 14.sp, fontWeight = EnigmaType.Strong, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))

@Composable
private fun Tile(label: String, value: String) {
    Column(Modifier.width(84.dp).background(EnigmaColors.Card, skinShape(10.dp)).border(1.dp, EnigmaColors.CardBorder, skinShape(10.dp)).padding(8.dp)) {
        Text(value, color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = EnigmaColors.Subtle, fontSize = 13.sp, modifier = Modifier.width(150.dp), maxLines = 2)
        Text(value, color = EnigmaColors.Text, fontSize = 13.sp)
    }
}

@Composable
private fun ClubMatchLine(m: PitchMatch, team: PitchTeam, locale: Locale, modifier: Modifier, onClick: () -> Unit) {
    val home = m.home.id == team.id
    val other = if (home) m.away else m.home
    val result = if (m.hasScore && m.status == PitchStatus.FINISHED) {
        val f = if (home) m.scoreHome!! else m.scoreAway!!
        val a = if (home) m.scoreAway!! else m.scoreHome!!
        when { f > a -> "W"; f == a -> "D"; else -> "L" }
    } else null
    Line(modifier, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(SoccerFormat.date(m.kickoffSec, locale), color = EnigmaColors.Subtle, fontSize = 12.sp, modifier = Modifier.width(90.dp))
            Text(stringResource(if (home) R.string.pitch_home else R.string.pitch_away), color = EnigmaColors.Subtle, fontSize = 12.sp, modifier = Modifier.width(50.dp))
            PitchImage(other.image, other.initials, 24.dp)
            Text(other.name, color = EnigmaColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pitchScore(m, locale), color = EnigmaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (result != null) FormDots(listOf(result)) else Spacer(Modifier.width(15.dp))
        }
    }
}

// ---------------------------------------------------------------- player

@Composable
private fun PlayerScreen(model: PitchViewModel, v: PitchViewModel.PlayerView) {
    val first = remember(v.person.id) { FocusRequester() }
    LaunchedEffect(v.person.id) { first.requestWhenReady() }
    val p = v.profile
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PitchImage(p?.image ?: v.person.image, v.person.name.take(1), 96.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p?.name ?: v.person.name, color = EnigmaColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (p != null) {
                    val pos = when (PitchStats.line(p.positionId)) {
                        PitchStats.Line.GK -> stringResource(R.string.pitch_pos_gk)
                        PitchStats.Line.DEF -> stringResource(R.string.pitch_pos_def)
                        PitchStats.Line.MID -> stringResource(R.string.pitch_pos_mid)
                        PitchStats.Line.ATT -> stringResource(R.string.pitch_pos_att)
                        PitchStats.Line.UNKNOWN -> null
                    }
                    Text(
                        listOfNotNull(p.shirt.takeIf { it.isNotBlank() }?.let { stringResource(R.string.pitch_shirt, it) }, pos, p.countryCode.takeIf { it.isNotBlank() }?.let { flagOrCode(it) }).joinToString(" · "),
                        color = EnigmaColors.Cyan, fontSize = 15.sp,
                    )
                }
                v.match?.rating?.let { Text(stringResource(R.string.pitch_rating, "%.1f".format(Locale.ROOT, it)), color = ratingColor(it), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
            EnigmaGlassButton(stringResource(R.string.home_close), model::back, if (v.match == null) Modifier.focusRequester(first) else Modifier)
        }
        if (v.loading) EnigmaLoadingIndicator(Modifier.size(22.dp))
        val groups = v.match?.groups.orEmpty().filter { it.second.isNotEmpty() }
        if (groups.isNotEmpty()) {
            Text(stringResource(R.string.pitch_in_match), color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = EnigmaType.Strong)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(groups, key = { _, g -> g.first }) { i, (name, stats) ->
                    Line(if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(groupName(name), color = EnigmaColors.Cyan, fontSize = 13.sp, fontWeight = EnigmaType.Strong)
                            stats.chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { (label, value) ->
                                        Row(Modifier.weight(1f).padding(end = 12.dp)) {
                                            Text(label, color = EnigmaColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(value, color = EnigmaColors.Text, fontSize = 12.sp, fontWeight = EnigmaType.Strong)
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.pitch_attribution), color = EnigmaColors.Subtle, fontSize = 11.sp)
    }
}

private fun groupName(key: String): String = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** NED → 🇳🇱 for the common ones; else the code. */
private fun flagOrCode(code: String): String {
    val iso2 = mapOf("NED" to "NL", "GER" to "DE", "ENG" to "GB", "ESP" to "ES", "FRA" to "FR", "ITA" to "IT", "POR" to "PT", "BEL" to "BE", "BRA" to "BR", "ARG" to "AR", "DEN" to "DK", "SWE" to "SE", "NOR" to "NO", "MAR" to "MA", "SUR" to "SR", "USA" to "US", "JPN" to "JP")[code] ?: return code
    val flag = iso2.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    return "$flag $code"
}
