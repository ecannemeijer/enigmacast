package com.enigmacast.ui.osd

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.util.asString

/**
 * The look of the info strip on the channel banner. Skins only place [OsdInfoView];
 * what the slots mean is decided in [OsdInfoPresenter]. Keyed by [OsdSkinId].
 */
interface OsdSkin {
    @Composable
    fun Strip(view: OsdInfoView, modifier: Modifier, trailing: @Composable () -> Unit = {})
}

object OsdSkins {
    fun of(id: OsdSkinId): OsdSkin = when (id) {
        OsdSkinId.GLASS_EARTH -> OrbitOsdSkin
        OsdSkinId.GLASS_WAVE -> WaveOsdSkin
        else -> StandardOsdSkin
    }
}

/**
 * Pills in the same shape as the resolution tag. They wrap inside the width the banner
 * gives them, at most two lines, so a long encryption name cannot crush the programme text.
 */
object StandardOsdSkin : OsdSkin {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Strip(view: OsdInfoView, modifier: Modifier, trailing: @Composable () -> Unit) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = 2,
        ) {
            view.signal?.let { signal ->
                signal.strengthPercent?.let { Pill(stringResource(R.string.osd_signal_pill, stringResource(R.string.osd_signal_strength), it)) }
                when {
                    signal.qualityPercent != null -> Pill(stringResource(R.string.osd_signal_pill, stringResource(R.string.osd_signal_quality), signal.qualityPercent))
                    signal.qualityDb != null -> Pill(stringResource(R.string.osd_signal_quality_db, signal.qualityDb))
                }
            }
            view.satellite?.let { Pill(it) }
            view.crypt?.let { badge ->
                val label = badge.systems?.let { "${badge.text.asString()} · $it" } ?: badge.text.asString()
                Pill(label, cryptColor(badge.tone))
            }
            view.tuner?.let { Pill(it.asString()) }
            view.technical?.let { Pill(it) }
            trailing()
        }
    }
}

@Composable
private fun Pill(text: String, color: Color = Color.White) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 168.dp)
            .background(EnigmaColors.Raised, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun cryptColor(tone: CryptTone): Color = when (tone) {
    CryptTone.FREE, CryptTone.OPEN -> EnigmaColors.Success
    CryptTone.LOCKED -> EnigmaColors.Accent
    CryptTone.PROBLEM -> EnigmaColors.Danger
}

/** Glass Earth: small glass chips, each with a tiny icon (signal bars, lock, dish, tuner). */
object OrbitOsdSkin : OsdSkin {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Strip(view: OsdInfoView, modifier: Modifier, trailing: @Composable () -> Unit) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(5.dp), maxLines = 2) {
            view.signal?.let { s ->
                val q = s.qualityPercent ?: s.strengthPercent
                val text = when {
                    s.qualityPercent != null -> "${s.qualityPercent}%"
                    s.qualityDb != null -> s.qualityDb
                    s.strengthPercent != null -> "${s.strengthPercent}%"
                    else -> null
                }
                if (text != null) IconChip(text) { MiniBars(q ?: 0) }
            }
            view.crypt?.let { badge ->
                val label = badge.systems?.let { "${badge.text.asString()} · $it" } ?: badge.text.asString()
                IconChip(label, cryptColor(badge.tone)) { MiniIcon(MiniKind.LOCK, cryptColor(badge.tone)) }
            }
            view.satellite?.let { IconChip(it) { MiniIcon(MiniKind.DISH) } }
            view.tuner?.let { IconChip(it.asString()) { MiniIcon(MiniKind.TUNER) } }
            view.technical?.let { IconChip(it) }
            trailing()
        }
    }
}

/** Glass Wave: strength and quality as slim meters, the other facts as quiet chips. */
object WaveOsdSkin : OsdSkin {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Strip(view: OsdInfoView, modifier: Modifier, trailing: @Composable () -> Unit) {
        androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            view.signal?.let { s ->
                s.strengthPercent?.let { Meter(stringResource(R.string.osd_signal_strength), it, "$it%") }
                when {
                    s.qualityPercent != null -> Meter(stringResource(R.string.osd_signal_quality), s.qualityPercent, "${s.qualityPercent}%")
                    s.qualityDb != null -> Meter(stringResource(R.string.osd_signal_quality), null, s.qualityDb)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(5.dp), maxLines = 2) {
                view.crypt?.let { badge ->
                    val label = badge.systems?.let { "${badge.text.asString()} · $it" } ?: badge.text.asString()
                    IconChip(label, cryptColor(badge.tone))
                }
                view.satellite?.let { IconChip(it) }
                view.tuner?.let { IconChip(it.asString()) }
                view.technical?.let { IconChip(it) }
                trailing()
            }
        }
    }
}

@Composable
private fun Meter(label: String, percent: Int?, value: String) {
    val p = com.enigmacast.ui.theme.ActiveSkin.current.palette
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(end = 6.dp))
        androidx.compose.foundation.Canvas(Modifier.padding(end = 6.dp).width(84.dp).height(4.dp)) {
            drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            if (percent != null) {
                drawRoundRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(p.accent, p.highlight)),
                    size = androidx.compose.ui.geometry.Size(size.width * percent / 100f, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
            }
        }
        Text(value, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun IconChip(text: String, color: Color = Color.White, icon: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    androidx.compose.foundation.layout.Row(
        Modifier
            .widthIn(max = 200.dp)
            .background(Color.White.copy(alpha = 0.10f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            androidx.compose.foundation.layout.Spacer(Modifier.width(5.dp))
        }
        Text(text, color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class MiniKind { LOCK, DISH, TUNER }

/** Tiny drawn icons, so no glyph depends on what the font or the device has. */
@Composable
private fun MiniIcon(kind: MiniKind, color: Color = com.enigmacast.ui.theme.ActiveSkin.current.palette.highlight) {
    androidx.compose.foundation.Canvas(Modifier.width(11.dp).height(11.dp)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx())
        when (kind) {
            MiniKind.LOCK -> {
                drawArc(color, 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.05f), size = androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.6f), style = stroke)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.42f), size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f))
            }
            MiniKind.DISH -> {
                drawArc(color, 90f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.05f, h * 0.1f), size = androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.8f), style = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.95f, h * 0.2f), strokeWidth = stroke.width)
            }
            MiniKind.TUNER -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.25f), size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.5f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f), style = stroke)
                drawCircle(color, w * 0.08f, androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.5f))
            }
        }
    }
}

/** Four tiny bars, lit up to [percent]. */
@Composable
private fun MiniBars(percent: Int) {
    val lit = com.enigmacast.ui.theme.ActiveSkin.current.palette.highlight
    androidx.compose.foundation.Canvas(Modifier.width(12.dp).height(10.dp)) {
        val w = size.width / 4f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            val on = percent >= (i + 1) * 25 - 12
            drawRect(if (on) lit else Color.White.copy(alpha = 0.25f), topLeft = androidx.compose.ui.geometry.Offset(i * w + 0.5f, size.height - h), size = androidx.compose.ui.geometry.Size(w - 1.5f, h))
        }
    }
}
