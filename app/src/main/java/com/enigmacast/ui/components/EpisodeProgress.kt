package com.enigmacast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enigmacast.R
import com.enigmacast.data.db.EpisodeHistoryRow
import com.enigmacast.domain.resume.EpisodeHistory
import com.enigmacast.domain.resume.ResumePolicy
import com.enigmacast.ui.theme.EnigmaColors

/**
 * The watch history on an episode card: a progress bar along the bottom of the picture and a
 * check mark once the episode was watched. Nothing when it was never played.
 */
@Composable
fun BoxScope.EpisodeProgressOverlay(row: EpisodeHistoryRow?) {
    row ?: return
    val fraction = EpisodeHistory.fraction(row)
    if (fraction > 0f) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0x99000000)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(if (row.watched) EnigmaColors.Success else EnigmaColors.Accent),
            )
        }
    }
    if (row.watched) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(4.dp))
}

/** The green check mark of a watched episode or film. */
@Composable
fun WatchedCheck(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.episode_watched)
    Box(
        modifier
            .size(20.dp)
            .background(EnigmaColors.Success, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A film's info page: its progress (bar and "1:02:03 / 1:58:00") or the check mark and
 * "Watched". Not focusable; nothing when the film was never played.
 */
@Composable
fun VodWatchLine(row: EpisodeHistoryRow?, modifier: Modifier = Modifier) {
    row ?: return
    val fraction = EpisodeHistory.fraction(row)
    if (fraction <= 0f && !row.watched) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.watched) WatchedCheck()
        Box(
            Modifier
                .width(320.dp)
                .height(4.dp)
                .background(Color(0x55FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(if (row.watched) EnigmaColors.Success else EnigmaColors.Accent),
            )
        }
        val text = if (row.watched && (row.positionMs <= 0L || ResumePolicy.watched(row.positionMs, row.durationMs))) {
            stringResource(R.string.episode_watched)
        } else if (row.durationMs > 0L) {
            "${ResumePolicy.clock(row.positionMs)} / ${ResumePolicy.clock(row.durationMs)}"
        } else {
            ResumePolicy.clock(row.positionMs)
        }
        Text(text, color = if (row.watched) EnigmaColors.Success else EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1)
    }
}
