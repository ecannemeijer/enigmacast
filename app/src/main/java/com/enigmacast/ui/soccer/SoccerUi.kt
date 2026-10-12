package com.enigmacast.ui.soccer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.enigmacast.R
import com.enigmacast.domain.soccer.MatchClock
import com.enigmacast.domain.soccer.MatchStatus
import com.enigmacast.domain.soccer.SoccerMatch
import com.enigmacast.domain.soccer.SoccerTeam
import com.enigmacast.ui.home.Badge
import com.enigmacast.ui.home.LiveRed
import com.enigmacast.ui.theme.EnigmaColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A team crest; SVG crests go through coil-svg, PNG through the default decoders. Initials while loading or on error. */
@Composable
fun Crest(team: SoccerTeam, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(team.crest) { mutableStateOf(false) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (team.crest.isBlank() || failed) {
            Box(Modifier.size(size).background(EnigmaColors.Raised, CircleShape), contentAlignment = Alignment.Center) {
                Text(team.tla.ifBlank { team.label.take(3).uppercase() }, color = EnigmaColors.Muted, fontSize = (size.value / 3.2f).sp, fontWeight = FontWeight.Bold)
            }
        } else {
            val request = remember(team.crest) {
                ImageRequest.Builder(context).data(team.crest).apply {
                    if (team.crest.endsWith(".svg", ignoreCase = true)) decoderFactory(SvgDecoder.Factory())
                }.build()
            }
            AsyncImage(
                model = request,
                contentDescription = team.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.size(size),
            )
        }
    }
}

/** LIVE 67', HT, FT, postponed… as a coloured badge; nothing for a normal upcoming match. */
@Composable
fun StatusBadge(match: SoccerMatch, nowSec: Long, modifier: Modifier = Modifier) {
    when (match.status) {
        MatchStatus.LIVE -> Badge(stringResource(R.string.soccer_live) + " " + (MatchClock.label(match, nowSec) ?: ""), LiveRed, modifier)
        MatchStatus.HALF_TIME -> Badge(stringResource(R.string.soccer_ht), LiveRed, modifier)
        MatchStatus.FINISHED -> Badge(stringResource(R.string.soccer_ft), EnigmaColors.Muted, modifier)
        MatchStatus.POSTPONED -> Badge(stringResource(R.string.soccer_postponed), EnigmaColors.Accent, modifier)
        MatchStatus.CANCELLED -> Badge(stringResource(R.string.soccer_cancelled), EnigmaColors.Accent, modifier)
        MatchStatus.SCHEDULED -> Unit
    }
}

/** Score for live/finished matches, else the kickoff time. */
fun scoreOrTime(match: SoccerMatch, locale: Locale): String =
    if ((match.status.isLive || match.status == MatchStatus.FINISHED) && match.fullTime.known) {
        "${match.fullTime.home} – ${match.fullTime.away}"
    } else if (match.status.isLive) {
        "0 – 0"
    } else {
        SoccerFormat.time(match.kickoffSec, locale)
    }

object SoccerFormat {
    private fun zoned(sec: Long) = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault())
    fun time(sec: Long, locale: Locale): String = DateTimeFormatter.ofPattern("HH:mm", locale).format(zoned(sec))
    fun date(sec: Long, locale: Locale): String = DateTimeFormatter.ofPattern("EEE d MMM", locale).format(zoned(sec))
    fun dateTime(sec: Long, locale: Locale): String = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale).format(zoned(sec))
}

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
