package com.enigmacast.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.live.TunerState
import com.enigmacast.live.TunerStatus
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.recording.RecordingDot
import com.enigmacast.ui.theme.ActiveSkin
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.StatusPill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun clockLabel(nowSec: Long): String =
    ClockFormat.format(Instant.ofEpochSecond(nowSec).atZone(ZoneId.systemDefault()))

private fun fractionOf(event: EpgEvent, nowSec: Long): Float {
    if (event.durationSec <= 0 || event.beginEpochSec <= 0) return 0f
    return ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
}

private fun minutesLeft(event: EpgEvent, nowSec: Long): Int? {
    if (event.durationSec <= 0 || event.beginEpochSec <= 0) return null
    val left = event.beginEpochSec + event.durationSec - nowSec
    return if (left > 0) ((left + 59) / 60).toInt() else null
}

/**
 * MetrixHD style: a flat bar, the channel number in a solid accent block on the left,
 * thin accent progress, the clock in an accent tile on the right. Square corners.
 */
@Composable
internal fun FlatBanner(
    channel: BouquetEntry,
    number: Int,
    current: EpgEvent?,
    next: EpgEvent?,
    nowSec: Long,
    loading: Boolean,
    recording: Boolean,
    tuner: TunerState,
    details: Boolean,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    scrim: androidx.compose.ui.graphics.Color,
    info: @Composable () -> Unit,
    docked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val p = ActiveSkin.current.palette
    Row(
        modifier
            .padding(
                start = EnigmaColors.SafeHorizontal,
                end = EnigmaColors.SafeHorizontal,
                top = EnigmaColors.SafeVertical,
                bottom = if (docked) 0.dp else EnigmaColors.SafeVertical,
            )
            .fillMaxWidth()
            .background(EnigmaColors.scrimOf(p.osdFill, scrim)),
    ) {
        Column(
            Modifier.width(150.dp).background(p.accent).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (number > 0) "$number" else "", color = p.onAccent, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Picon(channel, picon, cachedPicon, Modifier.width(116.dp).height(64.dp))
        }
        Column(Modifier.weight(1.3f).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, color = p.text, fontSize = 21.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (loading) EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
                if (recording) RecordingDot(Modifier.padding(start = 8.dp))
                if (tuner.status == TunerStatus.FULL) StatusPill(stringResource(R.string.osd_tuners_busy), p.danger)
            }
            if (current != null) {
                FlatLine(current, true)
                Box(Modifier.fillMaxWidth().height(3.dp).background(p.raised)) {
                    Box(Modifier.fillMaxWidth(fractionOf(current, nowSec)).fillMaxHeight().background(p.accent))
                }
            } else {
                Text(stringResource(R.string.osd_no_programme_data), color = p.muted, fontSize = 14.sp)
            }
            if (next != null) FlatLine(next, false)
            if (details && current != null) {
                val plot = current.plot.ifBlank { current.shortDescription }
                if (plot.isNotBlank()) Text(plot, color = p.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(0.8f).padding(top = 12.dp, end = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(clockLabel(nowSec), color = p.onAccent, fontSize = 18.sp, modifier = Modifier.background(p.accent).padding(horizontal = 10.dp, vertical = 2.dp))
            info()
        }
    }
}

@Composable
private fun FlatLine(event: EpgEvent, now: Boolean) {
    val p = ActiveSkin.current.palette
    Row {
        Text(
            listOf(event.beginLabel, event.endLabel).filter { it.isNotBlank() }.joinToString(" - "),
            color = if (now) p.accent else p.subtle,
            fontSize = if (now) 15.sp else 13.sp,
            maxLines = 1,
            modifier = Modifier.width(118.dp),
        )
        Text(
            event.title.ifBlank { stringResource(R.string.osd_unknown_programme) },
            color = if (now) p.text else p.muted,
            fontSize = if (now) 15.sp else 13.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
