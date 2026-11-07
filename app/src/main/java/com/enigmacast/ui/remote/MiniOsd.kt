package com.enigmacast.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.recording.RecordingDot
import com.enigmacast.ui.theme.ActiveSkin
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.OsdLayout
import com.enigmacast.ui.theme.glassSheen
import com.enigmacast.ui.theme.skinShape
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The panel never gets more see-through than this, so the mini OSD stays readable on bright video. */
private const val MIN_FILL_ALPHA = 0.82f

/**
 * Compact channel info at the top of the screen, shown with OK (with the recent channels below).
 * A zap still shows the full banner. Every OSD layout gets its own look: the PLi bar a square
 * bar with an accent line, Flat a coloured number block, Orbit a capsule, Wave an edge-to-edge
 * sheet with a glow progress line, and the cards (EnigmaCast, Midnight, Aurora, Slate Calm) a
 * small card in their own fill and edge.
 */
@Composable
fun MiniOsd(
    channel: BouquetEntry,
    number: Int,
    guide: ChannelGuide?,
    loading: Boolean,
    recording: Boolean,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    scrim: Color,
    modifier: Modifier = Modifier,
) {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    val events = guide?.events.orEmpty()
    val current = events.firstOrNull { it.beginEpochSec <= nowSec && nowSec < it.beginEpochSec + it.durationSec }
        ?: guide?.currentEvent
    val skin = ActiveSkin.current
    val p = skin.palette
    val layout = skin.osd
    val alpha = scrim.alpha.coerceAtLeast(MIN_FILL_ALPHA)
    val start = p.osdFill.copy(alpha = alpha)
    val end = p.osdFillEnd.copy(alpha = alpha)
    val shape: Shape = when (layout) {
        OsdLayout.PLI_BAR, OsdLayout.WAVE -> RectangleShape
        OsdLayout.ORBIT -> RoundedCornerShape(50)
        else -> skinShape(14.dp)
    }
    val fill: Brush = when (layout) {
        OsdLayout.PLI_BAR, OsdLayout.WAVE -> Brush.horizontalGradient(listOf(start, end))
        OsdLayout.GLASS, OsdLayout.CALM, OsdLayout.ORBIT -> Brush.linearGradient(listOf(start, end))
        else -> androidx.compose.ui.graphics.SolidColor(start)
    }
    val wide = layout == OsdLayout.PLI_BAR || layout == OsdLayout.WAVE
    val outer = if (wide) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .padding(horizontal = EnigmaColors.SafeHorizontal)
            .padding(top = EnigmaColors.SafeVertical)
            .widthIn(min = 520.dp, max = 900.dp)
    }
    Column(
        outer
            .clip(shape)
            .background(fill, shape)
            .then(
                when (layout) {
                    OsdLayout.MINIMAL, OsdLayout.FLAT, OsdLayout.PLI_BAR, OsdLayout.WAVE -> Modifier
                    OsdLayout.ORBIT -> Modifier.border(1.dp, p.accent.copy(alpha = 0.55f), shape)
                    else -> Modifier.border(1.dp, p.osdEdge, shape)
                },
            )
            .glassSheen(shape),
    ) {
        Row(
            Modifier
                .then(if (wide) Modifier.padding(horizontal = EnigmaColors.SafeHorizontal).padding(top = EnigmaColors.SafeVertical - 8.dp) else Modifier)
                .padding(horizontal = if (layout == OsdLayout.ORBIT) 22.dp else 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (layout == OsdLayout.FLAT && number > 0) {
                Box(
                    Modifier.background(p.accent, skinShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$number", color = p.onAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
            }
            Picon(
                channel, picon, cachedPicon,
                Modifier
                    .then(if (layout == OsdLayout.ORBIT) Modifier.clip(RoundedCornerShape(50)).border(2.dp, p.accent, RoundedCornerShape(50)).padding(4.dp) else Modifier)
                    .width(64.dp)
                    .height(38.dp),
                fallbackSize = 11.sp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (layout != OsdLayout.FLAT && number > 0) {
                        Text("$number", color = p.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(
                        channel.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = EnigmaType.Strong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (loading) EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
                    if (recording) RecordingDot(Modifier.padding(start = 8.dp))
                }
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val times = listOf(current.beginLabel, current.endLabel).filter { it.isNotBlank() }.joinToString(" – ")
                        if (times.isNotBlank()) {
                            Text(times, color = p.accent, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 10.dp))
                        }
                        Text(
                            current.title.ifBlank { stringResource(R.string.osd_unknown_programme) },
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (layout != OsdLayout.WAVE) EnigmaProgramProgress(progress(current, nowSec), Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.osd_no_programme_data), color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                remember(nowSec / 60) { LocalTime.now().format(ClockFormat) },
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        when (layout) {
            // Wave: the glow line is the progress; PLi: the accent rule under the bar.
            OsdLayout.WAVE -> if (current != null) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(p.raised.copy(alpha = 0.6f))) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress(current, nowSec))
                            .height(3.dp)
                            .background(Brush.horizontalGradient(listOf(p.accent, p.secondary))),
                    )
                }
            }
            OsdLayout.PLI_BAR -> Box(Modifier.fillMaxWidth().height(2.dp).background(p.accent))
            else -> Unit
        }
    }
}

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun progress(event: EpgEvent, nowSec: Long): Float {
    if (event.durationSec <= 0 || event.beginEpochSec <= 0) return 0f
    return ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
}
