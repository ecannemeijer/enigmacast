package com.enigmacast.ui.osd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
    fun Strip(view: OsdInfoView, modifier: Modifier)
}

object OsdSkins {
    fun of(id: OsdSkinId): OsdSkin = when (id) {
        OsdSkinId.STANDARD -> StandardOsdSkin
    }
}

/**
 * One row of pills, the same shape as the resolution tag, so the banner does not grow taller.
 * The banner places this row to the left of that resolution tag.
 */
object StandardOsdSkin : OsdSkin {
    @Composable
    override fun Strip(view: OsdInfoView, modifier: Modifier) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
        }
    }
}

@Composable
private fun Pill(text: String, color: Color = Color.White) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(EnigmaColors.Raised, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun cryptColor(tone: CryptTone): Color = when (tone) {
    CryptTone.FREE, CryptTone.OPEN -> EnigmaColors.Success
    CryptTone.LOCKED -> EnigmaColors.Accent
    CryptTone.PROBLEM -> EnigmaColors.Danger
}
