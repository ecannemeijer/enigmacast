package com.enigmacast.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.live.TunerState
import com.enigmacast.live.TunerStatus
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.recording.RecordingDot
import com.enigmacast.ui.theme.ActiveSkin
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.glassSheen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The two glass banners. Same data as every banner ([ChannelBanner] passes it in); only the
 * layout differs. Fill colours go through [EnigmaColors.scrimOf], so Settings › OSD transparency
 * still applies; the banner's timeout and keys stay with the caller.
 */
internal object GlassBannerMath {
    fun fraction(event: EpgEvent?, nowSec: Long): Float {
        if (event == null || event.durationSec <= 0 || event.beginEpochSec <= 0) return 0f
        return ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
    }

    fun minutesLeft(event: EpgEvent?, nowSec: Long): Int? {
        if (event == null || event.durationSec <= 0 || event.beginEpochSec <= 0) return null
        val left = event.beginEpochSec + event.durationSec - nowSec
        return if (left > 0) ((left + 59) / 60).toInt() else null
    }

    fun range(event: EpgEvent): String = listOf(event.beginLabel, event.endLabel).filter { it.isNotBlank() }.joinToString(" – ")

    /** Alpha of dot [i] of the particle divider: a slow wave along the line, 0.15–0.85. */
    fun dotAlpha(i: Int): Float = (0.5f + 0.35f * kotlin.math.sin(i * 0.35f)).coerceIn(0.15f, 0.85f)
}

/** Cheap entrance: fade and a short rise, on the layer only (no relayout). Off when motion is off. */
@Composable
private fun Modifier.appear(): Modifier {
    val spec = LocalMotionSpec.current
    val t = remember { Animatable(if (spec.animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (spec.animate) t.animateTo(1f, tween(spec.osdMs.coerceIn(120, 320), easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        alpha = t.value
        translationY = (1f - t.value) * 24.dp.toPx()
    }
}

private val DateFormat get() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/**
 * Glass Earth, "orbit": a floating glass capsule centred at the bottom with a cyan horizon line on
 * its top edge, the picon in a glowing circle, the programme title large over a thin glowing arc
 * (the horizon) as progress, the clock on the channel line and the facts as small chips. The next
 * programme shares the time line, so the banner is one compact capsule.
 */
@Composable
internal fun OrbitBanner(
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
    scrim: Color,
    info: @Composable () -> Unit,
    docked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val p = ActiveSkin.current.palette
    val capsule = RoundedCornerShape(30.dp)
    val fill = Brush.linearGradient(listOf(EnigmaColors.scrimOf(p.osdFill, scrim), EnigmaColors.scrimOf(p.osdFillEnd, scrim)))
    Column(
        modifier
            .padding(start = EnigmaColors.SafeHorizontal, end = EnigmaColors.SafeHorizontal, top = 12.dp, bottom = if (docked) 0.dp else 18.dp)
            .fillMaxWidth()
            .appear(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 1080.dp).fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(fill, capsule)
                    .glassSheen(capsule)
                    .drawBehind {
                        // The horizon: a cyan line on the top edge that fades out to both ends, with a soft glow under it.
                        val glow = p.highlight
                        val y = 1.dp.toPx()
                        val edge = Brush.horizontalGradient(0.08f to Color.Transparent, 0.5f to glow, 0.92f to Color.Transparent)
                        drawLine(edge, Offset(size.width * 0.08f, y), Offset(size.width * 0.92f, y), strokeWidth = 2.dp.toPx())
                        drawRect(
                            Brush.verticalGradient(0f to glow.copy(alpha = 0.16f), 1f to Color.Transparent, endY = 26.dp.toPx()),
                            topLeft = Offset(size.width * 0.2f, 0f),
                            size = androidx.compose.ui.geometry.Size(size.width * 0.6f, 26.dp.toPx()),
                        )
                    }
                    .padding(start = 12.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Picon in a glowing ring.
                Box(
                    Modifier
                        .size(84.dp)
                        .background(Brush.radialGradient(listOf(p.highlight.copy(alpha = 0.30f), Color.Transparent)), CircleShape)
                        .padding(5.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        .border(2.dp, p.highlight.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Picon(channel, picon, cachedPicon, Modifier.width(54.dp).height(34.dp), fallbackSize = 11.sp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (number > 0) Text("$number", color = p.highlight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
                        Text(channel.name, color = p.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (loading) EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
                        if (recording) RecordingDot(Modifier.padding(start = 8.dp))
                        if (tuner.status == TunerStatus.FULL) StatusPill(stringResource(R.string.osd_tuners_busy), p.danger)
                        Spacer(Modifier.weight(1f))
                        // The clock shares the channel line instead of a row of its own.
                        Text(clockLabel(nowSec), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                    }
                    if (current != null) {
                        Text(current.title.ifBlank { stringResource(R.string.osd_unknown_programme) }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Time, minutes left and what comes next on one line (the "next" pill used to float above).
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(GlassBannerMath.range(current), color = p.accent, fontSize = 13.sp, maxLines = 1, softWrap = false)
                            GlassBannerMath.minutesLeft(current, nowSec)?.let {
                                Text("  ·  " + stringResource(R.string.epg_minutes_left, it), color = p.muted, fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                            if (next != null) {
                                Text("    " + stringResource(R.string.osd_next_up).uppercase() + "  ", color = p.highlight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
                                Text(
                                    next.beginLabel.let { if (it.isBlank()) "" else "$it  " } + next.title.ifBlank { stringResource(R.string.osd_unknown_programme) },
                                    color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                        HorizonProgress(GlassBannerMath.fraction(current, nowSec), p.highlight, Modifier.fillMaxWidth().height(12.dp))
                        if (details) {
                            val plot = current.plot.ifBlank { current.shortDescription }
                            if (plot.isNotBlank()) Text(plot, color = p.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Text(stringResource(R.string.osd_no_programme_data), color = p.muted, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.widthIn(min = 150.dp, max = 300.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    info()
                }
            }
        }
    }
}

/** A thin arc like the Earth's horizon: faint track, the elapsed part bright with a glow. */
@Composable
private fun HorizonProgress(fraction: Float, glow: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, h * 0.85f)
            quadraticTo(w / 2f, -h * 0.35f, w, h * 0.85f)
        }
        drawPath(path, Color.White.copy(alpha = 0.18f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        if (fraction > 0f) {
            clipRect(right = w * fraction) {
                drawPath(path, glow.copy(alpha = 0.28f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, glow, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/**
 * Glass Wave, "wave": an edge-to-edge glass sheet with a soft fade on top and a dotted
 * particle line, the number and picon on the left, now and next as two columns, a blue-to-cyan
 * progress bar with a glowing dot, and the date and clock (one line) and signal meters on the right.
 */
@Composable
internal fun WaveBanner(
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
    scrim: Color,
    info: @Composable () -> Unit,
    docked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val p = ActiveSkin.current.palette
    val top = EnigmaColors.scrimOf(p.osdFill, scrim)
    val bottom = EnigmaColors.scrimOf(p.osdFillEnd, scrim)
    Column(
        modifier
            .fillMaxWidth()
            .appear()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.22f to top, 1f to bottom)),
    ) {
        Spacer(Modifier.height(14.dp))
        ParticleLine(p.highlight, Modifier.fillMaxWidth().height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = EnigmaColors.SafeHorizontal, end = EnigmaColors.SafeHorizontal, top = 10.dp, bottom = if (docked) 6.dp else 18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Number beside the picon instead of above it.
            Row(Modifier.width(170.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (number > 0) "$number" else "", color = p.highlight, fontSize = 28.sp, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp).padding(end = 10.dp))
                Picon(channel, picon, cachedPicon, Modifier.width(96.dp).height(50.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (loading) EnigmaLoadingIndicator(Modifier.padding(start = 8.dp))
                    if (recording) RecordingDot(Modifier.padding(start = 8.dp))
                    if (tuner.status == TunerStatus.FULL) StatusPill(stringResource(R.string.osd_tuners_busy), p.danger)
                }
                if (current == null) {
                    Text(stringResource(R.string.osd_no_programme_data), color = p.muted, fontSize = 15.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        WaveColumn(stringResource(R.string.osd_now), current, true, Modifier.weight(1.15f))
                        if (next != null) WaveColumn(stringResource(R.string.osd_next_up), next, false, Modifier.weight(0.85f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlowBar(GlassBannerMath.fraction(current, nowSec), p.accent, p.highlight, Modifier.weight(1f).height(10.dp))
                        GlassBannerMath.minutesLeft(current, nowSec)?.let {
                            Text(stringResource(R.string.epg_minutes_left, it), color = p.muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (details) {
                        val plot = current.plot.ifBlank { current.shortDescription }
                        if (plot.isNotBlank()) Text(plot, color = p.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.widthIn(min = 170.dp, max = 300.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Clock and date on one line.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(DateFormat.format(Instant.ofEpochSecond(nowSec).atZone(ZoneId.systemDefault())), color = p.muted, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 10.dp, bottom = 4.dp))
                    Text(clockLabel(nowSec), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
                }
                info()
            }
        }
    }
}

@Composable
private fun WaveColumn(label: String, event: EpgEvent, now: Boolean, modifier: Modifier) {
    val p = ActiveSkin.current.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = if (now) p.highlight else p.subtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            Text(GlassBannerMath.range(event), color = if (now) p.accent else p.subtle, fontSize = 13.sp, maxLines = 1, softWrap = false)
        }
        Text(
            event.title.ifBlank { stringResource(R.string.osd_unknown_programme) },
            color = if (now) Color.White else p.muted,
            fontSize = if (now) 17.sp else 14.sp,
            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Dots along the line with a slow wave of brightness, like the particle waves of the photo. */
@Composable
private fun ParticleLine(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val step = 7.dp.toPx()
        val r = 0.9.dp.toPx()
        var i = 0
        var x = step / 2
        while (x < size.width) {
            val edge = (minOf(x, size.width - x) / (size.width * 0.12f)).coerceIn(0f, 1f)
            drawCircle(color.copy(alpha = GlassBannerMath.dotAlpha(i) * edge), r, Offset(x, size.height / 2 + kotlin.math.sin(i * 0.18f) * size.height * 0.25f))
            x += step
            i++
        }
    }
}

/** Track, a blue-to-cyan fill and a glowing dot where the programme is now. */
@Composable
private fun GlowBar(fraction: Float, from: Color, to: Color, modifier: Modifier) {
    Canvas(modifier) {
        val y = size.height / 2
        val stroke = 4.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        val x = size.width * fraction
        if (fraction > 0f) {
            drawLine(Brush.horizontalGradient(listOf(from, to), endX = x.coerceAtLeast(1f)), Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
            drawCircle(to.copy(alpha = 0.30f), size.height / 2, Offset(x, y))
            drawCircle(Color.White, 3.5.dp.toPx(), Offset(x, y))
        }
    }
}
