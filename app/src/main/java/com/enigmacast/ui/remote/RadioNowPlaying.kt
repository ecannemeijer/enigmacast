package com.enigmacast.ui.remote

import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.ui.theme.EnigmaColors
import kotlinx.coroutines.delay

/**
 * What a radio station shows: the programme on air, and an artist or song when one is known.
 * Stream metadata wins over the guide's short description, which is where OpenWebif usually puts the artist.
 */
internal data class RadioCopy(val programme: String, val artist: String)

internal fun radioCopy(event: EpgEvent?, streamTitle: String, streamArtist: String): RadioCopy {
    val programme = event?.title?.trim().orEmpty().ifBlank { streamTitle.trim() }
    val fromStream = listOf(streamArtist, streamTitle)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals(programme, ignoreCase = true) }
        .distinct()
    val artist = when {
        fromStream.isNotEmpty() -> fromStream.joinToString(" · ")
        else -> event?.shortDescription?.trim().orEmpty().takeUnless { it.equals(programme, ignoreCase = true) }.orEmpty()
    }
    return RadioCopy(programme, artist)
}

internal fun onAirEvent(guide: ChannelGuide?, channel: BouquetEntry, nowSec: Long): EpgEvent? {
    val events = guide?.takeIf { BouquetChannels.sameService(it.serviceReference, channel.reference) }?.events.orEmpty()
    return events.firstOrNull { it.beginEpochSec <= nowSec && nowSec < it.beginEpochSec + it.durationSec }
        ?: guide?.currentEvent?.takeIf { BouquetChannels.sameService(guide.serviceReference, channel.reference) }
}

/**
 * Station, logo and programme over a dark background. The block drifts so a still logo
 * does not sit on the same pixels.
 */
@Composable
internal fun RadioNowPlaying(
    channel: BouquetEntry,
    guide: ChannelGuide?,
    streamTitle: String,
    streamArtist: String,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    val copy = radioCopy(onAirEvent(guide, channel, nowSec), streamTitle, streamArtist)
    val drift = burnInDrift(if (compact) 6f else 28f, if (compact) 4f else 16f)
    Box(
        modifier = modifier.background(EnigmaColors.Canvas),
        contentAlignment = Alignment.Center,
    ) {
        val card = Modifier.offset(drift.first.dp, drift.second.dp)
        if (compact) {
            CompactRadio(channel, copy, picon, cachedPicon, card.padding(8.dp))
        } else {
            FullRadio(channel, copy, picon, cachedPicon, card)
        }
    }
}

@Composable
private fun FullRadio(
    channel: BouquetEntry,
    copy: RadioCopy,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.width(520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Picon(
            channel,
            picon,
            cachedPicon,
            Modifier
                .width(220.dp)
                .height(140.dp)
                .background(EnigmaColors.Raised, skinShape(12.dp))
                .padding(12.dp),
            fallbackSize = 22.sp,
        )
        Text(
            channel.name,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = EnigmaType.Strong,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ProgrammeLines(copy, programmeSize = 20, artistSize = 16)
    }
}

@Composable
private fun CompactRadio(
    channel: BouquetEntry,
    copy: RadioCopy,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Picon(
            channel,
            picon,
            cachedPicon,
            Modifier
                .width(72.dp)
                .height(48.dp)
                .background(EnigmaColors.Raised, skinShape(6.dp))
                .padding(4.dp),
            fallbackSize = 9.sp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                channel.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = EnigmaType.Strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProgrammeLines(copy, programmeSize = 12, artistSize = 11)
        }
    }
}

@Composable
private fun ProgrammeLines(copy: RadioCopy, programmeSize: Int, artistSize: Int) {
    if (copy.programme.isBlank()) {
        Text(
            stringResource(R.string.osd_no_programme_data),
            color = EnigmaColors.Muted,
            fontSize = programmeSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            copy.programme,
            color = EnigmaColors.Text,
            fontSize = programmeSize.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (copy.artist.isNotBlank()) {
        Text(
            copy.artist,
            color = EnigmaColors.Accent,
            fontSize = artistSize.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Slow opposite drifts, in dp, so the logo does not rest on one spot. */
@Composable
private fun burnInDrift(xDp: Float, yDp: Float): Pair<Float, Float> {
    val transition = rememberInfiniteTransition(label = "radio-burn-in")
    val x by transition.animateFloat(
        initialValue = -xDp,
        targetValue = xDp,
        animationSpec = infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "radio-drift-x",
    )
    val y by transition.animateFloat(
        initialValue = -yDp,
        targetValue = yDp,
        animationSpec = infiniteRepeatable(tween(53_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "radio-drift-y",
    )
    return x to y
}
