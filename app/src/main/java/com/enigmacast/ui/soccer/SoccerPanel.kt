package com.enigmacast.ui.soccer

import com.enigmacast.ui.theme.holdPageFocus
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.soccer.SoccerMatch
import com.enigmacast.domain.soccer.StandingRow
import com.enigmacast.domain.soccer.StandingTable
import com.enigmacast.domain.soccer.StandingZones
import com.enigmacast.domain.soccer.Zone
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.home.Badge
import com.enigmacast.ui.home.LiveRed
import com.enigmacast.ui.home.PivotColumn
import com.enigmacast.ui.home.PivotState
import com.enigmacast.ui.remote.rememberGuideClock
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.util.asString
import com.enigmacast.util.isEmpty

/** One row/block of the Soccer page, in visual order; [key] names it in the focus chain. */
class PageSection(val key: String, val content: @Composable () -> Unit)

private val CardShape = RoundedCornerShape(12.dp)

/**
 * The Soccer page, in the style of Home: a hero with the most relevant match, rows of live,
 * today's, upcoming and recent matches, and the table. OK on a match opens [MatchInfoDialog].
 */
@Composable
fun SoccerPanel(
    contentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onRetreatTo: (FocusRequester) -> Unit,
    onWatch: (BouquetEntry) -> Unit,
    model: SoccerViewModel = hiltViewModel(),
    pitch: PitchViewModel = hiltViewModel(),
) {
    val nowSec = rememberGuideClock()
    val autoKeys by model.autoSwitchKeys.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val left = Modifier.focusProperties { left = menuFocus }
    val standingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { model.whileVisible() }
    LaunchedEffect(Unit) { pitch.whileVisible() }

    CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(0f to Color(0xFF0B2A1E), 0.55f to EnigmaColors.Canvas))
                .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) }
                .holdPageFocus(contentFocus, listOf(model.loading && model.sections.isEmpty, model.sections.isEmpty, model.sections.hero?.let { it.id })),
        ) {
            val sections = remember { PivotState() }
            val chain = rememberFocusChain()
            val s = model.sections
            val hasStandings = model.standings.values.any { t -> t.any { it.rows.isNotEmpty() } }
            // One list in visual top-to-bottom order; it drives both what is drawn and the focus chain,
            // so Down/Up always reach the row directly below/above on screen.
            val list = buildList<PageSection> {
                when {
                    model.loading && s.isEmpty -> add(PageSection("top") { Skeleton(left) })
                    s.isEmpty && model.problem != SoccerViewModel.Problem.NONE ->
                        add(PageSection("top") { ProblemState(model.problem, left) { model.refresh(true) } })
                    s.isEmpty -> add(PageSection("top") { EmptyState(left) { model.refresh(true) } })
                    else -> {
                        s.hero?.let { hero ->
                            add(PageSection("top") {
                                Hero(
                                    match = hero,
                                    nowSec = nowSec,
                                    locale = locale,
                                    channel = model.channelOf(hero)?.let { it.entry.name to SoccerFormat.time(it.event.beginEpochSec, locale) },
                                    scheduled = model.scheduleKey(hero)?.let { it in autoKeys } == true,
                                    firstModifier = left,
                                    onWatch = { model.channelOf(hero)?.let { onWatch(it.entry) } },
                                    onSchedule = { model.toggleSchedule(hero) },
                                    onStandings = { runCatching { standingsFocus.requestFocus() } },
                                    onInfo = { model.openInfo(hero) },
                                    showStandings = hasStandings,
                                )
                            })
                        }
                        if (s.live.isNotEmpty()) add(PageSection("fd-live") { MatchRow(stringResource(R.string.soccer_row_live), s.live, nowSec, locale, left, model) })
                        if (s.today.isNotEmpty()) add(PageSection("fd-today") { MatchRow(stringResource(R.string.soccer_row_today), s.today, nowSec, locale, left, model) })
                        s.upcoming.take(3).forEach { (matchday, matches) ->
                            add(PageSection("fd-up-$matchday") {
                                val title = if (matchday != null) stringResource(R.string.soccer_row_upcoming_md, matchday) else stringResource(R.string.soccer_row_upcoming)
                                MatchRow(title, matches, nowSec, locale, left, model)
                            })
                        }
                        if (s.recent.isNotEmpty()) add(PageSection("fd-recent") { MatchRow(stringResource(R.string.soccer_row_recent), s.recent, nowSec, locale, left, model) })
                    }
                }
                if (hasStandings) {
                    add(PageSection("fd-table") {
                        Standings(model, standingsFocus, left) { team -> pitch.findTeam(team.name)?.let { (t, league) -> pitch.openClub(t, league) } }
                    })
                }
                addAll(pitchSections(pitch, nowSec, locale, left))
            }
            chain.keys = list.map { it.key }
            PivotColumn(sections, Modifier.fillMaxSize()) {
                list.forEachIndexed { i, section ->
                    key(section.key) {
                        // The page's entry point (Right / OK on the Soccer menu item) is the top-most section.
                        val entry = if (i == 0) Modifier.focusRequester(contentFocus) else Modifier
                        Box(sections.section(i).then(entry).then(chain.section(section.key))) { section.content() }
                    }
                }
                Text(
                    stringResource(R.string.soccer_attribution),
                    color = EnigmaColors.Subtle,
                    fontSize = EnigmaType.Caption,
                    modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
                )
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!s.isEmpty && model.problem != SoccerViewModel.Problem.NONE) {
                    Badge(problemTitle(model.problem), EnigmaColors.Accent)
                }
                if (model.refreshing) EnigmaLoadingIndicator(Modifier.size(14.dp))
                Text(countryName(model.country.id), color = EnigmaColors.Muted, fontSize = 12.sp)
            }
            if (!model.notice.isEmpty()) {
                Text(
                    model.notice.asString(),
                    color = EnigmaColors.Text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .background(EnigmaColors.Raised, RoundedCornerShape(10.dp))
                        .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }

    model.info?.let { match ->
        MatchInfoDialog(
            match = match,
            nowSec = nowSec,
            channel = model.channelOf(match),
            scheduled = model.scheduleKey(match)?.let { it in autoKeys } == true,
            headToHead = model.headToHead,
            headToHeadLoading = model.headToHeadLoading,
            onWatch = {
                model.closeInfo()
                model.channelOf(match)?.let { onWatch(it.entry) }
            },
            onSchedule = { model.toggleSchedule(match) },
            onClose = model::closeInfo,
            onDetails = pitch.find(match)?.let { pm -> { model.closeInfo(); pitch.openMatch(pm) } },
        )
    }
    PitchScreenHost(pitch, nowSec)
}

@Composable
internal fun countryName(id: String): String = when (id) {
    "NL" -> stringResource(R.string.soccer_country_nl)
    "ENG" -> stringResource(R.string.soccer_country_eng)
    "DE" -> stringResource(R.string.soccer_country_de)
    "ES" -> stringResource(R.string.soccer_country_es)
    "IT" -> stringResource(R.string.soccer_country_it)
    "FR" -> stringResource(R.string.soccer_country_fr)
    "PT" -> stringResource(R.string.soccer_country_pt)
    "BR" -> stringResource(R.string.soccer_country_br)
    "EU" -> stringResource(R.string.soccer_country_eu)
    else -> stringResource(R.string.soccer_country_world)
}

@Composable
private fun problemTitle(p: SoccerViewModel.Problem): String = when (p) {
    SoccerViewModel.Problem.NO_KEY -> stringResource(R.string.soccer_no_key)
    SoccerViewModel.Problem.INVALID_KEY -> stringResource(R.string.soccer_invalid_key)
    SoccerViewModel.Problem.RATE_LIMITED -> stringResource(R.string.soccer_rate_limited)
    SoccerViewModel.Problem.NOT_IN_PLAN -> stringResource(R.string.soccer_not_in_plan)
    else -> stringResource(R.string.soccer_offline)
}

@Composable
private fun Hero(
    match: SoccerMatch,
    nowSec: Long,
    locale: java.util.Locale,
    channel: Pair<String, String>?,
    scheduled: Boolean,
    firstModifier: Modifier,
    onWatch: () -> Unit,
    onSchedule: () -> Unit,
    onStandings: () -> Unit,
    onInfo: () -> Unit,
    showStandings: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 28.dp, top = 22.dp, end = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (match.status.isLive) R.string.soccer_hero_live else R.string.soccer_hero_next),
                color = EnigmaColors.Cyan,
                fontSize = EnigmaType.Label,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                match.competitionName + (match.matchday?.let { " · " + stringResourceMatchday(it) } ?: ""),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TeamBlock(match.home, Modifier.width(210.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(170.dp)) {
                Text(scoreOrTime(match, locale), color = EnigmaColors.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                StatusBadge(match, nowSec)
                Text(SoccerFormat.date(match.kickoffSec, locale), color = EnigmaColors.Muted, fontSize = 13.sp)
            }
            TeamBlock(match.away, Modifier.width(210.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            match.venue?.let { Text(it, color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (channel != null) {
                Text(stringResource(R.string.soccer_on_channel, channel.first, channel.second), color = EnigmaColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (scheduled) Badge(stringResource(R.string.home_scheduled), EnigmaColors.Accent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            // The first button carries the focus target and the step left to the menu.
            val buttons = buildList<Pair<String, () -> Unit>> {
                if (channel != null) add(stringResourceWatch() to onWatch)
                if (channel != null && !match.status.isLive && match.status != com.enigmacast.domain.soccer.MatchStatus.FINISHED) {
                    add((if (scheduled) stringResourceCancel() else stringResourceSchedule()) to onSchedule)
                }
                add(stringResourceInfo() to onInfo)
                if (showStandings) add(stringResourceStandings() to onStandings)
            }
            buttons.forEachIndexed { i, (label, action) ->
                EnigmaGlassButton(label, action, if (i == 0) firstModifier else Modifier)
            }
        }
    }
}

@Composable private fun stringResourceMatchday(md: Int) = stringResource(R.string.soccer_matchday, md)
@Composable private fun stringResourceWatch() = stringResource(R.string.home_watch)
@Composable private fun stringResourceSchedule() = stringResource(R.string.home_schedule)
@Composable private fun stringResourceCancel() = stringResource(R.string.autoswitch_cancel_action)
@Composable private fun stringResourceInfo() = stringResource(R.string.home_info)
@Composable private fun stringResourceStandings() = stringResource(R.string.soccer_standings)

@Composable
private fun TeamBlock(team: com.enigmacast.domain.soccer.SoccerTeam, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Crest(team, 88.dp)
        Text(team.label, color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MatchRow(title: String, matches: List<SoccerMatch>, nowSec: Long, locale: java.util.Locale, left: Modifier, model: SoccerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 24.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = FontWeight.SemiBold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 6.dp, bottom = 6.dp),
        ) {
            itemsIndexed(matches, key = { _, m -> m.id }) { i, m ->
                MatchCard(m, nowSec, locale, model.channelOf(m)?.entry?.name, if (i == 0) left else Modifier) { model.openInfo(m) }
            }
        }
    }
}

@Composable
private fun MatchCard(match: SoccerMatch, nowSec: Long, locale: java.util.Locale, channel: String?, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val spec = LocalMotionSpec.current
    val scale by animateFloatAsState(if (focused && spec.animate) 1.06f else 1f, tween(spec.cardMs), label = "matchCard")
    Column(
        modifier
            .width(212.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) onClick()
                ok
            }
            .focusable(interactionSource = interaction)
            .clip(CardShape)
            .background(if (focused) EnigmaColors.Selected else EnigmaColors.Card)
            .border(if (focused) 2.dp else 1.dp, if (focused) EnigmaColors.Cyan else EnigmaColors.CardBorder, CardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(SoccerFormat.date(match.kickoffSec, locale), color = EnigmaColors.Subtle, fontSize = 11.sp)
            StatusBadge(match, nowSec)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Crest(match.home, 40.dp)
            Text(
                scoreOrTime(match, locale),
                color = if (match.status.isLive) LiveRed else EnigmaColors.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Crest(match.away, 40.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(match.home.label, color = EnigmaColors.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(match.away.label, color = EnigmaColors.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Text(channel ?: " ", color = EnigmaColors.Accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Standings(model: SoccerViewModel, firstFocus: FocusRequester, left: Modifier, onTeam: (com.enigmacast.domain.soccer.SoccerTeam) -> Unit) {
    val codes = model.country.competitions.filter { code -> model.standings[code]?.any { it.rows.isNotEmpty() } == true }
    val code = model.tableCode?.takeIf { it in codes } ?: codes.firstOrNull() ?: return
    val tables: List<StandingTable> = model.standings[code].orEmpty().filter { it.rows.isNotEmpty() }
    Column(Modifier.padding(start = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.soccer_standings), color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.pitch_hint_club), color = EnigmaColors.Subtle, fontSize = 12.sp)
            if (codes.size > 1) {
                codes.forEachIndexed { i, c ->
                    val name = model.matches.firstOrNull { it.competition == c }?.competitionName ?: c
                    EnigmaGlassButton(
                        (if (c == code) "● " else "") + name,
                        { model.tableCode = c },
                        if (i == 0) Modifier.focusRequester(firstFocus).then(left) else Modifier,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            HeaderCell("#", 30.dp)
            Spacer(Modifier.width(34.dp))
            Text(stringResource(R.string.soccer_col_team), color = EnigmaColors.Subtle, fontSize = 12.sp, modifier = Modifier.weight(1f))
            HeaderCell(stringResource(R.string.soccer_col_played), 36.dp)
            HeaderCell(stringResource(R.string.soccer_col_won), 30.dp)
            HeaderCell(stringResource(R.string.soccer_col_draw), 30.dp)
            HeaderCell(stringResource(R.string.soccer_col_lost), 30.dp)
            HeaderCell(stringResource(R.string.soccer_col_gd), 44.dp)
            HeaderCell(stringResource(R.string.soccer_col_points), 40.dp)
            HeaderCell(stringResource(R.string.soccer_col_form), 96.dp)
        }
        LazyColumn(Modifier.fillMaxWidth().height(330.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tables.forEachIndexed { t, table ->
                if (tables.size > 1 && table.group != null) {
                    item(key = "g$t") { Text(table.group, color = EnigmaColors.Cyan, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
                items(table.rows, key = { "$t-${it.position}-${it.team.id}" }) { row ->
                    val first = t == 0 && row == table.rows.first() && codes.size <= 1
                    StandingLine(code, row, table.rows.size, if (first) Modifier.focusRequester(firstFocus).then(left) else left) { onTeam(row.team) }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text, color = EnigmaColors.Subtle, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(width))
}

@Composable
private fun StandingLine(code: String, row: StandingRow, size: Int, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val zone = StandingZones.of(code, row.position, size)
    val bar = when (zone) {
        Zone.TOP -> EnigmaColors.Cyan
        Zone.EUROPE -> Color(0xFF4FA3FF)
        Zone.RELEGATION -> LiveRed
        Zone.NONE -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) onClick()
                ok
            }
            .focusable(interactionSource = interaction)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) EnigmaColors.Selected else EnigmaColors.Card.copy(alpha = 0.6f))
            .border(if (focused) 2.dp else 0.dp, if (focused) EnigmaColors.Cyan else Color.Transparent, RoundedCornerShape(8.dp))
            .height(30.dp)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(bar.copy(alpha = 0.8f)))
        Spacer(Modifier.width(6.dp))
        Cell(row.position.toString(), 30.dp, bold = true)
        Crest(row.team, 22.dp, Modifier.padding(end = 12.dp))
        Text(row.team.label, color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Cell(row.played.toString(), 36.dp)
        Cell(row.won.toString(), 30.dp)
        Cell(row.draw.toString(), 30.dp)
        Cell(row.lost.toString(), 30.dp)
        Cell((if (row.goalDifference > 0) "+" else "") + row.goalDifference, 44.dp)
        Cell(row.points.toString(), 40.dp, bold = true)
        FormCells(row.form, Modifier.width(96.dp))
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, bold: Boolean = false) {
    Text(text, color = EnigmaColors.Text, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.width(width))
}

@Composable
private fun FormCells(form: String?, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
        form?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeLast(5)?.forEach { r ->
            val color = when (r) {
                "W" -> Color(0xFF2FBF71)
                "L" -> LiveRed
                else -> EnigmaColors.Muted
            }
            Box(Modifier.size(15.dp).background(color, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Text(r, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Skeleton(left: Modifier) {
    val pulse by rememberInfiniteTransition(label = "soccerSkeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "soccerSkeletonAlpha",
    )
    val block = EnigmaColors.Raised.copy(alpha = pulse)
    Column(Modifier.padding(start = 28.dp, top = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            EnigmaLoadingIndicator(Modifier.size(22.dp))
            Text(stringResource(R.string.soccer_loading), color = EnigmaColors.Muted, fontSize = 14.sp, modifier = left.focusable())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp).background(block, RoundedCornerShape(44.dp)))
            Box(Modifier.width(120.dp).height(44.dp).background(block, RoundedCornerShape(8.dp)))
            Box(Modifier.size(88.dp).background(block, RoundedCornerShape(44.dp)))
        }
        Spacer(Modifier.height(40.dp))
        repeat(2) {
            Box(Modifier.width(160.dp).height(18.dp).background(block, RoundedCornerShape(6.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { Box(Modifier.width(212.dp).height(118.dp).background(block, CardShape)) }
            }
        }
    }
}

@Composable
private fun ProblemState(problem: SoccerViewModel.Problem, left: Modifier, onRetry: () -> Unit) {
    val body = when (problem) {
        SoccerViewModel.Problem.NO_KEY, SoccerViewModel.Problem.INVALID_KEY -> stringResource(R.string.soccer_key_hint)
        SoccerViewModel.Problem.RATE_LIMITED -> stringResource(R.string.soccer_rate_limited_hint)
        SoccerViewModel.Problem.NOT_IN_PLAN -> stringResource(R.string.soccer_not_in_plan_hint)
        else -> stringResource(R.string.soccer_offline_hint)
    }
    Message(problemTitle(problem), body, left, onRetry)
}

@Composable
private fun EmptyState(left: Modifier, onRetry: () -> Unit) =
    Message(stringResource(R.string.soccer_empty), stringResource(R.string.soccer_empty_hint), left, onRetry)

@Composable
private fun Message(title: String, body: String, left: Modifier, onRetry: () -> Unit) {
    Column(Modifier.padding(start = 28.dp, top = 60.dp).fillMaxWidth(0.7f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = FontWeight.SemiBold)
        Text(body, color = EnigmaColors.Muted, fontSize = 14.sp)
        EnigmaGlassButton(stringResource(R.string.home_try_again), onRetry, left)
        Spacer(Modifier.height(120.dp))
    }
}
