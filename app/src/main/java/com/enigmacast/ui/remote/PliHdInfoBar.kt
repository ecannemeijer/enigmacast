package com.enigmacast.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.osdinfo.OsdInfo
import com.enigmacast.live.TunerState
import com.enigmacast.live.TunerStatus
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.osd.CryptTone
import com.enigmacast.ui.osd.OsdInfoView
import com.enigmacast.util.asString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Colours of the OpenPLi PLi-HD skin (littlesat/skin-PLiHD, PLi-HD/skin.xml `<colors>`).
 * Enigma2 writes #AARRGGBB with the alpha inverted (00 is opaque); these are converted.
 */
internal object PliHd {
    val Foreground = Color(0xFFF0F0F0)      // foreground / white
    val Grey = Color(0xFFAAAAAA)            // grey: next event
    val DarkGrey = Color(0xFF555556)        // darkgrey
    val SecondFg = Color(0xFFFCC000)        // secondFG / selectedFG: clock, date, selection text
    val Yellow = Color(0xFFFFC000)          // yellow / orange
    val Blue = Color(0xFF879CE1)            // blue: genres
    val Red = Color(0xFFFF4A3C)             // red: rec, marked
    val Green = Color(0xFF56C856)
    val NoTransBg = Color(0xFF101214)       // noTransBG: menu background
    val SelectedBg = Color(0xFF303240)      // selectedBG (#06 → 98 %)
    val ProgressBg = Color(0xFF33333A)      // un33333a: progress background
    val TranspBlack = Color(0xAB111112)     // transpBlack #54111112
    /** infobar/hd.png: #101214, transparent at the top, 82 % from 1/3 of its height down. */
    val BarFill = Color(0xFF101214)
    /** infobar/pbar.png, top to bottom: dark bronze with a lighter middle line. */
    val ProgressFill = listOf(Color(0xFF31240B), Color(0xFF4C3811), Color(0xFF574013), Color(0xFF453310), Color(0xFF382A0D), Color(0xFF30240B), Color(0xFF291E09), Color(0xFF271D09))
    /** buttons/sel.png: glossy blue-grey selection bar, light top, dark middle, light edge at the bottom. */
    val Selection = listOf(Color(0xE6939FAB), Color(0xE6647080), Color(0xE62B3745), Color(0xE6061220), Color(0xE60D1E2E), Color(0xE6435260))
}

private val DateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

/**
 * The PLi-HD `InfoBar` (InfoBarTemplate in skin_templates.xml), laid out on its own 1280×720
 * grid and scaled to the screen width: bar image 1280×220 at y 510; date and clock (yellow)
 * top left with the rec mark; channel number and name next to the clock; crypto line top
 * right; service icons at y 588; the 840×8 progress bar at x 220; now/next with start time,
 * title and remaining / duration; picon 100×60 bottom left; video size, tuner, SNR and orbital
 * position in the column at x 1100.
 */
@Composable
internal fun PliHdInfoBar(
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
    view: OsdInfoView,
    osdInfo: OsdInfo,
    player: PlayerState,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val s = maxWidth.value / 1280f
        fun u(px: Int): Dp = (px * s).dp
        fun f(px: Int) = (px * s).sp
        Column(Modifier.fillMaxWidth()) {
            if (details && current != null) {
                // SecondInfoBarSimple: transpBlack panel, event name in secondFG, description in white.
                Column(Modifier.fillMaxWidth().background(PliHd.TranspBlack).padding(horizontal = u(60), vertical = u(20))) {
                    PliText(current.title, PliHd.SecondFg, f(22))
                    val plot = current.plot.ifBlank { current.shortDescription }
                    if (plot.isNotBlank()) {
                        Text(plot, color = PliHd.Foreground, fontSize = f(20), lineHeight = f(26), fontFamily = FontFamily.SansSerif, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(u(220))
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.08f to PliHd.BarFill.copy(alpha = 0.13f),
                            0.16f to PliHd.BarFill.copy(alpha = 0.45f),
                            0.25f to PliHd.BarFill.copy(alpha = 0.73f),
                            0.33f to PliHd.BarFill.copy(alpha = 0.82f),
                            1f to PliHd.BarFill.copy(alpha = 0.82f),
                        ),
                    ),
            ) {
                // Rows are given in skin pixels relative to the bar's top (screen y − 510).
                fun Modifier.at(x: Int, y: Int, w: Int, h: Int) = this.offset(u(x), u(y)).size(u(w), u(h))
                val now = Instant.ofEpochSecond(nowSec).atZone(zone)
                PliText(DateFormat.withLocale(locale).format(now), PliHd.SecondFg, f(16), Modifier.at(60, 40, 360, 30))
                PliText(clockLabel(nowSec), PliHd.SecondFg, f(26), Modifier.at(60, 63, 140, 50))
                if (recording) Box(Modifier.offset(u(170), u(78)).size(u(20)).background(PliHd.Red, CircleShape))
                if (number > 0) PliText("$number", PliHd.Foreground, f(26), Modifier.at(221, 63, 100, 50))
                Box(Modifier.at(305, 63, 500, 50), contentAlignment = Alignment.CenterStart) {
                    PliText(channel.name, PliHd.Foreground, f(26))
                }
                if (loading) EnigmaLoadingIndicator(Modifier.offset(u(810), u(78)))
                view.crypt?.let { badge ->
                    val label = badge.systems?.let { "${badge.text.asString()} · $it" } ?: badge.text.asString()
                    PliText(label, if (badge.tone == CryptTone.PROBLEM) PliHd.Red else PliHd.Foreground, f(16), Modifier.at(430, 40, 630, 30), TextAlign.End)
                }
                // Service icons on one row, right-aligned at x 1060 as in the skin.
                val hd = player.videoHeight >= 700 || osdInfo.video?.height?.let { it >= 700 } == true
                val format = when {
                    hd -> "HD"
                    osdInfo.video?.aspect == "4:3" -> "4:3"
                    player.videoHeight > 0 || osdInfo.video != null -> "16:9"
                    else -> null
                }
                format?.let { PliIcon(it, Modifier.at(1020, 78, 40, 21), f(13)) }
                if (osdInfo.teletext == true) PliIcon("TXT", Modifier.at(983, 78, 33, 21), f(12))
                if (player.audioChannels > 2 || (osdInfo.audio?.channels ?: 0) > 2) PliIcon("DD", Modifier.at(946, 78, 33, 21), f(12))
                if (view.crypt?.tone == CryptTone.LOCKED) PliIcon("$", Modifier.at(926, 78, 16, 21), f(12))
                if (tuner.status == TunerStatus.FULL) {
                    PliText(stringResource(R.string.osd_tuners_busy), PliHd.Red, f(16), Modifier.at(560, 78, 240, 21), TextAlign.End)
                }
                // Progress: 840×8, un33333a background, pbar.png fill.
                Box(Modifier.at(220, 115, 840, 8).background(PliHd.ProgressBg)) {
                    val fraction = current?.let { progressFraction(it, nowSec) } ?: 0f
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Brush.verticalGradient(PliHd.ProgressFill)))
                }
                if (current != null) {
                    PliText(current.beginLabel, PliHd.Foreground, f(24), Modifier.at(220, 132, 70, 30))
                    PliText(current.title.ifBlank { stringResource(R.string.osd_unknown_programme) }, PliHd.Foreground, f(24), Modifier.at(305, 132, 620, 30))
                    remainingMinutes(current, nowSec)?.let {
                        PliText(stringResource(R.string.osd_remaining_min, it), PliHd.Foreground, f(24), Modifier.at(940, 132, 120, 30), TextAlign.End)
                    }
                } else {
                    PliText(stringResource(R.string.osd_no_programme_data), PliHd.Grey, f(24), Modifier.at(305, 132, 620, 30))
                }
                if (next != null) {
                    PliText(next.beginLabel, PliHd.Grey, f(24), Modifier.at(220, 162, 70, 30))
                    PliText(next.title.ifBlank { stringResource(R.string.osd_unknown_programme) }, PliHd.Grey, f(24), Modifier.at(305, 162, 620, 30))
                    (next.durationSec / 60).takeIf { it > 0 }?.let {
                        PliText(stringResource(R.string.osd_duration_min, it.toInt()), PliHd.Grey, f(24), Modifier.at(940, 162, 120, 30), TextAlign.End)
                    }
                }
                Picon(channel, picon, cachedPicon, Modifier.at(60, 125, 100, 60), fallbackSize = f(14))
                // Right column: FrontendInfo STRING, VideoSize, SNR, orbital position.
                view.tuner?.let { PliText(it.asString(), PliHd.Foreground, f(20), Modifier.at(1100, 80, 137, 22)) }
                if (player.videoWidth > 0) PliText("${player.videoWidth}x${player.videoHeight}", PliHd.Foreground, f(19), Modifier.at(1100, 109, 150, 22))
                view.signal?.let { sig ->
                    val snr = sig.qualityDb ?: sig.qualityPercent?.let { "$it%" }
                    snr?.let { PliText(it, PliHd.Foreground, f(19), Modifier.at(1100, 137, 120, 22)) }
                }
                view.satellite?.let { PliText(it, PliHd.Foreground, f(19), Modifier.at(1100, 165, 150, 22)) }
            }
        }
    }
}

@Composable
private fun PliText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        color = color,
        fontSize = size,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The skin's small service icons (ico_format_hd.png …): light text in a thin grey frame. */
@Composable
private fun PliIcon(label: String, modifier: Modifier, size: androidx.compose.ui.unit.TextUnit) {
    Box(modifier.border(1.dp, PliHd.Grey, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
        Text(label, color = PliHd.Foreground, fontSize = size, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, maxLines = 1, softWrap = false)
    }
}

private fun progressFraction(event: EpgEvent, nowSec: Long): Float {
    if (event.durationSec <= 0 || event.beginEpochSec <= 0) return 0f
    return ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
}

private fun remainingMinutes(event: EpgEvent, nowSec: Long): Int? {
    if (event.durationSec <= 0 || event.beginEpochSec <= 0) return null
    val left = event.beginEpochSec + event.durationSec - nowSec
    return if (left > 0) ((left + 59) / 60).toInt() else null
}
