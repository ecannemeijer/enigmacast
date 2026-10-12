package com.enigmacast.ui.soccer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.enigmacast.data.soccer.MatchChannel
import com.enigmacast.domain.soccer.HeadToHead
import com.enigmacast.domain.soccer.MatchStatus
import com.enigmacast.domain.soccer.SoccerMatch
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.home.Badge
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.requestWhenReady

/** Full-screen match details that fit one 960×540 dp screen. Focus starts on the first button; Back closes. */
@Composable
fun MatchInfoDialog(
    match: SoccerMatch,
    nowSec: Long,
    channel: MatchChannel?,
    scheduled: Boolean,
    headToHead: HeadToHead?,
    headToHeadLoading: Boolean,
    onWatch: () -> Unit,
    onSchedule: () -> Unit,
    onClose: () -> Unit,
    /** Opens the PitchAPI match screen (events, lineups, stats) when the match is found there. */
    onDetails: (() -> Unit)? = null,
) {
    val locale = currentLocale()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        val first = remember { FocusRequester() }
        LaunchedEffect(match.id) { first.requestWhenReady() }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF0B2A1E), 0.6f to EnigmaColors.Canvas))) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    match.competitionName + (match.matchday?.let { " · " + stringResource(R.string.soccer_matchday, it) } ?: ""),
                    color = EnigmaColors.Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Team(match.home)
                    Column(Modifier.width(220.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(scoreOrTime(match, locale), color = EnigmaColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                        StatusBadge(match, nowSec)
                        if (match.halfTime.known && (match.status == MatchStatus.FINISHED || match.status.isLive)) {
                            Text(stringResource(R.string.soccer_halftime_score, match.halfTime.toString()), color = EnigmaColors.Muted, fontSize = 13.sp)
                        }
                    }
                    Team(match.away)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Detail(stringResource(R.string.soccer_kickoff), SoccerFormat.dateTime(match.kickoffSec, locale))
                    match.venue?.let { Detail(stringResource(R.string.soccer_venue), it) }
                    match.referee?.let { Detail(stringResource(R.string.soccer_referee), it) }
                    Detail(
                        stringResource(R.string.soccer_channel),
                        channel?.let { stringResource(R.string.soccer_on_channel, it.entry.name, SoccerFormat.time(it.event.beginEpochSec, locale)) }
                            ?: stringResource(R.string.soccer_no_channel),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.soccer_h2h), color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    when {
                        headToHead != null && headToHead.matches.isNotEmpty() -> {
                            Text(
                                stringResource(R.string.soccer_h2h_summary, match.home.label, headToHead.homeWins, headToHead.draws, match.away.label, headToHead.awayWins),
                                color = EnigmaColors.Muted,
                                fontSize = 13.sp,
                            )
                            headToHead.matches.take(5).forEach { m ->
                                Text(
                                    "${SoccerFormat.date(m.kickoffSec, locale)}   ${m.home.label}  ${m.fullTime}  ${m.away.label}",
                                    color = EnigmaColors.Text,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        headToHeadLoading -> EnigmaLoadingIndicator(Modifier.size(18.dp))
                        else -> Text(stringResource(R.string.soccer_h2h_none), color = EnigmaColors.Subtle, fontSize = 13.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val canSchedule = channel != null && match.status == MatchStatus.SCHEDULED
                    if (channel != null) EnigmaGlassButton(stringResource(R.string.home_watch), onWatch, Modifier.focusRequester(first))
                    if (canSchedule) {
                        EnigmaGlassButton(stringResource(if (scheduled) R.string.autoswitch_cancel_action else R.string.home_schedule), onSchedule)
                    }
                    if (onDetails != null) EnigmaGlassButton(stringResource(R.string.pitch_details), onDetails)
                    EnigmaGlassButton(stringResource(R.string.home_close), onClose, if (channel == null) Modifier.focusRequester(first) else Modifier)
                }
                Text(stringResource(R.string.soccer_attribution), color = EnigmaColors.Subtle, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Team(team: com.enigmacast.domain.soccer.SoccerTeam) {
    Column(Modifier.width(240.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Crest(team, 96.dp)
        Text(team.name, color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = EnigmaColors.Subtle, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(value, color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

