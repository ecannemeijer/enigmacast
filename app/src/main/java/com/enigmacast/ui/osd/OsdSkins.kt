package com.enigmacast.ui.osd

import androidx.compose.foundation.background
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
